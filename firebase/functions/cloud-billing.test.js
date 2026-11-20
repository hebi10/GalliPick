import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { BackupError } from './backup.js';
import { billingSettings, createBillingService, PILOT_ACCOUNT_QUOTA_BYTES, PRODUCTION_PROJECT,
    productionBillingSettings } from './cloud-billing.js';

const products = [
    { productId: 'cloud_small_test', basePlanId: 'monthly', quotaBytes: 100 },
    { productId: 'cloud_large_test', basePlanId: 'monthly', quotaBytes: 200 },
];
const user = uid => ({ uid, token: { firebase: { sign_in_provider: 'google.com' } } });
const accountId = uid => createHash('sha256').update(uid).digest('hex');
const token = n => `synthetic-test-purchase-token-${n}`;
const isCode = code => error => error.code === code;
const now = 1_800_000_000_000;

function createHarness({ enabled = true, acknowledge, settings = { enabled, products } } = {}) {
    const records = new Map();
    const purchases = new Map();
    const acknowledged = [];
    const catalogQueries = [];
    let time = now;
    let pending = Promise.resolve();
    const db = {
        doc: path => ({ path, get: async () => ({ data: () => records.get(path) }) }),
        collection: path => ({ where: (field, operator, value) => ({ limit: maximum => ({ count: () => ({
            async get() {
                catalogQueries.push({ path, field, operator, value, maximum });
                const prefix = `${path}/`;
                const count = [...records].filter(([key, record]) => key.startsWith(prefix)
                    && !key.slice(prefix.length).includes('/') && record[field] === value).length;
                return { data: () => ({ count: Math.min(count, maximum) }) };
            },
        }) }) }) }),
        runTransaction(run) {
            const started = pending.then(async () => {
                const writes = new Map();
                const result = await run({
                    get: async ({ path }) => ({ data: () => records.get(path) }),
                    set: ({ path }, value) => writes.set(path, value),
                });
                for (const [path, value] of writes) records.set(path, value);
                return result;
            });
            pending = started.catch(() => {});
            return started;
        },
    };
    const play = {
        async get(value) {
            const entry = purchases.get(value);
            return typeof entry === 'function' ? entry() : entry;
        },
        async acknowledge(value, productId) {
            acknowledged.push([value, productId]);
            await acknowledge?.(value, records);
            purchases.get(value).acknowledgementState = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED';
        },
    };
    const serviceFor = settings => createBillingService({ db, play, settings,
        pilotUsers: new Set(['owner-a', 'owner-b']), now: () => time });
    return { records, purchases, acknowledged, catalogQueries, service: serviceFor(settings), serviceFor, db,
        setTime: value => { time = value; } };
}

function subscription({ uid = 'owner-a', product = products[0], state = 'ACTIVE', expires = now + 20_000,
    acknowledged = true, testPurchase = {}, linkedPurchaseToken, recurringPrice } = {}) {
    return {
        testPurchase, linkedPurchaseToken,
        externalAccountIdentifiers: { obfuscatedExternalAccountId: accountId(uid) },
        subscriptionState: `SUBSCRIPTION_STATE_${state}`,
        acknowledgementState: `ACKNOWLEDGEMENT_STATE_${acknowledged ? 'ACKNOWLEDGED' : 'PENDING'}`,
        lineItems: [{ productId: product.productId, expiryTime: new Date(expires).toISOString(),
            offerDetails: { basePlanId: product.basePlanId }, autoRenewingPlan: { autoRenewEnabled: true, recurringPrice } }],
    };
}

test('billing defaults off outside the explicit dev pilot and rejects unsafe product caps', () => {
    const env = { GALLIPICK_BILLING_ENABLED: 'true', GALLIPICK_BILLING_TEST_PRODUCTS: JSON.stringify(products) };
    assert.equal(billingSettings('gallipick-dev', {}).enabled, false);
    assert.equal(billingSettings('other-project', env).enabled, false);
    assert.deepEqual(billingSettings('gallipick-dev', env), { enabled: true, products });
    assert.throws(() => billingSettings('gallipick-dev', { ...env, GALLIPICK_BILLING_TEST_PRODUCTS: JSON.stringify([
        { ...products[0], quotaBytes: PILOT_ACCOUNT_QUOTA_BYTES + 1 },
    ]) }), isCode('failed-precondition'));
});

test('verification rejects unapproved accounts, tokens bound to another UID, real payments, and off-catalog offers', async () => {
    const { purchases, service, acknowledged } = createHarness();
    purchases.set(token(1), subscription());
    purchases.set(token(2), subscription({ testPurchase: null }));
    purchases.set(token(3), subscription({ product: { ...products[0], basePlanId: 'other' } }));
    await assert.rejects(service.verify(null, { purchaseToken: token(1) }), isCode('unauthenticated'));
    await assert.rejects(service.verify(user('owner-b'), { purchaseToken: token(1) }), isCode('permission-denied'));
    await assert.rejects(service.verify(user('owner-a'), { purchaseToken: token(2) }), isCode('permission-denied'));
    await assert.rejects(service.verify(user('owner-a'), { purchaseToken: token(3) }), isCode('failed-precondition'));
    assert.equal((await service.getPlan(user('owner-a'))).uploadAllowed, false);
    assert.deepEqual(acknowledged, []);
});

