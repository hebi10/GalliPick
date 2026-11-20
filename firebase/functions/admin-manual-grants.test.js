import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { BackupError } from './backup.js';
import { createAdminManualGrantService } from './admin-manual-grants.js';
import { ADMIN_MANUAL_GRANTS_COLLECTION, ADMIN_MANUAL_DAY_MS,
    ADMIN_MANUAL_MAX_DAYS, manualGrantDocumentId, trustedManualGrant } from './admin-manual-policy.js';

const nowMs = 1_800_000_000_000;
const owner = createHash('sha256').update('member-manual').digest('hex');
const actorUid = 'staff-admin';
const actor = createHash('sha256').update(actorUid).digest('hex');
const otherOwner = createHash('sha256').update('unrelated-user').digest('hex');
const rand = index => index.toString(16).padStart(32, '0');
const auth = (modifiers = {}) => ({
    uid: actorUid,
    token: { firebase: { sign_in_provider: 'google.com' }, email_verified: true,
        gallipickAdmin: true, auth_time: nowMs / 1000, ...modifiers },
});
const codeIs = value => error => error instanceof BackupError && error.code === value;

function fakeDb() {
    const rows = new Map();
    let reads = 0;
    const doc = path => ({
        path,
        get: async () => { reads++; return { data: () => rows.get(path) }; },
        collection: name => {
            const history = `${path}/${name}`;
            return {
                doc: id => doc(`${history}/${id}`),
                orderBy: (field, direction) => {
                    assert.equal(field, 'recordedAtMs');
                    assert.equal(direction, 'desc');
                    return { limit: count => ({
                        get: async () => {
                            reads++;
                            const documents = [...rows.entries()]
                                .filter(([key]) => key.startsWith(`${history}/`))
                                .map(([, record]) => record)
                                .sort((a, b) => b.recordedAtMs - a.recordedAtMs)
                                .slice(0, count)
                                .map(record => ({ data: () => record }));
                            return { docs: documents };
                        },
                    }) };
                },
            };
        },
    });
    return {
        rows, get reads() { return reads; }, doc,
        async runTransaction(callback) {
            const writes = [];
            const output = await callback({
                get: ref => ref.get(),
                set(ref, data) { writes.push([ref.path, structuredClone(data)]); },
            });
            for (const [key, value] of writes) rows.set(key, value);
            return output;
        },
    };
}

function fixture({ enabled = true } = {}) {
    const db = fakeDb();
    let clock = nowMs;
    const service = createAdminManualGrantService({
        db, project: 'gallipick-production', allowedUidHashes: actor,
        enabled, now: () => clock,
    });
    return { db, service, tick: time => { clock += time; }, get nowMs() { return clock; } };
}

function issue(requestId = rand(1), expectedRevision = 0) {
    return {
        ownerId: owner, mode: 'live', kind: 'CLOUD_50GB',
        reason: '신규 가입자 QA를 위한 한시적인 클라우드 이용권 지급',
        durationDays: 14, requestId, expectedRevision,
        confirmation: 'ISSUE_MANUAL_ACCESS',
    };
}
function revoke(requestId = rand(2), expectedRevision = 1) {
    return {
        ownerId: owner, mode: 'live',
        reason: '체험 기간 종료로 운영자가 수동 이용권 회수',
        requestId, expectedRevision, confirmation: 'REVOKE_MANUAL_ACCESS',
    };
}

test('manual benefit policy is separate from the Play catalog and has a 7-day recovery window', () => {
    const record = {
        source: 'ADMIN_MANUAL', ownerId: owner, mode: 'live', status: 'ACTIVE', revision: 1,
        kind: 'CLOUD_50GB', issuedAtMs: nowMs, expiresAtMs: nowMs + 14 * ADMIN_MANUAL_DAY_MS,
    };
    assert.equal(manualGrantDocumentId(owner, 'live'), `live-${owner}`);
    assert.equal(manualGrantDocumentId('wrong-uid', 'live'), null);
    const valid = trustedManualGrant(record, nowMs, owner, 'live');
    assert.equal(valid.quotaBytes, 50_000_000_000);
    assert.equal(valid.mayUploadCloud, true);
    assert.equal(valid.adFree, true);
    const expired = trustedManualGrant(record, record.expiresAtMs + 2 * ADMIN_MANUAL_DAY_MS, owner, 'live');
    assert.equal(expired.active, false);
    assert.equal(expired.mayReadCloud, true);
    assert.equal(expired.mayUploadCloud, false);
    assert.equal(trustedManualGrant(record, record.expiresAtMs + 7 * ADMIN_MANUAL_DAY_MS,
        owner, 'live').mayReadCloud, false);
    assert.equal(trustedManualGrant(record, nowMs, otherOwner, 'live'), null);
    assert.equal(trustedManualGrant(record, nowMs, owner, 'license-test'), null);
    assert.equal(trustedManualGrant({ ...record, status: 'REVOKED' }, nowMs, owner, 'live'), null);
    assert.equal(trustedManualGrant({ ...record, source: 'PLAY' }, nowMs, owner, 'live'), null);
});

