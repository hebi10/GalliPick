import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { CLOUD_PURGE_WINDOW_MS } from './cloud-retention-policy.js';
import { evaluateFreshPlayExpiry, createCloudPlayExpiryRecheckService } from './cloud-play-expiry-recheck.js';

const hash = v => createHash('sha256').update(v).digest('hex');
const nowMs = 1_800_000_000_000;
const owner = hash('recheck-member');
const token = 'synthetic-play-recheck-purchase-token';
const tokenHash = hash(token);
const scope = 'gallipickProductionBilling';
const expiry = nowMs - CLOUD_PURGE_WINDOW_MS;
const candidate = {
    owner, sourceScope: scope, status: 'NEEDS_PLAY_RECHECK',
    purchaseTokenHash: tokenHash, verifiedExpiryAtMs: expiry,
    deletionDueAtMs: nowMs, billingRevision: 2,
};
const billing = {
    revision: 2, entitlements: {},
    cloudLifecycle: { tokenHash, productId: 'gallipick_cloud_10gb', basePlanId: 'monthly',
        expiresAtMs: expiry, checkedAtMs: expiry, recoveryAllowed: true },
};
const play = {
    subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED',
    externalAccountIdentifiers: { obfuscatedExternalAccountId: owner },
    testPurchase: null,
    lineItems: [{ productId: 'gallipick_cloud_10gb',
        offerDetails: { basePlanId: 'monthly' },
        expiryTime: new Date(expiry).toISOString() }],
};
const base = () => ({
    owner, mode: 'live', candidate: structuredClone(candidate),
    billing: structuredClone(billing), otherBilling: { entitlements: {} },
    exit: null, liveManual: null, licenseManual: null, play: structuredClone(play), nowMs,
});

test('even freshly Play-confirmed expired Cloud is never approved for deletion', () => {
    const verified = evaluateFreshPlayExpiry(base());
    assert.deepEqual(verified, {
        status: 'PLAY_EXPIRED_CONFIRMED', deletionAllowed: false,
        checkedAtMs: nowMs, verifiedPlayExpiryAtMs: expiry,
    });
    assert.equal(evaluateFreshPlayExpiry({ ...base(), play: null }).status, 'PLAY_RECHECK_UNAVAILABLE');
});

test('renewal, 14-day retention, changed billing revision and active sibling entitlements block expiry', () => {
    const b = base();
    b.billing.entitlements = { renewed: { expiresAtMs: nowMs + 86_400_000 } };
    assert.equal(evaluateFreshPlayExpiry(b).status, 'ACTIVE_ENTITLEMENT');
    b.billing.entitlements = {};
    b.otherBilling.entitlements = { other: { expiresAtMs: nowMs + 86_400_000 } };
    assert.equal(evaluateFreshPlayExpiry(b).status, 'ACTIVE_ENTITLEMENT');
    b.otherBilling.entitlements = {};
    b.billing.revision = 3;
    assert.equal(evaluateFreshPlayExpiry(b).status, 'STALE_BILLING_REVISION');
    b.billing.revision = 2;
    b.candidate.deletionDueAtMs++;
    assert.equal(evaluateFreshPlayExpiry(b).status, 'STALE_BILLING_REVISION');
    b.candidate.deletionDueAtMs = nowMs;
    b.play.lineItems[0].expiryTime = new Date(nowMs - 5 * 86_400_000).toISOString();
    assert.equal(evaluateFreshPlayExpiry(b).status, 'PLAY_RETENTION_NOT_COMPLETE');
});

test('a currently active manual Cloud benefit, even in the other isolated mode, blocks expiry', () => {
    const b = base();
    b.licenseManual = { source: 'ADMIN_MANUAL', ownerId: owner, mode: 'license-test',
        status: 'ACTIVE', revision: 1, kind: 'CLOUD_50GB',
        issuedAtMs: nowMs - 86_400_000, expiresAtMs: nowMs + 86_400_000 };
    assert.equal(evaluateFreshPlayExpiry(b).status, 'ACTIVE_MANUAL_ACCESS');
    b.licenseManual = null;
    b.exit = { scope: 'ACCOUNT', status: 'REQUESTED' };
    assert.equal(evaluateFreshPlayExpiry(b).status, 'EXISTING_DELETION');
});

test('Play ownership, billing mode, subscription status and exact product must all be verified', () => {
    const a = base();
    a.play.externalAccountIdentifiers.obfuscatedExternalAccountId = hash('different-user');
    assert.equal(evaluateFreshPlayExpiry(a).status, 'PLAY_OWNERSHIP_UNVERIFIED');
    a.play.externalAccountIdentifiers.obfuscatedExternalAccountId = owner;
    a.play.testPurchase = {};
    assert.equal(evaluateFreshPlayExpiry(a).status, 'PLAY_MODE_MISMATCH');
    a.play.testPurchase = null;
    for (const state of ['SUBSCRIPTION_STATE_ACTIVE', 'SUBSCRIPTION_STATE_CANCELED',
        'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', 'SUBSCRIPTION_STATE_ON_HOLD']) {
        a.play.subscriptionState = state;
        assert.equal(evaluateFreshPlayExpiry(a).status, 'PLAY_NOT_VERIFIED_EXPIRED');
    }
    a.play.subscriptionState = 'SUBSCRIPTION_STATE_EXPIRED';
    a.play.lineItems[0].productId = 'gallipick_ad_free';
    assert.equal(evaluateFreshPlayExpiry(a).status, 'PLAY_PRODUCT_CHANGED');
    a.play.lineItems = [];
    assert.equal(evaluateFreshPlayExpiry(a).status, 'PLAY_LINEITEMS_UNVERIFIED');
});

