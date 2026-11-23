import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { Readable } from 'node:stream';
import test from 'node:test';
import { ACCOUNT_EXIT_COLLECTION, mayProcessBackupAfterAccountExit } from './account-exit.js';
import { createResumableBackupService, RESUMABLE_PART_BYTES } from './resumable-backup.js';
import { composeVerifiedOriginal, storageKeys } from './resumable-storage.js';

const primary = 'a'.repeat(64);
const other = 'b'.repeat(64);
const video = Buffer.from('Synthetic original bytes');
const sha256 = createHash('sha256').update(video).digest('hex');
const media = { sha256, sizeBytes: video.length, mimeType: 'video/mp4', displayName: 'Travel.mp4' };
const limit = 100_000_000_000;
const prefix = 'cloud02/license-test/users';

function harness() {
    let time = 1_800_000_000_000;
    let nextTransaction = Promise.resolve();
    let quota = limit;
    let nextGeneration = 100;
    const records = new Map();
    const objects = new Map();
    const jobs = [];
    const signed = [];
    const deleted = [];
    const compositions = [];
    const stat = key => ({ data: () => structuredClone(records.get(key)) });
    const db = {
        doc: key => ({ key, get: async () => stat(key) }),
        collection(path) {
            let threshold;
            let count;
            const query = {
                where(field, operator, value) {
                    assert.equal(field, 'dueAtMs');
                    assert.equal(operator, '<=');
                    threshold = value;
                    return query;
                },
                orderBy(field) { assert.equal(field, 'dueAtMs'); return query; },
                limit(value) { count = value; return query; },
                async get() {
                    const prefix = `${path}/`;
                    return { docs: [...records].filter(([key, value]) => key.startsWith(prefix) && value.dueAtMs <= threshold)
                        .sort(([, left], [, right]) => left.dueAtMs - right.dueAtMs)
                        .slice(0, count).map(([key]) => stat(key)) };
                },
            };
            return query;
        },
        runTransaction(action) {
            const job = nextTransaction.then(async () => {
                const staged = new Map();
                const result = await action({
                    get: async ref => stat(ref.key),
                    set: (ref, value) => staged.set(ref.key, structuredClone(value)),
                });
                for (const [key, value] of staged) records.set(key, value);
                return result;
            });
            nextTransaction = job.catch(() => {});
            return job;
        },
    };
    function put(key, data, contentType = media.mimeType) {
        const entry = { data, contentType, generation: String(nextGeneration++) };
        objects.set(key, entry);
        return entry;
    }
    const bucket = {
        file(name, { generation } = {}) {
            function read() {
                const value = objects.get(name);
                if (!value || (generation && value.generation !== generation)) throw Object.assign(new Error('missing'), { code: 404 });
                return value;
            }
            return {
                name, generation, metadata: {},
                async getMetadata() { const value = read(); return [{ ...value, size: String(value.data.length) }]; },
                createReadStream: () => Readable.from([read().data]),
                async getSignedUrl(options) { signed.push({ name, options }); return [`https://storage.googleapis.com/synthetic/${name}?signed=fixture`]; },
                async save(data, { metadata, preconditionOpts }) {
                    const current = objects.get(name);
                    if (String(preconditionOpts.ifGenerationMatch) !== (current?.generation ?? '0')) {
                        throw Object.assign(new Error('precondition'), { code: 412 });
                    }
                    put(name, data, metadata.contentType);
                },
                async delete({ ifGenerationMatch }) {
                    assert.equal(String(ifGenerationMatch), read().generation);
                    deleted.push(name);
                    objects.delete(name);
                },
            };
        },
        async combine(sources, destination, options) {
            assert.equal(options.ifGenerationMatch, 0);
            if (objects.has(destination.name)) throw Object.assign(new Error('exists'), { code: 412 });
            const buffers = sources.map(source => {
                const current = objects.get(source.name);
                assert.equal(source.generation, current.generation);
                return current.data;
            });
            compositions.push(destination.name);
            put(destination.name, Buffer.concat(buffers), destination.metadata.contentType);
        },
    };
    function service(options = {}) {
        return createResumableBackupService({ db, getBucket: async () => bucket, authorizeOwner: user => user,
            allocationCollection: 'Allocation', catalogCollection: 'LargeCatalog', objectPrefix: prefix,
            uploadQuota: async () => quota, ownerLimit: limit, maxOwnerFiles: 20_000, catalogDocumentId: '__name__',
            enqueueVerification: async job => { jobs.push(job); }, now: () => time, ...options });
    }
    return { db, service, jobs, signed, records, objects, put, deleted, compositions, bucket,
        advance: duration => { time += duration; }, setQuota: value => { quota = value; } };
}

