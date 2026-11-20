import { createHash } from 'node:crypto';
import { BackupError } from './backup.js';
import { PRODUCTION_PROJECT } from './cloud-billing.js';
import { ACCOUNT_EXIT_COLLECTION } from './account-exit.js';
import { CLOUD_PURGE_REVIEW_COLLECTION } from './cloud-expiry-purge-review.js';
import { adminAllowlist, authorizeGalliPickAdmin } from './admin-overview.js';

export const ADMIN_DELETION_PAGE_SIZE = 15;
export const ADMIN_DELETION_DRY_RUN_LIMIT = 10;
const REAUTH_MS = 10 * 60 * 1000;
const MAX_OPERATOR_RETRIES = 3;
const OWNER_ID = /^[a-f0-9]{64}$/;
const REQUEST_ID = /^[a-f0-9]{32}$/;
const PURGE_ID = /^gallipickProduction(?:License)?Billing-[a-f0-9]{64}$/;
const REASON = /^[^\x00-\x08\x0b\x0c\x0e-\x1f]{10,280}$/u;
const SCOPES = new Set(['ACCOUNT', 'CLOUD']);
const EXIT_STATUSES = new Set([
    'REQUESTED', 'RUNNING', 'IN_PROGRESS', 'RETRY',
    'MANUAL_REVIEW', 'COMPLETED', 'RESUMED',
]);
const PURGE_STATUSES = new Set(['NEEDS_PLAY_RECHECK']);
const STAGES = new Set([
    'OBJECTS', 'UPLOAD_JOBS', 'BACKUP_DOCS', 'BILLING_TOKENS',
    'BILLING_DOCS', 'MANUAL_GRANTS', 'VERIFY', 'AUTH',
]);
const sha = value => createHash('sha256').update(value).digest('hex');
const goodTime = n => Number.isSafeInteger(n) && n >= 0;
const views = Object.freeze({
    EXIT: { collection: ACCOUNT_EXIT_COLLECTION, timestamp: 'requestedAtMs', id: OWNER_ID },
    PURGE: { collection: CLOUD_PURGE_REVIEW_COLLECTION, timestamp: 'reviewedAtMs', id: PURGE_ID },
});

function validateKeys(input, allowed) {
    if (!input || typeof input !== 'object' || Array.isArray(input) ||
        Object.keys(input).some(key => !allowed.includes(key))) {
        throw new BackupError('invalid-argument', 'Only explicitly supported administrator fields are accepted.');
    }
}
function requireView(view) {
    const config = views[view];
    if (!config) throw new BackupError('invalid-argument', 'Unknown deletion review view.');
    return config;
}
function scopedId(id, config) {
    if (typeof id !== 'string' || !config.id.test(id)) {
        throw new BackupError('invalid-argument', 'Invalid account-scoped review identifier.');
    }
    return id;
}
function adminAuth(auth, allow, nowMs, write = false) {
    authorizeGalliPickAdmin(auth, allow, nowMs);
    if (write) {
        const signedInAtMs = auth.token.auth_time * 1000;
        if (!goodTime(signedInAtMs) || signedInAtMs > nowMs + 60_000 ||
            nowMs - signedInAtMs > REAUTH_MS) {
            throw new BackupError('permission-denied', 'Recent administrator Google authentication is required.');
        }
    }
}
function safeExit(id, data) {
    if (!OWNER_ID.test(id) || !data || !SCOPES.has(data.scope) ||
        !EXIT_STATUSES.has(data.status) || !goodTime(data.requestedAtMs) ||
        !goodTime(data.deadlineAtMs) || data.deadlineAtMs < data.requestedAtMs ||
        !REQUEST_ID.test(data.requestId ?? '')) {
        throw new BackupError('failed-precondition', 'Deletion request needs server-side reconciliation.');
    }
    return {
        id, scope: data.scope, status: data.status,
        requestedAtMs: data.requestedAtMs, deadlineAtMs: data.deadlineAtMs,
        completedAtMs: goodTime(data.completedAtMs) ? data.completedAtMs : null,
        nextAttemptAtMs: goodTime(data.nextAttemptAtMs) ? data.nextAttemptAtMs : null,
        stage: typeof data.stage === 'string' && STAGES.has(data.stage) ? data.stage : null,
        consecutiveFailures: Number.isSafeInteger(data.consecutiveFailures)
            && data.consecutiveFailures >= 0 ? data.consecutiveFailures : 0,
        requestId: data.requestId,
        adminRetryCount: Number.isSafeInteger(data.adminRetryCount) ? data.adminRetryCount : 0,
        // Never include the original Firebase UID, email or signed upload capability.
    };
}
function safePurge(id, data) {
    if (!PURGE_ID.test(id) || !data || !PURGE_STATUSES.has(data.status) ||
        !OWNER_ID.test(data.owner ?? '') ||
        id !== `${data.sourceScope}-${data.owner}` ||
        !goodTime(data.reviewedAtMs) || !goodTime(data.verifiedExpiryAtMs) ||
        !goodTime(data.deletionDueAtMs)) {
        throw new BackupError('failed-precondition', 'Play verification review needs reconciliation.');
    }
    return {
        id, ownerId: data.owner, sourceScope: data.sourceScope,
        status: data.status, reviewedAtMs: data.reviewedAtMs,
        verifiedExpiryAtMs: data.verifiedExpiryAtMs,
        deletionDueAtMs: data.deletionDueAtMs,
        // Purchase token hashes and original purchase tokens are never exposed.
    };
}