test('persists verified entitlement before acknowledgement and retries without storing tokens', async () => {
    let attempts = 0;
    const { purchases, service, records } = createHarness({ acknowledge: async (value, docs) => {
        const saved = [...docs.values()];
        assert.ok(saved.some(record => record.entitlements && Object.keys(record.entitlements).length === 1));
        assert.equal(JSON.stringify(saved).includes(value), false);
        if (++attempts === 1) throw new Error('synthetic transient acknowledgement error');
    } });
    const input = { purchaseToken: token(1) };
    purchases.set(token(1), subscription({ acknowledged: false }));
    await assert.rejects(service.verify(user('owner-a'), input), isCode('unavailable'));
    assert.equal((await service.getPlan(user('owner-a'))).uploadAllowed, true);
    assert.equal((await service.verify(user('owner-a'), input)).activeProductId, products[0].productId);
    assert.equal((await service.verify(user('owner-a'), input)).uploadAllowed, true);
    assert.equal(attempts, 2);
    assert.equal(JSON.stringify([...records]).includes(token(1)), false);
});

test('a held or expired alternate cannot replace a valid subscription and upload quota expires', async () => {
    const { purchases, service, db, setTime } = createHarness();
    purchases.set(token(1), subscription());
    purchases.set(token(2), subscription({ product: products[1], state: 'ON_HOLD', acknowledged: false }));
    await service.verify(user('owner-a'), { purchaseToken: token(1) });
    await service.verify(user('owner-a'), { purchaseToken: token(2) });
    const owner = accountId('owner-a');
    const quota = () => db.runTransaction(transaction => service.uploadQuota(transaction, owner, 60));
    assert.equal(await quota(), 60);
    assert.equal((await service.getPlan(user('owner-a'))).activeProductId, products[0].productId);
    setTime(now + 20_000);
    assert.equal(await quota(), 0);
    assert.equal((await service.getPlan(user('owner-a'))).uploadAllowed, false);
});

test('paid replacement retires the older token without a stale callback regranting it', async () => {
    const { purchases, service } = createHarness();
    purchases.set(token(1), subscription({ product: products[1] }));
    purchases.set(token(2), subscription({ linkedPurchaseToken: token(1) }));
    await service.verify(user('owner-a'), { purchaseToken: token(1) });
    await service.verify(user('owner-a'), { purchaseToken: token(2) });
    assert.equal((await service.verify(user('owner-a'), { purchaseToken: token(1) })).activeProductId, products[0].productId);
});

test('a replacement already on hold retires the older entitlement', async () => {
    const { purchases, service } = createHarness();
    purchases.set(token(1), subscription({ product: products[1] }));
    purchases.set(token(2), subscription({ state: 'ON_HOLD', linkedPurchaseToken: token(1) }));
    await service.verify(user('owner-a'), { purchaseToken: token(1) });
    assert.equal((await service.verify(user('owner-a'), { purchaseToken: token(2) })).uploadAllowed, false);
    assert.equal((await service.verify(user('owner-a'), { purchaseToken: token(1) })).uploadAllowed, false);
});

test('a canceled pending payment leaves the previous subscription available', async () => {
    const { purchases, service } = createHarness();
    purchases.set(token(1), subscription({ product: products[1] }));
    purchases.set(token(2), subscription({ state: 'PENDING_PURCHASE_CANCELED', linkedPurchaseToken: token(1) }));
    await service.verify(user('owner-a'), { purchaseToken: token(1) });
    assert.equal((await service.verify(user('owner-a'), { purchaseToken: token(2) })).quotaBytes, 200);
});

test('later verification wins if a stale Play result returns after the newer result', async () => {
    const { purchases, service } = createHarness();
    let release;
    let started;
    const entered = new Promise(resolve => { started = resolve; });
    purchases.set(token(1), () => { started(); return new Promise(resolve => { release = resolve; }); });
    const pending = assert.rejects(service.verify(user('owner-a'), { purchaseToken: token(1) }), isCode('aborted'));
    await entered;
    purchases.set(token(1), subscription({ state: 'EXPIRED', expires: now - 1 }));
    await service.verify(user('owner-a'), { purchaseToken: token(1) });
    release(subscription());
    await pending;
    assert.equal((await service.getPlan(user('owner-a'))).uploadAllowed, false);
});

test('Play notifications use canonical state and do not process another Android package', async () => {
    const { purchases, service } = createHarness();
    purchases.set(token(1), subscription());
    const event = { packageName: 'com.hebi.gallipick', subscriptionNotification: {
        notificationType: 2, purchaseToken: token(1),
    } };
    assert.equal(await service.reconcileNotification({ ...event, packageName: 'other.package' }), false);
    assert.equal((await service.getPlan(user('owner-a'))).uploadAllowed, false);
    assert.equal(await service.reconcileNotification(event), true);
    assert.equal((await service.getPlan(user('owner-a'))).uploadAllowed, true);
    purchases.set(token(1), subscription({ state: 'EXPIRED', expires: now - 1 }));
    assert.equal(await service.reconcileNotification(event), true);
    assert.equal((await service.getPlan(user('owner-a'))).uploadAllowed, false);
});

test('a permanently unavailable Play token removes the saved quota for its bound account', async () => {
    const { purchases, service } = createHarness();
    const event = { packageName: 'com.hebi.gallipick', subscriptionNotification: { purchaseToken: token(1) } };
    purchases.set(token(1), subscription());
    await service.verify(user('owner-a'), { purchaseToken: token(1) });
    purchases.set(token(1), () => { throw new BackupError('not-found', 'Purchase permanently unavailable'); });
    await assert.rejects(service.verify(user('owner-b'), { purchaseToken: token(1) }), isCode('permission-denied'));
    assert.equal((await service.getPlan(user('owner-a'))).quotaBytes, 100);
    assert.equal(await service.reconcileNotification(event), true);
    assert.equal((await service.getPlan(user('owner-a'))).quotaBytes, 0);
    purchases.set(token(1), subscription());
    assert.equal(await service.reconcileNotification(event), false);
    assert.equal((await service.verify(user('owner-a'), { purchaseToken: token(1) })).quotaBytes, 0);
});