test('plan-sized originals reserve atomically without a separate original cap and retry without another reservation', async () => {
    const fixture = harness();
    const service = fixture.service();
    const full = { ...media, sizeBytes: limit };
    const [first, second] = await Promise.all([service.begin(primary, full), service.begin(primary, full)]);
    assert.equal(first.state, 'RESUMABLE');
    assert.equal(first.partSizeBytes, 64 * 1024 * 1024);
    assert.equal(first.partCount, 1491);
    assert.deepEqual(first, second);
    assert.equal(fixture.records.get(`Allocation/${primary}`).allocatedBytes, limit);
    await assert.rejects(service.begin(primary, media), { code: 'resource-exhausted' });
    await assert.rejects(service.begin(primary, { ...full, sizeBytes: limit + 1 }), { code: 'invalid-argument' });
    const last = await service.getPart(primary, { uploadId: first.uploadId, partIndex: 1490 });
    assert.equal(last.offsetBytes + last.sizeBytes, limit);
    assert.equal(last.headers['x-goog-content-length-range'], `${last.sizeBytes},${last.sizeBytes}`);
    assert.equal(last.headers['x-goog-if-generation-match'], '0');
    await assert.rejects(service.getPart(other, { uploadId: first.uploadId, partIndex: 1490 }), { code: 'not-found' });
});

test('worker verifies SHA-256 before create-only compose and authorizes only generation-pinned downloads', async () => {
    const fixture = harness();
    const service = fixture.service();
    const active = await service.begin(primary, media);
    const request = { uploadId: active.uploadId, partIndex: 0 };
    assert.equal((await service.getPart(primary, request)).state, 'UPLOAD');
    const partName = fixture.signed[0].name;
    fixture.put(partName, video);
    assert.deepEqual(await service.getPart(primary, request), { state: 'UPLOADED', offsetBytes: 0, sizeBytes: video.length });
    await assert.rejects(service.download(primary, request), { code: 'not-found' });
    assert.equal((await service.finish(primary, request)).state, 'VERIFYING');
    await service.verify(fixture.jobs[0]);
    assert.equal((await service.status(primary, request)).state, 'VERIFIED');
    const result = await service.download(primary, request);
    assert.equal(result.sha256, sha256);
    assert.equal(result.transferVersion, 2);
    assert.equal(fixture.signed.at(-1).options.queryParams.generation, result.generation);
    await assert.rejects(service.download(other, request), { code: 'not-found' });
    assert.equal(fixture.compositions.length, 1);
});

