import { createHash } from 'node:crypto';
import { Readable } from 'node:stream';
import assert from 'node:assert/strict';
import test from 'node:test';
import { createBackupService, MAX_FILE_BYTES, readOriginal } from './backup.js';
import { pilotSettings, requirePrivateBucket } from './pilot.js';

const photo = Buffer.from('small synthetic photo bytes');
const sha256 = createHash('sha256').update(photo).digest('hex');
const data = { sha256, sizeBytes: photo.length, mimeType: 'image/jpeg' };
const googleUser = uid => ({ uid, token: { firebase: { sign_in_provider: 'google.com' } } });
const isCode = code => error => error.code === code;

function createHarness({ ownerLimit = 1024, pilotLimit = 2048, requestsPerHour, uploadQuota } = {}) {
    const records = new Map();
    const bucketObjects = new Map();
    const signedOptions = [];
    let pendingTransaction = Promise.resolve();
    const db = {
        doc: path => ({ path, get: async () => ({ data: () => records.get(path) }) }),
        collection: path => ({
            where: (key, operator, value) => ({
                limit: limit => ({
                    async get() {
                        assert.equal(operator, '==');
                        const prefix = `${path}/`;
                        return { docs: [...records].filter(([id, record]) =>
                            id.startsWith(prefix) && !id.slice(prefix.length).includes('/') && record[key] === value)
                            .slice(0, limit).map(([id, record]) => ({ id: id.slice(prefix.length), data: () => record })) };
                    },
                }),
            }),
        }),
        runTransaction(run) {
            const running = pendingTransaction.then(async () => {
                const writes = new Map();
                const value = await run({
                    get: async ({ path }) => ({ data: () => records.get(path) }),
                    set: ({ path }, record) => writes.set(path, record),
                });
                for (const [path, record] of writes) records.set(path, record);
                return value;
            });
            pendingTransaction = running.catch(() => {});
            return running;
        },
    };
    const bucket = {
        name: 'gallipick-dev.firebasestorage.app',
        file(key, options = {}) {
            return {
                async getSignedUrl(options) {
                    signedOptions.push({ key, ...options });
                    return [`https://storage.googleapis.com/test/${key}?signed=test`];
                },
                async getMetadata() {
                    const object = bucketObjects.get(key);
                    if (!object || (options.generation && options.generation !== object.generation)) {
                        throw Object.assign(new Error('Not found'), { code: 404 });
                    }
                    return [{ size: String(object.bytes.length), generation: object.generation,
                        contentType: object.mimeType }];
                },
                createReadStream() {
                    const object = bucketObjects.get(key);
                    assert.equal(options.generation, object.generation);
                    return Readable.from([object.bytes]);
                },
            };
        },
    };
    const service = createBackupService({ db, getBucket: async () => bucket,
        pilotUsers: new Set(['owner-a', 'owner-b']), ownerLimit, pilotLimit, requestsPerHour, uploadQuota });
    return { service, signedOptions, bucketObjects, records };
}

test('only allowlisted Google accounts may reserve an original within pilot limits', async () => {
    const { service, records } = createHarness();
    await assert.rejects(service.begin(null, data), isCode('unauthenticated'));
    await assert.rejects(service.begin(googleUser('unknown'), data), isCode('permission-denied'));
    await assert.rejects(service.begin({ uid: 'owner-a', token: {} }, data), isCode('permission-denied'));
    await assert.rejects(service.begin(googleUser('owner-a'), { ...data, sizeBytes: MAX_FILE_BYTES + 1 }),
        isCode('invalid-argument'));
    await assert.rejects(service.begin(googleUser('owner-a'), { ...data, mimeType: 'application/json' }),
        isCode('invalid-argument'));
    assert.equal(records.size, 0);
});

test('concurrent retries reserve the same bytes once and sign an exact length, create-only PUT', async () => {
    const { service, signedOptions } = createHarness({ ownerLimit: photo.length });
    const [left, right] = await Promise.all([
        service.begin(googleUser('owner-a'), data), service.begin(googleUser('owner-a'), data),
    ]);
    assert.equal(left.state, 'UPLOAD');
    assert.equal(left.uploadId, right.uploadId);
    assert.deepEqual(left.headers, {
        'Content-Type': 'image/jpeg',
        'x-goog-content-length-range': `${photo.length},${photo.length}`,
        'x-goog-if-generation-match': '0',
    });
    assert.equal(signedOptions[0].key, signedOptions[1].key);
    assert.equal(signedOptions[0].action, 'write');
    assert.equal(signedOptions[0].version, 'v4');
    assert.equal(signedOptions[0].contentType, 'image/jpeg');
    assert.equal(signedOptions[0].extensionHeaders['x-goog-if-generation-match'], '0');
    await assert.rejects(service.begin(googleUser('owner-a'), { ...data, sha256: 'a'.repeat(64) }),
        isCode('resource-exhausted'));
});

