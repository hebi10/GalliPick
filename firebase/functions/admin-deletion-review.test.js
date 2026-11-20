import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { BackupError } from './backup.js';
import { CLOUD_PURGE_WINDOW_MS } from './cloud-retention-policy.js';
import {
    createAdminDeletionReviewService, ADMIN_DELETION_PAGE_LIMIT, ADMIN_DELETION_REVIEW_AUDITS,
} from './admin-deletion-review.js';

const nowMs = 1_800_000_000_000;
const sha = value => createHash('sha256').update(value).digest('hex');
const owner = sha('account-with-pending-deletion');
const secondOwner = sha('account-with-expired-cloud');
const actorId = 'approved-gallipick-operator';
const actorHash = sha(actorId);
const originalRequestId = 'a'.repeat(32);
const writeRequestId = 'c'.repeat(32);
const auth = (overrides = {}) => ({
    uid: actorId,
    token: {
        firebase: { sign_in_provider: 'google.com' }, email_verified: true,
        gallipickAdmin: true, auth_time: nowMs / 1000, ...overrides,
    },
});
const isCode = code => error => error instanceof BackupError && error.code === code;
const exitPath = `gallipickAccountExitRequests/${owner}`;
const auditPath = `${ADMIN_DELETION_REVIEW_AUDITS}/${writeRequestId}`;
const reviewPath = `gallipickCloudPurgeReviews/gallipickProductionBilling-${secondOwner}`;
const livePath = `gallipickProductionBillingAccounts/${secondOwner}`;
const otherPath = `gallipickProductionLicenseBillingAccounts/${secondOwner}`;

function dbFixture() {
    const rows = new Map();
    let reads = 0;
    const doc = path => ({
        path, id: path.split('/').at(-1),
        get: async () => { reads++; return {
            id: path.split('/').at(-1), ref: doc(path), data: () => rows.get(path),
        }; },
    });
    const collection = (collectionName, clauses = []) => ({
        where(field, operation, value) { return collection(collectionName, [...clauses, [field, operation, value]]); },
        limit(size) {
            return { async get() {
                reads++;
                const root = collectionName + '/';
                const matches = [...rows.entries()].filter(([path, data]) => {
                    if (!path.startsWith(root) || path.slice(root.length).includes('/')) return false;
                    return clauses.every(([field, operation, value]) => {
                        if (operation === 'in') return Array.isArray(value) && value.includes(data?.[field]);
                        if (operation === '==') return data?.[field] === value;
                        throw new Error('Unsupported Firestore test query');
                    });
                });
                return { docs: matches.slice(0, size).map(([path, data]) => ({
                    id: path.slice(root.length), ref: doc(path), data: () => data,
                })) };
            } };
        },
    });
    return {
        rows,
        get reads() { return reads; },
        doc, collection,
        async runTransaction(work) {
            const writes = [];
            const result = await work({
                get: d => d.get(),
                update: (d, changes) => writes.push(['update', d.path, structuredClone(changes)]),
                create: (d, value) => writes.push(['create', d.path, structuredClone(value)]),
            });
            for (const [op, path, value] of writes) {
                if (op === 'create' && rows.has(path)) throw new Error('Already exists');
                if (op === 'update') rows.set(path, { ...rows.get(path), ...value });
                else rows.set(path, value);
            }
            return result;
        },
    };
}

function fixture({ retryEnabled = true, workerEnabled = true } = {}) {
    const db = dbFixture();
    let clock = nowMs;
    const service = createAdminDeletionReviewService({
        db, project: 'gallipick-production', allowedUidHashes: actorHash,
        retryEnabled, workerEnabled, now: () => clock,
    });
    return { db, service, setTime: value => { clock = value; } };
}
function record() {
    return {
        uid: 'account-with-pending-deletion', scope: 'ACCOUNT', status: 'MANUAL_REVIEW',
        requestId: originalRequestId, requestedAtMs: nowMs - 5_000_000,
        deadlineAtMs: nowMs + 10_000_000, stage: 'OBJECTS',
        nextAttemptAtMs: Number.MAX_SAFE_INTEGER, consecutiveFailures: 10,
    };
}
function retryData(overrides = {}) {
    return {
        ownerId: owner, deletionRequestId: originalRequestId,
        idempotencyKey: writeRequestId,
        reason: '검증 후 기존 탈퇴 요청의 실패 단계만 재시도합니다.',
        confirmation: 'RETRY_EXISTING_DELETION_ONLY',
        ...overrides,
    };
}
function seedPurge(f) {
    const expiry = nowMs - CLOUD_PURGE_WINDOW_MS;
    const tokenHash = sha('server-recorded-purchase');
    f.db.rows.set(reviewPath, {
        owner: secondOwner, sourceScope: 'gallipickProductionBilling',
        purchaseTokenHash: tokenHash, billingRevision: 4,
        verifiedExpiryAtMs: expiry, deletionDueAtMs: nowMs, status: 'NEEDS_PLAY_RECHECK',
    });
    f.db.rows.set(livePath, {
        revision: 4, entitlements: {},
        cloudLifecycle: {
            productId: 'gallipick_cloud_10gb', basePlanId: 'monthly',
            tokenHash, expiresAtMs: expiry, checkedAtMs: expiry,
            recoveryAllowed: true,
        },
    });
    f.db.rows.set(otherPath, { revision: 1, entitlements: {} });
    return tokenHash;
}

