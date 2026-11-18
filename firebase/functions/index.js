import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { onMessagePublished } from 'firebase-functions/v2/pubsub';
import { BackupError, createBackupService } from './backup.js';
import { pilotSettings, requirePrivateBucket } from './pilot.js';
import { billingSettings, createBillingService, PRODUCTION_PROJECT, productionBillingSettings } from './cloud-billing.js';
import { createPlaySubscriptions } from './play-subscriptions.js';

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

function services() {
    const db = getFirestore(app);
    if (production) {
        const settings = productionBillingSettings(project, process.env);
        return { billing: createBillingService({ db, settings, play: createPlaySubscriptions() }), uploadsPaused: true };
    }
    const settings = pilotSettings(project, process.env);
    const billing = createBillingService({ db, pilotUsers: settings.users, settings: billingSettings(project, process.env),
        play: createPlaySubscriptions() });
    const backup = createBackupService({ db, pilotUsers: settings.users, uploadQuota: billing.uploadQuota,
        uploadsPaused: settings.uploadsPaused, getBucket: async () => {
            const bucket = getStorage(app).bucket(settings.bucketName);
            const [metadata] = await bucket.getMetadata();
            requirePrivateBucket(metadata);
            return bucket;
        } });
    return { backup, billing, uploadsPaused: settings.uploadsPaused };
}

function endpoint(operation, billingRequest = false) {
    return onCall(options, async request => {
        try {
            if (!request.auth) throw new BackupError('unauthenticated', 'Sign in to start a backup.');
            if (production && !billingRequest) {
                throw new BackupError('failed-precondition', 'Production Cloud backup is unavailable.');
            }
            const { backup, billing, uploadsPaused } = services();
            if (billingRequest && !production) await backup.countBillingRequest(request.auth);
            const result = await (billingRequest ? billing : backup)[operation](request.auth, request.data);
            return billingRequest && uploadsPaused ? { ...result, uploadAllowed: false } : result;
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
export const getCloudPlan = endpoint('getPlan', true);
export const verifyCloudSubscription = endpoint('verify', true);

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
