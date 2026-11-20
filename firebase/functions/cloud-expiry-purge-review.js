import { createHash } from 'node:crypto';
import { CLOUD_PURGE_WINDOW_MS } from './cloud-retention-policy.js';
import { BackupError } from './backup.js';
import { PRODUCTION_PROJECT } from './cloud-billing.js';

// A review queue, not a destructive deletion worker. Play only supports a fresh
// subscriptionsv2.get when the original purchase token is available. The
// existing entitlement stores token hashes, which are not sufficient.
export const CLOUD_PURGE_REVIEW_COLLECTION = 'gallipickCloudPurgeReviews';
export const CLOUD_PURGE_SOURCES = Object.freeze([
    { scope: 'gallipickProductionBilling', mode: 'live' },
    { scope: 'gallipickProductionLicenseBilling', mode: 'license-test' },
]);
const OWNER_ID = /^[a-f0-9]{64}$/;
const PRODUCT = /^gallipick_cloud_(?:10|50|100)gb$/;
const TOKEN_HASH = /^[a-f0-9]{64}$/;
const MAX_SAFE_TIME = Number.MAX_SAFE_INTEGER - CLOUD_PURGE_WINDOW_MS;
const withinTime = value => Number.isSafeInteger(value) && value >= 0 && value <= MAX_SAFE_TIME;

function entitlementStillActive(record, nowMs) {
    if (record?.entitlements == null) return false;
    if (typeof record.entitlements !== 'object' || Array.isArray(record.entitlements)) return true;
    return Object.values(record.entitlements).some(item =>
        !item || !Number.isSafeInteger(item.expiresAtMs) || item.expiresAtMs > nowMs);
}

export function cloudPurgeReview({ owner, billing, otherBilling = null, accountExit = null, nowMs, mode }) {
    if (!OWNER_ID.test(owner) || !withinTime(nowMs) || !['live', 'license-test'].includes(mode)) {
        throw new BackupError('invalid-argument', 'An owner-scoped production review is required.');
    }
    const lifecycle = billing?.cloudLifecycle;
    const expiry = lifecycle?.expiresAtMs;
    const valid = PRODUCT.test(lifecycle?.productId ?? '') &&
        ['monthly', 'annual'].includes(lifecycle?.basePlanId) &&
        TOKEN_HASH.test(lifecycle?.tokenHash ?? '') &&
        withinTime(expiry) && withinTime(lifecycle?.checkedAtMs) &&
        lifecycle.checkedAtMs <= nowMs && lifecycle.recoveryAllowed === true;
    if (!valid) return { status: 'INCOMPLETE_EVIDENCE', deletionAllowed: false };
    if (billing?.revision == null || !Number.isSafeInteger(billing.revision)) {
        return { status: 'INCOMPLETE_EVIDENCE', deletionAllowed: false };
    }
    if (accountExit && (accountExit.scope === 'ACCOUNT' ||
        (accountExit.status !== 'COMPLETED' && accountExit.status !== 'RESUMED'))) {
        return { status: 'EXISTING_DELETION', deletionAllowed: false };
    }
    if (entitlementStillActive(billing, nowMs) || entitlementStillActive(otherBilling, nowMs)) {
        return { status: 'ACTIVE_ENTITLEMENT', deletionAllowed: false };
    }
    if (nowMs < expiry + CLOUD_PURGE_WINDOW_MS) {
        return { status: 'NOT_DUE', deletionAllowed: false,
            deletionDueAtMs: expiry + CLOUD_PURGE_WINDOW_MS };
    }
    // No raw token or encrypted token escrow is available to query Play again;
    // never interpret the 14-day clock itself as authorization to delete.
    return {
        status: 'NEEDS_PLAY_RECHECK', deletionAllowed: false,
        deletionDueAtMs: expiry + CLOUD_PURGE_WINDOW_MS,
        verifiedExpiryAtMs: expiry,
        purchaseTokenHash: lifecycle.tokenHash,
        sourceMode: mode,
        checkedAtMs: lifecycle.checkedAtMs,
        billingRevision: billing.revision,
    };
}

export function createCloudPurgeReviewService({ db, project, now = Date.now }) {
    if (project !== PRODUCTION_PROJECT || !db || typeof db.collection !== 'function') {
        throw new BackupError('failed-precondition', 'Production-scoped billing review is required.');
    }

    async function review({ maximumPerMode = 10 } = {}) {
        if (!Number.isSafeInteger(maximumPerMode) || maximumPerMode < 1 || maximumPerMode > 25) {
            throw new BackupError('invalid-argument', 'The scan size must be bounded.');
        }
        const nowMs = now();
        if (!withinTime(nowMs) || nowMs < CLOUD_PURGE_WINDOW_MS) {
            throw new BackupError('failed-precondition', 'The server time is invalid.');
        }
        const cutoff = nowMs - CLOUD_PURGE_WINDOW_MS;
        const counts = { scanned: 0, needsPlayRecheck: 0, held: 0 };
        for (const { scope, mode } of CLOUD_PURGE_SOURCES) {
            const other = CLOUD_PURGE_SOURCES.find(item => item.scope !== scope).scope;
            const documents = (await db.collection(`${scope}Accounts`)
                .where('cloudLifecycle.expiresAtMs', '<=', cutoff).limit(maximumPerMode).get()).docs;
            for (const document of documents) {
                counts.scanned++;
                const owner = document.id;
                if (!OWNER_ID.test(owner)) { counts.held++; continue; }
                const [fresh, otherSnapshot, exit] = await Promise.all([
                    document.ref.get(),
                    db.doc(`${other}Accounts/${owner}`).get(),
                    db.doc(`gallipickAccountExitRequests/${owner}`).get(),
                ]);
                const decision = cloudPurgeReview({
                    owner, billing: fresh.data(), otherBilling: otherSnapshot.data(),
                    accountExit: exit.data(), nowMs, mode,
                });
                if (decision.status !== 'NEEDS_PLAY_RECHECK') {
                    counts.held++;
                    continue;
                }
                // Writes only a non-destructive review marker. No deletion queue is created.
                const key = `${scope}-${owner}`;
                await db.doc(`${CLOUD_PURGE_REVIEW_COLLECTION}/${key}`).set({
                    status: 'NEEDS_PLAY_RECHECK', sourceScope: scope,
                    owner, reviewedAtMs: nowMs,
                    verifiedExpiryAtMs: decision.verifiedExpiryAtMs,
                    deletionDueAtMs: decision.deletionDueAtMs,
                    purchaseTokenHash: decision.purchaseTokenHash,
                    billingRevision: decision.billingRevision,
                });
                counts.needsPlayRecheck++;
            }
        }
        return counts;
    }
    return { review };
}
