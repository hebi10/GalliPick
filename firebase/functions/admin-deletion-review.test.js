import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { BackupError } from './backup.js';
import { createAdminDeletionReviewService } from './admin-deletion-review.js';

const nowMs = 1_800_000_000_000;
const memberUid = 'exiting-member';
const otherUid = 'unrelated-member';
const operatorUid = 'deletion-operator';
const sha = text => createHash('sha256').update(text).digest('hex');
const owner = sha(memberUid);
const other = sha(otherUid);
const operatorHash = sha(operatorUid);
const originalId = 'a'.repeat(32);
const isError = code => error => error instanceof BackupError && error.code === code;
const signedAdmin = (changes = {}) => ({
    uid: operatorUid, token: {
        auth_time: nowMs / 1000, firebase: { sign_in_provider: 'google.com' },
        email_verified: true, gallipickAdmin: true, ...changes,
    },
});
const originalRequest = (fields = {}) => ({
    uid: memberUid, scope: 'ACCOUNT', status: 'MANUAL_REVIEW',
    requestId: originalId, requestedAtMs: nowMs - 2 * 86_400_000,
    deadlineAtMs: nowMs + 12 * 86_400_000,
    drainUntilMs: nowMs - 2 * 86_400_000 + 20 * 60_000,
    nextAttemptAtMs: Number.MAX_SAFE_INTEGER, consecutiveFailures: 10,
    stage: 'BACKUP_DOCS', stageIndex: 7, adminRetryCount: 0, lease: null,
    ...fields,
});
const purgeDoc = id => ({
    status: 'NEEDS_PLAY_RECHECK', owner: id, sourceScope: 'gallipickProductionBilling',
    reviewedAtMs: nowMs - 5000, verifiedExpiryAtMs: nowMs - 17 * 86_400_000,
    deletionDueAtMs: nowMs - 3 * 86_400_000, purchaseTokenHash: sha('private-purchase'),
    billingRevision: 2,
});

function fakeDb() {
    const rows = new Map();
    let writes = 0;
    let reads = 0;
    const doc = path => ({
        path, id: path.split('/').at(-1),
        get: async () => {
            reads += 1;
            return { ref: doc(path), id: path.split('/').at(-1), exists: rows.has(path),
                data: () => structuredClone(rows.get(path)) };
        },
        collection: collection => ({
            doc: id => doc(`${path}/${collection}/${id}`),
        }),
    });
    const collection = prefix => ({
        orderBy(field, direction) {
            assert.equal(direction, 'desc');
            const getter = () => [...rows.entries()]
                .filter(([path]) => path.startsWith(`${prefix}/`) &&
                    !path.slice(prefix.length + 1).includes('/'))
                .sort(([a, av], [b, bv]) => (bv[field] ?? 0) - (av[field] ?? 0) || a.localeCompare(b));
            return {
                limit(limit) {
                    assert.equal(limit, 15);
                    let last = null;
                    return {
                        startAfter(cursor) { last = cursor.id; return this; },
                        async get() {
                            reads++;
                            const entries = getter();
                            const index = last ? entries.findIndex(([path]) => path.endsWith(`/${last}`)) : -1;
                            return { docs: entries.slice(index + 1, index + 1 + limit)
                                .map(([path]) => ({
                                    id: path.split('/').at(-1), ref: doc(path),
                                    data: () => structuredClone(rows.get(path)),
                                })) };
                        },
                    };
                },
            };
        },
    });
    return {
        rows, doc, collection,
        get writes() { return writes; },
        get reads() { return reads; },
        async runTransaction(fn) {
            const scheduled = [];
            const result = await fn({
                get: ref => ref.get(),
                set(ref, value) { scheduled.push([ref.path, structuredClone(value)]); },
            });
            for (const [path, value] of scheduled) {
                rows.set(path, value);
                writes++;
            }
            return result;
        },
    };
}

function fixture({ enabled = true, workerEnabled = true } = {}) {
    const db = fakeDb();
    const service = createAdminDeletionReviewService({
        db, project: 'gallipick-production', allowedUidHashes: operatorHash,
        retryEnabled: enabled, accountExitWorkerEnabled: workerEnabled, now: () => nowMs,
    });
    return { db, service };
}

