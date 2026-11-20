// Pure date/permission policy. No data is deleted here; a separately verified worker is required.
export const CLOUD_RETENTION_DAY_MS = 24 * 60 * 60 * 1000;
export const CLOUD_RECOVERY_WINDOW_MS = 7 * CLOUD_RETENTION_DAY_MS;
export const CLOUD_PURGE_WINDOW_MS = 14 * CLOUD_RETENTION_DAY_MS;

const validTime = value => Number.isSafeInteger(value) && value >= 0
    && value <= Number.MAX_SAFE_INTEGER - CLOUD_PURGE_WINDOW_MS;

export function cloudRetentionState({ nowMs, lastVerifiedCloudExpiryMs = null, deletionRequestedAtMs = null }) {
    if (!validTime(nowMs) || (lastVerifiedCloudExpiryMs !== null && !validTime(lastVerifiedCloudExpiryMs))
        || (deletionRequestedAtMs !== null && (!validTime(deletionRequestedAtMs) || deletionRequestedAtMs > nowMs))) {
        throw new RangeError('A verified, bounded cloud lifecycle time is required.');
    }

    if (deletionRequestedAtMs !== null) {
        return {
            state: 'ACCOUNT_DELETION_REQUESTED', mayBrowse: false, mayDownload: false, mayUpload: false,
            recoveryEndsAtMs: null, deletionDueAtMs: deletionRequestedAtMs + CLOUD_PURGE_WINDOW_MS,
        };
    }

    // Never synthesize an expiration date for users without a verified paid Cloud entitlement.
    if (lastVerifiedCloudExpiryMs === null) {
        return {
            state: 'NO_VERIFIED_CLOUD_EXPIRY', mayBrowse: false, mayDownload: false, mayUpload: false,
            recoveryEndsAtMs: null, deletionDueAtMs: null,
        };
    }

    if (nowMs < lastVerifiedCloudExpiryMs) {
        return {
            state: 'PAID', mayBrowse: true, mayDownload: true, mayUpload: true,
            recoveryEndsAtMs: null, deletionDueAtMs: null,
        };
    }

    const recoveryEndsAtMs = lastVerifiedCloudExpiryMs + CLOUD_RECOVERY_WINDOW_MS;
    const deletionDueAtMs = lastVerifiedCloudExpiryMs + CLOUD_PURGE_WINDOW_MS;
    return {
        state: nowMs < recoveryEndsAtMs ? 'READ_ONLY_RECOVERY'
            : nowMs < deletionDueAtMs ? 'LOCKED_AWAITING_PURGE' : 'PURGE_DUE',
        mayBrowse: nowMs < recoveryEndsAtMs,
        mayDownload: nowMs < recoveryEndsAtMs,
        mayUpload: false,
        recoveryEndsAtMs,
        deletionDueAtMs,
    };
}