test('permanent provider errors cannot create a binding for an unseen token', async () => {
    const { purchases, service } = createHarness();
    purchases.set(token(1), () => { throw new BackupError('not-found', 'Purchase permanently unavailable'); });
    await assert.rejects(service.verify(user('owner-a'), { purchaseToken: token(1) }), isCode('not-found'));
    purchases.set(token(1), subscription({ uid: 'owner-b' }));
    assert.equal((await service.verify(user('owner-b'), { purchaseToken: token(1) })).quotaBytes, 100);
});

test('a confirmed plan remains available until its Play expiry without a new device verification', async () => {
    const { purchases, service, setTime } = createHarness();
    purchases.set(token(1), subscription({ expires: now + 60 * 60 * 1000 }));
    await service.verify(user('owner-a'), { purchaseToken: token(1) });
    setTime(now + 15 * 60 * 1000);
    assert.equal((await service.getPlan(user('owner-a'))).uploadAllowed, true);
    setTime(now + 60 * 60 * 1000);
    assert.equal((await service.getPlan(user('owner-a'))).uploadAllowed, false);
});

const productionEnv = (mode, uids = '', emails = '') => ({ GALLIPICK_PRODUCTION_AD_FREE_ENABLED: 'true',
    GALLIPICK_PRODUCTION_BILLING_MODE: mode, GALLIPICK_PRODUCTION_BILLING_TEST_UIDS: uids,
    GALLIPICK_PRODUCTION_BILLING_TEST_EMAILS: emails });
const cloudEnv = (mode, uids = '') => ({ ...productionEnv(mode, uids), GALLIPICK_PRODUCTION_CLOUD_ENABLED: 'true',
    GALLIPICK_PRODUCTION_CLOUD_MAX_ORIGINAL_BYTES: '268435456',
    ...(mode === 'live' ? { GALLIPICK_PRODUCTION_CLOUD_LIVE_ENABLED: 'true' } : {}) });
const live = productionBillingSettings(PRODUCTION_PROJECT, productionEnv('live'));
const license = productionBillingSettings(PRODUCTION_PROJECT, productionEnv('license-test', 'owner-a'));
const liveCloud = productionBillingSettings(PRODUCTION_PROJECT, cloudEnv('live'));
const licenseCloud = productionBillingSettings(PRODUCTION_PROJECT, cloudEnv('license-test', 'owner-a'));
const [monthly, annual] = live.products;
const productId = 'gallipick_ad_free';
const verify = (service, uid, purchaseToken) => service.verify(user(uid), { purchaseToken, cloudProtocolVersion: 1 });
const renewalEvent = purchaseToken => ({ packageName: 'com.hebi.gallipick', subscriptionNotification: { purchaseToken } });
const cloudOffer = (gb, base = 'monthly') => liveCloud.products.find(item =>
    item.productId === `gallipick_cloud_${gb}gb` && item.basePlanId === base);
const checkoutCloud = productionBillingSettings(PRODUCTION_PROJECT, { ...cloudEnv('license-test', 'owner-a,owner-b'),
    GALLIPICK_PRODUCTION_CLOUD_UPLOADS_PAUSED: 'false' });

test('only the authenticated ordinary catalog is counted for an opted-in production overview', async () => {
    const { service, records, catalogQueries } = createHarness({ settings: licenseCloud });
    const owner = accountId('owner-a');
    const path = `gallipickProductionLicenseBackupAccounts/${owner}/originals`;
    records.set(`${path}/one`, { state: 'VERIFIED' });
    records.set(`${path}/two`, { state: 'VERIFIED' });
    records.set(`${path}/pending`, { state: 'PENDING' });
    records.set(`gallipickProductionLicenseBackupVaultAccounts/${owner}/originals/private`, { state: 'VERIFIED' });
    records.set(`gallipickProductionBackupAccounts/${owner}/originals/live`, { state: 'VERIFIED' });
    records.set(`gallipickProductionLicenseBackupAccounts/${accountId('owner-b')}/originals/other`, { state: 'VERIFIED' });
    const input = { includeVerifiedCount: true, cloudProtocolVersion: 1, uid: 'owner-b' };

    assert.equal((await service.getPlan(user('owner-a'))).verifiedMediaCount, undefined);
    assert.equal((await service.getPlan(user('owner-a'), { includeVerifiedCount: true })).verifiedMediaCount, undefined);
    await assert.rejects(service.getPlan(user('owner-b'), input), isCode('permission-denied'));
    assert.deepEqual(catalogQueries, []);
    assert.equal((await service.getPlan(user('owner-a'), input)).verifiedMediaCount, 2);
    assert.deepEqual(catalogQueries, [{ path, field: 'state', operator: '==', value: 'VERIFIED', maximum: 20_000 }]);
});

test('empty, bounded and unavailable catalog aggregation preserve the original plan and usage', async () => {
    const { service, db, records, catalogQueries } = createHarness();
    const owner = accountId('owner-a');
    const path = `gallipickBackupAccounts/${owner}/originals`;
    const input = { includeVerifiedCount: true };
    records.set(`gallipickBackupAccounts/${owner}`, { allocatedBytes: 512 });
    assert.equal((await service.getPlan(user('owner-a'), input)).verifiedMediaCount, 0);
    for (let index = 0; index < 250; index++) records.set(`${path}/${index}`, { state: 'VERIFIED' });
    assert.equal((await service.getPlan(user('owner-a'), input)).verifiedMediaCount, null);
    assert.equal(catalogQueries.at(-1).maximum, 250);
    db.collection = () => { throw new Error('Aggregation unavailable'); };
    const plan = await service.getPlan(user('owner-a'), input);
    assert.equal(plan.verifiedMediaCount, null);
    assert.equal(plan.usedBytes, 512);
});

