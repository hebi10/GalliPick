import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { initializeApp } from 'firebase-admin/app';
import { getStorage } from 'firebase-admin/storage';
import { readOriginal, signOriginalUpload } from './backup.js';
import { DEV_BACKUP_BUCKET, requirePrivateBucket } from './pilot.js';

async function verifyPilotBucket() {
    const app = initializeApp({ projectId: 'gallipick-dev' });
    const bucket = getStorage(app).bucket(DEV_BACKUP_BUCKET);
    const [bucketMetadata] = await bucket.getMetadata();
    requirePrivateBucket(bucketMetadata);

    const fixture = Buffer.from('GalliPick synthetic upload guard probe');
    const key = `cloud02-preflight/${randomUUID()}`;
    const file = bucket.file(key);
    const { uploadUrl, headers } = await signOriginalUpload(file, { sizeBytes: fixture.length, mimeType: 'image/jpeg' });
    const put = async (bytes, uploadHeaders = headers) => {
        const response = await fetch(uploadUrl, { method: 'PUT', headers: uploadHeaders, body: bytes, redirect: 'error' });
        await response.body?.cancel();
        return response.status;
    };

    // Keep the temporary path visible to the project operator even if a later check fails.
    console.log(`Synthetic fixture path: gs://${DEV_BACKUP_BUCKET}/${key}`);
    const incompleteHeaders = { ...headers };
    delete incompleteHeaders['x-goog-content-length-range'];
    assert.equal(await put(fixture, incompleteHeaders), 403, 'Changing signed headers must be rejected');
    assert.deepEqual(await file.exists(), [false]);
    assert.equal(await put(fixture.subarray(1)), 400, 'Short PUT must be rejected');
    assert.deepEqual(await file.exists(), [false]);
    assert.equal(await put(Buffer.concat([fixture, Buffer.from([0])])), 400, 'Oversized PUT must be rejected');
    assert.deepEqual(await file.exists(), [false]);
    assert.equal(await put(fixture), 200, 'Exact-length PUT should create the fixture');

    const [{ generation, size }] = await file.getMetadata();
    assert.equal(Number(size), fixture.length);
    assert(Number.isSafeInteger(Number(generation)));
    assert.equal(await put(Buffer.alloc(fixture.length)), 412, 'Existing generation must block overwrites');
    const [after] = await file.getMetadata();
    assert.equal(after.generation, generation);
    assert.deepEqual(await readOriginal(bucket.file(key, { generation }).createReadStream({ decompress: false }), fixture.length),
        await readOriginal([fixture], fixture.length));

    const anonymousUrl = new URL(uploadUrl);
    anonymousUrl.search = '';
    const anonymous = await fetch(anonymousUrl, { redirect: 'error' });
    await anonymous.body?.cancel();
    assert.equal(anonymous.status, 403, 'Anonymous object reads must be denied');
    console.log('PASS: exact upload size, create-only generation and private object access');
}

verifyPilotBucket().catch(error => {
    // Network and signer errors can contain signed request URLs.
    console.error(`GalliPick synthetic upload verification failed (${error.name}).`);
    if (error instanceof assert.AssertionError) console.error(error.message.split('\n')[0]);
    process.exitCode = 1;
});