test('same bytes in a different account consume a separate reservation and global cap', async () => {
    const { service, signedOptions } = createHarness({ pilotLimit: photo.length * 2 });
    const uploader = await service.begin(googleUser('owner-a'), data);
    await assert.rejects(service.finish(googleUser('owner-b'), { uploadId: uploader.uploadId }), isCode('not-found'));
    await service.begin(googleUser('owner-b'), data);
    assert.notEqual(signedOptions[0].key, signedOptions[1].key);
    await assert.rejects(service.begin(googleUser('owner-b'), { ...data, sha256: 'a'.repeat(64) }),
        isCode('resource-exhausted'));
});

test('an expired plan blocks reservation and retry signing while verified originals stay available', async () => {
    let quota = 0;
    const { service, signedOptions, bucketObjects } = createHarness({ uploadQuota: async () => quota });
    const owner = googleUser('owner-a');
    await assert.rejects(service.begin(owner, data), isCode('resource-exhausted'));
    quota = photo.length;
    const { uploadId } = await service.begin(owner, data);
    quota = photo.length - 1;
    await assert.rejects(service.begin(owner, data), isCode('resource-exhausted'));
    assert.equal(signedOptions.length, 1);
    bucketObjects.set(signedOptions[0].key, { generation: '1740000000000000', bytes: photo, mimeType: data.mimeType });
    assert.deepEqual(await service.finish(owner, { uploadId }), { state: 'VERIFIED' });
    assert.deepEqual(await service.begin(owner, data), { state: 'VERIFIED' });
    assert.equal((await service.list(owner)).items.length, 1);
});

test('completion requires a generation-pinned exact original and makes concurrent retries idempotent', async () => {
    const { service, signedOptions, bucketObjects, records } = createHarness();
    const owner = googleUser('owner-a');
    const { uploadId } = await service.begin(owner, data);
    const complete = { uploadId };
    await assert.rejects(service.finish(owner, complete), isCode('failed-precondition'));
    const key = signedOptions[0].key;
    const corrupt = Buffer.alloc(photo.length);
    bucketObjects.set(key, { generation: '1739999999999999', bytes: corrupt, mimeType: data.mimeType });
    await assert.rejects(service.finish(owner, complete), isCode('data-loss'));
    bucketObjects.set(key, { generation: '9007199254740993', bytes: photo, mimeType: data.mimeType });
    await assert.rejects(service.finish(owner, complete), isCode('data-loss'));
    assert.equal([...records.values()].some(record => record.state === 'VERIFIED'), false);
    bucketObjects.set(key, { generation: '1740000000000000', bytes: photo, mimeType: data.mimeType });
    assert.deepEqual(await Promise.all([service.finish(owner, complete), service.finish(owner, complete)]),
        [{ state: 'VERIFIED' }, { state: 'VERIFIED' }]);
    assert.deepEqual(await service.begin(owner, data), { state: 'VERIFIED' });
    assert.equal([...records.values()].find(record => record.state === 'VERIFIED').generation, '1740000000000000');
    bucketObjects.delete(key);
    await assert.rejects(service.begin(owner, data), isCode('data-loss'));
    await assert.rejects(service.finish(owner, complete), isCode('data-loss'));
});

test('integrity rejects short or oversized streams before asserting a hash', async () => {
    await assert.rejects(readOriginal(Readable.from([Buffer.from('one')]), 4), isCode('data-loss'));
    await assert.rejects(readOriginal(Readable.from([Buffer.from('one')]), 2), isCode('data-loss'));
    assert.deepEqual(await readOriginal(Readable.from([photo]), photo.length), { sha256, sizeBytes: photo.length });
});