test('unauthorized operators cannot read or create manual grants, even with a copied owner hash', async () => {
    const f = fixture();
    for (const bad of [null, auth({ gallipickAdmin: false }),
        auth({ email_verified: false }), { ...auth(), uid: 'unapproved-staff' }]) {
        await assert.rejects(f.service.read(bad, { ownerId: owner, mode: 'live' }),
            codeIs('permission-denied'));
        await assert.rejects(f.service.issue(bad, issue()),
            codeIs('permission-denied'));
    }
    assert.equal(f.db.reads, 0);
    const disabled = fixture({ enabled: false });
    await assert.rejects(disabled.service.issue(auth(), issue()), codeIs('failed-precondition'));
    assert.equal(disabled.db.rows.size, 0);
});

test('issue, read and revoke are revision-guarded, audited, and idempotent', async () => {
    const f = fixture();
    const initial = await f.service.read(auth(), { ownerId: owner, mode: 'live' });
    assert.equal(initial.grant.status, 'NONE');
    const first = await f.service.issue(auth(), issue());
    assert.equal(first.grant.status, 'ACTIVE');
    assert.equal(first.grant.kind, 'CLOUD_50GB');
    assert.equal(first.grant.revision, 1);
    assert.equal(first.grant.expiresAtMs, nowMs + 14 * ADMIN_MANUAL_DAY_MS);
    const repeated = await f.service.issue(auth(), issue());
    assert.equal(repeated.repeated, true);
    assert.equal(repeated.grant.revision, 1);
    await assert.rejects(f.service.issue(auth(), issue(rand(3), 1)), codeIs('failed-precondition'));
    await assert.rejects(f.service.revoke(auth(), revoke(rand(4), 0)), codeIs('aborted'));

    const removed = await f.service.revoke(auth(), revoke());
    assert.equal(removed.grant.status, 'REVOKED');
    assert.equal(removed.grant.revision, 2);
    assert.equal((await f.service.revoke(auth(), revoke())).repeated, true);
    const history = await f.service.read(auth(), { ownerId: owner, mode: 'live' });
    assert.equal(history.history.length, 2);
    assert.deepEqual(history.history.map(item => item.action).sort(), ['ISSUE', 'REVOKE']);
    assert.ok(history.history.every(item => item.actorOwnerId === actor));
    assert.equal(JSON.stringify([...f.db.rows]).includes(actorUid), false);
    assert.equal(JSON.stringify([...f.db.rows]).includes('purchase-token'), false);

    const reissued = await f.service.issue(auth(), { ...issue(rand(5), 2), kind: 'AD_FREE' });
    assert.equal(reissued.grant.status, 'ACTIVE');
    assert.equal(reissued.grant.kind, 'AD_FREE');
    assert.equal(reissued.grant.revision, 3);
    assert.equal(f.db.rows.size, 4);
});

test('manual grants never bypass deletion requests, reauthentication or maximum duration', async () => {
    const f = fixture();
    for (const invalid of [
        { ...issue(rand(6)), durationDays: ADMIN_MANUAL_MAX_DAYS + 1 },
        { ...issue(rand(7)), kind: 'UNLIMITED_CLOUD' },
        { ...issue(rand(8)), ownerId: 'raw-uid' },
        { ...issue(rand(9)), mode: 'development' },
        { ...issue(rand(10)), reason: 'short' },
        { ...issue(rand(11)), purchaseToken: 'private-secret' },
    ]) {
        await assert.rejects(f.service.issue(auth(), invalid), codeIs('invalid-argument'));
    }
    await assert.rejects(f.service.issue(auth({
        auth_time: (nowMs - 11 * 60_000) / 1000,
    }), issue(rand(12))), codeIs('permission-denied'));
    f.db.rows.set(`gallipickAccountExitRequests/${owner}`, { scope: 'ACCOUNT', status: 'REQUESTED' });
    await assert.rejects(f.service.issue(auth(), issue(rand(13))), codeIs('failed-precondition'));
    assert.equal([...f.db.rows.keys()].filter(key => key.startsWith(ADMIN_MANUAL_GRANTS_COLLECTION)).length, 0);
});

test('grant expiration is calculated from server time and cannot be extended by the browser', async () => {
    const f = fixture();
    const created = await f.service.issue(auth(), issue());
    assert.equal(created.grant.status, 'ACTIVE');
    f.tick(15 * ADMIN_MANUAL_DAY_MS);
    const later = await f.service.read(auth({
        auth_time: f.nowMs / 1000,
    }), { ownerId: owner, mode: 'live' });
    assert.equal(later.grant.status, 'EXPIRED');
    assert.equal(later.grant.expiresAtMs, created.grant.expiresAtMs);
});

test('developer project and badly formed operator allowlist never enable manual billing', () => {
    const f = fixture();
    assert.throws(() => createAdminManualGrantService({
        db: f.db, project: 'gallipick-dev', allowedUidHashes: actor,
    }), codeIs('failed-precondition'));
    assert.throws(() => createAdminManualGrantService({
        db: f.db, project: 'gallipick-production', allowedUidHashes: actorUid,
    }), codeIs('failed-precondition'));
});
