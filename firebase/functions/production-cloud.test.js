import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { Readable } from 'node:stream';
import test from 'node:test';
import { createBackupService, productionBackupPlan, readOriginal } from './backup.js';
import { createBillingService, productionBillingSettings, PRODUCTION_PROJECT } from './cloud-billing.js';
import { requirePrivateBucket } from './pilot.js';

const mib = 1024 ** 2;
const bucketMetadata = {
    location: 'ASIA-NORTHEAST3',
    storageClass: 'REGIONAL',
    iamConfiguration: {
        publicAccessPrevention: 'enforced',
        uniformBucketLevelAccess: { enabled: true },
    },
};
const googleUser = uid => ({ uid, token: { firebase: { sign_in_provider: 'google.com' } } });
const isCode = code => error => error.code === code;

function testFirestore() {
    const docs = new Map();
    const snapshot = (path, id) => ({ id, data: () => docs.get(path) });
    return {
        doc: path => ({ path, get: async () => snapshot(path) }),
        async runTransaction(execute) {
            const updates = new Map();
            const result = await execute({
                get: async ({ path }) => snapshot(path),
                set: ({ path }, value) => updates.set(path, value),
            });
            for (const [path, value] of updates) docs.set(path, value);
            return result;
        },
        collection: collection => ({
            where: (field, operator, value) => ({
                orderBy: () => ({
                    limit: limit => ({
                        async get() {
                            assert.equal(operator, '==');
                            const prefix = `${collection}/`;
                            return { docs: [...docs].filter(([path, record]) => path.startsWith(prefix) &&
                                !path.slice(prefix.length).includes('/') && record[field] === value)
                                .slice(0, limit).map(([path]) => snapshot(path, path.slice(prefix.length))) };
                        },
                    }),
                }),
            }),
        }),
    };
}

