import { createHash, randomBytes } from 'node:crypto';
import { BackupError } from './backup.js';
import { PRODUCTION_PROJECT } from './cloud-billing.js';
import { adminAllowlist, authorizeGalliPickAdmin } from './admin-overview.js';
import { ADMIN_MANUAL_DAY_MS, ADMIN_MANUAL_GRANTS_COLLECTION, ADMIN_MANUAL_MAX_DAYS,
    ADMIN_MANUAL_QUOTAS, manualGrantDocumentId, trustedManualGrant } from './admin-manual-policy.js';

const SHA = /^[a-f0-9]{64}$/;
const REQUEST_ID = /^[0-9a-f]{32}$/;
const REASON = /^[^\x00-\x08\x0b\x0c\x0e-\x1f]{10,280}$/u;
const REAUTH_MS = 10 * 60 * 1000;
const hash = value => createHash('sha256').update(value).digest('hex');

function requireAdmin(auth, allowed, timestamp, write = false) {
    authorizeGalliPickAdmin(auth, allowed, timestamp);
    if (write) {
        const when = auth.token.auth_time * 1000;
        if (!Number.isSafeInteger(when) || when > timestamp + 60_000 ||
            timestamp - when > REAUTH_MS) {
            throw new BackupError('permission-denied', 'Reauthenticate before editing manual access.');
        }
    }
}

function identity(input, writable = false) {
    if (!input || typeof input !== 'object' || Array.isArray(input) ||
        !manualGrantDocumentId(input.ownerId, input.mode)) {
        throw new BackupError('invalid-argument', 'Choose a verified member and billing environment.');
    }
    const allowed = writable ? ['ownerId', 'mode', 'requestId', 'expectedRevision',
        'reason', 'confirmation', 'kind', 'durationDays'] : ['ownerId', 'mode'];
    if (Object.keys(input).some(key => !allowed.includes(key))) {
        throw new BackupError('invalid-argument', 'Unexpected grant request properties.');
    }
    return { owner: input.ownerId, mode: input.mode };
}

function requireWrite(input, operation, timestamp) {
    const reason = input.reason;
    if (typeof reason !== 'string' || reason !== reason.trim() || !REASON.test(reason) ||
        !REQUEST_ID.test(input.requestId ?? '') ||
        !Number.isSafeInteger(input.expectedRevision) ||
        input.expectedRevision < 0 || input.expectedRevision > 100_000 ||
        input.confirmation !== (operation === 'ISSUE' ? 'ISSUE_MANUAL_ACCESS' : 'REVOKE_MANUAL_ACCESS')) {
        throw new BackupError('invalid-argument', 'Reason, fresh confirmation, revision and request ID are required.');
    }
    if (operation === 'ISSUE' && (!Object.hasOwn(ADMIN_MANUAL_QUOTAS, input.kind) ||
        !Number.isSafeInteger(input.durationDays) || input.durationDays < 1 ||
        input.durationDays > ADMIN_MANUAL_MAX_DAYS ||
        timestamp > Number.MAX_SAFE_INTEGER - input.durationDays * ADMIN_MANUAL_DAY_MS)) {
        throw new BackupError('invalid-argument', 'Manual entitlement tier or duration is invalid.');
    }
    if (operation === 'REVOKE' && ('kind' in input || 'durationDays' in input)) {
        throw new BackupError('invalid-argument', 'Revocation must not change the grant tier.');
    }
}

function publicGrant(record, nowMs, owner, mode) {
    if (!record) return { ownerId: owner, mode, status: 'NONE', revision: 0 };
    const trusted = trustedManualGrant(record, nowMs, owner, mode);
    if (record.source !== 'ADMIN_MANUAL' || record.ownerId !== owner || record.mode !== mode ||
        !Number.isSafeInteger(record.revision) || record.revision < 1 ||
        !['ACTIVE', 'REVOKED'].includes(record.status) ||
        !Object.hasOwn(ADMIN_MANUAL_QUOTAS, record.kind)) {
        throw new BackupError('failed-precondition', 'Manual entitlement needs reconciliation.');
    }
    return {
        ownerId: owner, mode, revision: record.revision,
        status: record.status === 'REVOKED' ? 'REVOKED' : trusted?.active ? 'ACTIVE' : 'EXPIRED',
        kind: record.kind, issuedAtMs: record.issuedAtMs, expiresAtMs: record.expiresAtMs,
        revokedAtMs: record.revokedAtMs ?? null, reason: record.reason,
        updatedAtMs: record.updatedAtMs,
    };
}