test('only fresh verification of the sole active token returns its Play recurring price for a replacement', async () => {
    const { service, purchases, records } = createHarness({ settings: checkoutCloud });
    const recurringPrice = { currencyCode: 'USD', units: '7', nanos: 490_000_000 };
    purchases.set(token(1), subscription({ product: cloudOffer(10), recurringPrice }));
    purchases.set(token(2), subscription({ product: annual, recurringPrice }));
    const plan = await verify(service, 'owner-a', token(1));
    assert.equal(plan.replacementSourceAllowed, true);
    assert.deepEqual(plan.replacementSourcePrice, { amountMicros: 7_490_000, currencyCode: 'USD' });
    assert.equal(plan.scheduledReplacement, null);
    assert.equal(JSON.stringify(plan).includes(token(1)), false);
    assert.equal(JSON.stringify([...records]).includes(token(1)), false);
    assert.equal((await service.getPlan(user('owner-a'))).replacementSourceAllowed, false);
    assert.equal((await service.verify(user('owner-a'), { purchaseToken: token(1) })).replacementSourceAllowed, false);
    await assert.rejects(verify(service, 'owner-b', token(1)), isCode('permission-denied'));

    assert.equal((await verify(service, 'owner-a', token(2))).replacementSourceAllowed, false);
    assert.equal((await verify(service, 'owner-a', token(1))).replacementSourceAllowed, false);
});

test('paused checkout, inactive renewal and an imprecise Play price cannot authorize a replacement', async () => {
    const { service, serviceFor, purchases } = createHarness({ settings: checkoutCloud });
    const price = { currencyCode: 'KRW', units: '1900' };
    const active = subscription({ product: cloudOffer(10), recurringPrice: price });
    purchases.set(token(1), active);
    assert.equal((await verify(serviceFor(licenseCloud), 'owner-a', token(1))).replacementSourceAllowed, false);
    for (const current of [
        subscription({ product: cloudOffer(10), state: 'IN_GRACE_PERIOD', recurringPrice: price }),
        subscription({ product: cloudOffer(10), acknowledged: false, recurringPrice: price }),
        subscription({ product: cloudOffer(10), recurringPrice: { ...price, nanos: 1 } }),
        { ...active, lineItems: [{ ...active.lineItems[0], autoRenewingPlan: { autoRenewEnabled: false, recurringPrice: price } }] },
    ]) {
        purchases.set(token(1), current);
        const plan = await verify(service, 'owner-a', token(1));
        assert.equal(plan.replacementSourceAllowed, false);
        assert.equal(plan.replacementSourcePrice, null);
        assert.equal(plan.quotaBytes, 10_000_000_000);
    }
});

test('paid Cloud requires an explicit project-scoped rollout and bounded original size', async () => {
    for (const value of [undefined, '0', '-64', '268435457', '64.5', '2e7', ' 256 ']) {
        const disabled = productionBillingSettings(PRODUCTION_PROJECT, { ...cloudEnv('live'),
            GALLIPICK_PRODUCTION_CLOUD_MAX_ORIGINAL_BYTES: value });
        assert.equal(disabled.cloudEnabled, false);
        assert.equal(disabled.maxOriginalBytes, null);
        assert.deepEqual(disabled.products, live.products);
    }
    const flagOff = { ...cloudEnv('live'), GALLIPICK_PRODUCTION_CLOUD_ENABLED: 'false' };
    assert.equal(productionBillingSettings(PRODUCTION_PROJECT, flagOff).cloudEnabled, false);
    const noLiveApproval = { ...cloudEnv('license-test'), ...productionEnv('live') };
    const adOnlyLive = productionBillingSettings(PRODUCTION_PROJECT, noLiveApproval);
    assert.equal(adOnlyLive.cloudEnabled, false);
    assert.deepEqual(adOnlyLive.products, live.products);
    assert.equal(productionBillingSettings('gallipick-dev', cloudEnv('live')).cloudEnabled, false);
    assert.equal(liveCloud.maxOriginalBytes, 268_435_456);
    assert.equal(liveCloud.products.length, 8);
    for (const base of ['monthly', 'annual']) {
        for (const gb of [10, 50, 100]) {
            assert.deepEqual(cloudOffer(gb, base), {
                productId: `gallipick_cloud_${gb}gb`, basePlanId: base, kind: 'CLOUD', quotaBytes: gb * 1_000_000_000,
            });
        }
    }
    const { service, purchases } = createHarness({ settings: live });
    assert.deepEqual((await service.getPlan(user('owner-a'))).products, live.products);
    purchases.set(token(1), subscription({ product: cloudOffer(10), testPurchase: null }));
    const existingCloud = await verify(service, 'owner-a', token(1));
    assert.equal(existingCloud.activeProductId, cloudOffer(10).productId);
    assert.equal(existingCloud.quotaBytes, 0);
    assert.equal(existingCloud.uploadAllowed, false);
    assert.throws(() => service.authorizeBackup(user('owner-a')), isCode('failed-precondition'));
});

