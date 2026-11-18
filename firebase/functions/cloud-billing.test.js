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
    let time = now;
    let pending = Promise.resolve();
    const db = {
        doc: path => ({ path, get: async () => ({ data: () => records.get(path) }) }),
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
    return { records, purchases, acknowledged, service: serviceFor(settings), serviceFor, db,
        setTime: value => { time = value; } };
}

function subscription({ uid = 'owner-a', product = products[0], state = 'ACTIVE', expires = now + 20_000,
    acknowledged = true, testPurchase = {}, linkedPurchaseToken } = {}) {
    return {
        testPurchase, linkedPurchaseToken,
        externalAccountIdentifiers: { obfuscatedExternalAccountId: accountId(uid) },
        subscriptionState: `SUBSCRIPTION_STATE_${state}`,
        acknowledgementState: `ACKNOWLEDGEMENT_STATE_${acknowledged ? 'ACKNOWLEDGED' : 'PENDING'}`,
        lineItems: [{ productId: product.productId, expiryTime: new Date(expires).toISOString(),
            offerDetails: { basePlanId: product.basePlanId }, autoRenewingPlan: { autoRenewEnabled: true } }],
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

const productionEnv = (mode, uids = '') => ({ GALLIPICK_PRODUCTION_AD_FREE_ENABLED: 'true',
    GALLIPICK_PRODUCTION_BILLING_MODE: mode, GALLIPICK_PRODUCTION_BILLING_TEST_UIDS: uids });
const live = productionBillingSettings(PRODUCTION_PROJECT, productionEnv('live'));
const license = productionBillingSettings(PRODUCTION_PROJECT, productionEnv('license-test', 'owner-a'));
const [monthly, annual] = live.products;
const productId = 'gallipick_ad_free';
const verify = (service, uid, purchaseToken) => service.verify(user(uid), { purchaseToken });
const renewalEvent = purchaseToken => ({ packageName: 'com.hebi.gallipick', subscriptionNotification: { purchaseToken } });

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
