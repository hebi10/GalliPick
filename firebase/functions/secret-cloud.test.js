import assert from 'node:assert/strict';
import test from 'node:test';
import { authorize } from './backup.js';
import { createSecretCloud } from './secret-cloud.js';

const owner = { uid: 'secret-test-owner', token: { firebase: { sign_in_provider: 'google.com' } } };
const other = { ...owner, uid: 'secret-test-other-owner' };
const password = '합성 테스트 🔐 암호';
const restoredPassword = '새 비밀번호 테스트';
const minute = 60_000;

function createHarness() {
    let value = 1_800_000_000_000;
    let previousTransaction = Promise.resolve();
    const records = new Map();
    const snapshot = path => ({ data: () => structuredClone(records.get(path)) });
    const db = {
        doc: path => ({ path, get: async () => snapshot(path) }),
        runTransaction(action) {
            const pending = previousTransaction.then(async () => {
                const writes = new Map();
                const result = await action({
                    get: async ref => snapshot(ref.path),
                    set: (ref, data) => writes.set(ref.path, structuredClone(data)),
                });
                for (const [path, data] of writes) records.set(path, data);
                return result;
            });
            previousTransaction = pending.catch(() => {});
            return pending;
        },
    };
    const backup = Object.fromEntries(['begin', 'finish', 'list', 'download'].map(operation => [operation,
        async (auth, input) => ({ operation, uid: auth.uid, input })]));
    const service = resumable => createSecretCloud({ db, backup, resumable, credentialCollection: 'vaultCredentials',
        authorizeOwner: auth => authorize(auth, new Set(), true), now: () => value });
    return { service, records, advance: increment => { value += increment; } };
}

test('version 2 vault actions use the private resumable service only after a verified password', async () => {
    const harness = createHarness();
    const vault = harness.service({
        getPart: async (auth, input) => ({ owner: auth.uid, partIndex: input.partIndex }),
    });
    const { token } = await vault.handle(owner, { action: 'setup', password });
    const part = { action: 'getBackupPart', partIndex: 1, cloudProtocolVersion: 2 };
    await assert.rejects(vault.handle(owner, part), { code: 'permission-denied' });
    await assert.rejects(vault.handle(other, { ...part, token }), { code: 'permission-denied' });
    await assert.rejects(vault.handle(owner, { ...part, token, cloudProtocolVersion: 1 }), { code: 'failed-precondition' });
    assert.deepEqual(await vault.handle(owner, { ...part, token }), { owner: owner.uid, partIndex: 1 });
});