test('unapproved operators cannot enumerate deletions, preview candidates, or enqueue a retry', async () => {
    const f = fixture();
    f.db.rows.set(exitPath, record());
    for (const context of [null, { ...auth(), uid: 'unapproved' },
        auth({ gallipickAdmin: false }), auth({ email_verified: false }),
        auth({ firebase: { sign_in_provider: 'password' } })]) {
        await assert.rejects(f.service.list(context), isCode('permission-denied'));
        await assert.rejects(f.service.preview(context), isCode('permission-denied'));
        await assert.rejects(f.service.retry(context, retryData()), isCode('permission-denied'));
    }
    assert.equal(f.db.reads, 0);
});

test('monitoring shows only bounded hashed owner IDs and never exposes Firebase UID or purchase tokens', async () => {
    const f = fixture();
    f.db.rows.set(exitPath, record());
    const token = seedPurge(f);
    const result = await f.service.list(auth());
    assert.equal(result.displayLimit, ADMIN_DELETION_PAGE_LIMIT);
    assert.equal(result.deletionRequests.length, 1);
    assert.equal(result.deletionRequests[0].ownerId, owner);
    assert.equal(result.deletionRequests[0].status, 'MANUAL_REVIEW');
    assert.equal(result.deletionRequests[0].reviewState, 'REQUIRES_OPERATOR_REVIEW');
    assert.equal(result.purgeReviews.length, 1);
    assert.equal(result.purgeReviews[0].deletionAllowed, false);
    const text = JSON.stringify(result);
    assert.equal(text.includes('account-with-pending-deletion'), false);
    assert.equal(text.includes(token), false);
    assert.equal(text.includes('uid'), false);
    await assert.rejects(f.service.list(auth(), { ownerId: owner }), isCode('invalid-argument'));
    await assert.rejects(f.service.preview(auth(), { action: 'DELETE_ALL' }), isCode('invalid-argument'));
});

test('bulk dry-run never deletes media and always requires fresh Play review for a due record', async () => {
    const f = fixture();
    seedPurge(f);
    f.db.rows.set(`gallipickProductionBackupAccounts/${secondOwner}/originals/photo`,
        { state: 'VERIFIED', fileName: 'private-photo.jpg' });
    const before = structuredClone([...f.db.rows.entries()]);
    const preview = await f.service.preview(auth());
    assert.equal(preview.dryRunOnly, true);
    assert.equal(preview.deletionExecuted, false);
    assert.equal(preview.examined, 1);
    assert.deepEqual(preview.items, [{
        ownerId: secondOwner, mode: 'live', status: 'NEEDS_PLAY_RECHECK', deletionAllowed: false,
    }]);
    assert.deepEqual([...f.db.rows.entries()], before);
});

