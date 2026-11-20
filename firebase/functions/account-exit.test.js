import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { BackupError } from './backup.js';
import { ACCOUNT_EXIT_COLLECTION, ACCOUNT_EXIT_GRACE_MS, accountExitPaths,
    createAccountExitService } from './account-exit.js';

const SHA = value => createHash('sha256').update(value).digest('hex');
const minute = 60_000;
const startedAt = 1_800_000_000_000;
const googleAuth = (uid = 'member-one', signInMs = startedAt) => ({
    uid, token: { auth_time: Math.floor(signInMs / 1000), firebase: { sign_in_provider: 'google.com' } },
});
const confirmation = scope => ({
    scope, confirmation: scope === 'ACCOUNT' ? 'DELETE_ACCOUNT' : 'DELETE_CLOUD',
    acknowledgesPlayCancellation: true, acknowledgesPermanentDeletion: true,
});
const errorCode = value => error => error instanceof BackupError && error.code === value;

function fakeFirestore() {
    const records = new Map();
    const refFor = path => {
        const parts = path.split('/');
        return {
            path, id: parts.at(-1),
            async get() { return { exists: records.has(path), data: () => records.get(path) }; },
            async set(value) { records.set(path, structuredClone(value)); },
            async delete() { records.delete(path); },
            async listCollections() {
                const prefix = `${path}/`;
                return [...records.keys()].filter(key => key.startsWith(prefix)).map(key => key.slice(prefix.length).split('/')[0]);
            },
        };
    };
    const query = (name, clauses = []) => ({
        where(field, op, value) { return query(name, [...clauses, [field, op, value]]); },
        limit(count) {
            return {
                async get() {
                    let rows = [...records].filter(([path]) =>
                        path.startsWith(`${name}/`) && !path.slice(name.length + 1).includes('/'));
                    rows = rows.filter(([, data]) => clauses.every(([key, op, val]) => {
                        if (op === '==') return data[key] === val;
                        if (op === '<=') return Number.isFinite(data[key]) && data[key] <= val;
                        throw new Error('Unsupported test query');
                    }));
                    return { docs: rows.slice(0, count).map(([path]) => ({
                        ref: refFor(path), id: path.slice(name.length + 1),
                        data: () => records.get(path),
                    })) };
                },
            };
        },
    });
    return {
        records, doc: refFor, collection: name => query(name),
        async recursiveDelete(ref) {
            for (const path of [...records.keys()]) if (path === ref.path || path.startsWith(`${ref.path}/`)) records.delete(path);
        },
        async runTransaction(work) {
            const writes = [];
            const output = await work({
                get: ref => ref.get(),
                set: (ref, value) => writes.push([ref.path, structuredClone(value)]),
            });
            for (const [path, data] of writes) records.set(path, data);
            return output;
        },
    };
}

function fakeBucket() {
    const objects = new Map();
    let failDeleteOnce = false;
    return {
        objects,
        add(name, generation = '123456789') { objects.set(`${name}#${generation}`, { name, generation }); },
        failNextDelete() { failDeleteOnce = true; },
        async getFiles({ prefix, maxResults }) {
            const files = [...objects.values()].filter(file => file.name.startsWith(prefix))
                .slice(0, maxResults).map(file => ({
                    name: file.name, metadata: { generation: file.generation },
                }));
            return [files];
        },
        file(name, { generation }) {
            return {
                async delete(options) {
                    if (failDeleteOnce) { failDeleteOnce = false; throw Object.assign(new Error('Storage unavailable'), { code: 503 }); }
                    assert.equal(String(options.ifGenerationMatch), String(generation));
                    if (!objects.delete(`${name}#${generation}`)) throw Object.assign(new Error('Not found'), { code: 404 });
                },
            };
        },
    };
}

function harness() {
    const db = fakeFirestore();
    const bucket = fakeBucket();
    const deletedUsers = [];
    let time = startedAt;
    const admin = { async deleteUser(uid) { deletedUsers.push(uid); } };
    const service = createAccountExitService({ db, authAdmin: admin, getBucket: async () => bucket,
        project: 'gallipick-production', now: () => time });
    const tick = amount => { time += amount; };
    return { db, bucket, admin, deletedUsers, service, tick, get time() { return time; } };
}

