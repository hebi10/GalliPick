import { createHash } from 'node:crypto';

const MIB = 1024 * 1024;
export const MAX_FILE_BYTES = 64 * MIB;
export const PRODUCTION_MAX_FILE_BYTES = 256 * MIB;
export const PILOT_MAX_OWNER_FILES = 250;
export const PRODUCTION_MAX_OWNER_FILES = 20_000;
const PRODUCTION_CATALOG_PAGE_SIZE = 100;
const PRODUCTION_CATALOG_REQUESTS_PER_HOUR = 480;
const SIGNED_UPLOAD_MS = 5 * 60 * 1000;
const PRODUCTION_SIGNED_UPLOAD_MS = 15 * 60 * 1000;
const SIGNED_DOWNLOAD_MS = 2 * 60 * 1000;
const HOUR_MS = 60 * 60 * 1000;
const SHA256 = /^[a-f0-9]{64}$/;
const MEDIA_TYPE = /^(image|video)\/[a-z0-9][a-z0-9.+-]{0,62}$/;
const UPLOAD_ID = /^[a-f0-9]{64}-[1-9][0-9]{0,8}$/;
const VERIFIED = { state: 'VERIFIED' };
const ORIGINAL_EXTENSIONS = new Map([['jpeg', 'jpg'], ['quicktime', 'mov'], ['svg+xml', 'svg']]);

export const PRODUCTION_BACKUP_PREFIXES = Object.freeze({
    gallipickProductionLicenseBackup: 'cloud02/license-test/users',
    gallipickProductionBackup: 'cloud02/production/users',
});
export const PRODUCTION_BACKUP_PROTOCOL_VERSION = 1;

export function productionBackupPlan(plan, protocolVersion, rollout) {
    if (!rollout || protocolVersion !== PRODUCTION_BACKUP_PROTOCOL_VERSION) {
        return { ...plan, backupAvailable: false, cloudCheckoutAllowed: false, uploadAllowed: false,
            products: plan.products.filter(product =>
                product.quotaBytes === 0 || product.productId === plan.activeProductId),
        };
    }
    return { ...plan, backupAvailable: true, backupObjectPrefix: rollout.objectPrefix,
        maxOriginalBytes: rollout.maxOriginalBytes, cloudCheckoutAllowed: !rollout.uploadsPaused,
        uploadAllowed: plan.uploadAllowed && !rollout.uploadsPaused,
    };
}

export class BackupError extends Error {
    constructor(code, message) {
        super(message);
        this.code = code;
    }
}

export function authorize(auth, pilotUsers, allowAnyGoogleUser = false) {
    if (typeof auth?.uid !== 'string' || auth.uid.length === 0) {
        throw new BackupError('unauthenticated', 'Sign in to start a backup.');
    }
    if (auth.token?.firebase?.sign_in_provider !== 'google.com' || (!allowAnyGoogleUser && !pilotUsers?.has(auth.uid))) {
        throw new BackupError('permission-denied', 'This account is not enrolled in the backup pilot.');
    }
    return createHash('sha256').update(auth.uid).digest('hex');
}


const MAX_SOURCE_FOLDERS = 8;
const MAX_FOLDER_LENGTH = 160;
const MAX_PIXEL_DIMENSION = 100_000;

function normalizeSourceFolder(value) {
    if (value === undefined || value === null || value === '') return null;
    if (typeof value !== 'string' || value.length > MAX_FOLDER_LENGTH || value.startsWith('/')
        || value.includes('\\')) {
        throw new BackupError('invalid-argument', 'The source folder is invalid.');
    }
    const normalized = value.normalize('NFC').replace(/\/$/, '');
    const parts = normalized.split('/');
    if (parts.length < 1 || parts.length > 8 || parts.some(part => part.length === 0 || part.length > 60
        || part === '.' || part === '..' || /[\p{Cc}\p{Cf}]/u.test(part))) {
        throw new BackupError('invalid-argument', 'The source folder is invalid.');
    }
    return parts.join('/');
}

