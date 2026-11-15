import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { BackupError, createBackupService } from './backup.js';
import { pilotSettings, requirePrivateBucket } from './pilot.js';

const app = initializeApp();
const options = {
    region: 'asia-northeast3',
    serviceAccount: 'gallipick-backup@gallipick-dev.iam.gserviceaccount.com',
    maxInstances: 2,
    concurrency: 4,
    timeoutSeconds: 120,
};

function backupEndpoint(operation) {
    return onCall(options, async request => {
        try {
            if (!request.auth) throw new BackupError('unauthenticated', 'Sign in to start a backup.');
            const settings = pilotSettings(app.options.projectId ?? process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT,
                process.env);
            const service = createBackupService({ db: getFirestore(app), pilotUsers: settings.users, getBucket: async () => {
                const bucket = getStorage(app).bucket(settings.bucketName);
                const [metadata] = await bucket.getMetadata();
                requirePrivateBucket(metadata);
                return bucket;
            } });
            return await service[operation](request.auth, request.data);
        } catch (error) {
            if (error instanceof BackupError) throw new HttpsError(error.code, error.message);
            // Provider errors can include request details and signed capabilities.
            throw new HttpsError('unavailable', 'Backup is temporarily unavailable.');
        }
    });
}

export const beginBackup = backupEndpoint('begin');
export const finishBackup = backupEndpoint('finish');