test('paid Cloud uses exact verified quota and reports scoped usage for the signed-in owner', async () => {
    const { service, purchases, db, records, setTime } = createHarness({ settings: liveCloud });
    const uid = 'owner-a';
    const auth = user(uid);
    const owner = accountId(uid);
    const quota = (ceiling = 100_000_000_000) =>
        db.runTransaction(transaction => service.uploadQuota(transaction, owner, ceiling));
    assert.equal(service.authorizeBackup(auth), owner);
    assert.equal((await service.getPlan(auth)).backupAvailable, true);
    assert.equal(await quota(), 0);
    purchases.set(token(1), subscription({ product: cloudOffer(50, 'annual'), state: 'CANCELED', testPurchase: null }));
    purchases.set(token(2), subscription({ product: monthly, testPurchase: null }));
    const usage = `gallipickProductionBackupAccounts/${owner}`;
    records.set(usage, { allocatedBytes: 12_000_000_000 });
    records.set(`gallipickProductionLicenseBackupAccounts/${owner}`, { allocatedBytes: 90_000_000_000 });
    await assert.rejects(verify(service, 'owner-b', token(1)), isCode('permission-denied'));
    let plan = await verify(service, uid, token(1));
    assert.equal(plan.testPurchasesOnly, false);
    assert.equal(plan.activeProductId, 'gallipick_cloud_50gb');
    assert.equal(plan.activeBasePlanId, 'annual');
    assert.equal(plan.expiresAtMs, now + 20_000);
    assert.equal(plan.adFreeProductId, null);
    assert.equal(plan.quotaBytes, 50_000_000_000);
    assert.equal(plan.usedBytes, 12_000_000_000);
    assert.equal(plan.uploadAllowed, true);
    assert.equal(await quota(), 50_000_000_000);
    assert.equal(await quota(42_000_000_000), 42_000_000_000);
    await assert.rejects(quota(NaN), isCode('failed-precondition'));

    records.set(usage, { allocatedBytes: 50_000_000_000 });
    plan = await verify(service, uid, token(2));
    assert.equal(plan.uploadAllowed, false);
    assert.equal(plan.adFreeProductId, productId);
    assert.equal(plan.adFreeBasePlanId, 'monthly');
    assert.equal(plan.activeProductId, 'gallipick_cloud_50gb');

    setTime(now + 20_000);
    plan = await service.getPlan(auth);
    assert.equal(plan.backupAvailable, true);
    assert.equal(plan.quotaBytes, 0);
    assert.equal(plan.usedBytes, 50_000_000_000);
    assert.equal(plan.uploadAllowed, false);
    assert.equal(await quota(), 0);
    assert.equal(service.authorizeBackup(auth), owner);
});

test('test and live Cloud keep paid plans and usage isolated and reject pending or unknown offers', async () => {
    const { service, serviceFor, purchases, records } = createHarness({ settings: licenseCloud });
    const paid = serviceFor(liveCloud);
    const uid = 'owner-a';
    const owner = accountId(uid);
    const auth = user(uid);
    assert.throws(() => service.authorizeBackup(user('owner-b')), isCode('permission-denied'));
    purchases.set(token(1), subscription({ product: cloudOffer(100), state: 'PENDING' }));
    purchases.set(token(2), subscription({ product: { ...cloudOffer(100), basePlanId: 'unknown' } }));
    purchases.set(token(3), subscription({ product: cloudOffer(10), testPurchase: null }));
    purchases.set(token(4), subscription({ product: cloudOffer(50, 'annual') }));
    records.set(`gallipickProductionLicenseBackupAccounts/${owner}`, { allocatedBytes: 12 });
    records.set(`gallipickProductionBackupAccounts/${owner}`, { allocatedBytes: 34 });
    let testPlan = await verify(service, uid, token(1));
    assert.equal(testPlan.testPurchasesOnly, true);
    assert.equal(testPlan.quotaBytes, 0);
    await assert.rejects(verify(service, uid, token(2)), isCode('failed-precondition'));
    await assert.rejects(verify(service, uid, token(3)), isCode('permission-denied'));
    await assert.rejects(verify(paid, uid, token(4)), isCode('permission-denied'));
    testPlan = await verify(service, uid, token(4));
    assert.equal(testPlan.quotaBytes, 50_000_000_000);
    assert.equal(testPlan.usedBytes, 12);
    assert.equal((await paid.getPlan(auth)).quotaBytes, 0);
    assert.equal((await paid.getPlan(auth)).usedBytes, 34);
    assert.equal((await verify(paid, uid, token(3))).quotaBytes, 10_000_000_000);
    assert.equal((await service.getPlan(auth)).quotaBytes, 50_000_000_000);
    assert.equal(JSON.stringify([...records]).includes(token(4)), false);
});

test('deferred Play changes preserve the old verified tier until the replacement becomes effective', async () => {
    for (const [previousGb, replacementGb] of [[10, 100], [100, 10]]) {
        const { service, purchases, acknowledged, setTime } = createHarness({ settings: licenseCloud });
        const uid = 'owner-a';
        const auth = user(uid);
        const previous = subscription({ product: cloudOffer(previousGb), expires: now + 20_000 });
        const replacement = subscription({ product: cloudOffer(replacementGb, 'annual'), linkedPurchaseToken: token(1),
            expires: now + 100_000 });
        const oldItem = previous.lineItems[0];
        const futureItem = replacement.lineItems[0];
        purchases.set(token(1), previous);
        await verify(service, uid, token(1));
        purchases.set(token(2), { ...replacement, acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING', lineItems: [
            { ...oldItem, autoRenewingPlan: { autoRenewEnabled: false },
                deferredItemReplacement: { productId: futureItem.productId } },
            { ...futureItem, expiryTime: undefined, autoRenewingPlan: undefined },
        ] });
        assert.equal(await service.reconcileNotification(renewalEvent(token(2))), true);
        assert.deepEqual(acknowledged, [[token(2), futureItem.productId]]);
        assert.equal(await service.reconcileNotification(renewalEvent(token(1))), false);
        assert.equal((await service.getPlan(auth)).quotaBytes, previousGb * 1_000_000_000);
        assert.equal((await service.getPlan(auth)).activeBasePlanId, 'monthly');
        assert.deepEqual((await service.getPlan(auth)).scheduledReplacement, {
            productId: futureItem.productId, basePlanId: 'annual', effectiveAtMs: now + 20_000,
        });
        assert.equal((await verify(service, uid, token(2))).replacementSourceAllowed, false);
        setTime(now + 20_000);
        assert.equal((await service.getPlan(auth)).quotaBytes, 0);
        assert.equal((await service.getPlan(auth)).scheduledReplacement, null);
        purchases.set(token(2), { ...replacement, lineItems: [oldItem, futureItem] });
        assert.equal(await service.reconcileNotification(renewalEvent(token(2))), true);
        assert.equal((await service.getPlan(auth)).quotaBytes, replacementGb * 1_000_000_000);
        assert.equal((await service.getPlan(auth)).activeBasePlanId, 'annual');
        assert.equal((await verify(service, uid, token(1))).quotaBytes, replacementGb * 1_000_000_000);
        purchases.set(token(2), { ...replacement, subscriptionState: 'SUBSCRIPTION_STATE_ON_HOLD',
            lineItems: [oldItem, futureItem] });
        assert.equal(await service.reconcileNotification(renewalEvent(token(2))), true);
        assert.equal((await service.getPlan(auth)).quotaBytes, 0);
    }
});

