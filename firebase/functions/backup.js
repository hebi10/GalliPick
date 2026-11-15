import { createHash } from 'node:crypto';

const MIB = 1024 * 1024;
export const MAX_FILE_BYTES = 64 * MIB;
const SIGNED_UPLOAD_MS = 5 * 60 * 1000;
const HOUR_MS = 60 * 60 * 1000;
const SHA256 = /^[a-f0-9]{64}$/;
const MEDIA_TYPE = /^(image|video)\/[a-z0-9][a-z0-9.+-]{0,62}$/;
const UPLOAD_ID = /^[a-f0-9]{64}-[1-9][0-9]{0,7}$/;
const VERIFIED = { state: 'VERIFIED' };

export class BackupError extends Error {
    constructor(code, message) {
        super(message);
        this.code = code;
    }
}

export function authorize(auth, pilotUsers) {
    if (typeof auth?.uid !== 'string' || auth.uid.length === 0) {
        throw new BackupError('unauthenticated', 'Sign in to start a backup.');
    }
    if (auth.token?.firebase?.sign_in_provider !== 'google.com' || !pilotUsers.has(auth.uid)) {
        throw new BackupError('permission-denied', 'This account is not enrolled in the backup pilot.');
    }
    return createHash('sha256').update(auth.uid).digest('hex');
}

function originalInput(data) {
    const { sha256, sizeBytes, mimeType } = data ?? {};
    if (typeof sha256 !== 'string' || !SHA256.test(sha256) || !Number.isSafeInteger(sizeBytes)
        || sizeBytes <= 0 || sizeBytes > MAX_FILE_BYTES || typeof mimeType !== 'string' || !MEDIA_TYPE.test(mimeType)) {
        throw new BackupError('invalid-argument', 'Choose a photo or video no larger than 64 MiB.');
    }
    return { sha256, sizeBytes, mimeType };
}

function checkAllocation(current, byteLimit, countLimit, requestedBytes) {
    const { allocatedBytes, files } = current ?? { allocatedBytes: 0, files: 0 };
    if (!Number.isSafeInteger(allocatedBytes) || allocatedBytes < 0 || !Number.isSafeInteger(files) || files < 0) {
        throw new BackupError('failed-precondition', 'The backup allocation needs reconciliation.');
    }
    if (allocatedBytes + requestedBytes > byteLimit || files + 1 > countLimit) {
        throw new BackupError('resource-exhausted', 'The backup pilot allocation is full.');
    }
    return { allocatedBytes: allocatedBytes + requestedBytes, files: files + 1 };
}

export async function readOriginal(stream, expectedBytes) {
    const digest = createHash('sha256');
    let sizeBytes = 0;
    for await (const chunk of stream) {
        sizeBytes += chunk.length;
        if (sizeBytes > expectedBytes) throw new BackupError('data-loss', 'The uploaded length differs from the original.');
        digest.update(chunk);
    }
    if (sizeBytes !== expectedBytes) throw new BackupError('data-loss', 'The uploaded length differs from the original.');
    return { sha256: digest.digest('hex'), sizeBytes };
}

export async function signOriginalUpload(file, { sizeBytes, mimeType }) {
    const signedHeaders = {
        'x-goog-content-length-range': `${sizeBytes},${sizeBytes}`,
        'x-goog-if-generation-match': '0',
    };
    const [uploadUrl] = await file.getSignedUrl({
        version: 'v4', action: 'write', expires: Date.now() + SIGNED_UPLOAD_MS,
        contentType: mimeType, extensionHeaders: signedHeaders,
    });
    return { uploadUrl, headers: { 'Content-Type': mimeType, ...signedHeaders } };
}

async function verifyOriginal(bucket, key, record) {
    try {
        const [{ generation, size, contentType, contentEncoding, metadata }] = await bucket.file(key).getMetadata();
        // The Storage SDK translates this option to a JavaScript number when building the request.
        if (typeof generation !== 'string' || !/^[1-9][0-9]*$/.test(generation)
            || !Number.isSafeInteger(Number(generation)) || Number(size) !== record.sizeBytes || contentType !== record.mimeType
            || contentEncoding || Object.keys(metadata ?? {}).length > 0) {
            throw new BackupError('data-loss', 'The uploaded metadata differs from the reservation.');
        }
        const original = bucket.file(key, { generation });
        const { sha256 } = await readOriginal(original.createReadStream({ validation: 'crc32c', decompress: false }),
            record.sizeBytes);
        if (sha256 !== record.sha256) throw new BackupError('data-loss', 'The uploaded checksum differs from the original.');
        return generation;
    } catch (error) {
        if (error.code === 404) throw new BackupError('failed-precondition', 'The original upload is incomplete.');
        throw error;
    }
}

