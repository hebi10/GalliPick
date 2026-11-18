import { createHash } from 'node:crypto';
import { Readable } from 'node:stream';
import assert from 'node:assert/strict';
import test from 'node:test';
import { authorize, createBackupService, MAX_FILE_BYTES, productionBackupPlan,
    PRODUCTION_MAX_FILE_BYTES, readOriginal } from './backup.js';
import { pilotSettings, requirePrivateBucket } from './pilot.js';

const photo = Buffer.from('small synthetic photo bytes');
const sha256 = createHash('sha256').update(photo).digest('hex');
const data = { sha256, sizeBytes: photo.length, mimeType: 'image/jpeg' };
const googleUser = uid => ({ uid, token: { firebase: { sign_in_provider: 'google.com' } } });
const isCode = code => error => error.code === code;

function createHarness({ ownerLimit = 1024, pilotLimit = 2048, requestsPerHour, uploadQuota, production } = {}) {
    const records = new Map();
    const bucketObjects = new Map();
    const signedOptions = [];
    let pendingTransaction = Promise.resolve();
    function query(path, key, value, { ordered = false, cursor } = {}) {
        const prefix = `${path}/`;
        return {
            orderBy(field) {
                assert.equal(field, '__name__');
                return query(path, key, value, { ordered: true, cursor });
            },
            startAfter(after) {
                assert.equal(ordered, true);
                return query(path, key, value, { ordered, cursor: after });
            },
            limit: limit => ({
                async get() {
                    const matching = [...records].filter(([id, record]) => id.startsWith(prefix) &&
                        !id.slice(prefix.length).includes('/') && record[key] === value &&
                        (cursor === undefined || id.slice(prefix.length) > cursor));
                    if (ordered) matching.sort(([left], [right]) => left < right ? -1 : Number(left !== right));
                    return { docs: matching.slice(0, limit).map(([id, record]) =>
                        ({ id: id.slice(prefix.length), data: () => record })) };
                },
            }),
        };
    }
    const db = {
        doc: path => ({ path, get: async () => ({ data: () => records.get(path) }) }),
        collection: path => ({
            where: (key, operator, value) => {
                assert.equal(operator, '==');
                return query(path, key, value);
            },
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
                    return [{ size: String(object.sizeBytes ?? object.bytes.length), generation: object.generation,
                        contentType: object.mimeType }];
                },
                createReadStream(streamOptions) {
                    const object = bucketObjects.get(key);
                    assert.equal(options.generation, object.generation);
                    assert.deepEqual(streamOptions, { validation: 'crc32c', decompress: false });
                    return Readable.from(object.chunks ? object.chunks() : [object.bytes]);
                },
            };
        },
    };
    const createService = (uploadsPaused, productionSettings = production) => createBackupService({
        db, getBucket: async () => bucket, pilotUsers: new Set(['owner-a', 'owner-b']), ownerLimit, pilotLimit,
        requestsPerHour, uploadQuota, uploadsPaused, production: productionSettings,
    });
    return { service: createService(), createService, signedOptions, bucketObjects, records };
}

const productionLicense = {
    namespace: 'gallipickProductionLicenseBackup', maxOriginalBytes: PRODUCTION_MAX_FILE_BYTES,
    authorizeOwner: auth => authorize(auth, new Set(['owner-a', 'owner-b'])), catalogDocumentId: '__name__',
};

test('production storage cannot default to a quota or use an unrecognized namespace or file limit', () => {
    assert.throws(() => createHarness({ production: productionLicense }), isCode('failed-precondition'));
    for (const changes of [{ namespace: 'gallipickBackup' }, { maxOriginalBytes: undefined },
        { maxOriginalBytes: PRODUCTION_MAX_FILE_BYTES + 1 }, { maxOriginalBytes: 0 }, { authorizeOwner: undefined }]) {
        assert.throws(() => createHarness({ production: { ...productionLicense, ...changes }, uploadQuota: async () => 100 }),
            isCode('failed-precondition'));
    }
});