test('unrecognized multi-item replacements cannot acknowledge or replace the original purchase', async () => {
    const { service, purchases, acknowledged } = createHarness({ settings: licenseCloud });
    const previous = subscription({ product: cloudOffer(10) });
    const replacement = subscription({ product: cloudOffer(100, 'annual'), linkedPurchaseToken: token(1), acknowledged: false });
    purchases.set(token(1), previous);
    await verify(service, 'owner-a', token(1));
    purchases.set(token(2), { ...replacement, lineItems: [...previous.lineItems, ...replacement.lineItems] });
    await assert.rejects(verify(service, 'owner-a', token(2)), isCode('failed-precondition'));
    assert.equal((await verify(service, 'owner-a', token(1))).quotaBytes, 10_000_000_000);
    assert.deepEqual(acknowledged, []);
});

test('production billing activation is project scoped with no active Cloud products', async () => {
    assert.equal(productionBillingSettings(PRODUCTION_PROJECT, {}).enabled, false);
    assert.equal(productionBillingSettings('gallipick-dev', productionEnv('live')).enabled, false);
    assert.throws(() => productionBillingSettings(PRODUCTION_PROJECT, productionEnv('invalid')), isCode('failed-precondition'));
    assert.throws(() => productionBillingSettings(PRODUCTION_PROJECT, productionEnv('license-test')), isCode('failed-precondition'));
    assert.throws(() => productionBillingSettings(PRODUCTION_PROJECT, productionEnv('live', 'owner-a')), isCode('failed-precondition'));
    assert.deepEqual(live.products, ['monthly', 'annual'].map(basePlanId => ({ productId, basePlanId,
        kind: 'AD_FREE', quotaBytes: 0 })));
    const { service, db } = createHarness({ settings: live });
    const plan = await service.getPlan(user('owner-a'));
    assert.deepEqual(plan.products, live.products);
    assert.equal(plan.obfuscatedAccountId, accountId('owner-a'));
    assert.equal(plan.activeProductId, null);
    assert.equal(plan.adFreeProductId, null);
    assert.equal(plan.uploadAllowed, false);
    assert.equal(await db.runTransaction(transaction => service.uploadQuota(transaction, accountId('owner-a'), 60)), 0);
});

test('license-test entitlements cannot grant live benefits and paid receipts are rejected in that mode', async () => {
    const { service, serviceFor, purchases } = createHarness({ settings: license });
    const paid = serviceFor(live);
    purchases.set(token(1), subscription({ product: monthly }));
    purchases.set(token(2), subscription({ product: annual, testPurchase: null }));
    await assert.rejects(service.getPlan(user('owner-b')), isCode('permission-denied'));
    await assert.rejects(verify(service, 'owner-a', token(2)), isCode('permission-denied'));
    await assert.rejects(verify(paid, 'owner-a', token(1)), isCode('permission-denied'));
    assert.equal((await verify(service, 'owner-a', token(1))).adFreeProductId, productId);
    assert.equal((await paid.getPlan(user('owner-a'))).adFreeProductId, null);
    assert.equal((await verify(paid, 'owner-a', token(2))).adFreeProductId, productId);
});

test('annual production purchase verification grants no backup and is acknowledged without storing raw tokens', async () => {
    const { service, purchases, acknowledged, records } = createHarness({ settings: live });
    purchases.set(token(1), subscription({ product: annual, acknowledged: false, testPurchase: null, expires: now + 555_000 }));
    purchases.set(token(2), subscription({ product: products[0], testPurchase: null }));
    await assert.rejects(verify(service, 'owner-b', token(1)), isCode('permission-denied'));
    await assert.rejects(verify(service, 'owner-a', token(2)), isCode('failed-precondition'));
    const verified = await verify(service, 'owner-a', token(1));
    assert.equal(verified.adFreeProductId, productId);
    assert.equal(verified.adFreeExpiresAtMs, now + 555_000);
    assert.equal(verified.activeProductId, null);
    assert.equal(verified.expiresAtMs, null);
    assert.equal(verified.quotaBytes, 0);
    assert.equal(verified.uploadAllowed, false);
    assert.deepEqual(acknowledged, [[token(1), productId]]);
    assert.equal(JSON.stringify([...records]).includes(token(1)), false);
});

test('a production replacement can restore from a verified prior binding with no external account identifier', async () => {
    const { service, purchases } = createHarness({ settings: live });
    purchases.set(token(1), subscription({ product: monthly, testPurchase: null }));
    purchases.set(token(2), { ...subscription({ product: annual, testPurchase: null, linkedPurchaseToken: token(1) }),
        externalAccountIdentifiers: undefined });
    await assert.rejects(verify(service, 'owner-a', token(2)), isCode('permission-denied'));
    await verify(service, 'owner-a', token(1));
    await assert.rejects(verify(service, 'owner-b', token(2)), isCode('permission-denied'));
    assert.equal(await service.reconcileNotification(renewalEvent(token(2))), true);
    assert.equal(await service.reconcileNotification(renewalEvent(token(1))), false);
    assert.equal((await verify(service, 'owner-a', token(2))).adFreeProductId, productId);
});