test('validated paid storage links exact quota, original verification and owner-only recovery after lapse', async () => {
    const chunk = Buffer.alloc(mib, 0x5a);
    const source = () => Readable.from((function* () {
        for (let part = 0; part < 120; part++) yield chunk;
    })());
    const sizeBytes = 120 * mib;
    const contentType = 'video/mp4';
    const generation = '1740000000000000';
    const proof = await readOriginal(source(), sizeBytes);
    const uid = 'cloud-owner';
    const hashUid = createHash('sha256').update(uid).digest('hex');
    const owner = googleUser(uid);
    const other = googleUser('another-owner');
    const db = testFirestore();
    const signed = [];
    let objectStored = false;
    const bucket = {
        name: 'gallipick-production.firebasestorage.app',
        file(key, fileOptions = {}) {
            assert.match(key, new RegExp(`^cloud02/license-test/users/${hashUid}/`));
            return {
                async getSignedUrl(options) {
                    signed.push(options);
                    return [`https://storage.googleapis.com/${bucket.name}/${key}`];
                },
                async getMetadata() {
                    assert(objectStored);
                    return [{ generation, size: String(sizeBytes), contentType }];
                },
                createReadStream() {
                    assert.equal(fileOptions.generation, generation);
                    return source();
                },
            };
        },
    };
    const environment = {
        GALLIPICK_PRODUCTION_AD_FREE_ENABLED: 'true',
        GALLIPICK_PRODUCTION_BILLING_MODE: 'license-test',
        GALLIPICK_PRODUCTION_BILLING_TEST_UIDS: 'cloud-owner,another-owner',
        GALLIPICK_PRODUCTION_CLOUD_ENABLED: 'true',
        GALLIPICK_PRODUCTION_CLOUD_MAX_ORIGINAL_BYTES: String(128 * mib),
    };
    const settings = productionBillingSettings(PRODUCTION_PROJECT, environment);
    let now = 1_800_000_000_000;
    const expires = now + 60_000;
    const play = {
        get: async () => ({
            testPurchase: {},
            externalAccountIdentifiers: { obfuscatedExternalAccountId: hashUid },
            subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
            acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
            lineItems: [{ productId: 'gallipick_cloud_10gb', offerDetails: { basePlanId: 'monthly' },
                autoRenewingPlan: { autoRenewEnabled: true }, expiryTime: new Date(expires).toISOString() }],
        }),
    };
    const billing = createBillingService({ db, play, settings, now: () => now });
    const backups = createBackupService({
        db, getBucket: async () => bucket, ownerLimit: 100_000_000_000, uploadQuota: billing.uploadQuota,
        production: { namespace: settings.backupNamespace, maxOriginalBytes: settings.maxOriginalBytes,
            authorizeOwner: billing.authorizeBackup, catalogDocumentId: '__name__' },
    });
    const original = { ...proof, mimeType: contentType, displayName: 'synthetic-video.mp4' };
    await assert.rejects(backups.begin(owner, original), isCode('resource-exhausted'));
    assert.equal(signed.length, 0);
    const entitlement = await billing.verify(owner, { purchaseToken: 'synthetic-cloud-test-purchase' });
    assert.equal(entitlement.quotaBytes, 10_000_000_000);
    assert.equal(entitlement.activeProductId, 'gallipick_cloud_10gb');
    const { uploadId, headers } = await backups.begin(owner, original);
    assert.equal(headers['x-goog-content-length-range'], `${sizeBytes},${sizeBytes}`);
    objectStored = true;
    assert.deepEqual(await backups.finish(owner, { uploadId }), { state: 'VERIFIED' });
    assert.equal((await billing.getPlan(owner)).usedBytes, sizeBytes);

    const rollback = createBillingService({ db, play, now: () => now, settings:
        productionBillingSettings(PRODUCTION_PROJECT, { ...environment, GALLIPICK_PRODUCTION_CLOUD_ENABLED: 'false' }),
    });
    const rollbackPlan = productionBackupPlan(await rollback.verify(owner, {
        purchaseToken: 'synthetic-cloud-test-purchase',
    }));
    assert.equal(rollbackPlan.activeProductId, entitlement.activeProductId);
    assert.equal(rollbackPlan.backupAvailable, false);
    assert.equal(rollbackPlan.cloudCheckoutAllowed, false);
    assert.equal(rollbackPlan.quotaBytes, 0);
    assert.deepEqual(rollbackPlan.products.filter(product => product.quotaBytes > 0).map(({ productId, basePlanId }) =>
        [productId, basePlanId]), [['gallipick_cloud_10gb', 'monthly']]);

    now = expires;
    const expired = await billing.getPlan(owner);
    assert.equal(expired.quotaBytes, 0);
    assert.equal(expired.uploadAllowed, false);
    assert.equal(expired.backupAvailable, true);
    await assert.rejects(backups.begin(owner, original), isCode('resource-exhausted'));
    assert.equal((await backups.list(owner)).items[0].sha256, proof.sha256);
    assert.deepEqual(await backups.list(other), { items: [] });
    await assert.rejects(backups.download(other, { uploadId }), isCode('not-found'));
    const download = await backups.download(owner, { uploadId });
    assert.equal(download.generation, generation);
    assert.equal(download.sizeBytes, sizeBytes);
    assert.deepEqual(signed.map(option => option.action), ['write', 'read']);
});

test('the private Seoul production bucket may use the documented legacy Regional class', () => {
    assert.throws(() => requirePrivateBucket(bucketMetadata), isCode('failed-precondition'));
    assert.doesNotThrow(() => requirePrivateBucket(bucketMetadata, { allowLegacyRegional: true }));
    for (const metadata of [
        { ...bucketMetadata, location: 'ASIA' },
        { ...bucketMetadata, storageClass: 'NEARLINE' },
        { ...bucketMetadata, iamConfiguration: { ...bucketMetadata.iamConfiguration, publicAccessPrevention: 'inherited' } },
        { ...bucketMetadata, lifecycle: { rule: [{ action: { type: 'Delete' } }] } },
    ]) {
        assert.throws(() => requirePrivateBucket(metadata, { allowLegacyRegional: true }), isCode('failed-precondition'));
    }
});