test('production plan hides Cloud offers from legacy clients and allows owner restores when uploads pause', () => {
    const ad = { productId: 'gallipick_ad_free', quotaBytes: 0 };
    const smaller = { productId: 'gallipick_cloud_10gb', quotaBytes: 10_000_000_000 };
    const larger = { productId: 'gallipick_cloud_100gb', quotaBytes: 100_000_000_000 };
    const plan = { products: [ad, smaller, larger], activeProductId: null, uploadAllowed: true };
    const rollout = { objectPrefix: 'cloud02/license-test/users', maxOriginalBytes: PRODUCTION_MAX_FILE_BYTES,
        uploadsPaused: false };
    for (const version of [undefined, '1', 2]) {
        const legacy = productionBackupPlan(plan, version, rollout);
        assert.deepEqual(legacy.products, [ad]);
        assert.equal(legacy.backupAvailable, false);
        assert.equal(legacy.cloudCheckoutAllowed, false);
        assert.equal(legacy.uploadAllowed, false);
        assert.equal(legacy.backupObjectPrefix, undefined);
    }
    assert.deepEqual(productionBackupPlan({ ...plan, activeProductId: smaller.productId }, undefined, rollout).products,
        [ad, smaller]);
    const supported = productionBackupPlan(plan, 1, rollout);
    assert.equal(supported.cloudCheckoutAllowed, true);
    assert.equal(supported.backupAvailable, true);
    assert.equal(supported.backupObjectPrefix, rollout.objectPrefix);
    assert.equal(supported.maxOriginalBytes, rollout.maxOriginalBytes);
    assert.deepEqual(supported.products, plan.products);
    const paused = productionBackupPlan(plan, 1, { ...rollout, uploadsPaused: true });
    assert.equal(paused.backupAvailable, true);
    assert.equal(paused.cloudCheckoutAllowed, false);
    assert.equal(paused.uploadAllowed, false);
    assert.deepEqual(paused.products, plan.products);
    assert.equal(productionBackupPlan(plan, 1, undefined).backupAvailable, false);
});

