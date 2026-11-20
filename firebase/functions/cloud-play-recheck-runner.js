import { PRODUCTION_PROJECT } from './cloud-billing.js';

// No cron-triggered Play request without each independent rollout switch.
// This gate does not and will never approve deletion of stored media.
const REQUIRED_FLAGS = Object.freeze([
    'GALLIPICK_PRODUCTION_AD_FREE_ENABLED',
    'GALLIPICK_PRODUCTION_CLOUD_RETENTION_ENABLED',
    'GALLIPICK_CLOUD_PURGE_REVIEW_ENABLED',
    'GALLIPICK_PRODUCTION_PLAY_TOKEN_ESCROW_ENABLED',
    'GALLIPICK_PRODUCTION_PLAY_RECHECK_ENABLED',
]);

export function productionPlayRecheckAllowed(project, env) {
    return project === PRODUCTION_PROJECT && env != null &&
        REQUIRED_FLAGS.every(key => env[key] === 'true') &&
        typeof env.GALLIPICK_PRODUCTION_PLAY_TOKEN_KMS_KEY === 'string' &&
        env.GALLIPICK_PRODUCTION_PLAY_TOKEN_KMS_KEY.startsWith(
            'projects/gallipick-production/locations/asia-northeast3/keyRings/');
}

export const PLAY_RECHECK_SCHEDULE = 'every 12 hours';
export const PLAY_RECHECK_MAX_PER_MODE = 3;
