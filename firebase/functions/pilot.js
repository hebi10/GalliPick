import { BackupError } from './backup.js';

export const DEV_BACKUP_BUCKET = 'gallipick-dev.firebasestorage.app';

export function pilotSettings(project, env) {
    const bucketName = env.GALLIPICK_BACKUP_BUCKET;
    const pause = env.GALLIPICK_BACKUP_UPLOADS_PAUSED ?? 'false';
    const users = new Set((env.GALLIPICK_BACKUP_TEST_UIDS ?? '').split(',').map(uid => uid.trim()).filter(Boolean));
    if (project !== 'gallipick-dev' || env.GALLIPICK_BACKUP_UPLOADS_ENABLED !== 'true' || bucketName !== DEV_BACKUP_BUCKET
        || !['true', 'false'].includes(pause)
        || users.size === 0 || users.size > 10 || [...users].some(uid => !/^[A-Za-z0-9_-]{1,128}$/.test(uid))) {
        throw new BackupError('failed-precondition', 'The GalliPick Dev backup pilot is disabled.');
    }
    return { users, bucketName, uploadsPaused: pause === 'true' };
}

export function requirePrivateBucket(metadata, { allowLegacyRegional = false } = {}) {
    const storageClassAllowed = metadata.storageClass === 'STANDARD'
        || (allowLegacyRegional && metadata.storageClass === 'REGIONAL');
    if (metadata.location?.toLowerCase() !== 'asia-northeast3' || !storageClassAllowed
        || metadata.iamConfiguration?.publicAccessPrevention !== 'enforced'
        || metadata.iamConfiguration?.uniformBucketLevelAccess?.enabled !== true || metadata.lifecycle?.rule?.length) {
        throw new BackupError('failed-precondition', 'The backup bucket safety settings do not match the pilot.');
    }
}