test('production notifications recheck canonical billing state without binding previously unverified purchases', async () => {
    const { service, purchases, acknowledged } = createHarness({ settings: live });
    purchases.set(token(1), subscription({ product: monthly, testPurchase: null, acknowledged: false }));
    assert.equal(await service.reconcileNotification(renewalEvent(token(1))), false);
    await verify(service, 'owner-a', token(1));
    assert.equal(await service.reconcileNotification(renewalEvent(token(1))), true);
    assert.equal(await service.reconcileNotification(renewalEvent(token(1))), true);
    assert.deepEqual(acknowledged, [[token(1), productId]]);
    purchases.set(token(1), subscription({ product: monthly, testPurchase: null, state: 'ON_HOLD' }));
    assert.equal(await service.reconcileNotification(renewalEvent(token(1))), true);
    assert.equal((await service.getPlan(user('owner-a'))).adFreeProductId, null);
});

test('verified Google email can enroll a new UID only in the production license test environment', async () => {
    const email = 'cloud-tester@example.com';
    const settings = productionBillingSettings(PRODUCTION_PROJECT, productionEnv('license-test', '', email));
    const { service, purchases } = createHarness({ settings });
    const uid = 'new-license-tester';
    function auth(provider, verified, firebaseEmail = email) {
        return { uid, token: { email: firebaseEmail, email_verified: verified, firebase: { sign_in_provider: provider } } };
    }
    const allowedAuth = auth('google.com', true, email.toUpperCase());
    const request = { purchaseToken: token(1), email };
    purchases.set(token(1), subscription({ uid, product: monthly }));
    await assert.rejects(service.getPlan(auth('google.com', false)), isCode('permission-denied'));
    await assert.rejects(service.getPlan(auth('password', true)), isCode('permission-denied'));
    await assert.rejects(service.verify(auth('google.com', true, 'another@example.com'), request), isCode('permission-denied'));
    assert.equal((await service.getPlan(allowedAuth)).obfuscatedAccountId, accountId(uid));
    assert.equal((await service.verify(allowedAuth, request)).adFreeProductId, productId);
    assert.throws(() => productionBillingSettings(PRODUCTION_PROJECT, productionEnv('license-test', '', email.toUpperCase())),
        isCode('failed-precondition'));
    assert.throws(() => productionBillingSettings(PRODUCTION_PROJECT, productionEnv('license-test', '', 'missing-domain')),
        isCode('failed-precondition'));
    assert.throws(() => productionBillingSettings(PRODUCTION_PROJECT, productionEnv('live', '', email)),
        isCode('failed-precondition'));
});

test('license RTDN refresh uses only a verified existing email binding and respects allowlist removal', async () => {
    const email = 'cloud-tester@example.com';
    const settings = productionBillingSettings(PRODUCTION_PROJECT, productionEnv('license-test', '', email));
    const { service, serviceFor, purchases, records } = createHarness({ settings });
    const uid = 'new-license-tester';
    const auth = { uid, token: { email, email_verified: true, firebase: { sign_in_provider: 'google.com' } } };
    purchases.set(token(1), subscription({ uid, product: monthly }));
    assert.equal(await service.reconcileNotification(renewalEvent(token(1))), false);
    await service.verify(auth, { purchaseToken: token(1) });
    assert.equal(JSON.stringify([...records]).includes(email), false);
    assert.equal(await service.reconcileNotification(renewalEvent(token(1))), true);

    purchases.set(token(2), { ...subscription({ product: annual, linkedPurchaseToken: token(1) }),
        externalAccountIdentifiers: undefined });
    assert.equal(await service.reconcileNotification(renewalEvent(token(2))), true);
    assert.equal(await service.reconcileNotification(renewalEvent(token(1))), false);

    const afterRemoval = serviceFor(productionBillingSettings(PRODUCTION_PROJECT, productionEnv('license-test', 'owner-a')));
    assert.equal(await afterRemoval.reconcileNotification(renewalEvent(token(2))), false);
    await assert.rejects(afterRemoval.getPlan(auth), isCode('permission-denied'));
});

const guardedLiveCloud = { ...liveCloud, retentionEnabled: true };
const guardedLicenseCloud = { ...licenseCloud, retentionEnabled: true };

test('production Cloud retention must be explicitly enabled and never opens dev or ad-free access', async () => {
    assert.equal(liveCloud.retentionEnabled, false);
    assert.equal(licenseCloud.retentionEnabled, false);
    assert.equal(productionBillingSettings(PRODUCTION_PROJECT, {
        ...cloudEnv('live'), GALLIPICK_PRODUCTION_CLOUD_RETENTION_ENABLED: 'true',
    }).retentionEnabled, true);
    assert.equal(productionBillingSettings('gallipick-dev', {
        ...cloudEnv('live'), GALLIPICK_PRODUCTION_CLOUD_RETENTION_ENABLED: 'true',
    }).enabled, false);
    const { service, purchases, records } = createHarness({ settings: guardedLiveCloud });
    const auth = user('owner-a');
    await assert.rejects(service.requireCloudAccess(auth, 'read'), isCode('permission-denied'));
    await assert.rejects(service.requireCloudAccess(auth, 'write'), isCode('permission-denied'));
    purchases.set(token(1), subscription({ product: monthly, testPurchase: null }));
    await verify(service, 'owner-a', token(1));
    await assert.rejects(service.requireCloudAccess(auth, 'read'), isCode('permission-denied'));
    assert.equal(records.get(`gallipickProductionBillingAccounts/${accountId('owner-a')}`).cloudLifecycle, undefined);
    await assert.rejects(service.requireCloudAccess(user('owner-b'), 'root'), isCode('invalid-argument'));
    const disabled = createHarness({ settings: liveCloud }).service;
    assert.equal(await disabled.requireCloudAccess(auth, 'read'), undefined);
});