test('deletion blocks a paused composition while another account can finish its upload', async () => {
    const fixture = harness();
    const service = fixture.service({ mayProcessOwner: (transaction, owner) =>
        mayProcessBackupAfterAccountExit(fixture.db, transaction, owner) });
    let uploadId;
    let generation;
    let partName;
    for (const owner of [primary, other]) {
        ({ uploadId } = await service.begin(owner, media));
        await service.getPart(owner, { uploadId, partIndex: 0 });
        const recentPart = fixture.signed.at(-1).name;
        const object = fixture.put(recentPart, video);
        if (owner === primary) {
            generation = object.generation;
            partName = recentPart;
        }
        await service.finish(owner, { uploadId });
    }
    const recordKey = `LargeCatalog/${primary}/originals/${uploadId}`;
    fixture.records.set(recordKey, { ...fixture.records.get(recordKey), composing: true, generations: [generation] });
    fixture.records.set(`${ACCOUNT_EXIT_COLLECTION}/${primary}`, { scope: 'ACCOUNT', status: 'REQUESTED' });

    await service.processPending({ timeBudgetMs: 10_000 });

    assert.equal(fixture.records.get(recordKey).state, 'VERIFYING');
    assert.equal(fixture.records.get(`LargeCatalogJobs/${primary}-${uploadId}`).dueAtMs, Number.MAX_SAFE_INTEGER);
    assert.deepEqual(fixture.objects.get(partName).data, video);
    assert.equal((await service.status(other, { uploadId })).state, 'VERIFIED');
    assert.equal(fixture.compositions.length, 1);
    assert.ok(fixture.compositions[0].startsWith(`${prefix}/${other}/`));
});

test('bad bytes never produce a catalogue original and cleanup frees only this reservation', async () => {
    const fixture = harness();
    const service = fixture.service();
    await fixture.service({ catalogCollection: 'OtherCatalog' }).begin(primary, { ...media, sizeBytes: 100 });
    const active = await service.begin(primary, media);
    const request = { uploadId: active.uploadId, partIndex: 0 };
    await service.getPart(primary, request);
    const partName = fixture.signed[0].name;
    fixture.put(partName, Buffer.alloc(video.length));
    await service.finish(primary, request);
    await service.verify(fixture.jobs[0]);
    assert.equal((await service.status(primary, request)).state, 'FAILED');
    assert.equal(fixture.compositions.length, 0);
    fixture.advance(30 * 60 * 1000);
    await service.cleanup(fixture.jobs[0]);
    assert.equal((await service.status(primary, request)).state, 'CANCELLED');
    assert.equal(fixture.records.get(`Allocation/${primary}`).allocatedBytes, 100);
    assert.equal(fixture.objects.get(partName).data.length, 0);
    assert.equal(fixture.objects.get(partName).contentType, 'application/octet-stream');
    assert.deepEqual(fixture.deleted, []);
});

