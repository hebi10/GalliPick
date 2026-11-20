import { CLOUD_RECOVERY_WINDOW_MS } from './cloud-retention-policy.js';

export const ADMIN_MANUAL_GRANTS_COLLECTION = 'gallipickAdminManualGrants';
export const ADMIN_MANUAL_DAY_MS = 24 * 60 * 60 * 1000;
export const ADMIN_MANUAL_MAX_DAYS = 90;
export const ADMIN_MANUAL_QUOTAS = Object.freeze({
    CLOUD_10GB: 10_000_000_000,
    CLOUD_50GB: 50_000_000_000,
    CLOUD_100GB: 100_000_000_000,
    AD_FREE: 0,
});
const OWNER_HASH = /^[a-f0-9]{64}$/;
const safeTime = value => Number.isSafeInteger(value) && value >= 0
    && value < Number.MAX_SAFE_INTEGER - ADMIN_MANUAL_MAX_DAYS * ADMIN_MANUAL_DAY_MS;

export function manualGrantDocumentId(owner, mode) {
    if (!OWNER_HASH.test(owner) || !['live', 'license-test'].includes(mode)) return null;
    return `${mode}-${owner}`;
}

/**
 * Admin grants are never Play subscriptions. Return fail-closed if corrupt,
 * revoked, unrecognized, cross-environment, or not yet issued.
 */
export function trustedManualGrant(record, nowMs, owner, mode) {
    if (!safeTime(nowMs) || !manualGrantDocumentId(owner, mode) ||
        !record || record.source !== 'ADMIN_MANUAL' || record.ownerId !== owner ||
        record.mode !== mode || record.status !== 'ACTIVE' ||
        !Object.hasOwn(ADMIN_MANUAL_QUOTAS, record.kind) ||
        !safeTime(record.issuedAtMs) || !safeTime(record.expiresAtMs) ||
        record.expiresAtMs <= record.issuedAtMs ||
        record.issuedAtMs > nowMs ||
        !Number.isSafeInteger(record.revision) || record.revision < 1 ||
        record.expiresAtMs > record.issuedAtMs + ADMIN_MANUAL_MAX_DAYS * ADMIN_MANUAL_DAY_MS) return null;
    const quotaBytes = ADMIN_MANUAL_QUOTAS[record.kind];
    const active = nowMs < record.expiresAtMs;
    const recoveryEndsAtMs = quotaBytes > 0 && record.expiresAtMs < Number.MAX_SAFE_INTEGER - CLOUD_RECOVERY_WINDOW_MS
        ? record.expiresAtMs + CLOUD_RECOVERY_WINDOW_MS : null;
    const recovery = !active && quotaBytes > 0 && recoveryEndsAtMs !== null && nowMs < recoveryEndsAtMs;
    return {
        kind: record.kind, quotaBytes: active ? quotaBytes : 0,
        expiresAtMs: record.expiresAtMs, active,
        mayReadCloud: active && quotaBytes > 0 || recovery,
        mayUploadCloud: active && quotaBytes > 0,
        adFree: active,
        recoveryEndsAtMs: recovery ? recoveryEndsAtMs : null,
    };
}