function storedSourceFolders(record) {
    if (record.sourceFolders === undefined) return [];
    if (!Array.isArray(record.sourceFolders) || record.sourceFolders.length > MAX_SOURCE_FOLDERS) {
        throw new BackupError('data-loss', 'The source folder catalog is invalid.');
    }
    const paths = record.sourceFolders;
    for (const path of paths) {
        try {
            if (normalizeSourceFolder(path) !== path) throw new Error('non-canonical folder');
        } catch {
            throw new BackupError('data-loss', 'The source folder catalog is invalid.');
        }
    }
    if (new Set(paths).size !== paths.length) {
        throw new BackupError('data-loss', 'The source folder catalog is invalid.');
    }
    return paths;
}

function metadataInteger(input, key, maximum = Number.MAX_SAFE_INTEGER) {
    if (input?.[key] === undefined) return undefined;
    const number = input[key];
    if (!Number.isSafeInteger(number) || number <= 0 || number > maximum) {
        throw new BackupError('invalid-argument', 'The media metadata is invalid.');
    }
    return number;
}

function verifiedMetadata(record) {
    const result = {};
    const fields = [['capturedAtMs', Number.MAX_SAFE_INTEGER], ['width', MAX_PIXEL_DIMENSION],
        ['height', MAX_PIXEL_DIMENSION], ['durationMs', Number.MAX_SAFE_INTEGER]];
    for (const [field, maximum] of fields) {
        try {
            const value = metadataInteger(record, field, maximum);
            if (value !== undefined) result[field] = value;
        } catch {
            throw new BackupError('data-loss', 'The media catalog metadata is invalid.');
        }
    }
    const folders = storedSourceFolders(record);
    if (folders.length > 0) result.sourceFolders = folders;
    return result;
}

export function originalInput(data, maxOriginalBytes) {
    const { sha256, sizeBytes, mimeType, displayName } = data ?? {};
    if (typeof sha256 !== 'string' || !SHA256.test(sha256) || !Number.isSafeInteger(sizeBytes)
        || sizeBytes <= 0 || sizeBytes > maxOriginalBytes || typeof mimeType !== 'string' || !MEDIA_TYPE.test(mimeType)) {
        const configuredLimit = maxOriginalBytes === MAX_FILE_BYTES ? '64 MiB' : `${maxOriginalBytes} bytes`;
        throw new BackupError('invalid-argument', `Choose a photo or video no larger than ${configuredLimit}.`);
    }
    const folder = normalizeSourceFolder(data?.sourceFolder);
    const metadata = {};
    for (const [field, limit] of [['capturedAtMs', Number.MAX_SAFE_INTEGER], ['width', MAX_PIXEL_DIMENSION],
        ['height', MAX_PIXEL_DIMENSION], ['durationMs', Number.MAX_SAFE_INTEGER]]) {
        const value = metadataInteger(data, field, limit);
        if (value !== undefined) metadata[field] = value;
    }
    return { sha256, sizeBytes, mimeType, displayName: safeOriginalName(displayName, sha256, mimeType),
        ...(folder ? { sourceFolders: [folder] } : {}), ...metadata };
}

