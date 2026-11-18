import { initializeApp } from 'firebase-admin/app';
import { FieldPath, getFirestore } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { onMessagePublished } from 'firebase-functions/v2/pubsub';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { authorize, BackupError, createBackupService, PILOT_MAX_OWNER_FILES, PRODUCTION_MAX_OWNER_FILES,
    productionBackupPlan, PRODUCTION_BACKUP_PREFIXES,
    PRODUCTION_BACKUP_PROTOCOL_VERSION } from './backup.js';
import { pilotSettings, requirePrivateBucket } from './pilot.js';
import { billingSettings, createBillingService, PILOT_ACCOUNT_QUOTA_BYTES, PRODUCTION_PROJECT,
    productionBillingSettings } from './cloud-billing.js';
import { createPlaySubscriptions } from './play-subscriptions.js';
import { PRODUCTION_CLOUD_MAX_BYTES } from './monetization-entitlements.js';
import { createSecretCloud } from './secret-cloud.js';
import { createResumableBackupService } from './resumable-backup.js';

const app = initializeApp();
const project = app.options.projectId ?? process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT;
const production = project === PRODUCTION_PROJECT;
const options = {
    region: 'asia-northeast3',
    serviceAccount: production ? 'gallipick-billing@gallipick-production.iam.gserviceaccount.com'
        : 'gallipick-backup@gallipick-dev.iam.gserviceaccount.com',
    maxInstances: 2,
    concurrency: 4,
    timeoutSeconds: 120,
};
const topic = production ? 'gallipick-play-rtdn-production' : 'gallipick-play-rtdn-test';
const PRODUCTION_BACKUP_BUCKET = 'gallipick-production.firebasestorage.app';
const RESUMABLE_PROTOCOL_VERSION = 2;

async function checkedBucket(bucketName, allowLegacyRegional = false) {
    const bucket = getStorage(app).bucket(bucketName);
    const [metadata] = await bucket.getMetadata();
    requirePrivateBucket(metadata, { allowLegacyRegional });
    return bucket;
}

function services(privateVault = false) {
    const db = getFirestore(app);
    if (production) {
        const settings = productionBillingSettings(project, process.env);
        const billing = createBillingService({ db, settings, play: createPlaySubscriptions() });
        if (!settings.cloudEnabled) return { billing };

        const uploadsPaused = process.env.GALLIPICK_PRODUCTION_CLOUD_UPLOADS_PAUSED !== 'false';
        const getBucket = () => checkedBucket(PRODUCTION_BACKUP_BUCKET, true);
        const backup = createBackupService({ db, getBucket, uploadQuota: billing.uploadQuota,
            ownerLimit: PRODUCTION_CLOUD_MAX_BYTES, uploadsPaused, privateVault,
            production: { namespace: settings.backupNamespace, maxOriginalBytes: settings.maxOriginalBytes,
                authorizeOwner: billing.authorizeBackup, catalogDocumentId: FieldPath.documentId() },
        });
        const scope = settings.backupNamespace;
        const objectPrefix = PRODUCTION_BACKUP_PREFIXES[scope];
        return scopedBackup({ db, backup, billing, scope, objectPrefix, privateVault,
            authorizeOwner: billing.authorizeBackup, ownerLimit: PRODUCTION_CLOUD_MAX_BYTES,
            maxOwnerFiles: PRODUCTION_MAX_OWNER_FILES, getBucket, uploadsPaused,
            rollout: { objectPrefix, maxOriginalBytes: settings.maxOriginalBytes, uploadsPaused, getBucket },
        });
    }
    const settings = pilotSettings(project, process.env);
    const billing = createBillingService({ db, pilotUsers: settings.users, settings: billingSettings(project, process.env),
        play: createPlaySubscriptions() });
    const getBucket = () => checkedBucket(settings.bucketName);
    const backup = createBackupService({ db, pilotUsers: settings.users, uploadQuota: billing.uploadQuota,
        uploadsPaused: settings.uploadsPaused, privateVault, getBucket });
    return scopedBackup({ db, backup, billing, scope: 'gallipickBackup', objectPrefix: 'cloud02/users', privateVault,
        authorizeOwner: auth => authorize(auth, settings.users), ownerLimit: PILOT_ACCOUNT_QUOTA_BYTES,
        maxOwnerFiles: PILOT_MAX_OWNER_FILES, getBucket, uploadsPaused: settings.uploadsPaused,
        globalAllocation: { path: 'gallipickBackupControl/pilot', byteLimit: 2 * PILOT_ACCOUNT_QUOTA_BYTES, fileLimit: 500 },
    });
}

function scopedBackup({ db, backup, billing, scope, objectPrefix, privateVault, authorizeOwner, ownerLimit,
    maxOwnerFiles, getBucket, uploadsPaused, rollout, globalAllocation }) {
    const resumablePaused = uploadsPaused || process.env.GALLIPICK_RESUMABLE_UPLOADS_PAUSED !== 'false';
    const resumable = process.env.GALLIPICK_RESUMABLE_ENABLED === 'true' ? createResumableBackupService({
        db, getBucket, authorizeOwner, objectPrefix, privateVault, ownerLimit, maxOwnerFiles, globalAllocation,
        uploadQuota: billing.uploadQuota, allocationCollection: `${scope}Accounts`,
        catalogCollection: `${scope}${privateVault ? 'Vault' : ''}ResumableAccounts`,
        catalogDocumentId: FieldPath.documentId(), uploadsPaused: resumablePaused,
    }) : undefined;
    const vault = privateVault ? createSecretCloud({ db, authorizeOwner, backup, resumable,
        credentialCollection: `${scope}VaultCredentials` }) : undefined;
    return { backup, billing, vault, resumable, resumablePaused, uploadsPaused, rollout, getBucket };
}

