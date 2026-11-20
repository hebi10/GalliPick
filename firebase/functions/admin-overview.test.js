import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { adminAllowlist, authorizeGalliPickAdmin, createAdminOverviewService } from './admin-overview.js';

const now = 1_800_000_000_000;
const uid = 'verified-admin-test';
const owner = createHash('sha256').update(uid).digest('hex');
const admin = (overrides = {}) => ({
    uid,
    token: {
        auth_time: now / 1000, firebase: { sign_in_provider: 'google.com' },
        email_verified: true, gallipickAdmin: true, ...overrides,
    },
});

function dbWithCounts() {
    const counters = new Map([
        ['gallipickProductionBillingAccounts', 17],
        ['gallipickProductionLicenseBillingAccounts', 4],
        ['gallipickAccountExitRequests|REQUESTED', 3],
        ['gallipickAccountExitRequests|IN_PROGRESS', 1],
        ['gallipickAccountExitRequests|RUNNING', 2],
        ['gallipickAccountExitRequests|RETRY', 1],
        ['gallipickAccountExitRequests|MANUAL_REVIEW', 2],
        ['gallipickCloudPurgeReviews|NEEDS_PLAY_RECHECK', 5],
    ]);
    return {
        collection(name) {
            return {
                where(field, op, status) {
                    assert.equal(field, 'status');
                    assert.equal(op, '==');
                    return { count: () => ({ get: async () => ({ data: () => ({
                        count: counters.get(`${name}|${status}`) ?? 0,
                    }) }) }) };
                },
                count: () => ({ get: async () => ({ data: () => ({ count: counters.get(name) ?? 0 }) }) }),
            };
        },
    };
}

test('admin access requires a production-scoped, valid immutable UID hash allowlist', () => {
    assert.deepEqual([...adminAllowlist(owner)], [owner]);
    for (const input of ['', 'not-a-hash', `${owner},${owner}`, `${owner},invalid`]) {
        assert.throws(() => adminAllowlist(input), error => error.code === 'failed-precondition');
    }
    assert.throws(() => createAdminOverviewService({
        db: dbWithCounts(), project: 'gallipick-dev', allowedUidHashes: owner,
    }), error => error.code === 'failed-precondition');
});

test('admin access is blocked without verified Google and explicit custom claim', () => {
    const allow = new Set([owner]);
    for (const context of [
        null, admin({ gallipickAdmin: false }), admin({ email_verified: false }),
        admin({ firebase: { sign_in_provider: 'password' } }),
        { ...admin(), uid: 'other-admin' },
        admin({ auth_time: (now - 25 * 60 * 60 * 1000) / 1000 }),
    ]) {
        assert.throws(() => authorizeGalliPickAdmin(context, allow, now),
            error => error.code === 'permission-denied');
    }
    assert.doesNotThrow(() => authorizeGalliPickAdmin(admin(), allow, now));
});

test('verified admin sees aggregate counts only, not account identifiers or purchase tokens', async () => {
    const service = createAdminOverviewService({
        db: dbWithCounts(), project: 'gallipick-production', allowedUidHashes: owner, now: () => now,
    });
    await assert.rejects(service.overview(null), error => error.code === 'permission-denied');
    await assert.rejects(service.overview({ ...admin(), uid: 'unapproved' }),
        error => error.code === 'permission-denied');
    const output = await service.overview(admin());
    assert.deepEqual(output, {
        checkedAtMs: now,
        liveBillingRecords: 17,
        licenseBillingRecords: 4,
        pendingDeletionRequests: 7,
        manualReviewDeletionRequests: 2,
        purgeCandidatesPendingPlayRecheck: 5,
    });
    assert.equal(JSON.stringify(output).includes(uid), false);
});

test('invalid Firestore aggregates fail closed rather than displaying fake zero counts', async () => {
    const service = createAdminOverviewService({
        db: { collection: () => {
            const aggregate = { count: () => ({ get: async () => ({ data: () => ({ count: NaN }) }) }) };
            return { ...aggregate, where: () => aggregate };
        } },
        project: 'gallipick-production', allowedUidHashes: owner, now: () => now,
    });
    await assert.rejects(service.overview(admin()), error => error.code === 'failed-precondition');
});