async function drainJob(h, uid = 'member-one', scope = 'ACCOUNT') {
    const owner = SHA(uid);
    const ref = h.db.doc(`${ACCOUNT_EXIT_COLLECTION}/${owner}`);
    for (let i = 0; i < 100; i++) {
        const status = (await ref.get()).data()?.status;
        if (status === 'COMPLETED') return;
        await h.service.processOne(ref);
        h.tick(3 * minute);
    }
    assert.fail('Account exit did not finish in the bounded test steps');
}

function seedUserData(h, uid = 'member-one') {
    const owner = SHA(uid);
    const prefix = 'cloud02/production/users';
    const license = 'cloud02/license-test/users';
    h.bucket.add(`${prefix}/${owner}/photo.jpg`, '101');
    h.bucket.add(`${prefix}/${owner}/secret/video.mp4`, '102');
    h.bucket.add(`${prefix}/${owner}/v2/files/large-video.mp4`, '103');
    h.bucket.add(`${license}/${owner}/pilot.mov`, '104');
    h.bucket.add(`${prefix}/${owner}/photo.jpg`, '99');
    for (const root of accountExitPaths.ownerDocumentRoots) {
        h.db.records.set(`${root}/${owner}`, { owner, fixture: true });
        if (root.endsWith('Accounts')) h.db.records.set(`${root}/${owner}/originals/a`, { state: 'VERIFIED' });
    }
    for (const name of accountExitPaths.resumableJobCollections) {
        h.db.records.set(`${name}/${owner}-a`, { owner, uploadId: 'a' });
    }
    for (const name of accountExitPaths.billingDocumentRoots) {
        h.db.records.set(`${name}/${owner}`, { entitlements: { synthetic: true } });
    }
    for (const name of accountExitPaths.billingTokenCollections) {
        h.db.records.set(`${name}/token-${owner}`, { owner });
    }
    return owner;
}

test('self-service deletion requires a recent Google session and explicit scope confirmation', async () => {
    const h = harness();
    await assert.rejects(h.service.request(null, confirmation('ACCOUNT')), errorCode('unauthenticated'));
    await assert.rejects(h.service.request({ uid: 'member-one', token: {
        auth_time: startedAt / 1000, firebase: { sign_in_provider: 'password' },
    } }, confirmation('ACCOUNT')), errorCode('unauthenticated'));
    await assert.rejects(h.service.request(googleAuth('member-one', startedAt - 11 * minute),
        confirmation('ACCOUNT')), errorCode('failed-precondition'));
    await assert.rejects(h.service.request(googleAuth(), { ...confirmation('ACCOUNT'),
        acknowledgesPlayCancellation: false }), errorCode('invalid-argument'));
    await assert.rejects(h.service.request(googleAuth(), { ...confirmation('ACCOUNT'),
        uid: 'member-two' }), errorCode('invalid-argument'));
    const accepted = await h.service.request(googleAuth(), confirmation('ACCOUNT'));
    assert.equal(accepted.status, 'REQUESTED');
    assert.equal(accepted.scope, 'ACCOUNT');
    assert.equal(accepted.deadlineAtMs, startedAt + 14 * 24 * 60 * minute);
    assert.equal(await h.service.status(googleAuth('member-two')).then(s => s.status), 'NONE');
    const repeat = await h.service.request(googleAuth(), confirmation('ACCOUNT'));
    assert.deepEqual(repeat, accepted);
    await assert.rejects(h.service.request(googleAuth(), confirmation('CLOUD')), errorCode('failed-precondition'));
});

test('request blocks Cloud APIs immediately without affecting another account', async () => {
    const h = harness();
    assert.equal(await h.service.assertActive(googleAuth()), SHA('member-one'));
    await h.service.request(googleAuth(), confirmation('ACCOUNT'));
    await assert.rejects(h.service.assertActive(googleAuth()), errorCode('permission-denied'));
    await assert.rejects(h.service.assertActiveOwner(SHA('member-one')), errorCode('permission-denied'));
    assert.equal(await h.service.assertActiveOwner(SHA('member-two')), undefined);
    assert.equal(await h.service.assertActive(googleAuth('member-two')), SHA('member-two'));
    const ready = await h.service.processDue();
    assert.equal(ready.processed, 0);
});

