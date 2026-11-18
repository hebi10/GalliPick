import { BackupError } from './backup.js';

const sourceLimit = 32;
const streamTimeoutMs = 75_000;

export function storageKeys(base, record) {
    const stage = `${base}/staging/${record.uploadId}/${record.transferId}`;
    return {
        part: index => `${stage}/parts/${index}`,
        intermediate: (level, index) => `${stage}/compose/${level}-${index}`,
        final: `${base}/files/${record.uploadId}`,
    };
}

export function partRange(record, index) {
    if (!Number.isSafeInteger(index) || index < 0 || index >= record.partCount) {
        throw new BackupError('invalid-argument', 'The upload part is outside this reservation.');
    }
    const offsetBytes = index * record.partSizeBytes;
    return { offsetBytes, sizeBytes: Math.min(record.partSizeBytes, record.sizeBytes - offsetBytes) };
}

export async function objectMetadata(bucket, name, generation) {
    try {
        const [metadata] = await bucket.file(name, generation === undefined ? {} : { generation }).getMetadata();
        return metadata;
    } catch (error) {
        if (Number(error.code) !== 404) throw error;
        return null;
    }
}

export function verifiedMetadata(metadata, { sizeBytes, mimeType, generation }) {
    if (typeof metadata?.generation !== 'string' || !/^[1-9][0-9]*$/.test(metadata.generation)
        || !Number.isSafeInteger(Number(metadata.generation)) || Number(metadata.size) !== sizeBytes
        || metadata.contentType !== mimeType || metadata.contentEncoding || Object.keys(metadata.metadata ?? {}).length > 0
        || (generation !== undefined && metadata.generation !== generation)) {
        throw new BackupError('data-loss', 'The stored object does not match the backup reservation.');
    }
    return metadata.generation;
}

export async function addVerifiedPart(bucket, name, expected, digest, remainingMs = streamTimeoutMs) {
    const metadata = await objectMetadata(bucket, name);
    if (metadata === null) return null;
    const generation = verifiedMetadata(metadata, expected);
    const stream = bucket.file(name, { generation }).createReadStream({ validation: 'crc32c', decompress: false });
    const timeout = setTimeout(() => stream.destroy(new Error('Storage part verification timed out.')),
        Math.max(1, Math.min(remainingMs, streamTimeoutMs)));
    timeout.unref();
    try {
        let bytes = 0;
        for await (const buffer of stream) {
            bytes += buffer.length;
            if (bytes > expected.sizeBytes) throw new BackupError('data-loss', 'An uploaded part has an invalid length.');
            digest.update(buffer);
        }
        if (bytes !== expected.sizeBytes) throw new BackupError('data-loss', 'An uploaded part has an invalid length.');
        return generation;
    } finally {
        clearTimeout(timeout);
        stream.destroy();
    }
}

export function intermediateKeys(partCount, keys) {
    const result = [];
    let level = 0;
    while (partCount > sourceLimit) {
        partCount = Math.ceil(partCount / sourceLimit);
        for (let index = 0; index < partCount; index += 1) result.push(keys.intermediate(level, index));
        level += 1;
    }
    return result;
}

async function composeNode(bucket, name, sources, mimeType) {
    const sizeBytes = sources.reduce((total, source) => total + source.sizeBytes, 0);
    let metadata = await objectMetadata(bucket, name);
    if (metadata === null) {
        const destination = bucket.file(name);
        destination.metadata.contentType = mimeType;
        const sourceFiles = sources.map(source => bucket.file(source.name, { generation: source.generation }));
        try {
            await bucket.combine(sourceFiles, destination, { ifGenerationMatch: 0 });
        } catch (error) {
            if (Number(error.code) !== 412) throw error;
        }
        metadata = await objectMetadata(bucket, name);
    }
    const generation = verifiedMetadata(metadata, { sizeBytes, mimeType });
    return { name, generation, sizeBytes };
}

export async function composeVerifiedOriginal(bucket, keys, record, shouldContinue) {
    let sources = record.generations.map((generation, index) => ({ name: keys.part(index), generation,
        sizeBytes: partRange(record, index).sizeBytes }));
    let level = 0;
    while (sources.length > sourceLimit) {
        const combined = [];
        for (let offset = 0; offset < sources.length; offset += sourceLimit) {
            if (!shouldContinue()) return null;
            combined.push(await composeNode(bucket, keys.intermediate(level, offset / sourceLimit),
                sources.slice(offset, offset + sourceLimit), record.mimeType));
        }
        sources = combined;
        level += 1;
    }
    if (!shouldContinue()) return null;
    return composeNode(bucket, keys.final, sources, record.mimeType);
}

export async function removeStagedObject(bucket, name) {
    const metadata = await objectMetadata(bucket, name);
    if (metadata === null) return;
    if (typeof metadata.generation !== 'string' || !/^[1-9][0-9]*$/.test(metadata.generation)) {
        throw new BackupError('data-loss', 'A staging object generation is invalid.');
    }
    try {
        await bucket.file(name, { generation: metadata.generation }).delete({ ifGenerationMatch: metadata.generation });
    } catch (error) {
        if (Number(error.code) !== 404) throw error;
    }
}

export async function retireStagedPart(bucket, name) {
    const metadata = await objectMetadata(bucket, name);
    if (metadata && Number(metadata.size) === 0 && metadata.contentType === 'application/octet-stream') return;
    if (metadata && (typeof metadata.generation !== 'string' || !/^[1-9][0-9]*$/.test(metadata.generation))) {
        throw new BackupError('data-loss', 'A staging object generation is invalid.');
    }
    // Replace the media with a zero-byte fence without briefly reopening the key to an in-flight create-only grant.
    await bucket.file(name).save(Buffer.alloc(0), { resumable: false,
        metadata: { contentType: 'application/octet-stream' },
        preconditionOpts: { ifGenerationMatch: metadata?.generation ?? 0 } });
}
