import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { billingSettings, createBillingService, PILOT_ACCOUNT_QUOTA_BYTES } from './cloud-billing.js';

const products = [
    { productId: 'cloud_small_test', basePlanId: 'monthly', quotaBytes: 100 },
    { productId: 'cloud_large_test', basePlanId: 'monthly', quotaBytes: 200 },
];
const user = uid => ({ uid, token: { firebase: { sign_in_provider: 'google.com' } } });
const accountId = uid => createHash('sha256').update(uid).digest('hex');
const token = n => `synthetic-test-purchase-token-${n}`;
const isCode = code => error => error.code === code;
const now = 1_800_000_000_000;

function createHarness({ enabled = true, acknowledge } = {}) {
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
    const service = createBillingService({ db, play, settings: { enabled, products },
        pilotUsers: new Set(['owner-a', 'owner-b']), now: () => time });
    return { records, purchases, acknowledged, service, db, setTime: value => { time = value; } };
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

test('a confirmed plan remains available until its Play expiry without a new device verification', async () => {
    const { purchases, service, setTime } = createHarness();
    purchases.set(token(1), subscription({ expires: now + 60 * 60 * 1000 }));
    await service.verify(user('owner-a'), { purchaseToken: token(1) });
    setTime(now + 15 * 60 * 1000);
    assert.equal((await service.getPlan(user('owner-a'))).uploadAllowed, true);
    setTime(now + 60 * 60 * 1000);
    assert.equal((await service.getPlan(user('owner-a'))).uploadAllowed, false);
});
