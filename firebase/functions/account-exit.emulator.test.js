import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { initializeApp, deleteApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';
import { createAccountExitService, ACCOUNT_EXIT_COLLECTION, ACCOUNT_EXIT_GRACE_MS,
    accountExitPaths } from './account-exit.js';

const projectId = 'demo-gallipick-exit-qa';
const required = ['FIRESTORE_EMULATOR_HOST', 'FIREBASE_AUTH_EMULATOR_HOST', 'FIREBASE_STORAGE_EMULATOR_HOST'];
const enabled = process.env.GP_EXIT_EMULATOR_QA === 'true';
if (enabled && (process.env.GCLOUD_PROJECT !== projectId ||
    required.some(name => !process.env[name] || !/^(127\.0\.0\.1|localhost):\d+$/.test(process.env[name])))) {
    throw new Error('Deletion QA requires the isolated demo Firebase emulators on localhost only.');
}
const ownerId = uid => createHash('sha256').update(uid).digest('hex');
const authContext = (uid, nowMs) => ({
    uid, token: { auth_time: Math.floor(nowMs / 1000), firebase: { sign_in_provider: 'google.com' } },
});
const confirmation = scope => ({
    scope, confirmation: scope === 'CLOUD' ? 'DELETE_CLOUD' : 'DELETE_ACCOUNT',
    acknowledgesPermanentDeletion: true, acknowledgesPlayCancellation: true,
});

test('Firestore, Auth and Storage emulators delete only the verified owner and preserve billing for CLOUD-only requests',
    { skip: !enabled }, async () => {
        const app = initializeApp({ projectId, storageBucket: 'gallipick-production.firebasestorage.app' },
            'gallipick-exit-emulator-test');
        const db = getFirestore(app);
        const auth = getAuth(app);
        const bucket = getStorage(app).bucket('gallipick-production.firebasestorage.app');
        const firstUid = 'exit-emulator-member-a';
        const secondUid = 'exit-emulator-member-b';
        const first = ownerId(firstUid);
        const second = ownerId(secondUid);
        let nowMs = 1_800_000_000_000;
        const service = createAccountExitService({
            db, authAdmin: auth, getBucket: async () => bucket,
            project: 'gallipick-production', now: () => nowMs,
        });
        const object = (root, owner, key) => `${root}/${owner}/${key}`;
        const primaryObjects = [
            object('cloud02/production/users', first, 'one.jpg'),
            object('cloud02/production/users', first, 'secret/two.mp4'),
            object('cloud02/license-test/users', first, 'v2/files/three.bin'),
        ];
        const otherObject = object('cloud02/production/users', second, 'remain.jpg');
        try {
            await auth.createUser({ uid: firstUid, email: 'exit-a@example.test' });
            await auth.createUser({ uid: secondUid, email: 'exit-b@example.test' });
            await db.doc(`gallipickProductionBackupAccounts/${first}`).set({ allocatedBytes: 3, files: 3 });
            await db.doc(`gallipickProductionBackupAccounts/${first}/originals/a`).set({ state: 'VERIFIED' });
            await db.doc(`gallipickProductionBackupVaultAccounts/${first}`).set({ allocatedBytes: 3 });
            await db.doc(`gallipickProductionBackupVaultAccounts/${first}/originals/b`).set({ state: 'VERIFIED' });
            await db.doc(`gallipickProductionBackupResumableAccountsJobs/${first}-job`).set({ owner: first });
            await db.doc(`gallipickProductionBillingAccounts/${first}`).set({
                revision: 1, entitlements: { syntheticTestEntitlement: { productId: 'gallipick_cloud_10gb' } },
            });
            await db.doc(`gallipickProductionBackupAccounts/${second}`).set({ allocatedBytes: 1, files: 1 });
            await db.doc(`gallipickProductionBillingAccounts/${second}`).set({ revision: 1 });
            for (const [scope, mode] of [
                ['gallipickProductionBilling', 'live'],
                ['gallipickProductionLicenseBilling', 'license-test'],
            ]) {
                await db.doc(`${scope}TokenEscrows/${'1'.repeat(64)}`).set({
                    owner: first, tokenHash: '1'.repeat(64), mode,
                    formatVersion: 1, ciphertext: 'opaque-synthetic-ciphertext',
                });
            }
            await db.doc(`gallipickProductionBillingTokenEscrows/${'2'.repeat(64)}`).set({
                owner: second, tokenHash: '2'.repeat(64), mode: 'live',
                formatVersion: 1, ciphertext: 'other-customer-must-not-be-deleted',
            });
            for (const mode of ['live', 'license-test']) {
                const ref = db.doc(`gallipickAdminManualGrants/${mode}-${first}`);
                await ref.set({ source: 'ADMIN_MANUAL', ownerId: first, mode,
                    status: 'ACTIVE', kind: 'CLOUD_10GB' });
                await ref.collection('history').doc('synthetic').set({
                    action: 'ISSUE', ownerId: first, actorOwnerId: first,
                    mode, recordedAtMs: nowMs,
                });
            }
            await db.doc(`gallipickAdminManualGrants/live-${second}`).set({
                source: 'ADMIN_MANUAL', ownerId: second, mode: 'live',
                status: 'ACTIVE', kind: 'AD_FREE',
            });
            for (const name of [...primaryObjects, otherObject]) {
                await bucket.file(name).save(Buffer.from('synthetic-emulator-only'));
            }

            const state = await service.request(authContext(firstUid, nowMs), confirmation('CLOUD'));
            assert.equal(state.status, 'REQUESTED');
            await assert.rejects(service.assertActive(authContext(firstUid, nowMs), 'write'),
                error => error.code === 'permission-denied');
            const existing = await service.status(authContext(secondUid, nowMs));
            assert.equal(existing.status, 'NONE');

            nowMs += ACCOUNT_EXIT_GRACE_MS + 60_000;
            const ref = db.doc(`${ACCOUNT_EXIT_COLLECTION}/${first}`);
            for (let i = 0; i < 120; i++) {
                if ((await ref.get()).data()?.status === 'COMPLETED') break;
                const work = await service.processOne(ref);
                if (!work.claimed && i > 1) throw new Error('Deletion stopped progressing in emulators');
                nowMs += 3 * 60_000;
            }
            assert.equal((await ref.get()).data()?.status, 'COMPLETED');
            for (const file of primaryObjects) {
                const [exists] = await bucket.file(file).exists();
                assert.equal(exists, false, `File survived deletion: ${file}`);
            }
            assert.equal((await bucket.file(otherObject).exists())[0], true);
            assert.equal((await db.doc(`gallipickProductionBackupAccounts/${first}`).get()).exists, false);
            assert.equal((await db.doc(`gallipickProductionBackupAccounts/${first}/originals/a`).get()).exists, false);
            assert.equal((await db.doc(`gallipickProductionBackupVaultAccounts/${first}/originals/b`).get()).exists, false);
            assert.equal((await db.doc(`gallipickProductionBackupResumableAccountsJobs/${first}-job`).get()).exists, false);
            assert.equal((await db.doc(`gallipickProductionBillingAccounts/${first}`).get()).exists, true);
            for (const scope of ['gallipickProductionBilling', 'gallipickProductionLicenseBilling']) {
                assert.equal((await db.doc(`${scope}TokenEscrows/${'1'.repeat(64)}`).get()).exists, true);
            }
            for (const mode of ['live', 'license-test']) {
                assert.equal((await db.doc(`gallipickAdminManualGrants/${mode}-${first}`).get()).exists, true);
            }
            assert.equal((await db.doc(`gallipickProductionBackupAccounts/${second}`).get()).exists, true);
            assert.equal((await auth.getUser(firstUid)).uid, firstUid);
            assert.equal((await auth.getUser(secondUid)).uid, secondUid);
            await assert.rejects(service.assertActive(authContext(firstUid, nowMs), 'write'),
                error => error.code === 'permission-denied');

            await assert.rejects(service.resumeCloudBackup(authContext(firstUid, nowMs - 11 * 60_000),
                { confirmation: 'RESUME_CLOUD_BACKUP' }), error => error.code === 'failed-precondition');
            const restarted = await service.resumeCloudBackup(authContext(firstUid, nowMs),
                { confirmation: 'RESUME_CLOUD_BACKUP' });
            assert.equal(restarted.status, 'RESUMED');
            await service.assertActive(authContext(firstUid, nowMs), 'write');

            const fresh = await service.request(authContext(firstUid, nowMs), confirmation('ACCOUNT'));
            assert.equal(fresh.scope, 'ACCOUNT');
            nowMs += ACCOUNT_EXIT_GRACE_MS + 60_000;
            for (let i = 0; i < 150; i++) {
                if ((await ref.get()).data()?.status === 'COMPLETED' &&
                    (await ref.get()).data()?.scope === 'ACCOUNT') break;
                await service.processOne(ref);
                nowMs += 3 * 60_000;
            }
            const finished = (await ref.get()).data();
            assert.equal(finished.status, 'COMPLETED');
            assert.equal(finished.scope, 'ACCOUNT');
            assert.equal(finished.uid, undefined);
            assert.equal((await db.doc(`gallipickProductionBillingAccounts/${first}`).get()).exists, false);
            for (const scope of ['gallipickProductionBilling', 'gallipickProductionLicenseBilling']) {
                assert.equal((await db.doc(`${scope}TokenEscrows/${'1'.repeat(64)}`).get()).exists, false);
            }
            assert.equal((await db.doc(`gallipickProductionBillingTokenEscrows/${'2'.repeat(64)}`).get()).exists, true);
            for (const mode of ['live', 'license-test']) {
                const path = `gallipickAdminManualGrants/${mode}-${first}`;
                assert.equal((await db.doc(path).get()).exists, false);
                assert.equal((await db.doc(`${path}/history/synthetic`).get()).exists, false);
            }
            assert.equal((await db.doc(`gallipickAdminManualGrants/live-${second}`).get()).exists, true);
            await assert.rejects(auth.getUser(firstUid), error => error.code === 'auth/user-not-found');
            assert.equal((await auth.getUser(secondUid)).uid, secondUid);
            assert.equal((await bucket.file(otherObject).exists())[0], true);
            for (const root of accountExitPaths.objectRoots) {
                const [items] = await bucket.getFiles({ prefix: `${root}/${first}/`, maxResults: 1 });
                assert.equal(items.length, 0);
            }
        } finally {
            // Synthetic emulator project only. Never clean up an unverified production resource.
            if (process.env.GCLOUD_PROJECT === projectId && required.every(name => process.env[name])) {
                await Promise.allSettled([auth.deleteUser(firstUid), auth.deleteUser(secondUid)]);
                await Promise.allSettled([db.recursiveDelete(db.doc(`gallipickAccountExitRequests/${first}`)),
                    db.recursiveDelete(db.doc(`gallipickProductionBillingAccounts/${second}`)),
                    db.recursiveDelete(db.doc(`gallipickProductionBackupAccounts/${second}`)),
                    db.recursiveDelete(db.doc(`gallipickAdminManualGrants/live-${second}`)),
                    db.recursiveDelete(db.doc(`gallipickProductionBillingTokenEscrows/${'2'.repeat(64)}`))]);
            }
            await deleteApp(app);
        }
    });