test('a missing purchase receipt, incorrect environment, malformed evidence or unknown owner cannot authorize a purge', () => {
    const a = base();
    for (const mutated of [
        { ...a, candidate: { ...a.candidate, status: 'EXPIRED' } },
        { ...a, candidate: { ...a.candidate, owner: hash('other-owner') } },
        { ...a, mode: 'development' },
        { ...a, owner: 'non-hash' },
        { ...a, candidate: { ...a.candidate, purchaseTokenHash: 'wrong' } },
    ]) {
        assert.equal(evaluateFreshPlayExpiry(mutated).deletionAllowed, false);
    }
    const license = base();
    license.mode = 'license-test';
    license.candidate.sourceScope = 'gallipickProductionLicenseBilling';
    license.play.testPurchase = {};
    assert.equal(evaluateFreshPlayExpiry(license).status, 'PLAY_EXPIRED_CONFIRMED');
});

function fixture({ missingEscrow = false, playFailure = false } = {}) {
    const records = new Map([
        [`gallipickCloudPurgeReviews/${scope}-${owner}`, structuredClone(candidate)],
        [`${scope}Accounts/${owner}`, structuredClone(billing)],
        [`gallipickProductionLicenseBillingAccounts/${owner}`, { revision: 1, entitlements: {} }],
        [`${scope}Tokens/${tokenHash}`, { owner }],
    ]);
    if (!missingEscrow) records.set(`${scope}TokenEscrows/${tokenHash}`,
        { owner, mode: 'live', tokenHash, formatVersion: 1,
            ciphertext: 'opaque-synthetic-encrypted-token' });
    let calls = 0;
    const db = {
        doc(path) {
            return { async get() {
                return { data: () => records.get(path) };
            } };
        },
        collection(root) {
            return {
                where(field, op, value) {
                    assert.equal(field, 'sourceScope');
                    assert.equal(op, '==');
                    return { limit(n) {
                        return { async get() {
                            return { docs: [...records.entries()]
                                .filter(([path, data]) => path.startsWith(`${root}/`) &&
                                    !path.slice(root.length + 1).includes('/') &&
                                    data.sourceScope === value)
                                .slice(0, n).map(([path, data]) =>
                                    ({ id: path.slice(root.length + 1), data: () => data })) };
                        } };
                    } };
                },
            };
        },
    };
    const vaults = {
        live: { async decrypt({ owner: checkedOwner, tokenHash: checkedHash }) {
            calls++;
            assert.equal(checkedOwner, owner);
            assert.equal(checkedHash, tokenHash);
            return token;
        } },
        'license-test': { async decrypt() { throw new Error('There is no license-test record'); } },
    };
    const playProvider = { async get(value) {
        assert.equal(value, token);
        if (playFailure) throw new Error('Provider URL with synthetic purchase token: ' + token);
        return structuredClone(play);
    } };
    const service = createCloudPlayExpiryRecheckService({
        db, play: playProvider, vaults, project: 'gallipick-production', now: () => nowMs,
    });
    return { records, service, get calls() { return calls; } };
}

test('server recheck service decrypts a bound token only for read-only Play verification', async () => {
    const f = fixture();
    const prior = structuredClone([...f.records.entries()]);
    const outcome = await f.service.review();
    assert.deepEqual(outcome, {
        checkedAtMs: nowMs, scanned: 1,
        statuses: { PLAY_EXPIRED_CONFIRMED: 1 },
        deletionExecuted: false, deletionAllowed: false,
    });
    assert.equal(f.calls, 1);
    assert.deepEqual([...f.records.entries()], prior, 'Read-only recheck must not mutate any customer data');
    assert.equal(JSON.stringify(outcome).includes(token), false);
    assert.equal(JSON.stringify(outcome).includes(owner), false);
});

test('missing KMS ciphertext and provider errors cannot authorize deletion or leak a token', async () => {
    const noEscrow = fixture({ missingEscrow: true });
    const unavailable = fixture({ playFailure: true });
    assert.deepEqual((await noEscrow.service.review()).statuses, { TOKEN_ESCROW_MISSING_OR_INVALID: 1 });
    assert.equal(noEscrow.calls, 0);
    const result = await unavailable.service.review();
    assert.deepEqual(result.statuses, { PLAY_RECHECK_UNAVAILABLE: 1 });
    assert.equal(JSON.stringify(result).includes(token), false);
    assert.equal(result.deletionExecuted, false);
});

test('non-production, missing Play verification and unbounded batches fail closed', async () => {
    const f = fixture();
    await assert.rejects(f.service.review({ maximumPerMode: 6 }), /small/);
    assert.throws(() => createCloudPlayExpiryRecheckService({
        db: {}, play: {}, vaults: {}, project: 'gallipick-dev',
    }), /production-scoped/);
});