test('private and ordinary v2 catalogues share capacity without sharing keys or object access', async () => {
    const fixture = harness();
    fixture.setQuota(video.length);
    const ordinary = fixture.service();
    const vault = fixture.service({ privateVault: true, catalogCollection: 'VaultLargeCatalog' });
    const active = await vault.begin(primary, media);
    await vault.getPart(primary, { uploadId: active.uploadId, partIndex: 0 });
    assert.match(fixture.signed[0].name, /\/secret\/v2\/staging\//);
    await assert.rejects(ordinary.begin(primary, media), { code: 'resource-exhausted' });
    await assert.rejects(ordinary.getPart(primary, { uploadId: active.uploadId, partIndex: 0 }), { code: 'not-found' });
    assert.equal(RESUMABLE_PART_BYTES, active.partSizeBytes);
});

test('the optional legacy pilot-wide allocation is reserved and released with the owner allocation', async () => {
    const fixture = harness();
    const globalAllocation = { path: 'Control/pilot', byteLimit: 145, fileLimit: 500 };
    fixture.records.set(globalAllocation.path, { allocatedBytes: 120, files: 499 });
    const service = fixture.service({ globalAllocation });
    const started = await service.begin(primary, media);
    assert.deepEqual(fixture.records.get(globalAllocation.path), { allocatedBytes: 120 + media.sizeBytes, files: 500 });
    await assert.rejects(service.begin(other, { ...media, sizeBytes: 1 }), { code: 'resource-exhausted' });
    assert.equal(fixture.records.has(`Allocation/${other}`), false);
    await service.cancel(primary, { uploadId: started.uploadId });
    await service.cleanup({ owner: primary, ...started });
    assert.deepEqual(fixture.records.get(globalAllocation.path), { allocatedBytes: 120, files: 499 });
    assert.deepEqual(fixture.records.get(`Allocation/${primary}`), { allocatedBytes: 0, files: 0 });
});

test('an acknowledged contiguous part is skipped on reselect without accepting a missing part', async () => {
    const fixture = harness();
    const service = fixture.service();
    const original = { ...media, sizeBytes: RESUMABLE_PART_BYTES * 3 + 1 };
    const { uploadId } = await service.begin(primary, original);
    await service.getPart(primary, { uploadId, partIndex: 0 });
    fixture.put(fixture.signed[0].name, Buffer.alloc(RESUMABLE_PART_BYTES));
    await service.getPart(primary, { uploadId, partIndex: 1 });
    assert.equal((await service.begin(primary, original)).nextPartIndex, 1);
    await service.getPart(primary, { uploadId, partIndex: 3 });
    fixture.put(fixture.signed.at(-1).name, Buffer.alloc(1));
    await service.getPart(primary, { uploadId, partIndex: 3 });
    assert.equal((await service.begin(primary, original)).nextPartIndex, 1);
});

test('verification resumes its saved SHA-256 state on another worker invocation', async () => {
    const fixture = harness();
    const first = Buffer.alloc(RESUMABLE_PART_BYTES, 23);
    const second = Buffer.from('Second part');
    const media = { sha256: createHash('sha256').update(first).update(second).digest('hex'),
        sizeBytes: first.length + second.length, mimeType: 'video/mp4' };
    const service = fixture.service();
    const { uploadId } = await service.begin(primary, media);
    for (const [partIndex, bytes] of [first, second].entries()) {
        await service.getPart(primary, { uploadId, partIndex });
        fixture.put(fixture.signed.at(-1).name, bytes);
    }
    await service.finish(primary, { uploadId });
    await service.verify(fixture.jobs[0], { maximumParts: 1 });
    assert.equal((await service.status(primary, { uploadId })).state, 'VERIFYING');
    assert.equal(fixture.compositions.length, 0);
    await fixture.service().verify(fixture.jobs[0], { maximumParts: 1 });
    assert.equal((await service.status(primary, { uploadId })).state, 'VERIFIED');
    assert.equal(fixture.compositions.length, 1);
});

test('object composition preserves order across the provider source-object limit', async () => {
    const fixture = harness();
    const media = { uploadId: 'synthetic', transferId: 'fixture', partCount: 33, partSizeBytes: 1,
        sizeBytes: 33, mimeType: 'video/mp4' };
    const names = storageKeys('v2/fixture', media);
    const bytes = Buffer.from(Array.from({ length: 33 }, (_, index) => index));
    const generations = [...bytes].map((byte, index) => fixture.put(names.part(index), Buffer.from([byte])).generation);
    const output = await composeVerifiedOriginal(fixture.bucket, names, { ...media, generations }, () => true);
    assert.deepEqual(fixture.compositions, [names.intermediate(0, 0), names.intermediate(0, 1), names.final]);
    assert.deepEqual(fixture.objects.get(output.name).data, bytes);
});

test('the durable worker queue handles finalization and scheduled cleanup without an enqueue callback', async () => {
    const fixture = harness();
    const service = fixture.service({ enqueueVerification: undefined });
    const { uploadId } = await service.begin(primary, media);
    await service.getPart(primary, { uploadId, partIndex: 0 });
    const partName = fixture.signed[0].name;
    fixture.put(partName, video);
    await service.finish(primary, { uploadId });
    assert.deepEqual(fixture.jobs, []);
    await fixture.service().processPending({ timeBudgetMs: 10_000 });
    assert.equal((await service.status(primary, { uploadId })).state, 'VERIFIED');
    fixture.advance(30 * 60 * 1000);
    await fixture.service().processPending({ timeBudgetMs: 10_000 });
    assert.equal(fixture.objects.get(partName).data.length, 0);
    assert.equal(fixture.records.get(`Allocation/${primary}`).allocatedBytes, video.length);
    assert.equal((await service.status(primary, { uploadId })).state, 'VERIFIED');
});