function appendFolderMembership(transaction, recordRef, existing, request) {
    if (existing.sha256 !== request.sha256 || existing.sizeBytes !== request.sizeBytes ||
        existing.mimeType !== request.mimeType) {
        throw new BackupError('failed-precondition', 'The existing original does not match this request.');
    }
    const folder = request.sourceFolders?.[0];
    if (!folder) return existing;
    const folders = storedSourceFolders(existing);
    if (folders.includes(folder)) return existing;
    if (folders.length >= MAX_SOURCE_FOLDERS) {
        throw new BackupError('resource-exhausted', 'Too many source folders reference this original.');
    }
    const changed = { ...existing, sourceFolders: [...folders, folder] };
    transaction.set(recordRef, changed);
    return changed;
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

export function catalogOriginal(uploadId, record, protocolFileLimit) {
    if (typeof record.sha256 !== 'string' || !SHA256.test(record.sha256)
        || !Number.isSafeInteger(record.sizeBytes) || record.sizeBytes <= 0 || record.sizeBytes > protocolFileLimit
        || typeof record.mimeType !== 'string' || !MEDIA_TYPE.test(record.mimeType)
        || uploadId !== `${record.sha256}-${record.sizeBytes}` || !validGeneration(record.generation)) {
        throw new BackupError('data-loss', 'The backup catalog entry is invalid.');
    }
    const { sha256, sizeBytes, mimeType } = record;
    return { uploadId, displayName: safeOriginalName(record.displayName, sha256, mimeType),
        mimeType, sizeBytes, sha256, ...verifiedMetadata(record) };
}

export function readAllocation(current) {
    const { allocatedBytes, files } = current ?? { allocatedBytes: 0, files: 0 };
    if (!Number.isSafeInteger(allocatedBytes) || allocatedBytes < 0 || !Number.isSafeInteger(files) || files < 0) {
        throw new BackupError('failed-precondition', 'The backup allocation needs reconciliation.');
    }
    return { allocatedBytes, files };
}

export function checkAllocation(current, byteLimit, countLimit, requestedBytes) {
    const { allocatedBytes, files } = readAllocation(current);
    if (allocatedBytes + requestedBytes > byteLimit || files + 1 > countLimit) {
        throw new BackupError('resource-exhausted', 'The backup allocation is full.');
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

export async function signOriginalUpload(file, { sizeBytes, mimeType }, expiresInMs = SIGNED_UPLOAD_MS) {
    const signedHeaders = {
        'x-goog-content-length-range': `${sizeBytes},${sizeBytes}`,
        'x-goog-if-generation-match': '0',
    };
    const [uploadUrl] = await file.getSignedUrl({
        version: 'v4', action: 'write', expires: Date.now() + expiresInMs,
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

export function createRequestLimiter(db, collection, requestsPerHour) {
    return function countRequest(owner) {
        const ref = db.doc(`${collection}/${owner}`);
        const hour = Math.floor(Date.now() / HOUR_MS);
        return db.runTransaction(async transaction => {
            const previous = (await transaction.get(ref)).data();
            const requests = previous?.hour >= hour ? previous.requests : 0;
            if (!Number.isSafeInteger(requests) || requests < 0 || requests >= requestsPerHour) {
                throw new BackupError('resource-exhausted', 'The account backup request limit was reached.');
            }
            transaction.set(ref, { hour, requests: requests + 1 });
        });
    };
}

export function createBackupService({ db, getBucket, pilotUsers, ownerLimit = 1024 * MIB, pilotLimit = 2048 * MIB,
    requestsPerHour = 120, uploadQuota, uploadsPaused = false, production, privateVault = false }) {
    if (production && (!Object.hasOwn(PRODUCTION_BACKUP_PREFIXES, production.namespace)
        || typeof production.authorizeOwner !== 'function' || !production.catalogDocumentId
        || !Number.isSafeInteger(production.maxOriginalBytes) || production.maxOriginalBytes <= 0
        || production.maxOriginalBytes > PRODUCTION_MAX_FILE_BYTES
        || !Number.isSafeInteger(ownerLimit) || ownerLimit <= 0 || typeof uploadQuota !== 'function')) {
        throw new BackupError('failed-precondition', 'The production backup configuration is invalid.');
    }
    const namespace = production?.namespace ?? 'gallipickBackup';
    const maxOriginalBytes = production?.maxOriginalBytes ?? MAX_FILE_BYTES;
    const protocolFileLimit = production ? PRODUCTION_MAX_FILE_BYTES : MAX_FILE_BYTES;
    const maxOwnerFiles = production ? PRODUCTION_MAX_OWNER_FILES : PILOT_MAX_OWNER_FILES;
    const signedUploadMs = production ? PRODUCTION_SIGNED_UPLOAD_MS : SIGNED_UPLOAD_MS;
    const objectPrefix = production ? PRODUCTION_BACKUP_PREFIXES[namespace] : 'cloud02/users';
    const authorizeOwner = production?.authorizeOwner ?? (auth => authorize(auth, pilotUsers));
    const getUploadQuota = uploadQuota ?? (async (_transaction, _owner, limit) => limit);
    const pilotAllocation = production ? null : db.doc('gallipickBackupControl/pilot');
    const catalogNamespace = privateVault ? `${namespace}Vault` : namespace;
    const ownerPath = owner => `${catalogNamespace}Accounts/${owner}`;
    const allocationPath = owner => `${namespace}Accounts/${owner}`;
    const objectRecord = (owner, uploadId) => db.doc(`${ownerPath(owner)}/originals/${uploadId}`);
    const objectKey = (owner, uploadId) => `${objectPrefix}/${owner}/${privateVault ? 'secret/' : ''}${uploadId}`;
    const countRequest = createRequestLimiter(db, `${catalogNamespace}RequestLimits`, requestsPerHour);
    const countListRequest = production
        ? createRequestLimiter(db, `${catalogNamespace}CatalogRequestLimits`, PRODUCTION_CATALOG_REQUESTS_PER_HOUR)
        : countRequest;

    async function requireUploadQuota(transaction, owner, ownerAllocation) {
        const ownerUsage = (await transaction.get(ownerAllocation)).data();
        const quota = await getUploadQuota(transaction, owner, ownerLimit);
        if (!Number.isSafeInteger(quota) || quota <= 0 || quota > ownerLimit
            || (ownerUsage?.allocatedBytes ?? 0) > quota) {
            throw new BackupError('resource-exhausted', 'The account does not have available upload storage.');
        }
        if (production && readAllocation(ownerUsage).files > maxOwnerFiles) {
            throw new BackupError('failed-precondition', 'The backup allocation needs reconciliation.');
        }
        return { ownerUsage, quota };
    }

    return {
        async countBillingRequest(auth) { await countRequest(authorizeOwner(auth)); },

        async begin(auth, input) {
            const owner = authorizeOwner(auth);
            if (uploadsPaused) throw new BackupError('failed-precondition', 'New backup uploads are temporarily paused.');
            const request = originalInput(input, maxOriginalBytes);
            const uploadId = `${request.sha256}-${request.sizeBytes}`;
            const recordRef = objectRecord(owner, uploadId);
            const ownerAllocation = db.doc(allocationPath(owner));
            await countRequest(owner);
            const bucket = await getBucket();

            const record = await db.runTransaction(async transaction => {
                const existing = (await transaction.get(recordRef)).data();
                if (!production && existing?.state === 'VERIFIED') {
                    return appendFolderMembership(transaction, recordRef, existing, request);
                }
                const { ownerUsage, quota } = await requireUploadQuota(transaction, owner, ownerAllocation);
                if (existing) return appendFolderMembership(transaction, recordRef, existing, request);
                const nextOwner = checkAllocation(ownerUsage, quota, maxOwnerFiles, request.sizeBytes);
                if (pilotAllocation) {
                    const pilotUsage = (await transaction.get(pilotAllocation)).data();
                    const nextPilot = checkAllocation(pilotUsage, pilotLimit, 500, request.sizeBytes);
                    transaction.set(pilotAllocation, nextPilot);
                }
                const reservation = { ...request, state: 'RESERVED', createdAt: new Date() };
                transaction.set(ownerAllocation, nextOwner);
                transaction.set(recordRef, reservation);
                return reservation;
            });

            const key = objectKey(owner, uploadId);
            if (record.state === 'VERIFIED') return verifiedOriginal(bucket, key, record);
            if (record.state !== 'RESERVED' || record.mimeType !== request.mimeType) {
                throw new BackupError('failed-precondition', 'The backup reservation differs from the request.');
            }
            const upload = await signOriginalUpload(bucket.file(key), record, signedUploadMs);
            return { state: 'UPLOAD', uploadId, ...upload };
        },

        async finish(auth, input) {
            const owner = authorizeOwner(auth);
            const uploadId = input?.uploadId;
            if (typeof uploadId !== 'string' || !UPLOAD_ID.test(uploadId)) {
                throw new BackupError('invalid-argument', 'A valid backup reservation is required.');
            }
            await countRequest(owner);
            const recordRef = objectRecord(owner, uploadId);
            const ownerAllocation = db.doc(allocationPath(owner));
            const record = (await recordRef.get()).data();
            if (!record) throw new BackupError('not-found', 'The backup reservation was not found.');
            const key = objectKey(owner, uploadId);
            if (record.state === 'VERIFIED') return verifiedOriginal(await getBucket(), key, record);
            if (record.state !== 'RESERVED') throw new BackupError('failed-precondition', 'The backup reservation is unavailable.');

            if (production) await db.runTransaction(transaction => requireUploadQuota(transaction, owner, ownerAllocation));
            const bucket = await getBucket();

            const generation = await verifyOriginal(bucket, key, record);
            return db.runTransaction(async transaction => {
                const latest = (await transaction.get(recordRef)).data();
                if (latest?.state === 'VERIFIED' && latest.generation === generation) return VERIFIED;
                if (latest?.state !== 'RESERVED' || latest.sha256 !== record.sha256 || latest.sizeBytes !== record.sizeBytes
                    || latest.mimeType !== record.mimeType) {
                    throw new BackupError('aborted', 'The backup reservation changed while verifying.');
                }
                if (production) await requireUploadQuota(transaction, owner, ownerAllocation);
                transaction.set(recordRef, { ...latest, state: 'VERIFIED', generation, verifiedAt: new Date() });
                return VERIFIED;
            });
        },

        async list(auth, input) {
            const owner = authorizeOwner(auth);
            const cursor = input?.cursor;
            if (production && cursor !== undefined && (typeof cursor !== 'string' || !UPLOAD_ID.test(cursor))) {
                throw new BackupError('invalid-argument', 'The backup catalog cursor is invalid.');
            }
            await countListRequest(owner);
            let query = db.collection(`${ownerPath(owner)}/originals`).where('state', '==', 'VERIFIED');
            if (production) {
                query = query.orderBy(production.catalogDocumentId);
                if (cursor !== undefined) query = query.startAfter(cursor);
            }
            const pageSize = production ? PRODUCTION_CATALOG_PAGE_SIZE : maxOwnerFiles;
            const { docs } = await query.limit(pageSize + 1).get();
            if (!production && docs.length > pageSize) {
                throw new BackupError('data-loss', 'The backup catalog exceeds its limit.');
            }
            const page = docs.slice(0, pageSize);
            const items = page.map(snapshot => {
                const record = snapshot.data();
                const verifiedAtMs = record.verifiedAt instanceof Date
                    ? record.verifiedAt.getTime() : record.verifiedAt?.toMillis?.();
                if (!Number.isSafeInteger(verifiedAtMs) || verifiedAtMs < 0) {
                    throw new BackupError('data-loss', 'The backup catalog timestamp is invalid.');
                }
                return { ...catalogOriginal(snapshot.id, record, protocolFileLimit), verifiedAtMs };
            });
            if (production) return docs.length > pageSize ? { items, nextCursor: page.at(-1).id } : { items };
            items.sort((left, right) => right.verifiedAtMs - left.verifiedAtMs || left.uploadId.localeCompare(right.uploadId));
            return { items };
        },

        async download(auth, input) {
            const owner = authorizeOwner(auth);
            const uploadId = input?.uploadId;
            if (typeof uploadId !== 'string' || !UPLOAD_ID.test(uploadId)) {
                throw new BackupError('invalid-argument', 'A valid backup ID is required.');
            }
            await countRequest(owner);
            const record = (await objectRecord(owner, uploadId).get()).data();
            if (record?.state !== 'VERIFIED') throw new BackupError('not-found', 'The verified original was not found.');
            const original = catalogOriginal(uploadId, record, protocolFileLimit);
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