test('Play-verified Cloud purchase keeps durable expiry and enforces seven-day read-only recovery', async () => {
    const { service, purchases, records, setTime } = createHarness({ settings: guardedLiveCloud });
    const auth = user('owner-a');
    const expiry = now + 20_000;
    purchases.set(token(1), subscription({ product: cloudOffer(10), testPurchase: null, expires: expiry }));
    let plan = await verify(service, 'owner-a', token(1));
    assert.equal(plan.cloudLifecycleState, 'PAID');
    assert.equal(plan.cloudReadAllowed, true);
    assert.equal(plan.cloudRecoveryEndsAtMs, null);
    const record = records.get(`gallipickProductionBillingAccounts/${accountId('owner-a')}`);
    assert.deepEqual(Object.keys(record.cloudLifecycle).sort(),
        ['basePlanId', 'checkedAtMs', 'expiresAtMs', 'productId', 'recoveryAllowed', 'tokenHash'].sort());
    assert.equal(record.cloudLifecycle.expiresAtMs, expiry);
    assert.equal(record.cloudLifecycle.recoveryAllowed, true);
    assert.equal(JSON.stringify([...records]).includes(token(1)), false);
    assert.equal((await service.requireCloudAccess(auth, 'write')).state, 'PAID');

    setTime(expiry);
    plan = await service.getPlan(auth);
    assert.equal(plan.activeProductId, null);
    assert.equal(plan.cloudLifecycleState, 'READ_ONLY_RECOVERY');
    assert.equal(plan.cloudReadAllowed, true);
    assert.equal(plan.cloudRecoveryEndsAtMs, expiry + 7 * 86_400_000);
    assert.equal(plan.cloudDeletionDueAtMs, expiry + 14 * 86_400_000);
    assert.equal((await service.requireCloudAccess(auth, 'read')).state, 'READ_ONLY_RECOVERY');
    await assert.rejects(service.requireCloudAccess(auth, 'write'), isCode('permission-denied'));

    setTime(expiry + 7 * 86_400_000);
    assert.equal((await service.getPlan(auth)).cloudLifecycleState, 'LOCKED_AWAITING_PURGE');
    await assert.rejects(service.requireCloudAccess(auth, 'read'), isCode('permission-denied'));
    setTime(expiry + 14 * 86_400_000);
    assert.equal((await service.getPlan(auth)).cloudLifecycleState, 'PURGE_DUE');
    // This state is not a deletion worker and must not mutate any originals.
    assert.equal(record.cloudLifecycle.expiresAtMs, expiry);
});

test('Play RTDN renewal moves expiry forward and hold/revocation removes access without deleting data', async () => {
    const { service, purchases, setTime, records } = createHarness({ settings: guardedLicenseCloud });
    const auth = user('owner-a');
    const oldExpiry = now + 30_000;
    const renewal = now + 80 * 86_400_000;
    purchases.set(token(1), subscription({ product: cloudOffer(50), expires: oldExpiry }));
    await verify(service, 'owner-a', token(1));
    setTime(oldExpiry + 1000);
    assert.equal((await service.getPlan(auth)).cloudLifecycleState, 'READ_ONLY_RECOVERY');
    purchases.set(token(1), subscription({ product: cloudOffer(50), expires: renewal }));
    assert.equal(await service.reconcileNotification(renewalEvent(token(1))), true);
    assert.equal((await service.getPlan(auth)).cloudLifecycleState, 'PAID');
    const accountPath = `gallipickProductionLicenseBillingAccounts/${accountId('owner-a')}`;
    assert.equal(records.get(accountPath).cloudLifecycle.expiresAtMs, renewal);
    assert.equal((await service.requireCloudAccess(auth, 'write')).state, 'PAID');

    purchases.set(token(1), subscription({ product: cloudOffer(50), expires: renewal, state: 'ON_HOLD' }));
    assert.equal(await service.reconcileNotification(renewalEvent(token(1))), true);
    assert.equal((await service.getPlan(auth)).cloudLifecycleState, 'CLOUD_ACCESS_SUSPENDED');
    await assert.rejects(service.requireCloudAccess(auth, 'read'), isCode('permission-denied'));

    purchases.set(token(1), () => { throw new BackupError('not-found', 'The purchase has been revoked.'); });
    assert.equal(await service.reconcileNotification(renewalEvent(token(1))), true);
    assert.equal(records.get(accountPath).cloudLifecycle.recoveryAllowed, false);
    await assert.rejects(service.requireCloudAccess(auth, 'read'), isCode('permission-denied'));
});

test('license-test and live Cloud access remain isolated, even for the same Firebase UID', async () => {
    const { service, purchases, serviceFor } = createHarness({ settings: guardedLicenseCloud });
    const paid = serviceFor(guardedLiveCloud);
    const auth = user('owner-a');
    purchases.set(token(1), subscription({ product: cloudOffer(10) }));
    assert.equal((await verify(service, 'owner-a', token(1))).cloudLifecycleState, 'PAID');
    assert.equal((await service.requireCloudAccess(auth, 'read')).state, 'PAID');
    await assert.rejects(paid.requireCloudAccess(auth, 'read'), isCode('permission-denied'));
    await assert.rejects(service.requireCloudAccess(user('owner-b'), 'read'), isCode('permission-denied'));
});
