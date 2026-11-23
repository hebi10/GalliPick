import { randomBytes } from 'node:crypto';
import { createSHA256 } from 'hash-wasm';
import { BackupError, catalogOriginal, checkAllocation, createRequestLimiter, originalInput, readAllocation,
    signOriginalUpload } from './backup.js';
import { PRODUCTION_CLOUD_MAX_BYTES } from './monetization-entitlements.js';
import { addVerifiedPart, composeVerifiedOriginal, intermediateKeys, objectMetadata, partRange,
    removeStagedObject, retireStagedPart, storageKeys, verifiedMetadata } from './resumable-storage.js';

export const RESUMABLE_PART_BYTES = 64 * 1024 * 1024;
const minute = 60_000;
const partUrlMs = 5 * minute;
const downloadUrlMs = 2 * minute;
const reservationMs = 7 * 24 * 60 * minute;
const stagingDrainMs = 20 * minute;
const workerLeaseMs = 12 * minute;
const maximumPassParts = 12;
const maximumCleanupObjects = 32;
const finishedJob = Number.MAX_SAFE_INTEGER;
const pageSize = 100;
const ownerPattern = /^[a-f0-9]{64}$/;
const transferPattern = /^[a-f0-9]{32}$/;
const uploadPattern = /^[a-f0-9]{64}-[1-9][0-9]{0,11}$/;

const notFound = () => new BackupError('not-found', 'The resumable backup was not found.');
const unavailable = () => new BackupError('failed-precondition', 'The resumable backup is not accepting new parts.');

function uploadIdOf(input) {
    if (typeof input?.uploadId !== 'string' || !uploadPattern.test(input.uploadId)) {
        throw new BackupError('invalid-argument', 'A valid resumable backup ID is required.');
    }
    return input.uploadId;
}

function progressOf({ state, uploadId, transferId, partSizeBytes, partCount, nextPartIndex = 0 }) {
    return { state: state === 'DISCARDING' ? 'FAILED' : state, uploadId, transferId, partSizeBytes, partCount, nextPartIndex };
}

function keyForJob(record, owner, dueAtMs) {
    return { owner, uploadId: record.uploadId, transferId: record.transferId, dueAtMs };
}

function checkedJob(input) {
    if (typeof input?.owner !== 'string' || !ownerPattern.test(input.owner)
        || typeof input.transferId !== 'string' || !transferPattern.test(input.transferId)) {
        throw new BackupError('invalid-argument', 'The scheduled backup job identifier is invalid.');
    }
    return { owner: input.owner, uploadId: uploadIdOf(input), transferId: input.transferId };
}

function withoutReservation(usage, sizeBytes) {
    if (usage.allocatedBytes < sizeBytes || usage.files === 0) {
        throw new BackupError('failed-precondition', 'The backup allocation needs reconciliation.');
    }
    return { allocatedBytes: usage.allocatedBytes - sizeBytes, files: usage.files - 1 };
}