function retryInput(fields = {}) {
    return {
        ownerId: owner, originalRequestId: originalId,
        actionId: 'b'.repeat(32), scope: 'ACCOUNT', expectedStage: 'BACKUP_DOCS',
        reason: '사용자가 신청한 탈퇴 요청의 보관 데이터 정리 오류 재처리',
        confirmation: 'RETRY_EXISTING_USER_DELETION', ...fields,
    };
}

test('all list and preview calls validate admin permissions before touching Firestore', async () => {
    const f = fixture();
    for (const bad of [
        null, signedAdmin({ gallipickAdmin: false }), signedAdmin({ email_verified: false }),
        signedAdmin({ firebase: { sign_in_provider: 'password' } }),
        { ...signedAdmin(), uid: 'another-operator' },
    ]) {
        await assert.rejects(f.service.list(bad, { view: 'EXIT' }), isError('permission-denied'));
        await assert.rejects(f.service.preview(bad, { view: 'EXIT', ids: [owner] }), isError('permission-denied'));
        await assert.rejects(f.service.retry(bad, retryInput()), isError('permission-denied'));
    }
    assert.equal(f.db.reads, 0);
    assert.equal(f.db.writes, 0);
});

test('review listing paginates and exposes no Firebase UID, private purchase tokens or media paths', async () => {
    const f = fixture();
    const record = originalRequest();
    f.db.rows.set(`gallipickAccountExitRequests/${owner}`, record);
    const purgeId = `gallipickProductionBilling-${owner}`;
    f.db.rows.set(`gallipickCloudPurgeReviews/${purgeId}`, purgeDoc(owner));
    const exits = await f.service.list(signedAdmin(), { view: 'EXIT' });
    assert.equal(exits.pageSize, 15);
    assert.equal(exits.rows.length, 1);
    assert.equal(exits.rows[0].status, 'MANUAL_REVIEW');
    assert.equal(exits.rows[0].stage, 'BACKUP_DOCS');
    assert.equal(exits.rows[0].id, owner);
    assert.equal(exits.nextCursor, null);
    const purges = await f.service.list(signedAdmin(), { view: 'PURGE' });
    assert.equal(purges.rows.length, 1);
    assert.equal(purges.rows[0].status, 'NEEDS_PLAY_RECHECK');
    assert.equal(purges.rows[0].ownerId, owner);
    const payload = JSON.stringify({ exits, purges });
    for (const secret of [memberUid, 'purchase-token', sha('private-purchase'), 'secret/',
        'uploadUrl', 'uid']) {
        assert.equal(payload.includes(secret), false, secret);
    }
});

test('bulk deletion preview never changes any records or authorizes execution', async () => {
    const f = fixture();
    const reviewId = `gallipickProductionBilling-${owner}`;
    f.db.rows.set(`gallipickAccountExitRequests/${owner}`, originalRequest());
    f.db.rows.set(`gallipickAccountExitRequests/${other}`, originalRequest({
        uid: otherUid, scope: 'CLOUD', status: 'COMPLETED', requestId: 'c'.repeat(32),
    }));
    f.db.rows.set(`gallipickCloudPurgeReviews/${reviewId}`, purgeDoc(owner));
    const original = JSON.stringify([...f.db.rows]);
    const userExit = await f.service.preview(signedAdmin(), { view: 'EXIT', ids: [owner, other] });
    assert.deepEqual(userExit.rows.map(row => row.decision), ['INDIVIDUAL_RETRY_ONLY', 'ALREADY_PROCESSED']);
    assert.equal(userExit.canExecute, false);
    assert.equal(userExit.serverDeletesPerformed, 0);
    const purge = await f.service.preview(signedAdmin(), { view: 'PURGE', ids: [reviewId] });
    assert.equal(purge.rows[0].decision, 'NEEDS_PLAY_RECHECK');
    assert.equal(purge.canExecute, false);
    assert.equal(f.db.writes, 0);
    assert.equal(JSON.stringify([...f.db.rows]), original);
});

test('preview and listing reject arbitrary raw UIDs, fields, duplicate and oversized bulk selections', async () => {
    const f = fixture();
    for (const input of [
        { view: 'DELETE' }, { view: 'EXIT', cursor: '../another' },
        { view: 'EXIT', owner: owner }, { view: 'EXIT', cursor: 'not-a-cursor' },
    ]) {
        await assert.rejects(f.service.list(signedAdmin(), input), isError('invalid-argument'));
    }
    for (const input of [
        { view: 'EXIT', ids: ['raw-uid'] },
        { view: 'PURGE', ids: [owner] },
        { view: 'EXIT', ids: [] }, { view: 'EXIT', ids: [owner, owner] },
        { view: 'EXIT', ids: Array.from({ length: 11 }, (_, i) => sha(String(i))) },
        { view: 'EXIT', ids: [owner], delete: true },
    ]) {
        await assert.rejects(f.service.preview(signedAdmin(), input), isError('invalid-argument'));
    }
    assert.equal(f.db.writes, 0);
});

