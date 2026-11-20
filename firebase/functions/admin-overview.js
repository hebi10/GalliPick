import { createHash } from 'node:crypto';
import { BackupError } from './backup.js';
import { PRODUCTION_PROJECT } from './cloud-billing.js';

const OWNER_HASH = /^[a-f0-9]{64}$/;
const MAX_ADMINS = 5;
const ADMIN_REAUTH_WINDOW_MS = 24 * 60 * 60 * 1000;
const VALID_STATUS = ['REQUESTED', 'IN_PROGRESS', 'RUNNING', 'RETRY'];

export function adminAllowlist(value) {
    if (typeof value !== 'string' || value.length > 5_000) {
        throw new BackupError('failed-precondition', 'GalliPick admin allowlist is not configured.');
    }
    const hashes = value.split(',').map(item => item.trim()).filter(Boolean);
    if (hashes.length < 1 || hashes.length > MAX_ADMINS ||
        new Set(hashes).size !== hashes.length ||
        hashes.some(item => !OWNER_HASH.test(item))) {
        throw new BackupError('failed-precondition', 'GalliPick admin allowlist requires verified UID hashes.');
    }
    return new Set(hashes);
}

export function authorizeGalliPickAdmin(auth, allowlist, nowMs) {
    if (!Number.isSafeInteger(nowMs) || nowMs < 0 || typeof auth?.uid !== 'string' ||
        auth.uid.length < 1 || auth.uid.length > 128 ||
        auth.token?.firebase?.sign_in_provider !== 'google.com' ||
        auth.token?.email_verified !== true || auth.token?.gallipickAdmin !== true ||
        !allowlist?.has(createHash('sha256').update(auth.uid).digest('hex'))) {
        throw new BackupError('permission-denied', 'GalliPick admin access is unavailable.');
    }
    const signedAtMs = auth.token.auth_time * 1000;
    if (!Number.isSafeInteger(signedAtMs) || signedAtMs > nowMs + 60_000 ||
        nowMs - signedAtMs > ADMIN_REAUTH_WINDOW_MS) {
        throw new BackupError('permission-denied', 'A recent Google sign-in is required for GalliPick admin.');
    }
}

export function createAdminOverviewService({ db, project, allowedUidHashes, now = Date.now }) {
    if (project !== PRODUCTION_PROJECT || !db || typeof db.collection !== 'function') {
        throw new BackupError('failed-precondition', 'Admin access must be scoped to GalliPick production.');
    }
    const allowlist = adminAllowlist(allowedUidHashes);
    const count = async (name, status = null) => {
        let query = db.collection(name);
        if (status !== null) query = query.where('status', '==', status);
        const result = await query.count().get();
        const total = result.data()?.count;
        if (!Number.isSafeInteger(total) || total < 0) {
            throw new BackupError('failed-precondition', 'GalliPick admin count cannot be verified.');
        }
        return total;
    };

    async function overview(auth) {
        const timestamp = now();
        authorizeGalliPickAdmin(auth, allowlist, timestamp);
        const [
            liveBillingRecords, licenseBillingRecords, pending, manualReview, reviewCandidates,
        ] = await Promise.all([
            count('gallipickProductionBillingAccounts'),
            count('gallipickProductionLicenseBillingAccounts'),
            Promise.all(VALID_STATUS.map(status => count('gallipickAccountExitRequests', status))),
            count('gallipickAccountExitRequests', 'MANUAL_REVIEW'),
            count('gallipickCloudPurgeReviews', 'NEEDS_PLAY_RECHECK'),
        ]);
        return {
            checkedAtMs: timestamp,
            liveBillingRecords,
            licenseBillingRecords,
            pendingDeletionRequests: pending.reduce((sum, item) => sum + item, 0),
            manualReviewDeletionRequests: manualReview,
            purgeCandidatesPendingPlayRecheck: reviewCandidates,
        };
    }

    return { overview };
}