test('the Firebase pilot defaults to unavailable and validates project and private Seoul bucket', () => {
    const env = { GALLIPICK_BACKUP_UPLOADS_ENABLED: 'true', GALLIPICK_BACKUP_TEST_UIDS: 'account-a',
        GALLIPICK_BACKUP_BUCKET: 'gallipick-dev.firebasestorage.app' };
    const metadata = { location: 'ASIA-NORTHEAST3', storageClass: 'STANDARD', iamConfiguration: {
        publicAccessPrevention: 'enforced', uniformBucketLevelAccess: { enabled: true },
    } };
    assert.throws(() => pilotSettings('gallipick-dev', {}), isCode('failed-precondition'));
    assert.throws(() => pilotSettings('gallipick-prod', env), isCode('failed-precondition'));
    assert.throws(() => requirePrivateBucket({ ...metadata, location: 'US' }), isCode('failed-precondition'));
    assert.throws(() => requirePrivateBucket({ ...metadata, iamConfiguration: {} }), isCode('failed-precondition'));
    assert.throws(() => requirePrivateBucket({ ...metadata, lifecycle: { rule: [{ action: { type: 'Delete' } }] } }),
        isCode('failed-precondition'));
    assert.equal(pilotSettings('gallipick-dev', env).bucketName, env.GALLIPICK_BACKUP_BUCKET);
    assert.doesNotThrow(() => requirePrivateBucket(metadata));
});

test('an account cannot mint additional upload URLs after exhausting its request allowance', async () => {
    const { service, signedOptions } = createHarness({ requestsPerHour: 1 });
    const owner = googleUser('owner-a');
    const { uploadId } = await service.begin(owner, data);
    await assert.rejects(service.begin(owner, data), isCode('resource-exhausted'));
    await assert.rejects(service.finish(owner, { uploadId }), isCode('resource-exhausted'));
    await service.begin(googleUser('owner-b'), data);
    assert.equal(signedOptions.length, 2);
});

test('the owner catalog includes only verified originals and sanitizes names with a legacy fallback', async () => {
    const { service, signedOptions, bucketObjects } = createHarness();
    const owner = googleUser('owner-a');
    const { uploadId } = await service.begin(owner, { ...data, displayName: 'private/location/겨울\u202e 사진.jpg' });
    await service.begin(owner, { ...data, sha256: 'a'.repeat(64) });
    assert.deepEqual(await service.list(owner), { items: [] });
    assert.deepEqual(await service.list(googleUser('owner-b')), { items: [] });
    await assert.rejects(service.list(googleUser('unknown')), isCode('permission-denied'));
    const generation = '1740000000000000';
    bucketObjects.set(signedOptions[0].key, { generation, bytes: photo, mimeType: data.mimeType });
    await service.finish(owner, { uploadId });
    const otherOwner = googleUser('owner-b');
    const other = await service.begin(otherOwner, data);
    bucketObjects.set(signedOptions[2].key, { generation, bytes: photo, mimeType: data.mimeType });
    await service.finish(otherOwner, { uploadId: other.uploadId });
    const [visible] = (await service.list(owner)).items;
    const [legacy] = (await service.list(otherOwner)).items;
    assert.deepEqual(visible, { uploadId, displayName: '겨울 사진.jpg', mimeType: data.mimeType,
        sizeBytes: photo.length, sha256, verifiedAtMs: visible.verifiedAtMs });
    assert.ok(Number.isSafeInteger(visible.verifiedAtMs));
    assert.equal(legacy.displayName, `original-${sha256.slice(0, 12)}.jpg`);
});

test('download capabilities are short-lived, generation-pinned and owner-scoped', async () => {
    const { service, signedOptions, bucketObjects } = createHarness();
    const owner = googleUser('owner-a');
    const { uploadId } = await service.begin(owner, data);
    await assert.rejects(service.download(owner, { uploadId }), isCode('not-found'));
    const key = signedOptions[0].key;
    const generation = '1740000000000000';
    bucketObjects.set(key, { generation, bytes: photo, mimeType: data.mimeType });
    await service.finish(owner, { uploadId });
    await assert.rejects(service.download(googleUser('owner-b'), { uploadId }), isCode('not-found'));

    const startedAt = Date.now();
    const downloaded = await service.download(owner, { uploadId });
    const signed = signedOptions.at(-1);
    assert.deepEqual(downloaded, { uploadId, displayName: `original-${sha256.slice(0, 12)}.jpg`,
        mimeType: data.mimeType, sizeBytes: photo.length, sha256, generation,
        downloadUrl: `https://storage.googleapis.com/test/${key}?signed=test` });
    assert.deepEqual(signed.queryParams, { generation });
    assert.equal(signed.action, 'read');
    assert.equal(signed.version, 'v4');
    assert.equal(signed.key, key);
    assert.ok(signed.expires >= startedAt + 120_000 && signed.expires <= Date.now() + 120_000);

    bucketObjects.set(key, { generation: '1740000000000001', bytes: photo, mimeType: data.mimeType });
    await assert.rejects(service.download(owner, { uploadId }), isCode('data-loss'));
    assert.equal(signedOptions.length, 2);
});