export function createResumableBackupService({ db, getBucket, authorizeOwner, allocationCollection, catalogCollection,
    objectPrefix, privateVault = false, uploadQuota, ownerLimit, maxOwnerFiles, catalogDocumentId, uploadsPaused = false,
    globalAllocation, enqueueVerification, checkQuotaOnPublish = false, mayProcessOwner, now = Date.now }) {
    if (!Number.isSafeInteger(ownerLimit) || ownerLimit <= 0 || ownerLimit > PRODUCTION_CLOUD_MAX_BYTES
        || !Number.isSafeInteger(maxOwnerFiles) || maxOwnerFiles <= 0 || !catalogDocumentId
        || typeof authorizeOwner !== 'function' || typeof uploadQuota !== 'function'
        || (globalAllocation && (typeof globalAllocation.path !== 'string' || !globalAllocation.path
            || !Number.isSafeInteger(globalAllocation.byteLimit) || globalAllocation.byteLimit <= 0
            || !Number.isSafeInteger(globalAllocation.fileLimit) || globalAllocation.fileLimit <= 0))) {
        throw new BackupError('failed-precondition', 'Resumable backups are not configured.');
    }
    const jobsCollection = `${catalogCollection}Jobs`;
    const originalsPath = owner => `${catalogCollection}/${owner}/originals`;
    const recordRef = (owner, uploadId) => db.doc(`${originalsPath(owner)}/${uploadId}`);
    const jobRef = (owner, uploadId) => db.doc(`${jobsCollection}/${owner}-${uploadId}`);
    const allocationRef = owner => db.doc(`${allocationCollection}/${owner}`);
    const globalRef = globalAllocation ? db.doc(globalAllocation.path) : null;
    const names = (owner, record) => storageKeys(`${objectPrefix}/${owner}/${privateVault ? 'secret/' : ''}v2`, record);
    const countPart = createRequestLimiter(db, `${catalogCollection}PartLimits`, 2_400);
    const countRead = createRequestLimiter(db, `${catalogCollection}ReadLimits`, 2_400);
    const countWrite = createRequestLimiter(db, `${catalogCollection}WriteLimits`, 120);

    async function quotaState(transaction, owner) {
        const allocation = allocationRef(owner);
        const usage = readAllocation((await transaction.get(allocation)).data());
        const quota = await uploadQuota(transaction, owner, ownerLimit);
        if (!Number.isSafeInteger(quota) || quota <= 0 || quota > ownerLimit || usage.allocatedBytes > quota) {
            throw new BackupError('resource-exhausted', 'The account does not have available upload storage.');
        }
        return { allocation, usage, quota };
    }

    function save(transaction, owner, record, dueAtMs) {
        transaction.set(recordRef(owner, record.uploadId), record);
        transaction.set(jobRef(owner, record.uploadId), keyForJob(record, owner, dueAtMs));
    }

    async function reserve(auth, input) {
        const owner = authorizeOwner(auth);
        if (uploadsPaused) throw unavailable();
        const original = originalInput(input, ownerLimit);
        const uploadId = `${original.sha256}-${original.sizeBytes}`;
        await countWrite(owner);
        const transferId = randomBytes(16).toString('hex');
        return db.runTransaction(async transaction => {
            const existing = (await transaction.get(recordRef(owner, uploadId))).data();
            if (existing?.state === 'VERIFIED') return progressOf(existing);
            const { allocation, usage, quota } = await quotaState(transaction, owner);
            if (existing && existing.state !== 'CANCELLED') {
                if (existing.mimeType !== original.mimeType || existing.sizeBytes !== original.sizeBytes
                    || existing.sha256 !== original.sha256) throw unavailable();
                return progressOf(existing);
            }
            const record = { ...original, uploadId, transferId, state: 'RESUMABLE', partSizeBytes: RESUMABLE_PART_BYTES,
                partCount: Math.ceil(original.sizeBytes / RESUMABLE_PART_BYTES), expiresAtMs: now() + reservationMs,
                lastPartGrantUntilMs: 0, nextPartIndex: 0, issuedParts: [] };
            const ownerUsage = checkAllocation(usage, quota, maxOwnerFiles, original.sizeBytes);
            if (globalRef) {
                const shared = (await transaction.get(globalRef)).data();
                const next = checkAllocation(shared, globalAllocation.byteLimit, globalAllocation.fileLimit, original.sizeBytes);
                transaction.set(globalRef, next);
            }
            transaction.set(allocation, ownerUsage);
            save(transaction, owner, record, record.expiresAtMs);
            return progressOf(record);
        });
    }

    async function part(auth, input) {
        const owner = authorizeOwner(auth);
        if (uploadsPaused) throw unavailable();
        const uploadId = uploadIdOf(input);
        const ref = recordRef(owner, uploadId);
        await countPart(owner);
        const record = (await ref.get()).data();
        if (!record) throw notFound();
        if (record.state !== 'RESUMABLE' || record.expiresAtMs <= now()) throw unavailable();
        const index = input?.partIndex;
        const range = partRange(record, index);
        const bucket = await getBucket();
        const keys = names(owner, record);
        const path = keys.part(index);
        const existing = await objectMetadata(bucket, path);
        let nextPartIndex = record.nextPartIndex;
        if (index === nextPartIndex + 1) {
            const previous = await objectMetadata(bucket, keys.part(nextPartIndex));
            if (previous) {
                verifiedMetadata(previous, { ...partRange(record, nextPartIndex), mimeType: record.mimeType });
                nextPartIndex += 1;
            }
        }
        if (existing) verifiedMetadata(existing, { ...range, mimeType: record.mimeType });
        if (existing && index === nextPartIndex) nextPartIndex += 1;
        const deadline = await db.runTransaction(async transaction => {
            const current = (await transaction.get(ref)).data();
            const timestamp = now();
            if (current?.transferId !== record.transferId || current.state !== 'RESUMABLE'
                || current.expiresAtMs <= timestamp) throw unavailable();
            const next = Math.max(current.nextPartIndex, nextPartIndex);
            if (existing) {
                if (next > current.nextPartIndex) save(transaction, owner, { ...current, nextPartIndex: next }, current.expiresAtMs);
                return 0;
            }
            await quotaState(transaction, owner);
            const grantUntil = timestamp + partUrlMs;
            const issuedParts = current.issuedParts.includes(index) ? current.issuedParts : [...current.issuedParts, index];
            const changed = { ...current, lastPartGrantUntilMs: Math.max(current.lastPartGrantUntilMs, grantUntil),
                expiresAtMs: timestamp + reservationMs, nextPartIndex: next, issuedParts };
            save(transaction, owner, changed, changed.expiresAtMs);
            return grantUntil;
        });
        if (existing) return { state: 'UPLOADED', ...range };
        const activeFor = deadline - now();
        if (activeFor <= 0) throw unavailable();
        const url = await signOriginalUpload(bucket.file(path), { ...range, mimeType: record.mimeType }, activeFor);
        return { state: 'UPLOAD', ...range, ...url };
    }

    async function finish(auth, input) {
        const owner = authorizeOwner(auth);
        const uploadId = uploadIdOf(input);
        const ref = recordRef(owner, uploadId);
        await countWrite(owner);
        const result = await db.runTransaction(async transaction => {
            const record = (await transaction.get(ref)).data();
            if (!record) throw notFound();
            if (record.state === 'VERIFIED' || record.state === 'VERIFYING') return { record };
            if (record.state !== 'RESUMABLE' || record.expiresAtMs <= now()) throw unavailable();
            await quotaState(transaction, owner);
            const changed = { ...record, state: 'VERIFYING' };
            const dueAtMs = now();
            save(transaction, owner, changed, dueAtMs);
            return { record: changed, job: keyForJob(changed, owner, dueAtMs) };
        });
        if (result.job && enqueueVerification) await enqueueVerification(result.job);
        return progressOf(result.record);
    }

    async function beginWorker(job, action) {
        const { owner, uploadId, transferId } = job;
        const ref = recordRef(owner, uploadId);
        const lease = randomBytes(16).toString('hex');
        return db.runTransaction(async transaction => {
            const record = (await transaction.get(ref)).data();
            const timestamp = now();
            if (!record || record.transferId !== transferId || record.leaseUntilMs > timestamp) return null;
            if (action === 'verify' && record.state !== 'VERIFYING') return null;
            if (mayProcessOwner && !await mayProcessOwner(transaction, owner)) {
                // The account deletion worker owns cleanup from this point.
                transaction.set(jobRef(owner, uploadId), keyForJob(record, owner, finishedJob));
                return null;
            }
            if (action === 'cleanup') {
                if ((!['VERIFIED', 'FAILED', 'DISCARDING'].includes(record.state)
                    && (record.state !== 'RESUMABLE' || record.expiresAtMs > timestamp)) || record.cleaned === true) return null;
                if (record.lastPartGrantUntilMs > 0 && timestamp < record.lastPartGrantUntilMs + stagingDrainMs) {
                    transaction.set(jobRef(owner, uploadId), keyForJob(record, owner, record.lastPartGrantUntilMs + stagingDrainMs));
                    return null;
                }
            }
            const state = action === 'cleanup' && record.state !== 'VERIFIED' ? 'DISCARDING' : record.state;
            const claimed = { ...record, state, lease, leaseUntilMs: timestamp + workerLeaseMs };
            save(transaction, owner, claimed, claimed.leaseUntilMs);
            return claimed;
        });
    }

    async function updateWorker(job, leased, changes, dueAtMs, options = {}) {
        const ref = recordRef(job.owner, job.uploadId);
        return db.runTransaction(async transaction => {
            const current = (await transaction.get(ref)).data();
            if (current?.transferId !== job.transferId || current.lease !== leased.lease) return null;
            if (options.checkQuota) await quotaState(transaction, job.owner);
            if (options.releaseQuota) {
                const allocation = allocationRef(job.owner);
                const usage = readAllocation((await transaction.get(allocation)).data());
                const ownerUsage = withoutReservation(usage, current.sizeBytes);
                if (globalRef) {
                    const shared = readAllocation((await transaction.get(globalRef)).data());
                    transaction.set(globalRef, withoutReservation(shared, current.sizeBytes));
                }
                transaction.set(allocation, ownerUsage);
            }
            const next = { ...current, ...changes };
            if (!options.keepLease) {
                delete next.lease;
                delete next.leaseUntilMs;
            }
            save(transaction, job.owner, next, options.keepLease ? current.leaseUntilMs : dueAtMs);
            return next;
        });
    }

    async function verify(input, { timeBudgetMs = 180_000, maximumParts = maximumPassParts } = {}) {
        const job = checkedJob(input);
        const record = await beginWorker(job, 'verify');
        if (!record) return;
        const stop = performance.now() + timeBudgetMs;
        const enoughTime = () => performance.now() < stop;
        let verifiedParts = record.generations ?? [];
        let hashState = record.hashState ?? null;
        let isComposing = record.composing === true;
        try {
            const bucket = await getBucket();
            const keys = names(job.owner, record);
            if (!isComposing) {
                const digest = await createSHA256();
                if (hashState) digest.load(Buffer.from(hashState, 'base64'));
                verifiedParts = [...verifiedParts];
                let processed = 0;
                while (verifiedParts.length < record.partCount && processed < Math.min(maximumParts, maximumPassParts) && enoughTime()) {
                    const index = verifiedParts.length;
                    const generation = await addVerifiedPart(bucket, keys.part(index),
                        { ...partRange(record, index), mimeType: record.mimeType }, digest, stop - performance.now());
                    if (generation === null) {
                        await updateWorker(job, record, { state: 'RESUMABLE', generations: verifiedParts, hashState,
                            nextPartIndex: Math.min(record.nextPartIndex, index) }, record.expiresAtMs);
                        return;
                    }
                    verifiedParts.push(generation);
                    hashState = Buffer.from(digest.save()).toString('base64');
                    processed += 1;
                }
                if (verifiedParts.length < record.partCount) {
                    await updateWorker(job, record, { generations: verifiedParts, hashState }, now());
                    return;
                }
                if (digest.digest('hex') !== record.sha256) {
                    throw new BackupError('data-loss', 'The uploaded checksum differs from the original.');
                }
                const canPublish = await updateWorker(job, record, { composing: true, generations: verifiedParts, hashState: null }, now(),
                    { checkQuota: true, keepLease: true });
                if (!canPublish) return;
                isComposing = true;
            }
            const result = await composeVerifiedOriginal(bucket, keys, { ...record, generations: verifiedParts }, enoughTime);
            if (!result) {
                await updateWorker(job, record, {}, now());
                return;
            }
            await updateWorker(job, record, { state: 'VERIFIED', verifiedAt: new Date(now()), generation: result.generation,
                composing: false, hashState: null, generations: [], cleaned: false }, now(),
                { checkQuota: checkQuotaOnPublish });
        } catch (error) {
            if (error instanceof BackupError && error.code === 'data-loss' && !isComposing) {
                await updateWorker(job, record, { state: 'FAILED', hashState: null, generations: [] }, now());
                return;
            }
            if (error instanceof BackupError && error.code === 'resource-exhausted' && !isComposing) {
                await updateWorker(job, record, { state: 'RESUMABLE', generations: verifiedParts, hashState }, record.expiresAtMs);
                return;
            }
            const progress = isComposing ? {} : { generations: verifiedParts, hashState };
            await updateWorker(job, record, progress, now() + minute);
            throw error;
        }
    }

    async function cleanup(input, { timeBudgetMs = 180_000 } = {}) {
        const job = checkedJob(input);
        const record = await beginWorker(job, 'cleanup');
        if (!record) return;
        const stop = performance.now() + timeBudgetMs;
        const keys = names(job.owner, record);
        const intermediate = intermediateKeys(record.partCount, keys);
        const granted = record.issuedParts;
        const total = granted.length + intermediate.length;
        let cursor = record.cleanupCursor ?? 0;
        const end = Math.min(total, cursor + maximumCleanupObjects);
        try {
            const bucket = await getBucket();
            while (cursor < end && performance.now() < stop) {
                if (cursor < granted.length) await retireStagedPart(bucket, keys.part(granted[cursor]));
                else await removeStagedObject(bucket, intermediate[cursor - granted.length]);
                cursor += 1;
            }
            if (cursor < total) {
                await updateWorker(job, record, { cleanupCursor: cursor }, now());
                return;
            }
            const published = record.state === 'VERIFIED';
            await updateWorker(job, record, { cleanupCursor: cursor, cleaned: true, hashState: null, generations: [],
                state: published ? 'VERIFIED' : 'CANCELLED' }, finishedJob, { releaseQuota: !published });
        } catch (error) {
            await updateWorker(job, record, { cleanupCursor: cursor }, now() + minute);
            throw error;
        }
    }

    return {
        begin: reserve,
        getPart: part,
        finish,
        verify,
        cleanup,
        async countVerifiedMedia(auth) {
            const owner = authorizeOwner(auth);
            try {
                const aggregate = await db.collection(originalsPath(owner)).where('state', '==', 'VERIFIED')
                    .limit(maxOwnerFiles).count().get();
                const count = aggregate.data().count;
                return Number.isSafeInteger(count) && count >= 0 && count < maxOwnerFiles ? count : null;
            } catch {
                return null;
            }
        },
        async status(auth, input) {
            const owner = authorizeOwner(auth);
            await countRead(owner);
            const record = (await recordRef(owner, uploadIdOf(input)).get()).data();
            if (!record) throw notFound();
            return progressOf(record);
        },
        async cancel(auth, input) {
            const owner = authorizeOwner(auth);
            const uploadId = uploadIdOf(input);
            await countWrite(owner);
            return db.runTransaction(async transaction => {
                const record = (await transaction.get(recordRef(owner, uploadId))).data();
                if (!record) throw notFound();
                if (record.state === 'CANCELLED' || record.state === 'DISCARDING') return progressOf(record);
                if (record.state !== 'RESUMABLE' && record.state !== 'FAILED') throw unavailable();
                const cancelled = { ...record, state: 'DISCARDING' };
                save(transaction, owner, cancelled, Math.max(now(), record.lastPartGrantUntilMs + stagingDrainMs));
                return progressOf(cancelled);
            });
        },
        async list(auth, input) {
            const owner = authorizeOwner(auth);
            const cursor = input?.cursor;
            if (cursor !== undefined) uploadIdOf({ uploadId: cursor });
            await countRead(owner);
            let query = db.collection(originalsPath(owner)).where('state', '==', 'VERIFIED').orderBy(catalogDocumentId);
            if (cursor !== undefined) query = query.startAfter(cursor);
            const { docs } = await query.limit(pageSize + 1).get();
            const page = docs.slice(0, pageSize);
            const items = page.map(document => {
                const record = document.data();
                const verifiedAtMs = record.verifiedAt instanceof Date ? record.verifiedAt.getTime() : record.verifiedAt?.toMillis?.();
                if (!Number.isSafeInteger(verifiedAtMs) || verifiedAtMs < 0) {
                    throw new BackupError('data-loss', 'The backup catalog timestamp is invalid.');
                }
                return { ...catalogOriginal(document.id, record, ownerLimit), verifiedAtMs, transferVersion: 2 };
            });
            return docs.length > pageSize ? { items, nextCursor: page.at(-1).id } : { items };
        },
        async download(auth, input) {
            const owner = authorizeOwner(auth);
            const uploadId = uploadIdOf(input);
            await countRead(owner);
            const record = (await recordRef(owner, uploadId).get()).data();
            if (record?.state !== 'VERIFIED') throw notFound();
            const original = catalogOriginal(uploadId, record, ownerLimit);
            const bucket = await getBucket();
            const key = names(owner, record).final;
            const { generation } = record;
            verifiedMetadata(await objectMetadata(bucket, key, generation), record);
            const [downloadUrl] = await bucket.file(key, { generation }).getSignedUrl({ version: 'v4', action: 'read',
                expires: Date.now() + downloadUrlMs, queryParams: { generation } });
            return { ...original, generation, downloadUrl, transferVersion: 2 };
        },
        async processPending({ timeBudgetMs = 330_000 } = {}) {
            const stop = performance.now() + timeBudgetMs;
            let processed = 0;
            let failures = 0;
            for (let round = 0; round < 32 && stop - performance.now() >= 5_000; round += 1) {
                const { docs } = await db.collection(jobsCollection).where('dueAtMs', '<=', now())
                    .orderBy('dueAtMs').limit(20).get();
                const before = processed;
                for (const document of docs) {
                    const remaining = stop - performance.now();
                    if (remaining < 5_000) break;
                    const job = checkedJob(document.data());
                    const record = (await recordRef(job.owner, job.uploadId).get()).data();
                    if (record?.transferId !== job.transferId) continue;
                    const run = record.state === 'VERIFYING' ? verify : cleanup;
                    try {
                        await run(job, { timeBudgetMs: remaining });
                    } catch {
                        // Provider errors may contain temporary capabilities. The lease or retry timestamp schedules the next pass.
                        failures += 1;
                    }
                    processed += 1;
                }
                if (processed === before) break;
            }
            return { processed, failures };
        },
    };
}
