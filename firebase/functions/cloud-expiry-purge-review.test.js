import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { CLOUD_PURGE_WINDOW_MS } from './cloud-retention-policy.js';
import { cloudPurgeReview, createCloudPurgeReviewService, CLOUD_PURGE_REVIEW_COLLECTION } from './cloud-expiry-purge-review.js';

const nowMs = 1_800_000_000_000;
const owner = createHash('sha256').update('purge-owner').digest('hex');
const other = createHash('sha256').update('other-owner').digest('hex');
const tokenHash = createHash('sha256').update('synthetic-token').digest('hex');
const expiry = nowMs - CLOUD_PURGE_WINDOW_MS;

function billing(overrides = {}) {
    return {
        revision: 5, entitlements: {},
        cloudLifecycle: { productId: 'gallipick_cloud_10gb', basePlanId: 'monthly',
            tokenHash, expiresAtMs: expiry, checkedAtMs: expiry, recoveryAllowed: true },
        ...overrides,
    };
}
const check = (data = billing(), options = {}) => cloudPurgeReview({
    owner, billing: data, otherBilling: null, accountExit: null, nowMs, mode: 'live', ...options,
});

test('the fourteenth-day boundary only requests a fresh Play review, never deletion', () => {
    assert.deepEqual(check(billing(), { nowMs: nowMs - 1 }).status, 'NOT_DUE');
    const due = check();
    assert.equal(due.status, 'NEEDS_PLAY_RECHECK');
    assert.equal(due.deletionAllowed, false);
    assert.equal(due.deletionDueAtMs, nowMs);
    assert.equal(due.purchaseTokenHash, tokenHash);
});

test('active or unrecognized purchase state must not be treated as safe to delete', () => {
    assert.equal(check(billing({ entitlements: {
        [tokenHash]: { productId: 'gallipick_cloud_10gb', expiresAtMs: nowMs + 1000 },
    } })).status, 'ACTIVE_ENTITLEMENT');
    assert.equal(check(billing({ entitlements: { [tokenHash]: { expiresAtMs: NaN } } })).status, 'ACTIVE_ENTITLEMENT');
    assert.equal(check(billing(), { otherBilling: { entitlements: {
        [tokenHash]: { expiresAtMs: nowMs + 1000 },
    } } }).status, 'ACTIVE_ENTITLEMENT');
    assert.equal(check(billing(), { accountExit: { scope: 'ACCOUNT', status: 'REQUESTED' } }).status,
        'EXISTING_DELETION');
    assert.equal(check(billing(), { accountExit: { scope: 'CLOUD', status: 'IN_PROGRESS' } }).status,
        'EXISTING_DELETION');
});

test('missing, revoked, or forged expiry evidence never permits purge', () => {
    for (const value of [
        null, billing({ cloudLifecycle: null }),
        billing({ cloudLifecycle: { ...billing().cloudLifecycle, recoveryAllowed: false } }),
        billing({ cloudLifecycle: { ...billing().cloudLifecycle, productId: 'gallipick_ad_free' } }),
        billing({ cloudLifecycle: { ...billing().cloudLifecycle, checkedAtMs: nowMs + 1 } }),
        billing({ cloudLifecycle: { ...billing().cloudLifecycle, tokenHash: 'purchase-token' } }),
        billing({ revision: null }),
    ]) {
        assert.equal(check(value).status, 'INCOMPLETE_EVIDENCE');
    }
});

function fakeDb() {
    const records = new Map();
    const doc = path => ({
        id: path.split('/').at(-1),
        path,
        async get() { return { id: path.split('/').at(-1), ref: doc(path), data: () => records.get(path) }; },
        async set(value) { records.set(path, structuredClone(value)); },
    });
    const collection = root => ({
        where(field, comparison, cutoff) {
            assert.equal(field, 'cloudLifecycle.expiresAtMs');
            assert.equal(comparison, '<=');
            return { limit(n) { return { async get() {
                const prefix = `${root}/`;
                const docs = [...records].filter(([path, data]) => path.startsWith(prefix) &&
                    !path.slice(prefix.length).includes('/') &&
                    Number.isSafeInteger(data?.cloudLifecycle?.expiresAtMs) &&
                    data.cloudLifecycle.expiresAtMs <= cutoff
                ).slice(0, n).map(([path]) => ({
                    ref: doc(path), id: path.slice(prefix.length),
                }));
                return { docs };
            } }; } };
        },
    });
    return { records, doc, collection };
}

test('periodic scan writes review markers only and retains all original, user and billing records', async () => {
    const db = fakeDb();
    db.records.set(`gallipickProductionBillingAccounts/${owner}`, billing());
    db.records.set(`gallipickProductionLicenseBillingAccounts/${other}`,
        billing({ entitlements: { active: { expiresAtMs: nowMs + 300 } } }));
    db.records.set(`gallipickProductionBackupAccounts/${owner}/originals/photo`, { state: 'VERIFIED' });
    db.records.set(`gallipickProductionBillingAccounts/${other}`, billing({
        entitlements: { active: { expiresAtMs: nowMs + 500 } },
    }));
    const service = createCloudPurgeReviewService({ db, now: () => nowMs, project: 'gallipick-production' });
    const result = await service.review();
    assert.equal(result.scanned, 3);
    assert.equal(result.needsPlayRecheck, 1);
    assert.equal(result.held, 2);
    const reviews = [...db.records].filter(([path]) => path.startsWith(`${CLOUD_PURGE_REVIEW_COLLECTION}/`));
    assert.equal(reviews.length, 1);
    assert.equal(reviews[0][1].status, 'NEEDS_PLAY_RECHECK');
    assert.equal(reviews[0][1].owner, owner);
    assert.equal(db.records.get(`gallipickProductionBackupAccounts/${owner}/originals/photo`).state, 'VERIFIED');
    assert.equal(db.records.get(`gallipickProductionBillingAccounts/${owner}`).revision, 5);
});

test('purge review may only run against the production project', () => {
    assert.throws(() => createCloudPurgeReviewService({
        db: fakeDb(), now: () => nowMs, project: 'gallipick-dev',
    }), /Production-scoped/);
});