test('Cloud-only deletion removes all owner originals and metadata while retaining Firebase/Auth billing', async () => {
    const h = harness();
    const owner = seedUserData(h);
    seedUserData(h, 'member-two');
    await h.service.request(googleAuth(), confirmation('CLOUD'));
    h.tick(ACCOUNT_EXIT_GRACE_MS);
    await drainJob(h, 'member-one', 'CLOUD');

    assert.equal(h.deletedUsers.length, 0);
    assert.ok([...h.bucket.objects.values()].every(file => !file.name.includes(`/${owner}/`)));
    assert.ok([...h.bucket.objects.values()].some(file => file.name.includes(`/${SHA('member-two')}/`)));
    for (const root of accountExitPaths.ownerDocumentRoots) {
        assert.equal([...h.db.records.keys()].some(path => path.startsWith(`${root}/${owner}`)), false);
    }
    for (const name of accountExitPaths.billingDocumentRoots) assert.ok(h.db.records.has(`${name}/${owner}`));
    for (const name of accountExitPaths.billingTokenCollections) assert.ok(h.db.records.has(`${name}/token-${owner}`));
    const record = (await h.db.doc(`${ACCOUNT_EXIT_COLLECTION}/${owner}`).get()).data();
    assert.equal(record.status, 'COMPLETED');
    assert.equal(record.uid, undefined);
    assert.equal(await h.service.assertActive(googleAuth()), owner);
});

test('account deletion removes both environments, vault, large-part data and auth only after verification', async () => {
    const h = harness();
    const owner = seedUserData(h);
    seedUserData(h, 'member-two');
    await h.service.request(googleAuth(), confirmation('ACCOUNT'));
    h.tick(ACCOUNT_EXIT_GRACE_MS);
    await drainJob(h);
    assert.deepEqual(h.deletedUsers, ['member-one']);
    assert.ok([...h.db.records.keys()].every(path =>
        !path.includes(owner) || path === `${ACCOUNT_EXIT_COLLECTION}/${owner}`));
    assert.ok([...h.bucket.objects.values()].every(file => !file.name.includes(`/${owner}/`)));
    assert.ok([...h.db.records.keys()].some(path => path.includes(SHA('member-two'))));
    await assert.rejects(h.service.assertActive(googleAuth()), errorCode('permission-denied'));
    const completed = (await h.db.doc(`${ACCOUNT_EXIT_COLLECTION}/${owner}`).get()).data();
    assert.equal(completed.uid, undefined);
    assert.equal(completed.scope, 'ACCOUNT');
    assert.equal(completed.status, 'COMPLETED');
});

test('transient Storage failure pauses deletion and never erases Firebase auth before retry', async () => {
    const h = harness();
    seedUserData(h);
    await h.service.request(googleAuth(), confirmation('ACCOUNT'));
    h.tick(ACCOUNT_EXIT_GRACE_MS);
    h.bucket.failNextDelete();
    const ref = h.db.doc(`${ACCOUNT_EXIT_COLLECTION}/${SHA('member-one')}`);
    await assert.rejects(h.service.processOne(ref), error => error.code === 503);
    assert.equal(h.deletedUsers.length, 0);
    assert.equal((await ref.get()).data().status, 'RETRY');
    h.tick(3 * minute);
    await drainJob(h);
    assert.deepEqual(h.deletedUsers, ['member-one']);
});

test('owner-scoped Storage precondition prevents deletion of another user object', async () => {
    const h = harness();
    await h.service.request(googleAuth(), confirmation('ACCOUNT'));
    h.tick(ACCOUNT_EXIT_GRACE_MS);
    const targetOwner = SHA('member-one');
    const root = accountExitPaths.objectRoots[0];
    const existing = h.bucket.getFiles;
    h.bucket.getFiles = async args => args.prefix === `${root}/${targetOwner}/`
        ? [[{ name: `${root}/${SHA('member-two')}/sensitive.jpg`, metadata: { generation: '11' } }]]
        : existing(args);
    const ref = h.db.doc(`${ACCOUNT_EXIT_COLLECTION}/${targetOwner}`);
    await assert.rejects(h.service.processOne(ref), errorCode('data-loss'));
    assert.equal(h.deletedUsers.length, 0);
});