test('manual retry requires explicit authorization and original verified user deletion request', async () => {
    const f = fixture();
    const path = `gallipickAccountExitRequests/${owner}`;
    f.db.rows.set(path, originalRequest());
    const first = await f.service.retry(signedAdmin(), retryInput());
    assert.equal(first.scheduled, true);
    assert.equal(first.repeated, false);
    assert.equal(first.status, 'RETRY');
    const record = f.db.rows.get(path);
    assert.equal(record.uid, memberUid);
    assert.equal(record.scope, 'ACCOUNT');
    assert.equal(record.requestId, originalId);
    assert.equal(record.stage, 'BACKUP_DOCS');
    assert.equal(record.stageIndex, 7);
    assert.equal(record.nextAttemptAtMs, nowMs);
    assert.equal(record.adminRetryCount, 1);
    assert.equal(record.consecutiveFailures, 0);
    const audit = f.db.rows.get(`${path}/adminHistory/${'b'.repeat(32)}`);
    assert.equal(audit.action, 'RETRY');
    assert.equal(audit.actorOwnerId, operatorHash);
    assert.equal(audit.reason, retryInput().reason);
    const same = await f.service.retry(signedAdmin(), retryInput());
    assert.equal(same.repeated, true);
    assert.equal(record.scope, 'ACCOUNT');
    assert.equal(f.db.writes, 2);
    await assert.rejects(f.service.retry(signedAdmin(), retryInput({
        reason: '다른 사유를 이용한 기존 재시도 ID 부당 재사용 차단 테스트',
    })), isError('permission-denied'));
});

test('manual retry is disabled by default and cannot widen, replace or restart unrelated deletions', async () => {
    for (const cfg of [{ enabled: false }, { workerEnabled: false }]) {
        const f = fixture(cfg);
        f.db.rows.set(`gallipickAccountExitRequests/${owner}`, originalRequest());
        await assert.rejects(f.service.retry(signedAdmin(), retryInput()), isError('failed-precondition'));
        assert.equal(f.db.writes, 0);
    }
    const f = fixture();
    const path = `gallipickAccountExitRequests/${owner}`;
    f.db.rows.set(path, originalRequest());
    for (const invalid of [
        retryInput({ ownerId: other }),
        retryInput({ scope: 'CLOUD' }),
        retryInput({ originalRequestId: 'e'.repeat(32) }),
        retryInput({ expectedStage: 'AUTH' }),
        retryInput({ confirmation: 'DELETE_ALL_USERS' }),
        retryInput({ reason: 'short' }),
        { ...retryInput(), uid: memberUid },
    ]) {
        await assert.rejects(f.service.retry(signedAdmin(), invalid),
            error => error instanceof BackupError && ['failed-precondition', 'invalid-argument'].includes(error.code));
    }
    await assert.rejects(f.service.retry(signedAdmin({
        auth_time: (nowMs - 11 * 60_000) / 1000,
    }), retryInput()), isError('permission-denied'));
    assert.equal(f.db.writes, 0);
    f.db.rows.set(path, originalRequest({ uid: otherUid }));
    await assert.rejects(f.service.retry(signedAdmin(), retryInput()), isError('failed-precondition'));
    f.db.rows.set(path, originalRequest({ status: 'COMPLETED' }));
    await assert.rejects(f.service.retry(signedAdmin(), retryInput()), isError('failed-precondition'));
    f.db.rows.set(path, originalRequest({ lease: { untilMs: nowMs + 60_000 } }));
    await assert.rejects(f.service.retry(signedAdmin(), retryInput()), isError('failed-precondition'));
    assert.equal(f.db.writes, 0);
});

test('developer project cannot create administrator deletion review service', () => {
    const f = fixture();
    assert.throws(() => createAdminDeletionReviewService({
        db: f.db, project: 'gallipick-dev', allowedUidHashes: operatorHash,
    }), isError('failed-precondition'));
});
