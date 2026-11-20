import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { createAdminMembersService, verifiedAdminSubscription, maskedAdminEmail,
    ADMIN_MEMBERS_PAGE_SIZE } from './admin-members.js';
import { BackupError } from './backup.js';

const nowMs = 1_800_000_000_000;
const adminUid = 'authorized-admin';
const allowed = createHash('sha256').update(adminUid).digest('hex');
const firstUid = 'member-first';
const secondUid = 'member-second';
const firstOwner = createHash('sha256').update(firstUid).digest('hex');
const secondOwner = createHash('sha256').update(secondUid).digest('hex');
const tokenHash = createHash('sha256').update('test-purchase').digest('hex');
const context = (uid = adminUid, token = {}) => ({
    uid, token: {
        firebase: { sign_in_provider: 'google.com' }, email_verified: true,
        gallipickAdmin: true, auth_time: nowMs / 1000, ...token,
    },
});
const isCode = code => error => error instanceof BackupError && error.code === code;
const product = (productId, basePlanId, quotaBytes, expiry) => ({
    productId, basePlanId, quotaBytes, expiresAtMs: expiry, checkedAtMs: nowMs - 3_600_000,
});
const cloud = expiry => product('gallipick_cloud_50gb', 'monthly', 50_000_000_000, expiry);
const adFree = expiry => product('gallipick_ad_free', 'annual', 0, expiry);

function fixtures() {
    const records = new Map([
        [`gallipickProductionBillingAccounts/${firstOwner}`, {
            revision: 2, entitlements: { [tokenHash]: cloud(nowMs + 30 * 86_400_000) },
            cloudLifecycle: { productId: 'gallipick_cloud_50gb', basePlanId: 'monthly',
                tokenHash, expiresAtMs: nowMs + 30 * 86_400_000,
                checkedAtMs: nowMs - 3_600_000, recoveryAllowed: true },
        }],
        [`gallipickProductionBackupAccounts/${firstOwner}`, { allocatedBytes: 12_000_000_000, files: 28 }],
        [`gallipickProductionLicenseBillingAccounts/${secondOwner}`, {
            revision: 1, entitlements: { [tokenHash]: adFree(nowMs + 86_400_000) },
        }],
        [`gallipickProductionLicenseBackupAccounts/${secondOwner}`, { allocatedBytes: 0, files: 0 }],
        [`gallipickAccountExitRequests/${secondOwner}`, { scope: 'CLOUD', status: 'REQUESTED', uid: secondUid }],
    ]);
    const reads = [];
    const db = {
        doc(path) {
            return { async get() {
                reads.push(path);
                return { data: () => records.get(path) };
            } };
        },
    };
    let listCount = 0;
    const authAdmin = {
        async listUsers(limit, token) {
            listCount++;
            assert.equal(limit, ADMIN_MEMBERS_PAGE_SIZE);
            if (!token) return {
                users: [
                    { uid: firstUid, email: 'firstmember@gmail.com', disabled: false,
                        metadata: { creationTime: '2025-11-01T02:00:00.000Z',
                            lastSignInTime: '2026-11-01T02:00:00.000Z' } },
                    { uid: secondUid, email: 'second@gmail.com', disabled: true, metadata: {} },
                ],
                pageToken: 'next-test-page',
            };
            assert.equal(token, 'next-test-page');
            return { users: [] };
        },
    };
    const service = createAdminMembersService({
        db, authAdmin, project: 'gallipick-production', allowedUidHashes: allowed,
        now: () => nowMs,
    });
    return { service, records, reads, get listCount() { return listCount; }, authAdmin, db };
}

test('member lookup refuses non-admins before Firebase Auth or Firestore reads', async () => {
    const f = fixtures();
    for (const user of [null, context('unapproved'), context(adminUid, { gallipickAdmin: false }),
        context(adminUid, { email_verified: false }), context(adminUid, { auth_time: 1 })]) {
        await assert.rejects(f.service.list(user), isCode('permission-denied'));
    }
    assert.equal(f.listCount, 0);
    assert.deepEqual(f.reads, []);
});