export function createAdminDeletionReviewService({
    db, project, allowedUidHashes, retryEnabled = false,
    accountExitWorkerEnabled = false, now = Date.now,
}) {
    if (project !== PRODUCTION_PROJECT || !db || typeof db.runTransaction !== 'function' ||
        typeof db.collection !== 'function') {
        throw new BackupError('failed-precondition', 'Only the production deletion-review datastore is allowed.');
    }
    const allow = adminAllowlist(allowedUidHashes);

    async function list(auth, input) {
        const timestamp = now();
        adminAuth(auth, allow, timestamp);
        validateKeys(input, ['view', 'cursor']);
        const config = requireView(input.view);
        if (input.cursor != null) scopedId(input.cursor, config);

        let query = db.collection(config.collection)
            .orderBy(config.timestamp, 'desc')
            .limit(ADMIN_DELETION_PAGE_SIZE);
        if (input.cursor) {
            const cursor = await db.doc(`${config.collection}/${input.cursor}`).get();
            if (!cursor.exists) throw new BackupError('invalid-argument', 'Review page cursor has expired.');
            query = query.startAfter(cursor);
        }
        const page = (await query.get()).docs;
        if (!Array.isArray(page) || page.length > ADMIN_DELETION_PAGE_SIZE) {
            throw new BackupError('failed-precondition', 'Deletion review pagination is invalid.');
        }
        const rows = page.map(snap => config === views.EXIT
            ? safeExit(snap.id, snap.data()) : safePurge(snap.id, snap.data()));
        return { view: input.view, checkedAtMs: timestamp, rows,
            nextCursor: page.length === ADMIN_DELETION_PAGE_SIZE ? page.at(-1).id : null,
            pageSize: ADMIN_DELETION_PAGE_SIZE };
    }

    async function preview(auth, input) {
        const timestamp = now();
        adminAuth(auth, allow, timestamp);
        validateKeys(input, ['view', 'ids']);
        const config = requireView(input.view);
        const ids = input.ids;
        if (!Array.isArray(ids) || ids.length < 1 || ids.length > ADMIN_DELETION_DRY_RUN_LIMIT ||
            ids.some(id => !config.id.test(id)) || new Set(ids).size !== ids.length) {
            throw new BackupError('invalid-argument', 'Dry-run accepts at most ten distinct reviewed records.');
        }
        const rows = await Promise.all(ids.map(async id => {
            const data = (await db.doc(`${config.collection}/${id}`).get()).data();
            if (!data) return { id, decision: 'NOT_FOUND' };
            const item = config === views.EXIT ? safeExit(id, data) : safePurge(id, data);
            return {
                id, decision: config === views.PURGE ? 'NEEDS_PLAY_RECHECK'
                    : item.status === 'MANUAL_REVIEW' ? 'INDIVIDUAL_RETRY_ONLY'
                    : item.status === 'COMPLETED' || item.status === 'RESUMED'
                        ? 'ALREADY_PROCESSED' : 'USER_REQUEST_IN_PROGRESS',
            };
        }));
        // This endpoint cannot approve a bulk deletion, or create deletion jobs.
        return { view: input.view, checkedAtMs: timestamp,
            canExecute: false, serverDeletesPerformed: 0, rows };
    }

    async function retry(auth, input) {
        const timestamp = now();
        adminAuth(auth, allow, timestamp, true);
        if (!retryEnabled || !accountExitWorkerEnabled) {
            throw new BackupError('failed-precondition', 'Administrative deletion retries are not enabled.');
        }
        validateKeys(input, ['ownerId', 'originalRequestId', 'actionId', 'scope',
            'expectedStage', 'reason', 'confirmation']);
        if (!OWNER_ID.test(input.ownerId ?? '') ||
            !REQUEST_ID.test(input.originalRequestId ?? '') ||
            !REQUEST_ID.test(input.actionId ?? '') ||
            !SCOPES.has(input.scope) || !STAGES.has(input.expectedStage) ||
            typeof input.reason !== 'string' ||
            input.reason !== input.reason.trim() || !REASON.test(input.reason) ||
            input.confirmation !== 'RETRY_EXISTING_USER_DELETION') {
            throw new BackupError('invalid-argument', 'Existing deletion request confirmation and reason are required.');
        }
        const ref = db.doc(`${ACCOUNT_EXIT_COLLECTION}/${input.ownerId}`);
        const audit = ref.collection('adminHistory').doc(input.actionId);
        const actor = sha(auth.uid);
        return db.runTransaction(async tx => {
            const [recordSnap, auditSnap] = await Promise.all([tx.get(ref), tx.get(audit)]);
            const record = recordSnap.data();
            const previous = auditSnap.data();
            if (previous) {
                if (previous.ownerId !== input.ownerId || previous.originalRequestId !== input.originalRequestId ||
                    previous.actorOwnerId !== actor || previous.action !== 'RETRY') {
                    throw new BackupError('permission-denied', 'The administrator action ID was previously used.');
                }
                return { scheduled: true, repeated: true, status: record?.status ?? 'UNKNOWN' };
            }
            if (!record || record.status !== 'MANUAL_REVIEW' ||
                record.requestId !== input.originalRequestId || record.scope !== input.scope ||
                record.stage !== input.expectedStage ||
                !STAGES.has(record.stage) ||
                typeof record.uid !== 'string' || sha(record.uid) !== input.ownerId ||
                !goodTime(record.requestedAtMs) || !goodTime(record.deadlineAtMs) ||
                !goodTime(record.drainUntilMs) || record.drainUntilMs > timestamp ||
                (record.lease?.untilMs ?? 0) > timestamp ||
                !Number.isSafeInteger(record.stageIndex) || record.stageIndex < 0 ||
                !Number.isSafeInteger(record.adminRetryCount ?? 0) ||
                (record.adminRetryCount ?? 0) >= MAX_OPERATOR_RETRIES) {
                throw new BackupError('failed-precondition', 'Only a previously authenticated and failed user request can be retried.');
            }
            // A retry only resumes the user's own previously requested scope and
            // existing stage. The client cannot create or widen a deletion target.
            tx.set(ref, { ...record, status: 'RETRY', nextAttemptAtMs: timestamp,
                consecutiveFailures: 0, lease: null,
                adminRetryCount: (record.adminRetryCount ?? 0) + 1,
                lastAdminRetryAtMs: timestamp });
            tx.set(audit, {
                action: 'RETRY', ownerId: input.ownerId, originalRequestId: record.requestId,
                actorOwnerId: actor, recordedAtMs: timestamp, scope: record.scope,
                stage: record.stage, reason: input.reason,
            });
            return { scheduled: true, repeated: false, status: 'RETRY' };
        });
    }

    return { list, preview, retry };
}