async function billingPlan(plan, data, configured, auth) {
    const { resumable, resumablePaused, rollout, uploadsPaused, getBucket } = configured;
    const protocolVersion = data?.cloudProtocolVersion;
    let available = production ? productionBackupPlan(plan, protocolVersion, rollout)
        : (uploadsPaused ? { ...plan, uploadAllowed: false } : plan);
    let writableBucket = true;
    if ((available.cloudCheckoutAllowed || (resumable && !resumablePaused)) && getBucket) {
        try {
            await getBucket();
        } catch {
            writableBucket = false;
            if (production) {
                available = productionBackupPlan(plan, protocolVersion, { ...rollout, uploadsPaused: true });
            }
        }
    }
    if (!resumable || data?.acceptsResumableTransfers !== true || (production && !available.backupAvailable)) {
        return available;
    }
    const { verifiedMediaCount, ...included } = available;
    const modernCount = Number.isSafeInteger(verifiedMediaCount) && data?.includeVerifiedCount === true
        ? await resumable.countVerifiedMedia(auth) : null;
    const total = modernCount === null ? null : verifiedMediaCount + modernCount;
    return { ...included,
        ...(total !== null && total < PRODUCTION_MAX_OWNER_FILES ? { verifiedMediaCount: total } : {}),
        supportsResumableTransfers: true,
        resumableUploadAllowed: !resumablePaused && writableBucket && included.quotaBytes > 0
            && included.usedBytes <= included.quotaBytes };
}

function endpoint(operation, billingRequest = false) {
    const callOptions = production && operation === 'finish' ? { ...options, timeoutSeconds: 540 } : options;
    return onCall(callOptions, async request => {
        try {
            if (!request.auth) throw new BackupError('unauthenticated', 'Sign in to start a backup.');
            const configured = services();
            const { backup, billing, resumable } = configured;
            const protocolVersion = request.data?.cloudProtocolVersion;
            if (billingRequest) {
                if (!production) await backup.countBillingRequest(request.auth);
                return await billingPlan(await billing[operation](request.auth, request.data), request.data, configured, request.auth);
            }
            const protocol = protocolVersion === RESUMABLE_PROTOCOL_VERSION ? resumable
                : (!production || protocolVersion === PRODUCTION_BACKUP_PROTOCOL_VERSION) ? backup : undefined;
            if (typeof protocol?.[operation] !== 'function') {
                throw new BackupError('failed-precondition', 'Cloud backup is unavailable.');
            }
            return await protocol[operation](request.auth, request.data);
        } catch (error) {
            if (error instanceof BackupError) throw new HttpsError(error.code, error.message);
            // Provider errors can include request details and signed capabilities.
            throw new HttpsError('unavailable', 'Backup is temporarily unavailable.');
        }
    });
}

export const beginBackup = endpoint('begin');
export const finishBackup = endpoint('finish');
export const listBackups = endpoint('list');
export const getBackupDownload = endpoint('download');
export const getBackupPart = endpoint('getPart');
export const getBackupStatus = endpoint('status');
export const getCloudPlan = endpoint('getPlan', true);
export const verifyCloudSubscription = endpoint('verify', true);

export const secretCloud = onCall({ ...options, timeoutSeconds: 540 }, async request => {
    try {
        if (!request.auth) throw new BackupError('unauthenticated', 'Sign in to use secret cloud.');
        const version = request.data?.cloudProtocolVersion;
        if (production && version !== PRODUCTION_BACKUP_PROTOCOL_VERSION && version !== RESUMABLE_PROTOCOL_VERSION) {
            throw new BackupError('failed-precondition', 'Secret cloud is unavailable.');
        }
        const { vault } = services(true);
        if (!vault) throw new BackupError('failed-precondition', 'Secret cloud is unavailable.');
        return await vault.handle(request.auth, request.data);
    } catch (error) {
        if (error instanceof BackupError) throw new HttpsError(error.code, error.message);
        throw new HttpsError('unavailable', 'Secret cloud is temporarily unavailable.');
    }
});

export const processResumableBackups = onSchedule({
    ...options, schedule: 'every 2 minutes', timeoutSeconds: 170, memory: '512MiB', maxInstances: 1, concurrency: 1,
}, async () => {
    if (process.env.GALLIPICK_RESUMABLE_ENABLED !== 'true') return;
    for (const privateVault of [false, true]) {
        const result = await services(privateVault).resumable?.processPending({ timeBudgetMs: 65_000 });
        if (result?.failures) console.warn('A resumable backup worker pass will retry.', { failures: result.failures });
    }
});

export const refreshCloudSubscription = onMessagePublished({ ...options, topic, retry: true },
    async event => {
        let message;
        try { message = event.data.message.json; } catch { return; }
        try {
            await services().billing.reconcileNotification(message);
        } catch (error) {
            if (error instanceof BackupError && !['unavailable', 'aborted'].includes(error.code)) return;
            throw new Error('The Play subscription could not be refreshed.');
        }
    });