test('server-paginated accounts use correct owner hash and expose only verified plan summaries', async () => {
    const f = fixtures();
    const first = await f.service.list(context());
    assert.equal(first.checkedAtMs, nowMs);
    assert.equal(first.pageSize, 15);
    assert.equal(first.nextPageToken, 'next-test-page');
    assert.deepEqual(first.members.map(item => item.ownerId), [firstOwner, secondOwner]);
    const member = first.members[0];
    assert.equal(member.emailMasked, 'fi***@gmail.com');
    assert.equal(member.disabled, false);
    assert.equal(member.plans[0].mode, 'live');
    assert.equal(member.plans[0].subscriptionStatus, 'VERIFIED_CLOUD');
    assert.equal(member.plans[0].quotaBytes, 50_000_000_000);
    assert.equal(member.plans[0].usedBytes, 12_000_000_000);
    assert.equal(member.plans[0].lifecycleState, 'PAID');
    assert.equal(member.plans[1].subscriptionStatus, 'NO_VERIFIED_ACTIVE_PRODUCT');
    assert.equal(member.plans[1].usedBytes, null);
    const other = first.members[1];
    assert.equal(other.plans[1].subscriptionStatus, 'VERIFIED_AD_FREE');
    assert.equal(other.plans[1].quotaBytes, 0);
    assert.equal(other.plans[1].usedBytes, 0);
    assert.equal(other.exitStatus, 'CLOUD:REQUESTED');
    const json = JSON.stringify(first);
    for (const sensitive of [firstUid, secondUid, 'firstmember@gmail.com', 'second@gmail.com',
        tokenHash, 'test-purchase', 'entitlements', 'uid']) {
        assert.equal(json.includes(sensitive), false, sensitive);
    }
    const next = await f.service.list(context(), { pageToken: first.nextPageToken });
    assert.equal(next.members.length, 0);
    assert.equal(next.nextPageToken, null);
    assert.equal(f.listCount, 2);
    assert.ok(f.reads.some(path => path === `gallipickAccountExitRequests/${secondOwner}`));
    assert.ok(f.reads.every(path => !path.includes(firstUid) && !path.includes(secondUid)));
});

test('expired or invalid offers never count as active, even with large usage', () => {
    const expired = verifiedAdminSubscription({
        mode: 'live', nowMs,
        billing: {
            entitlements: { [tokenHash]: cloud(nowMs - 86_400_000) },
            cloudLifecycle: { tokenHash, productId: 'gallipick_cloud_50gb', basePlanId: 'monthly',
                expiresAtMs: nowMs - 8 * 86_400_000, checkedAtMs: nowMs - 86_400_000,
                recoveryAllowed: true },
        },
        usage: { allocatedBytes: 20_000_000_000 },
    });
    assert.equal(expired.subscriptionStatus, 'NO_VERIFIED_ACTIVE_PRODUCT');
    assert.equal(expired.quotaBytes, 0);
    assert.equal(expired.usedBytes, 20_000_000_000);
    assert.equal(expired.lifecycleState, 'LOCKED_AWAITING_PURGE');
    const invalid = verifiedAdminSubscription({
        mode: 'license-test', nowMs,
        billing: { entitlements: { [tokenHash]: product('untrusted_cloud', 'monthly', 1_000_000_000,
            nowMs + 86_400_000) } },
        usage: undefined,
    });
    assert.equal(invalid.subscriptionStatus, 'NO_VERIFIED_ACTIVE_PRODUCT');
    assert.equal(invalid.quotaBytes, 0);
});

test('never invent a quota or a valid storage number from corrupt allocation metadata', () => {
    for (const bad of [-1, NaN, '100', 1.5]) {
        assert.throws(() => verifiedAdminSubscription({
            mode: 'live', billing: {}, usage: { allocatedBytes: bad }, nowMs,
        }), isCode('failed-precondition'));
    }
    assert.equal(maskedAdminEmail(undefined), null);
    assert.equal(maskedAdminEmail('not-an-email'), null);
    assert.equal(maskedAdminEmail('valid@example.com'), 'va***@example.com');
});

test('strict pagination and a bounded auth page prevent untrusted cursors and record enumeration', async () => {
    const f = fixtures();
    for (const input of [{ ownerId: firstOwner }, { pageToken: '' },
        { pageToken: 'bad\nline' }, { pageToken: 'x'.repeat(2_049) }, []]) {
        await assert.rejects(f.service.list(context(), input), isCode('invalid-argument'));
    }
    assert.equal(f.listCount, 0);
    const bad = createAdminMembersService({
        db: f.db, project: 'gallipick-production', allowedUidHashes: allowed, now: () => nowMs,
        authAdmin: { listUsers: async () => ({
            users: Array.from({ length: 16 }, (_, i) => ({ uid: `member-${i}` })),
        }) },
    });
    await assert.rejects(bad.list(context()), isCode('failed-precondition'));
});

test('development project is never an admin member data source', () => {
    const f = fixtures();
    assert.throws(() => createAdminMembersService({
        db: f.db, authAdmin: f.authAdmin, project: 'gallipick-dev', allowedUidHashes: allowed,
    }), isCode('failed-precondition'));
});