export function createAdminManualGrantService({
    db, project, allowedUidHashes, enabled = false, now = Date.now,
}) {
    if (project !== PRODUCTION_PROJECT || !db || typeof db.runTransaction !== 'function') {
        throw new BackupError('failed-precondition', 'Manual grants require production Firestore.');
    }
    const allowed = adminAllowlist(allowedUidHashes);
    const refOf = (owner, mode) => db.doc(`${ADMIN_MANUAL_GRANTS_COLLECTION}/${manualGrantDocumentId(owner, mode)}`);

    async function read(auth, input) {
        const timestamp = now();
        requireAdmin(auth, allowed, timestamp);
        const { owner, mode } = identity(input);
        const ref = refOf(owner, mode);
        const [snapshot, history] = await Promise.all([
            ref.get(), ref.collection('history').orderBy('recordedAtMs', 'desc').limit(12).get(),
        ]);
        return {
            grant: publicGrant(snapshot.data(), timestamp, owner, mode),
            history: history.docs.map(document => {
                const entry = document.data();
                if (!['ISSUE', 'REVOKE'].includes(entry?.action) || entry.ownerId !== owner ||
                    entry.mode !== mode || !SHA.test(entry.actorOwnerId ?? '')) {
                    throw new BackupError('failed-precondition', 'Manual grant audit needs reconciliation.');
                }
                return {
                    action: entry.action, kind: entry.kind, recordedAtMs: entry.recordedAtMs,
                    expiresAtMs: entry.expiresAtMs ?? null, reason: entry.reason,
                    revision: entry.revision, actorOwnerId: entry.actorOwnerId,
                };
            }),
        };
    }

    async function mutate(auth, input, action) {
        const timestamp = now();
        requireAdmin(auth, allowed, timestamp, true);
        if (!enabled) throw new BackupError('failed-precondition', 'Manual access changes are disabled.');
        const { owner, mode } = identity(input, true);
        requireWrite(input, action, timestamp);
        const ref = refOf(owner, mode);
        const auditRef = ref.collection('history').doc(input.requestId);
        const actorOwnerId = hash(auth.uid);
        return db.runTransaction(async tx => {
            const [snap, oldAudit, accountExit] = await Promise.all([
                tx.get(ref), tx.get(auditRef), tx.get(db.doc(`gallipickAccountExitRequests/${owner}`)),
            ]);
            const prior = snap.data();
            const already = oldAudit.data();
            if (already) {
                if (already.action !== action || already.actorOwnerId !== actorOwnerId ||
                    already.ownerId !== owner || already.mode !== mode) {
                    throw new BackupError('permission-denied', 'Manual grant request ID was already used.');
                }
                return { grant: publicGrant(prior, timestamp, owner, mode), repeated: true };
            }
            const deletion = accountExit.data();
            if (deletion && (deletion.scope === 'ACCOUNT' ||
                (deletion.status !== 'RESUMED' && deletion.status !== 'COMPLETED'))) {
                throw new BackupError('failed-precondition', 'The account has a pending deletion.');
            }
            const revision = prior?.revision ?? 0;
            if (!Number.isSafeInteger(revision) || revision !== input.expectedRevision) {
                throw new BackupError('aborted', 'The manual entitlement changed. Refresh before editing.');
            }
            if (action === 'ISSUE' && prior?.status === 'ACTIVE' &&
                Number.isSafeInteger(prior.expiresAtMs) && prior.expiresAtMs > timestamp) {
                throw new BackupError('failed-precondition', 'Revoke the current grant before issuing another.');
            }
            if (action === 'REVOKE' && (prior?.status !== 'ACTIVE' || !Number.isSafeInteger(prior.expiresAtMs))) {
                throw new BackupError('failed-precondition', 'There is no current manual grant to revoke.');
            }
            const next = action === 'ISSUE' ? {
                source: 'ADMIN_MANUAL', ownerId: owner, mode, status: 'ACTIVE',
                kind: input.kind, issuedAtMs: timestamp,
                expiresAtMs: timestamp + input.durationDays * ADMIN_MANUAL_DAY_MS,
                reason: input.reason, issuedByOwnerId: actorOwnerId,
                updatedAtMs: timestamp, revision: revision + 1,
            } : {
                ...prior, status: 'REVOKED', revokedAtMs: timestamp,
                revokedByOwnerId: actorOwnerId, revocationReason: input.reason,
                updatedAtMs: timestamp, revision: revision + 1,
            };
            tx.set(ref, next);
            tx.set(auditRef, {
                action, mode, ownerId: owner, actorOwnerId, revision: next.revision,
                kind: next.kind, expiresAtMs: next.expiresAtMs,
                recordedAtMs: timestamp, reason: input.reason,
            });
            return { grant: publicGrant(next, timestamp, owner, mode), repeated: false };
        });
    }

    return {
        read,
        issue: (auth, input) => mutate(auth, input, 'ISSUE'),
        revoke: (auth, input) => mutate(auth, input, 'REVOKE'),
    };
}