async function verifiedOriginal(bucket, key, record) {
    if (typeof record.generation !== 'string' || !/^[1-9][0-9]*$/.test(record.generation)
        || !Number.isSafeInteger(Number(record.generation))) {
        throw new BackupError('data-loss', 'The verified original generation is unavailable.');
    }
    try {
        const [{ generation, size }] = await bucket.file(key, { generation: record.generation }).getMetadata();
        if (generation === record.generation && Number(size) === record.sizeBytes) return VERIFIED;
    } catch (error) {
        if (error.code !== 404) throw error;
    }
    throw new BackupError('data-loss', 'The verified original is unavailable.');
}

export function createBackupService({ db, getBucket, pilotUsers, ownerLimit = 1024 * MIB, pilotLimit = 2048 * MIB,
    requestsPerHour = 120 }) {
    const pilotAllocation = db.doc('gallipickBackupControl/pilot');
    const ownerPath = owner => `gallipickBackupAccounts/${owner}`;
    const objectRecord = (owner, uploadId) => db.doc(`${ownerPath(owner)}/originals/${uploadId}`);
    const objectKey = (owner, uploadId) => `cloud02/users/${owner}/${uploadId}`;

    function countRequest(owner) {
        const ref = db.doc(`gallipickBackupRequestLimits/${owner}`);
        const hour = Math.floor(Date.now() / HOUR_MS);
        return db.runTransaction(async transaction => {
            const previous = (await transaction.get(ref)).data();
            const requests = previous?.hour >= hour ? previous.requests : 0;
            if (!Number.isSafeInteger(requests) || requests < 0 || requests >= requestsPerHour) {
                throw new BackupError('resource-exhausted', 'The account backup request limit was reached.');
            }
            transaction.set(ref, { hour, requests: requests + 1 });
        });
    }

    return {
        async begin(auth, input) {
            const owner = authorize(auth, pilotUsers);
            const request = originalInput(input);
            const uploadId = `${request.sha256}-${request.sizeBytes}`;
            const recordRef = objectRecord(owner, uploadId);
            const ownerAllocation = db.doc(ownerPath(owner));
            await countRequest(owner);
            const bucket = await getBucket();

            const record = await db.runTransaction(async transaction => {
                const existing = (await transaction.get(recordRef)).data();
                if (existing) return existing;

                const ownerUsage = (await transaction.get(ownerAllocation)).data();
                const pilotUsage = (await transaction.get(pilotAllocation)).data();
                const nextOwner = checkAllocation(ownerUsage, ownerLimit, 250, request.sizeBytes);
                const nextPilot = checkAllocation(pilotUsage, pilotLimit, 500, request.sizeBytes);
                const reservation = { ...request, state: 'RESERVED', createdAt: new Date() };
                transaction.set(ownerAllocation, nextOwner);
                transaction.set(pilotAllocation, nextPilot);
                transaction.set(recordRef, reservation);
                return reservation;
            });

            const key = objectKey(owner, uploadId);
            if (record.state === 'VERIFIED') return verifiedOriginal(bucket, key, record);
            if (record.state !== 'RESERVED' || record.mimeType !== request.mimeType) {
                throw new BackupError('failed-precondition', 'The backup reservation differs from the request.');
            }
            const upload = await signOriginalUpload(bucket.file(key), record);
            return { state: 'UPLOAD', uploadId, ...upload };
        },

        async finish(auth, input) {
            const owner = authorize(auth, pilotUsers);
            const uploadId = input?.uploadId;
            if (typeof uploadId !== 'string' || !UPLOAD_ID.test(uploadId)) {
                throw new BackupError('invalid-argument', 'A valid backup reservation is required.');
            }
            await countRequest(owner);
            const recordRef = objectRecord(owner, uploadId);
            const record = (await recordRef.get()).data();
            if (!record) throw new BackupError('not-found', 'The backup reservation was not found.');
            const bucket = await getBucket();
            const key = objectKey(owner, uploadId);
            if (record.state === 'VERIFIED') return verifiedOriginal(bucket, key, record);
            if (record.state !== 'RESERVED') throw new BackupError('failed-precondition', 'The backup reservation is unavailable.');

            const generation = await verifyOriginal(bucket, key, record);
            return db.runTransaction(async transaction => {
                const latest = (await transaction.get(recordRef)).data();
                if (latest?.state === 'VERIFIED' && latest.generation === generation) return VERIFIED;
                if (latest?.state !== 'RESERVED' || latest.sha256 !== record.sha256 || latest.sizeBytes !== record.sizeBytes
                    || latest.mimeType !== record.mimeType) {
                    throw new BackupError('aborted', 'The backup reservation changed while verifying.');
                }
                transaction.set(recordRef, { ...latest, state: 'VERIFIED', generation, verifiedAt: new Date() });
                return VERIFIED;
            });
        },
    };
}