test('production finalize rejects lapsed or lowered quotas even if bytes arrived after reservation', async () => {
    let quota = photo.length;
    const { service, createService, signedOptions, bucketObjects, records } = createHarness({
        production: productionLicense, uploadQuota: async () => quota,
    });
    const owner = googleUser('owner-a');
    const otherOwner = googleUser('owner-b');
    const beforeSigning = Date.now();
    const { uploadId } = await service.begin(owner, data);
    const [signed] = signedOptions;
    assert.match(signed.key, /^cloud02\/license-test\/users\//);
    assert.ok(signed.expires >= beforeSigning + 15 * 60 * 1000 && signed.expires <= Date.now() + 15 * 60 * 1000);
    await assert.rejects(createService(true).begin(owner, data), isCode('failed-precondition'));
    let streamed = false;
    const object = { generation: '1740000000000000', bytes: photo, mimeType: data.mimeType,
        async *chunks() { streamed = true; quota = photo.length - 1; yield photo; } };
    bucketObjects.set(signed.key, object);
    quota = 0;
    await assert.rejects(service.finish(owner, { uploadId }), isCode('resource-exhausted'));
    assert.equal(streamed, false);
    quota = photo.length;
    await assert.rejects(service.finish(owner, { uploadId }), isCode('resource-exhausted'));
    assert.equal(streamed, true);
    assert.equal((await service.list(owner)).items.length, 0);
    assert.equal([...records.values()].filter(record => record.state === 'RESERVED').length, 1);

    quota = photo.length;
    delete object.chunks;
    await assert.rejects(service.finish(otherOwner, { uploadId }), isCode('not-found'));
    assert.deepEqual(await service.finish(owner, { uploadId }), { state: 'VERIFIED' });
    quota = 0;
    await assert.rejects(service.begin(owner, { ...data, sourceFolder: 'New folder' }), isCode('resource-exhausted'));
    assert.equal((await service.list(owner)).items.length, 1);
    assert.equal((await service.list(owner)).items[0].sourceFolders, undefined);
    assert.equal((await service.list(otherOwner)).items.length, 0);
    await assert.rejects(service.download(otherOwner, { uploadId }), isCode('not-found'));
    assert.equal((await createService(true).download(owner, { uploadId })).generation, object.generation);
    assert.equal(signedOptions.at(-1).action, 'read');
    assert.deepEqual(signedOptions.at(-1).queryParams, { generation: object.generation });
    const live = createService(false, { ...productionLicense, namespace: 'gallipickProductionBackup' });
    assert.deepEqual(await live.list(owner), { items: [] });
    await assert.rejects(live.download(owner, { uploadId }), isCode('not-found'));
    await assert.rejects(service.download(googleUser('unapproved'), { uploadId }), isCode('permission-denied'));
});

test('production verifies a synthetic large original with a bounded stream and retains reads if the cap falls', async () => {
    const chunk = Buffer.alloc(1024 * 1024, 90);
    const chunkCount = PRODUCTION_MAX_FILE_BYTES / chunk.length;
    const hash = createHash('sha256');
    for (let i = 0; i < chunkCount; i += 1) hash.update(chunk);
    const sizeBytes = chunk.length * chunkCount;
    const original = { sha256: hash.digest('hex'), sizeBytes, mimeType: 'video/mp4' };
    const { service, createService, signedOptions, bucketObjects, records } = createHarness({
        ownerLimit: 100_000_000_000, pilotLimit: 1, production: productionLicense,
        uploadQuota: async () => 10_000_000_000,
    });
    const owner = googleUser('owner-a');
    await assert.rejects(service.begin(owner, { ...original, sizeBytes: sizeBytes + 1 }), isCode('invalid-argument'));
    const { uploadId, headers } = await service.begin(owner, original);
    assert.match(uploadId, /-[1-9][0-9]{8}$/);
    assert.equal(headers['x-goog-content-length-range'], `${sizeBytes},${sizeBytes}`);
    assert.equal(records.has('gallipickBackupControl/pilot'), false);
    bucketObjects.set(signedOptions[0].key, { generation: '1740000000000000', sizeBytes, mimeType: original.mimeType,
        async *chunks() { for (let i = 0; i < chunkCount; i += 1) yield chunk; } });
    assert.deepEqual(await service.finish(owner, { uploadId }), { state: 'VERIFIED' });
    const lowerCap = createService(false, { ...productionLicense, maxOriginalBytes: MAX_FILE_BYTES });
    await assert.rejects(lowerCap.begin(owner, original), isCode('invalid-argument'));
    assert.equal((await lowerCap.list(owner)).items[0].sizeBytes, sizeBytes);
    assert.equal((await lowerCap.download(owner, { uploadId })).sha256, original.sha256);
});

test('production catalogue pages in stable owner-scoped order without mixing reservations or modes', async () => {
    const { service, records } = createHarness({ production: productionLicense, uploadQuota: async () => 0 });
    const owner = googleUser('owner-a');
    const storagePath = `gallipickProductionLicenseBackupAccounts/${productionLicense.authorizeOwner(owner)}/originals`;
    const expected = [];
    for (let i = 104; i >= 0; i -= 1) {
        const sha = i.toString(16).padStart(64, '0');
        const uploadId = `${sha}-${photo.length}`;
        const state = i === 102 ? 'RESERVED' : 'VERIFIED';
        records.set(`${storagePath}/${uploadId}`, { ...data, sha256: sha, state,
            generation: '1740000000000000', verifiedAt: new Date(1000 + i) });
        if (state === 'VERIFIED') expected.unshift(uploadId);
    }
    await assert.rejects(service.list(owner, { cursor: '../some-account' }), isCode('invalid-argument'));
    const first = await service.list(owner);
    assert.equal(first.items.length, 100);
    assert.equal(first.nextCursor, expected[99]);
    assert.deepEqual(first.items.map(item => item.uploadId), expected.slice(0, 100));
    const next = await service.list(owner, { cursor: first.nextCursor });
    assert.deepEqual(next.items.map(item => item.uploadId), expected.slice(100));
    assert.equal(next.nextCursor, undefined);
    assert.deepEqual(await service.list(googleUser('owner-b'), { cursor: first.nextCursor }), { items: [] });
});

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

test('the upload pause requires explicit configuration in an enabled dev pilot', () => {
    const env = { GALLIPICK_BACKUP_UPLOADS_ENABLED: 'true', GALLIPICK_BACKUP_TEST_UIDS: 'owner-a,owner-b',
        GALLIPICK_BACKUP_BUCKET: 'gallipick-dev.firebasestorage.app' };
    const pause = { GALLIPICK_BACKUP_UPLOADS_PAUSED: 'true' };
    assert.equal(pilotSettings('gallipick-dev', env).uploadsPaused, false);
    assert.equal(pilotSettings('gallipick-dev', { ...env, GALLIPICK_BACKUP_UPLOADS_PAUSED: 'false' }).uploadsPaused, false);
    assert.equal(pilotSettings('gallipick-dev', { ...env, ...pause }).uploadsPaused, true);
    assert.throws(() => pilotSettings('gallipick-dev', { ...env, GALLIPICK_BACKUP_UPLOADS_PAUSED: 'TRUE' }),
        isCode('failed-precondition'));
    assert.throws(() => pilotSettings('gallipick-dev', { ...env, ...pause, GALLIPICK_BACKUP_UPLOADS_ENABLED: 'false' }),
        isCode('failed-precondition'));
    assert.throws(() => pilotSettings('gallipick-dev', { ...env, ...pause, GALLIPICK_BACKUP_TEST_UIDS: '' }),
        isCode('failed-precondition'));
});

test('paused uploads grant no new write capability while the owner can finish and restore', async () => {
    const { service, createService, signedOptions, bucketObjects, records } = createHarness();
    const owner = googleUser('owner-a');
    const otherOwner = googleUser('owner-b');
    const { uploadId } = await service.begin(owner, data);
    const before = new Map(records);
    const paused = createService(true);
    await assert.rejects(paused.begin(null, data), isCode('unauthenticated'));
    await assert.rejects(paused.begin(googleUser('unknown'), data), isCode('permission-denied'));
    await assert.rejects(paused.begin(owner, { ...data, sha256: 'a'.repeat(64) }), isCode('failed-precondition'));
    await assert.rejects(paused.begin(owner, data), isCode('failed-precondition'));
    assert.deepEqual(records, before);
    assert.equal(signedOptions.length, 1);

    const key = signedOptions[0].key;
    const generation = '1740000000000000';
    bucketObjects.set(key, { generation, bytes: Buffer.alloc(photo.length), mimeType: data.mimeType });
    await assert.rejects(paused.finish(owner, { uploadId }), isCode('data-loss'));
    assert.deepEqual(await paused.list(owner), { items: [] });
    bucketObjects.set(key, { generation, bytes: photo, mimeType: data.mimeType });
    await assert.rejects(paused.finish(otherOwner, { uploadId }), isCode('not-found'));
    assert.deepEqual(await paused.finish(owner, { uploadId }), { state: 'VERIFIED' });
    assert.deepEqual(await paused.finish(owner, { uploadId }), { state: 'VERIFIED' });
    const { items } = await paused.list(owner);
    assert.deepEqual(items.map(item => item.uploadId), [uploadId]);
    assert.deepEqual(await paused.list(otherOwner), { items: [] });
    await assert.rejects(paused.download(otherOwner, { uploadId }), isCode('not-found'));
    assert.equal((await paused.download(owner, { uploadId })).generation, generation);
    assert.deepEqual(signedOptions.map(({ action }) => action), ['write', 'read']);
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


test('source-folder metadata survives verification and a deduplicated second folder', async () => {
    const { service, bucketObjects, signedOptions, records } = createHarness();
    const owner = googleUser('owner-a');
    const first = await service.begin(owner, { ...data, displayName: 'IMG_110.jpg',
        sourceFolder: 'DCIM/Camera/', capturedAtMs: 1730000000000, width: 1920, height: 1080 });
    assert.equal(first.state, 'UPLOAD');
    const object = signedOptions[0];
    bucketObjects.set(object.key, { generation: '1740000000000000', bytes: photo, mimeType: data.mimeType });
    await service.finish(owner, { uploadId: first.uploadId });
    const firstItem = (await service.list(owner)).items[0];
    assert.deepEqual(firstItem.sourceFolders, ['DCIM/Camera']);
    assert.equal(firstItem.capturedAtMs, 1730000000000);
    assert.equal(firstItem.width, 1920);
    assert.equal(firstItem.height, 1080);
    assert.equal(firstItem.displayName, 'IMG_110.jpg');

    const allocationBefore = records.get('gallipickBackupAccounts/' +
        createHash('sha256').update('owner-a').digest('hex')).allocatedBytes;
    assert.deepEqual(await service.begin(owner, { ...data, sourceFolder: 'Pictures/Pets/' }),
        { state: 'VERIFIED' });
    const [updated] = (await service.list(owner)).items;
    assert.deepEqual(updated.sourceFolders, ['DCIM/Camera', 'Pictures/Pets']);
    await assert.rejects(service.begin(owner, { ...data, mimeType: 'image/png', sourceFolder: 'Movies/' }),
        isCode('failed-precondition'));
    assert.deepEqual((await service.list(owner)).items[0].sourceFolders,
        ['DCIM/Camera', 'Pictures/Pets']);
    assert.equal(records.get('gallipickBackupAccounts/' +
        createHash('sha256').update('owner-a').digest('hex')).allocatedBytes, allocationBefore);
    assert.equal(signedOptions.filter(options => options.action === 'write').length, 1);
    const downloaded = await service.download(owner, { uploadId: first.uploadId });
    assert.deepEqual(downloaded.sourceFolders, updated.sourceFolders);
    assert.equal((await service.list(googleUser('owner-b'))).items.length, 0);
});

test('source-folder metadata refuses traversal, controls, extra segments and forged fields', async () => {
    const { service, signedOptions } = createHarness();
    const owner = googleUser('owner-a');
    for (const sourceFolder of ['/DCIM', '../Camera', 'DCIM/../Camera', 'DCIM//Camera',
        'DCIM\\Camera', 'DCIM/./Camera', 'A/\u0000B', 'a'.repeat(161)]) {
        await assert.rejects(service.begin(owner, { ...data, sourceFolder }), isCode('invalid-argument'));
    }
    for (const extra of [{ width: -1 }, { width: 100001 }, { capturedAtMs: '123' },
        { durationMs: Number.MAX_SAFE_INTEGER + 1 }, { height: null }]) {
        await assert.rejects(service.begin(owner, { ...data, ...extra }), isCode('invalid-argument'));
    }
    assert.equal(signedOptions.length, 0);
});

test('a legacy original remains readable and gains metadata only on an authorized retry', async () => {
    const { service, signedOptions, bucketObjects } = createHarness();
    const owner = googleUser('owner-a');
    const { uploadId } = await service.begin(owner, data);
    bucketObjects.set(signedOptions[0].key, { generation: '1740000000000000',
        bytes: photo, mimeType: data.mimeType });
    await service.finish(owner, { uploadId });
    const legacy = (await service.list(owner)).items[0];
    assert.equal('sourceFolders' in legacy, false);
    assert.equal('capturedAtMs' in legacy, false);
    assert.deepEqual(await service.begin(owner, { ...data, sourceFolder: 'DCIM/Camera/' }),
        { state: 'VERIFIED' });
    const updated = (await service.list(owner)).items[0];
    assert.deepEqual(updated.sourceFolders, ['DCIM/Camera']);
    assert.equal(updated.sha256, legacy.sha256);
    assert.equal(updated.sizeBytes, legacy.sizeBytes);
});