test('dry-run blocks a renewed purchase, changed billing revision and an active manual Cloud grant', async () => {
    const f = fixture();
    seedPurge(f);
    f.db.rows.get(livePath).entitlements[sha('renewal')] = {
        productId: 'gallipick_cloud_50gb', basePlanId: 'monthly',
        expiresAtMs: nowMs + 90_000,
    };
    assert.equal((await f.service.preview(auth())).items[0].status, 'ACTIVE_ENTITLEMENT');
    f.db.rows.get(livePath).entitlements = {};
    f.db.rows.get(livePath).revision = 5;
    assert.equal((await f.service.preview(auth())).items[0].status, 'STALE_BILLING_REVISION');
    f.db.rows.get(livePath).revision = 4;
    f.db.rows.set(`gallipickAdminManualGrants/live-${secondOwner}`, {
        source: 'ADMIN_MANUAL', ownerId: secondOwner, mode: 'live',
        status: 'ACTIVE', kind: 'CLOUD_10GB', revision: 1,
        issuedAtMs: nowMs - 86_400_000, expiresAtMs: nowMs + 86_400_000,
    });
    assert.equal((await f.service.preview(auth())).items[0].status, 'ACTIVE_MANUAL_ACCESS');
    f.db.rows.delete(`gallipickAdminManualGrants/live-${secondOwner}`);
    f.db.rows.set(`gallipickAccountExitRequests/${secondOwner}`, record());
    assert.equal((await f.service.preview(auth())).items[0].status, 'EXISTING_DELETION');
});

test('retry is fail-closed until both operator permission and trusted worker are enabled', async () => {
    for (const options of [{ retryEnabled: false }, { workerEnabled: false }]) {
        const f = fixture(options);
        f.db.rows.set(exitPath, record());
        await assert.rejects(f.service.retry(auth(), retryData()), isCode('failed-precondition'));
        assert.equal(f.db.rows.get(exitPath).status, 'MANUAL_REVIEW');
        assert.equal(f.db.rows.has(auditPath), false);
    }
    const f = fixture();
    f.db.rows.set(exitPath, record());
    await assert.rejects(f.service.retry(auth({ auth_time: (nowMs - 11 * 60_000) / 1000 }),
        retryData()), isCode('permission-denied'));
    assert.equal(f.db.rows.has(auditPath), false);
});

test('retry requeues only the same previously requested failed job and creates a single audit record', async () => {
    const f = fixture();
    f.db.rows.set(exitPath, record());
    const result = await f.service.retry(auth(), retryData());
    assert.deepEqual(result, { accepted: true, repeated: false, status: 'RETRY' });
    assert.equal(f.db.rows.get(exitPath).status, 'RETRY');
    assert.equal(f.db.rows.get(exitPath).consecutiveFailures, 0);
    assert.equal(f.db.rows.get(exitPath).nextAttemptAtMs, nowMs);
    assert.equal(f.db.rows.get(exitPath).uid, 'account-with-pending-deletion');
    const audit = f.db.rows.get(auditPath);
    assert.equal(audit.action, 'RETRY');
    assert.equal(audit.actorOwnerId, actorHash);
    assert.equal(audit.ownerId, owner);
    assert.equal(JSON.stringify(audit).includes('account-with-pending-deletion'), false);
    const replay = await f.service.retry(auth(), retryData());
    assert.deepEqual(replay, { accepted: true, repeated: true, status: 'RETRY' });
    assert.equal([...f.db.rows.keys()].filter(key =>
        key.startsWith(ADMIN_DELETION_REVIEW_AUDITS + '/')).length, 1);
});

test('retry denies attempts to create a deletion, switch owners, or bypass manual review', async () => {
    const f = fixture();
    await assert.rejects(f.service.retry(auth(), retryData()), isCode('failed-precondition'));
    f.db.rows.set(exitPath, record());
    const invalid = [
        retryData({ ownerId: sha('other') }),
        retryData({ deletionRequestId: 'b'.repeat(32) }),
        retryData({ reason: 'short' }),
        retryData({ idempotencyKey: 'not-uuid' }),
        retryData({ confirmation: 'DELETE_ALL_ACCOUNTS' }),
        retryData({ newDeletionScope: 'ACCOUNT' }),
    ];
    for (const input of invalid) {
        await assert.rejects(f.service.retry(auth(), input),
            error => ['invalid-argument', 'failed-precondition'].includes(error.code));
    }
    f.db.rows.get(exitPath).status = 'COMPLETED';
    await assert.rejects(f.service.retry(auth(), retryData()), isCode('failed-precondition'));
    f.db.rows.get(exitPath).status = 'MANUAL_REVIEW';
    f.db.rows.get(exitPath).uid = 'a-different-member';
    await assert.rejects(f.service.retry(auth(), retryData()), isCode('failed-precondition'));
    assert.equal(f.db.rows.has(auditPath), false);
});

test('only production may expose deletion administration', () => {
    const db = dbFixture();
    assert.throws(() => createAdminDeletionReviewService({
        db, project: 'gallipick-dev', allowedUidHashes: actorHash,
    }), isCode('failed-precondition'));
});
