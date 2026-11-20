import { createHash } from 'node:crypto';
import { BackupError } from './backup.js';
import { PRODUCTION_PROJECT } from './cloud-billing.js';
import { adminAllowlist, authorizeGalliPickAdmin } from './admin-overview.js';
import { ACCOUNT_EXIT_COLLECTION } from './account-exit.js';
import { CLOUD_PURGE_REVIEW_COLLECTION, cloudPurgeReview } from './cloud-expiry-purge-review.js';
import { trustedManualGrant, manualGrantDocumentId, ADMIN_MANUAL_GRANTS_COLLECTION } from './admin-manual-policy.js';

export const ADMIN_DELETION_REVIEW_AUDITS = 'gallipickAdminDeletionReviewAudits';
export const ADMIN_DELETION_PAGE_LIMIT = 20;
const OWNER = /^[a-f0-9]{64}$/;
const REQUEST_ID = /^[a-f0-9]{32}$/;
const REASON = /^[^\x00-\x08\x0b\x0c\x0e-\x1f]{12,280}$/u;
const REAUTH_MS = 10 * 60 * 1000;
const exitStatuses = ['REQUESTED', 'IN_PROGRESS', 'RUNNING', 'RETRY', 'MANUAL_REVIEW'];
const jobStages = ['OBJECTS', 'UPLOAD_JOBS', 'BACKUP_DOCS',
    'BILLING_TOKENS', 'BILLING_DOCS', 'MANUAL_GRANTS', 'VERIFY', 'AUTH', 'COMPLETE'];
const hash = value => createHash('sha256').update(value).digest('hex');
const validTime = value => Number.isSafeInteger(value) && value >= 0;
const validAdminRequest = input => Boolean(input && typeof input === 'object' && !Array.isArray(input));

function requireAdmin(auth, allowed, nowMs, mutate = false) {
    authorizeGalliPickAdmin(auth, allowed, nowMs);
    if (mutate) {
        const authenticatedAt = auth.token.auth_time * 1_000;
        if (!Number.isSafeInteger(authenticatedAt) || authenticatedAt > nowMs + 60_000 ||
            nowMs - authenticatedAt > REAUTH_MS) {
            throw new BackupError('permission-denied', 'Recent Google reauthentication is required for deletion retries.');
        }
    }
}

function safeExitRecord(document) {
    const value = document.data();
    if (!OWNER.test(document.id) || !exitStatuses.includes(value?.status) ||
        !['ACCOUNT', 'CLOUD'].includes(value?.scope) ||
        !REQUEST_ID.test(value?.requestId ?? '') ||
        !validTime(value?.requestedAtMs) || !validTime(value?.deadlineAtMs)) {
        return { ownerId: OWNER.test(document.id) ? document.id : null, reviewState: 'INCONSISTENT_RECORD' };
    }
    return {
        ownerId: document.id, deletionRequestId: value.requestId, scope: value.scope,
        status: value.status, stage: jobStages.includes(value.stage) ? value.stage : 'UNKNOWN',
        requestedAtMs: value.requestedAtMs, deadlineAtMs: value.deadlineAtMs,
        consecutiveFailures: Number.isSafeInteger(value.consecutiveFailures)
            ? value.consecutiveFailures : null,
        reviewState: value.status === 'MANUAL_REVIEW' ? 'REQUIRES_OPERATOR_REVIEW' : 'SCHEDULED_OR_RUNNING',
    };
}

function assertReadOnlyInput(input) {
    if (input !== undefined && input !== null && (!validAdminRequest(input) || Object.keys(input).length)) {
        throw new BackupError('invalid-argument', 'Deletion overview does not accept account selectors.');
    }
}

function retryInput(input) {
    const allowed = ['ownerId', 'deletionRequestId', 'idempotencyKey', 'reason', 'confirmation'];
    if (!validAdminRequest(input) || Object.keys(input).length !== allowed.length ||
        Object.keys(input).some(key => !allowed.includes(key)) ||
        !OWNER.test(input.ownerId ?? '') || !REQUEST_ID.test(input.deletionRequestId ?? '') ||
        !REQUEST_ID.test(input.idempotencyKey ?? '') ||
        typeof input.reason !== 'string' || input.reason.trim() !== input.reason ||
        !REASON.test(input.reason) ||
        input.confirmation !== 'RETRY_EXISTING_DELETION_ONLY') {
        throw new BackupError('invalid-argument', 'A matching pending deletion and an explicit reason are required.');
    }
}