test('vault setup persists only derived secrets and returns a usable session', async () => {
    const harness = createHarness();
    const vault = harness.service();
    assert.deepEqual(await vault.handle(owner, { action: 'status' }), { configured: false });
    const { token, expiresInMs } = await vault.handle(owner, { action: 'setup', password });
    assert.match(token, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(expiresInMs, 15 * minute);
    assert.deepEqual(await vault.handle(owner, { action: 'status' }), { configured: true });
    assert.deepEqual(await vault.handle(other, { action: 'status' }), { configured: false });
    const [record] = harness.records.values();
    const stored = JSON.stringify(record);
    assert.equal(stored.includes(password), false);
    assert.equal(stored.includes(token), false);

    await vault.handle(other, { action: 'setup', password });
    const [, otherRecord] = harness.records.values();
    assert.notEqual(otherRecord.credential.salt, record.credential.salt);
    assert.notEqual(otherRecord.credential.hash, record.credential.hash);
});

test('vault requires approved Google authorization to inspect or configure a vault', async () => {
    const { service, records } = createHarness();
    const vault = service();
    await assert.rejects(vault.handle(null, { action: 'status' }), { code: 'unauthenticated' });
    const wrongProvider = { ...owner, token: { firebase: { sign_in_provider: 'password' } } };
    await assert.rejects(vault.handle(wrongProvider, { action: 'setup', password }), { code: 'permission-denied' });
    assert.equal(records.size, 0);
});

test('setup validates Unicode character count and UTF-8 byte limit', async () => {
    const { service, records } = createHarness();
    const vault = service();
    for (const invalid of ['1234567', '🔐'.repeat(7), '🔐'.repeat(65), 'abcde123\ud800']) {
        await assert.rejects(vault.handle(owner, { action: 'setup', password: invalid }), { code: 'invalid-argument' });
    }
    assert.equal(records.size, 0);
});

test('only one concurrent setup can choose the account password', async () => {
    const { service } = createHarness();
    const outcomes = await Promise.allSettled([password, restoredPassword].map(candidate =>
        service().handle(owner, { action: 'setup', password: candidate })));
    assert.equal(outcomes.filter(outcome => outcome.status === 'fulfilled').length, 1);
    const rejected = outcomes.find(outcome => outcome.status === 'rejected');
    assert.equal(rejected.reason.code, 'already-exists');
    await assert.rejects(service().handle(owner, { action: 'setup', password: restoredPassword }),
        { code: 'already-exists' });
});

test('unlock rotates the account session and expiry is checked before releasing backup access', async () => {
    const harness = createHarness();
    const vault = harness.service();
    const list = token => ({ action: 'listBackups', token });
    const first = await vault.handle(owner, { action: 'setup', password });
    await assert.rejects(vault.handle(owner, { action: 'unlock', password: restoredPassword }), { code: 'permission-denied' });
    assert.equal((await vault.handle(owner, list(first.token))).operation, 'list');
    const next = await vault.handle(owner, { action: 'unlock', password });
    await assert.rejects(vault.handle(owner, list(first.token)), { code: 'permission-denied' });
    assert.equal((await vault.handle(owner, list(next.token))).operation, 'list');
    harness.advance(next.expiresInMs);
    await assert.rejects(vault.handle(owner, list(next.token)), { code: 'permission-denied' });
});

test('overlapping unlocks cannot replace a newly issued session', async () => {
    const { service } = createHarness();
    await service().handle(owner, { action: 'setup', password });
    const unlock = () => service().handle(owner, { action: 'unlock', password });
    const outcomes = await Promise.allSettled([unlock(), unlock()]);
    assert.equal(outcomes.filter(outcome => outcome.status === 'fulfilled').length, 1);
    assert.equal(outcomes.find(outcome => outcome.status === 'rejected').reason.code, 'aborted');
    const { token } = outcomes.find(outcome => outcome.status === 'fulfilled').value;
    assert.equal((await service().handle(owner, { action: 'listBackups', token })).operation, 'list');
});

test('only the associated account can dispatch backup operations, with credential fields stripped', async () => {
    const { service } = createHarness();
    const vault = service();
    const { token } = await vault.handle(owner, { action: 'setup', password });
    await vault.handle(other, { action: 'setup', password });
    for (const [action, operation] of [['beginBackup', 'begin'], ['finishBackup', 'finish'],
        ['listBackups', 'list'], ['getBackupDownload', 'download']]) {
        const fields = { uploadId: 'synthetic', cloudProtocolVersion: 1 };
        const request = { ...fields, action, password, token };
        await assert.rejects(vault.handle(other, request), { code: 'permission-denied' });
        await assert.rejects(vault.handle(owner, { ...request, token: 'bogus' }), { code: 'permission-denied' });
        assert.deepEqual(await vault.handle(owner, request), { operation, uid: owner.uid, input: fields });
    }
    await assert.rejects(vault.handle(owner, { action: 'delete', token }), { code: 'invalid-argument' });
});

test('attempt limit survives service recreation and stays scoped to the locked account', async () => {
    const harness = createHarness();
    await harness.service().handle(owner, { action: 'setup', password });
    await harness.service().handle(other, { action: 'setup', password });
    harness.advance(15 * minute);
    for (let attempt = 0; attempt < 5; attempt += 1) {
        await assert.rejects(harness.service().handle(owner, { action: 'unlock', password: restoredPassword }),
            { code: 'permission-denied' });
    }
    await assert.rejects(harness.service().handle(owner, { action: 'unlock', password }), { code: 'resource-exhausted' });
    assert.match((await harness.service().handle(other, { action: 'unlock', password })).token, /^[A-Za-z0-9_-]{43}$/);
    harness.advance(15 * minute);
    assert.match((await harness.service().handle(owner, { action: 'unlock', password })).token, /^[A-Za-z0-9_-]{43}$/);
});
