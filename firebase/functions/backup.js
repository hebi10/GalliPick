import { createHash } from 'node:crypto';

const MIB = 1024 * 1024;
export const MAX_FILE_BYTES = 64 * MIB;
const MAX_OWNER_FILES = 250;
const SIGNED_UPLOAD_MS = 5 * 60 * 1000;
const SIGNED_DOWNLOAD_MS = 2 * 60 * 1000;
const HOUR_MS = 60 * 60 * 1000;
const SHA256 = /^[a-f0-9]{64}$/;
const MEDIA_TYPE = /^(image|video)\/[a-z0-9][a-z0-9.+-]{0,62}$/;
const UPLOAD_ID = /^[a-f0-9]{64}-[1-9][0-9]{0,7}$/;
const VERIFIED = { state: 'VERIFIED' };
const ORIGINAL_EXTENSIONS = new Map([['jpeg', 'jpg'], ['quicktime', 'mov'], ['svg+xml', 'svg']]);

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
    const { sha256, sizeBytes, mimeType, displayName } = data ?? {};
    if (typeof sha256 !== 'string' || !SHA256.test(sha256) || !Number.isSafeInteger(sizeBytes)
        || sizeBytes <= 0 || sizeBytes > MAX_FILE_BYTES || typeof mimeType !== 'string' || !MEDIA_TYPE.test(mimeType)) {
        throw new BackupError('invalid-argument', 'Choose a photo or video no larger than 64 MiB.');
    }
    return { sha256, sizeBytes, mimeType, displayName: safeOriginalName(displayName, sha256, mimeType) };
}

function safeOriginalName(displayName, sha256, mimeType) {
    if (typeof displayName === 'string' && displayName.length <= 400) {
        const basename = displayName.split(/[/\\]/).at(-1).normalize('NFC')
            .replace(/[\p{Cc}\p{Cf}]/gu, '').replace(/[<>:"|?*]/g, '_').trim();
        const trimmed = Array.from(basename).slice(0, 60).join('').replace(/^[. ]+|[. ]+$/g, '');
        if (trimmed) return trimmed;
    }
    const subtype = mimeType.split('/')[1];
    const extension = ORIGINAL_EXTENSIONS.get(subtype)
        ?? (/^[a-z0-9]{1,12}$/.test(subtype) ? subtype : 'bin');
    return `original-${sha256.slice(0, 12)}.${extension}`;
}

function validGeneration(generation) {
    return typeof generation === 'string' && /^[1-9][0-9]*$/.test(generation)
        && Number.isSafeInteger(Number(generation));
}

function catalogOriginal(uploadId, record) {
    if (typeof record.sha256 !== 'string' || !SHA256.test(record.sha256)
        || !Number.isSafeInteger(record.sizeBytes) || record.sizeBytes <= 0 || record.sizeBytes > MAX_FILE_BYTES
        || typeof record.mimeType !== 'string' || !MEDIA_TYPE.test(record.mimeType)
        || uploadId !== `${record.sha256}-${record.sizeBytes}` || !validGeneration(record.generation)) {
        throw new BackupError('data-loss', 'The backup catalog entry is invalid.');
    }
    const { sha256, sizeBytes, mimeType } = record;
    return { uploadId, displayName: safeOriginalName(record.displayName, sha256, mimeType), mimeType, sizeBytes, sha256 };
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
        if (!validGeneration(generation) || Number(size) !== record.sizeBytes || contentType !== record.mimeType
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
    if (!validGeneration(record.generation)) {
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
                const nextOwner = checkAllocation(ownerUsage, ownerLimit, MAX_OWNER_FILES, request.sizeBytes);
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

        async list(auth) {
            const owner = authorize(auth, pilotUsers);
            await countRequest(owner);
            const { docs } = await db.collection(`${ownerPath(owner)}/originals`)
                .where('state', '==', 'VERIFIED').limit(MAX_OWNER_FILES + 1).get();
            if (docs.length > MAX_OWNER_FILES) throw new BackupError('data-loss', 'The backup catalog exceeds its limit.');
            const items = docs.map(snapshot => {
                const record = snapshot.data();
                const verifiedAtMs = record.verifiedAt instanceof Date
                    ? record.verifiedAt.getTime() : record.verifiedAt?.toMillis?.();
                if (!Number.isSafeInteger(verifiedAtMs) || verifiedAtMs < 0) {
                    throw new BackupError('data-loss', 'The backup catalog timestamp is invalid.');
                }
                return { ...catalogOriginal(snapshot.id, record), verifiedAtMs };
            });
            items.sort((left, right) => right.verifiedAtMs - left.verifiedAtMs || left.uploadId.localeCompare(right.uploadId));
            return { items };
        },

        async download(auth, input) {
            const owner = authorize(auth, pilotUsers);
            const uploadId = input?.uploadId;
            if (typeof uploadId !== 'string' || !UPLOAD_ID.test(uploadId)) {
                throw new BackupError('invalid-argument', 'A valid backup ID is required.');
            }
            await countRequest(owner);
            const record = (await objectRecord(owner, uploadId).get()).data();
            if (record?.state !== 'VERIFIED') throw new BackupError('not-found', 'The verified original was not found.');
            const original = catalogOriginal(uploadId, record);
            const bucket = await getBucket();
            const generation = record.generation;
            const file = bucket.file(objectKey(owner, uploadId), { generation });
            try {
                const [metadata] = await file.getMetadata();
                if (metadata.generation === generation && Number(metadata.size) === record.sizeBytes
                    && metadata.contentType === record.mimeType && !metadata.contentEncoding) {
                    const [downloadUrl] = await file.getSignedUrl({
                        version: 'v4', action: 'read', expires: Date.now() + SIGNED_DOWNLOAD_MS,
                        queryParams: { generation },
                    });
                    return { ...original, generation, downloadUrl };
                }
            } catch (error) {
                if (error.code !== 404) throw error;
            }
            throw new BackupError('data-loss', 'The verified original is unavailable.');
        },
    };
}