export function createAdminDeletionReviewService({
    db, project, allowedUidHashes, retryEnabled = false, workerEnabled = false, now = Date.now,
}) {
    if (project !== PRODUCTION_PROJECT || !db ||
        typeof db.collection !== 'function' || typeof db.runTransaction !== 'function') {
        throw new BackupError('failed-precondition', 'Production-only deletion review service is required.');
    }
    const allowed = adminAllowlist(allowedUidHashes);

    async function list(auth, input) {
        const evaluatedAt = now();
        requireAdmin(auth, allowed, evaluatedAt);
        assertReadOnlyInput(input);
        const [exits, reviews] = await Promise.all([
            db.collection(ACCOUNT_EXIT_COLLECTION)
                .where('status', 'in', exitStatuses).limit(ADMIN_DELETION_PAGE_LIMIT + 1).get(),
            db.collection(CLOUD_PURGE_REVIEW_COLLECTION)
                .where('status', '==', 'NEEDS_PLAY_RECHECK').limit(ADMIN_DELETION_PAGE_LIMIT + 1).get(),
        ]);
        const first = exits.docs.slice(0, ADMIN_DELETION_PAGE_LIMIT);
        const second = reviews.docs.slice(0, ADMIN_DELETION_PAGE_LIMIT);
        return {
            checkedAtMs: evaluatedAt,
            displayLimit: ADMIN_DELETION_PAGE_LIMIT,
            moreDeletionRequests: exits.docs.length > ADMIN_DELETION_PAGE_LIMIT,
            morePurgeReviews: reviews.docs.length > ADMIN_DELETION_PAGE_LIMIT,
            deletionRequests: first.map(safeExitRecord),
            // A review marker is NOT fresh Play verification and never approves deletion.
            purgeReviews: second.map(doc => {
                const r = doc.data();
                return {
                    ownerId: OWNER.test(r?.owner) ? r.owner : null,
                    scope: ['gallipickProductionBilling', 'gallipickProductionLicenseBilling']
                        .includes(r?.sourceScope) ? r.sourceScope : null,
                    expiresAtMs: validTime(r?.verifiedExpiryAtMs) ? r.verifiedExpiryAtMs : null,
                    deletionDueAtMs: validTime(r?.deletionDueAtMs) ? r.deletionDueAtMs : null,
                    status: 'NEEDS_PLAY_RECHECK',
                    deletionAllowed: false,
                };
            }),
        };
    }

    async function preview(auth, input) {
        const evaluatedAt = now();
        requireAdmin(auth, allowed, evaluatedAt);
        assertReadOnlyInput(input);
        const reviews = await db.collection(CLOUD_PURGE_REVIEW_COLLECTION)
            .where('status', '==', 'NEEDS_PLAY_RECHECK').limit(ADMIN_DELETION_PAGE_LIMIT + 1).get();
        const items = [];
        for (const snapshot of reviews.docs.slice(0, ADMIN_DELETION_PAGE_LIMIT)) {
            const record = snapshot.data();
            const owner = record?.owner;
            const scope = record?.sourceScope;
            if (!OWNER.test(owner ?? '') ||
                !['gallipickProductionBilling', 'gallipickProductionLicenseBilling'].includes(scope)) {
                items.push({ ownerId: null, mode: null, status: 'INCOMPLETE_EVIDENCE', deletionAllowed: false });
                continue;
            }
            const mode = scope === 'gallipickProductionBilling' ? 'live' : 'license-test';
            const otherScope = mode === 'live'
                ? 'gallipickProductionLicenseBilling' : 'gallipickProductionBilling';
            const refs = [
                `${scope}Accounts/${owner}`,
                `${otherScope}Accounts/${owner}`,
                `${ACCOUNT_EXIT_COLLECTION}/${owner}`,
                ...['live', 'license-test'].map(source =>
                    `${ADMIN_MANUAL_GRANTS_COLLECTION}/${manualGrantDocumentId(owner, source)}`),
            ];
            const [current, other, deletion, liveManual, testManual] =
                await Promise.all(refs.map(path => db.doc(path).get()));
            let status = 'INCOMPLETE_EVIDENCE';
            const currentData = current.data();
            const lastVerified = currentData?.cloudLifecycle;
            const unchanged = Number.isSafeInteger(record?.billingRevision) &&
                currentData?.revision === record.billingRevision &&
                record.purchaseTokenHash === lastVerified?.tokenHash &&
                record.verifiedExpiryAtMs === lastVerified?.expiresAtMs;
            if (unchanged) {
                const manualInEffect = [
                    trustedManualGrant(liveManual.data(), evaluatedAt, owner, 'live'),
                    trustedManualGrant(testManual.data(), evaluatedAt, owner, 'license-test'),
                ].some(grant => grant?.mayReadCloud === true || grant?.mayUploadCloud === true);
                if (manualInEffect) status = 'ACTIVE_MANUAL_ACCESS';
                else {
                    try {
                        status = cloudPurgeReview({
                            owner, billing: currentData, otherBilling: other.data(),
                            accountExit: deletion.data(), nowMs: evaluatedAt, mode,
                        }).status;
                    } catch {
                        status = 'INCOMPLETE_EVIDENCE';
                    }
                }
            } else status = 'STALE_BILLING_REVISION';
            items.push({ ownerId: owner, mode, status, deletionAllowed: false });
        }
        return {
            checkedAtMs: evaluatedAt,
            examined: items.length,
            hasMore: reviews.docs.length > ADMIN_DELETION_PAGE_LIMIT,
            dryRunOnly: true,
            deletionExecuted: false,
            items,
        };
    }

    async function retry(auth, input) {
        const evaluatedAt = now();
        requireAdmin(auth, allowed, evaluatedAt, true);
        if (!retryEnabled || !workerEnabled) {
            throw new BackupError('failed-precondition', 'Deletion retries and the trusted worker are disabled.');
        }
        retryInput(input);
        const actor = hash(auth.uid);
        const target = db.doc(`${ACCOUNT_EXIT_COLLECTION}/${input.ownerId}`);
        const audit = db.doc(`${ADMIN_DELETION_REVIEW_AUDITS}/${input.idempotencyKey}`);
        return db.runTransaction(async tx => {
            const [currentSnap, auditSnap] = await Promise.all([tx.get(target), tx.get(audit)]);
            const current = currentSnap.data();
            const priorAudit = auditSnap.data();
            if (priorAudit) {
                if (priorAudit.action !== 'RETRY' || priorAudit.actorOwnerId !== actor ||
                    priorAudit.ownerId !== input.ownerId ||
                    priorAudit.deletionRequestId !== input.deletionRequestId) {
                    throw new BackupError('permission-denied', 'Retry request key already belongs to another operation.');
                }
                return { accepted: true, repeated: true, status: 'RETRY' };
            }
            if (current?.status !== 'MANUAL_REVIEW' || current.requestId !== input.deletionRequestId ||
                !['ACCOUNT', 'CLOUD'].includes(current.scope) ||
                !jobStages.includes(current.stage) ||
                typeof current.uid !== 'string' || hash(current.uid) !== input.ownerId ||
                !validTime(current.requestedAtMs) || !validTime(current.deadlineAtMs) ||
                (current.lease?.untilMs ?? 0) > evaluatedAt) {
                throw new BackupError('failed-precondition', 'Only the matching failed user-requested job can be retried.');
            }
            tx.update(target, {
                status: 'RETRY', nextAttemptAtMs: evaluatedAt, consecutiveFailures: 0,
                lease: null, lastAdminReviewAtMs: evaluatedAt,
            });
            tx.create(audit, {
                action: 'RETRY', ownerId: input.ownerId,
                deletionRequestId: input.deletionRequestId,
                actorOwnerId: actor, reason: input.reason,
                recordedAtMs: evaluatedAt, previousStatus: 'MANUAL_REVIEW',
            });
            return { accepted: true, repeated: false, status: 'RETRY' };
        });
    }

    return { list, preview, retry };
}
