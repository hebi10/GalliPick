import { createHash, randomBytes } from 'node:crypto';
import { BackupError, PRODUCTION_BACKUP_PREFIXES } from './backup.js';
import { PRODUCTION_PROJECT } from './cloud-billing.js';

// This module never deletes anything from an unauthenticated client invocation.
// A separate, explicitly enabled trusted worker is responsible for destructive work.
export const ACCOUNT_EXIT_COLLECTION = 'gallipickAccountExitRequests';
export const ACCOUNT_EXIT_GRACE_MS = 20 * 60 * 1000;
export const ACCOUNT_EXIT_MAX_MS = 14 * 24 * 60 * 60 * 1000;
const SIGN_IN_MAX_AGE_MS = 10 * 60 * 1000;
const WORKER_LEASE_MS = 5 * 60 * 1000;
const RETRY_MS = 2 * 60 * 1000;
const MAX_RETRIES = 10;
const OBJECT_PAGE_SIZE = 40;
const DOC_PAGE_SIZE = 100;
const MAX_PILOT_OWNERS = 10;
const sha = value => createHash('sha256').update(value).digest('hex');
const validOwner = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const validMillis = value => Number.isSafeInteger(value) && value >= 0 && value < Number.MAX_SAFE_INTEGER - ACCOUNT_EXIT_MAX_MS;

const backupScopes = Object.keys(PRODUCTION_BACKUP_PREFIXES);
const billingScopes = ['gallipickProductionLicenseBilling', 'gallipickProductionBilling'];
const objectRoots = backupScopes.map(scope => PRODUCTION_BACKUP_PREFIXES[scope]);
const ownerDocumentRoots = backupScopes.flatMap(scope => [
    `${scope}Accounts`, `${scope}VaultAccounts`,
    `${scope}ResumableAccounts`, `${scope}VaultResumableAccounts`,
    `${scope}VaultCredentials`,
    `${scope}RequestLimits`, `${scope}CatalogRequestLimits`,
    `${scope}VaultRequestLimits`, `${scope}VaultCatalogRequestLimits`,
    `${scope}ResumableAccountsPartLimits`, `${scope}ResumableAccountsReadLimits`,
    `${scope}ResumableAccountsWriteLimits`,
    `${scope}VaultResumableAccountsPartLimits`, `${scope}VaultResumableAccountsReadLimits`,
    `${scope}VaultResumableAccountsWriteLimits`,
]);
const resumableJobCollections = backupScopes.flatMap(scope => [
    `${scope}ResumableAccountsJobs`, `${scope}VaultResumableAccountsJobs`,
]);
const billingDocumentRoots = billingScopes.flatMap(scope => [`${scope}Accounts`, `${scope}RequestLimits`]);
// Full account exit must also erase encrypted Play-token escrow for both modes.
// CLOUD-only deletion retains billing, escrow and renewal obligations.
const billingTokenCollections = billingScopes.flatMap(scope => [
    `${scope}Tokens`, `${scope}TokenEscrows`,
]);
const manualGrantPaths = owner => ['live', 'license-test'].map(mode =>
    `gallipickAdminManualGrants/${mode}-${owner}`);

export const accountExitPaths = Object.freeze({
    objectRoots: Object.freeze([...objectRoots]),
    ownerDocumentRoots: Object.freeze([...ownerDocumentRoots]),
    resumableJobCollections: Object.freeze([...resumableJobCollections]),
    billingDocumentRoots: Object.freeze([...billingDocumentRoots]),
    billingTokenCollections: Object.freeze([...billingTokenCollections]),
});

function ownerFromAuth(auth) {
    if (typeof auth?.uid !== 'string' || auth.uid.length < 1 || auth.uid.length > 128 ||
        auth.token?.firebase?.sign_in_provider !== 'google.com') {
        throw new BackupError('unauthenticated', 'A verified Google sign-in is required.');
    }
    return sha(auth.uid);
}

function requireRecentSignIn(auth, nowMs) {
    const signInSec = auth.token?.auth_time;
    const signedInAtMs = Number.isSafeInteger(signInSec) && signInSec >= 0 ? signInSec * 1000 : NaN;
    if (!Number.isSafeInteger(signedInAtMs) || signedInAtMs > nowMs + 60_000 ||
        nowMs - signedInAtMs > SIGN_IN_MAX_AGE_MS) {
        throw new BackupError('failed-precondition', 'Reauthenticate with Google before requesting deletion.');
    }
}

function validateRequest(input) {
    if (!input || typeof input !== 'object' || Array.isArray(input) ||
        !['ACCOUNT', 'CLOUD'].includes(input.scope) ||
        input.confirmation !== (input.scope === 'ACCOUNT' ? 'DELETE_ACCOUNT' : 'DELETE_CLOUD') ||
        input.acknowledgesPermanentDeletion !== true || input.acknowledgesPlayCancellation !== true ||
        Object.keys(input).some(key => !['scope', 'confirmation', 'acknowledgesPermanentDeletion',
            'acknowledgesPlayCancellation'].includes(key))) {
        throw new BackupError('invalid-argument', 'Explicit, scoped deletion confirmation is required.');
    }
    return input.scope;
}

function publicStatus(record) {
    if (!record) return { status: 'NONE' };
    return { scope: record.scope, status: record.status, requestedAtMs: record.requestedAtMs,
        deadlineAtMs: record.deadlineAtMs, completedAtMs: record.completedAtMs ?? null,
        progressStage: record.stage ?? null };
}

function activeExit(record, operation = 'read') {
    if (!record) return false;
    if (record.scope === 'ACCOUNT') return true;
    if (record.status === 'RESUMED') return false;
    if (record.status === 'COMPLETED') return operation === 'write';
    return true;
}

function exitRollout(env) {
    const configuredHashes = env.GALLIPICK_ACCOUNT_EXIT_PILOT_UID_HASHES?.trim() ?? '';
    const mode = env.GALLIPICK_ACCOUNT_EXIT_ROLLOUT;
    if (mode === 'general' && configuredHashes === '') return { mode };
    if (mode !== 'pilot' || configuredHashes.length > MAX_PILOT_OWNERS * 66) return null;
    const owners = configuredHashes.split(',').map(hash => hash.trim());
    return owners.length <= MAX_PILOT_OWNERS && owners.every(validOwner)
        ? { mode, owners: new Set(owners) } : null;
}

export async function mayProcessBackupAfterAccountExit(db, transaction, owner) {
    if (!validOwner(owner)) return false;
    const snapshot = await transaction.get(db.doc(`${ACCOUNT_EXIT_COLLECTION}/${owner}`));
    return !activeExit(snapshot.data(), 'write');
}

function isNotFound(error) {
    return error?.code === 404 || error?.code === 'auth/user-not-found';
}

function requiredClaim(ref, record, nowMs) {
    const lease = record?.lease;
    if (!lease || typeof record.uid !== 'string' || !validOwner(ref.id) || sha(record.uid) !== ref.id ||
        !['ACCOUNT', 'CLOUD'].includes(record.scope) || record.status !== 'RUNNING' ||
        !validMillis(record.requestedAtMs) || !validMillis(record.deadlineAtMs) ||
        !validMillis(record.drainUntilMs) || !validMillis(lease.untilMs) || lease.untilMs <= nowMs) {
        throw new BackupError('failed-precondition', 'The account deletion job requires reconciliation.');
    }
}

async function deleteOneCollectionPage(db, name, owner) {
    const docs = (await db.collection(name).where('owner', '==', owner).limit(DOC_PAGE_SIZE).get()).docs;
    for (const document of docs) {
        if (document.data()?.owner !== owner) throw new BackupError('data-loss', 'Deletion target ownership changed.');
        await document.ref.delete();
    }
    return docs.length > 0;
}

async function deleteOneObjectPage(bucket, root, owner) {
    const prefix = `${root}/${owner}/`;
    const [files] = await bucket.getFiles({
        prefix, versions: true, maxResults: OBJECT_PAGE_SIZE, autoPaginate: false,
    });
    if (!Array.isArray(files) || files.length > OBJECT_PAGE_SIZE) {
        throw new BackupError('failed-precondition', 'The Storage deletion listing is invalid.');
    }
    for (const file of files) {
        const generation = file.metadata?.generation;
        if (typeof file.name !== 'string' || !file.name.startsWith(prefix) ||
            file.name.length <= prefix.length || !/^[1-9][0-9]*$/.test(String(generation ?? ''))) {
            throw new BackupError('data-loss', 'Unsafe Storage object or generation in deletion listing.');
        }
        try {
            await bucket.file(file.name, { generation: String(generation) })
                .delete({ ifGenerationMatch: String(generation) });
        } catch (error) {
            if (!isNotFound(error)) throw error;
        }
    }
    return files.length > 0;
}

function advance(record, stage, stageIndex = 0) {
    return { ...record, stage, stageIndex };
}

async function nextDeletionStep({ db, bucket, authAdmin }, ref, record) {
    const owner = ref.id;
    if (record.stage === 'OBJECTS') {
        for (const root of objectRoots) {
            if (await deleteOneObjectPage(bucket, root, owner)) return record;
        }
        return advance(record, 'UPLOAD_JOBS');
    }
    if (record.stage === 'UPLOAD_JOBS') {
        const name = resumableJobCollections[record.stageIndex];
        if (!name) return advance(record, 'BACKUP_DOCS');
        if (await deleteOneCollectionPage(db, name, owner)) return record;
        return advance(record, 'UPLOAD_JOBS', record.stageIndex + 1);
    }
    if (record.stage === 'BACKUP_DOCS') {
        const name = ownerDocumentRoots[record.stageIndex];
        if (!name) return advance(record, record.scope === 'ACCOUNT' ? 'BILLING_TOKENS' : 'VERIFY');
        // Firestore deleting only a parent document leaves its subcollections orphaned.
        await db.recursiveDelete(db.doc(`${name}/${owner}`));
        return advance(record, 'BACKUP_DOCS', record.stageIndex + 1);
    }
    if (record.stage === 'BILLING_TOKENS') {
        const name = billingTokenCollections[record.stageIndex];
        if (!name) return advance(record, 'BILLING_DOCS');
        if (await deleteOneCollectionPage(db, name, owner)) return record;
        return advance(record, 'BILLING_TOKENS', record.stageIndex + 1);
    }
    if (record.stage === 'BILLING_DOCS') {
        const name = billingDocumentRoots[record.stageIndex];
        if (!name) return advance(record, 'MANUAL_GRANTS');
        await db.recursiveDelete(db.doc(`${name}/${owner}`));
        return advance(record, 'BILLING_DOCS', record.stageIndex + 1);
    }
    if (record.stage === 'MANUAL_GRANTS') {
        const path = manualGrantPaths(owner)[record.stageIndex];
        if (!path) return advance(record, 'VERIFY');
        // Remove a departed member's free access and nested audit entries.
        await db.recursiveDelete(db.doc(path));
        return advance(record, 'MANUAL_GRANTS', record.stageIndex + 1);
    }
    if (record.stage === 'VERIFY') {
        for (const root of objectRoots) {
            const [files] = await bucket.getFiles({ prefix: `${root}/${owner}/`,
                versions: true, maxResults: 1, autoPaginate: false });
            if (files.length) return advance(record, 'OBJECTS');
        }
        for (const name of resumableJobCollections) {
            const docs = (await db.collection(name).where('owner', '==', owner).limit(1).get()).docs;
            if (docs.length) return advance(record, 'UPLOAD_JOBS');
        }
        for (const name of ownerDocumentRoots) {
            const target = db.doc(`${name}/${owner}`);
            const [document, children] = await Promise.all([target.get(), target.listCollections()]);
            if (document.exists || children.length) return advance(record, 'BACKUP_DOCS');
        }
        if (record.scope === 'ACCOUNT') {
            for (const name of billingTokenCollections) {
                const docs = (await db.collection(name).where('owner', '==', owner).limit(1).get()).docs;
                if (docs.length) return advance(record, 'BILLING_TOKENS');
            }
            for (const name of billingDocumentRoots) {
                const target = db.doc(`${name}/${owner}`);
                const [document, children] = await Promise.all([target.get(), target.listCollections()]);
                if (document.exists || children.length) return advance(record, 'BILLING_DOCS');
            }
            for (const path of manualGrantPaths(owner)) {
                const target = db.doc(path);
                const [document, children] = await Promise.all([target.get(), target.listCollections()]);
                if (document.exists || children.length) return advance(record, 'MANUAL_GRANTS');
            }
        }
        return advance(record, record.scope === 'ACCOUNT' ? 'AUTH' : 'COMPLETE');
    }
    if (record.stage === 'AUTH') {
        try {
            await authAdmin.deleteUser(record.uid);
        } catch (error) {
            if (!isNotFound(error)) throw error;
        }
        return advance(record, 'COMPLETE');
    }
    if (record.stage === 'COMPLETE') return record;
    throw new BackupError('failed-precondition', 'Unknown account deletion stage.');
}

export function createAccountExitService({ db, authAdmin, getBucket, project, rolloutEnv = {}, now = Date.now }) {
    if (project !== PRODUCTION_PROJECT || !db || !authAdmin || typeof getBucket !== 'function') {
        throw new BackupError('failed-precondition', 'A scoped production deletion service is required.');
    }
    const rollout = exitRollout(rolloutEnv);
    const ownerRef = owner => db.doc(`${ACCOUNT_EXIT_COLLECTION}/${owner}`);
    const allowsOwner = owner => rollout?.mode === 'general' || rollout?.owners?.has(owner) === true;

    async function status(auth) {
        const owner = ownerFromAuth(auth);
        return publicStatus((await ownerRef(owner).get()).data());
    }

    async function assertActive(auth, operation = 'read') {
        const owner = ownerFromAuth(auth);
        if (!['read', 'write'].includes(operation)) {
            throw new BackupError('invalid-argument', 'Unknown account access operation.');
        }
        const record = (await ownerRef(owner).get()).data();
        if (activeExit(record, operation)) {
            throw new BackupError('permission-denied', 'This GalliPick account has a pending deletion or requires fresh backup consent.');
        }
        return owner;
    }

    async function assertActiveOwner(owner, operation = 'read') {
        if (!validOwner(owner) || !['read', 'write'].includes(operation)) {
            throw new BackupError('permission-denied', 'Invalid Cloud owner or access operation.');
        }
        const record = (await ownerRef(owner).get()).data();
        if (activeExit(record, operation)) {
            throw new BackupError('permission-denied', 'This GalliPick account has a pending deletion.');
        }
    }

    async function request(auth, input) {
        const owner = ownerFromAuth(auth);
        if (!rollout) throw new BackupError('failed-precondition', 'Account deletion rollout is not configured.');
        if (!allowsOwner(owner)) throw new BackupError('permission-denied', 'Account deletion is unavailable for this account.');
        const timestamp = now();
        if (!validMillis(timestamp)) throw new BackupError('failed-precondition', 'Invalid server time.');
        requireRecentSignIn(auth, timestamp);
        const scope = validateRequest(input);
        const ref = ownerRef(owner);
        return db.runTransaction(async tx => {
            const old = (await tx.get(ref)).data();
            if (old && old.status !== 'COMPLETED' && old.status !== 'RESUMED') {
                if (old.scope !== scope) throw new BackupError('failed-precondition', 'Another deletion request is in progress.');
                return publicStatus(old);
            }
            if (old?.scope === 'ACCOUNT') return publicStatus(old);
            const record = { uid: auth.uid, scope, status: 'REQUESTED',
                requestId: randomBytes(16).toString('hex'), requestedAtMs: timestamp,
                deadlineAtMs: timestamp + ACCOUNT_EXIT_MAX_MS,
                drainUntilMs: timestamp + ACCOUNT_EXIT_GRACE_MS,
                nextAttemptAtMs: timestamp + ACCOUNT_EXIT_GRACE_MS,
                stage: 'OBJECTS', stageIndex: 0, consecutiveFailures: 0 };
            tx.set(ref, record);
            return publicStatus(record);
        });
    }

    async function resumeCloudBackup(auth, input) {
        const owner = ownerFromAuth(auth);
        const timestamp = now();
        requireRecentSignIn(auth, timestamp);
        if (!input || typeof input !== 'object' || Array.isArray(input) ||
            Object.keys(input).length !== 1 || input.confirmation !== 'RESUME_CLOUD_BACKUP') {
            throw new BackupError('invalid-argument', 'Explicit backup restart confirmation is required.');
        }
        return db.runTransaction(async tx => {
            const ref = ownerRef(owner);
            const previous = (await tx.get(ref)).data();
            if (previous?.scope !== 'CLOUD' ||
                !['COMPLETED', 'RESUMED'].includes(previous.status)) {
                throw new BackupError('failed-precondition', 'The Cloud-only deletion must finish before restarting backup.');
            }
            if (previous.status === 'RESUMED') return publicStatus(previous);
            const resumed = { scope: 'CLOUD', status: 'RESUMED', requestId: previous.requestId,
                requestedAtMs: previous.requestedAtMs, completedAtMs: previous.completedAtMs,
                deadlineAtMs: previous.deadlineAtMs, resumedAtMs: timestamp,
                nextAttemptAtMs: Number.MAX_SAFE_INTEGER, stage: 'RESUMED' };
            tx.set(ref, resumed);
            return publicStatus(resumed);
        });
    }

    async function claim(ref, timestamp) {
        return db.runTransaction(async tx => {
            const current = (await tx.get(ref)).data();
            if (!current || !['REQUESTED', 'IN_PROGRESS', 'RUNNING', 'RETRY'].includes(current.status) ||
                !Number.isSafeInteger(current.nextAttemptAtMs) || current.nextAttemptAtMs > timestamp ||
                (current.lease?.untilMs ?? 0) > timestamp) return null;
            if (!validOwner(ref.id) || typeof current.uid !== 'string' || sha(current.uid) !== ref.id) {
                throw new BackupError('failed-precondition', 'Account deletion owner mismatch.');
            }
            const updated = { ...current, status: 'RUNNING',
                lease: { id: randomBytes(16).toString('hex'), untilMs: timestamp + WORKER_LEASE_MS } };
            tx.set(ref, updated);
            return updated;
        });
    }

    async function checkpoint(ref, record, changes, timestamp) {
        return db.runTransaction(async tx => {
            const current = (await tx.get(ref)).data();
            if (current?.requestId !== record.requestId || current?.lease?.id !== record.lease?.id) {
                throw new BackupError('aborted', 'The deletion job lease was replaced.');
            }
            const terminal = changes.status === 'COMPLETED';
            tx.set(ref, terminal ? {
                ...changes, nextAttemptAtMs: Number.MAX_SAFE_INTEGER, lease: null,
            } : {
                ...current, ...changes,
                status: changes.status ?? 'IN_PROGRESS',
                nextAttemptAtMs: changes.status === 'MANUAL_REVIEW' ? Number.MAX_SAFE_INTEGER
                    : changes.nextAttemptAtMs ?? timestamp + RETRY_MS,
                lease: null,
            });
        });
    }

    async function processOne(ref, { steps = 4 } = {}) {
        if (!allowsOwner(ref.id)) return { claimed: false };
        const timestamp = now();
        const claimed = await claim(ref, timestamp);
        if (!claimed) return { claimed: false };
        try {
            requiredClaim(ref, claimed, timestamp);
            if (timestamp < claimed.drainUntilMs) {
                await checkpoint(ref, claimed, { status: 'REQUESTED', nextAttemptAtMs: claimed.drainUntilMs }, timestamp);
                return { claimed: true, status: 'REQUESTED' };
            }
            const bucket = await getBucket();
            let record = claimed;
            for (let index = 0; index < steps; index += 1) {
                record = await nextDeletionStep({ db, bucket, authAdmin }, ref, record);
                if (record.stage === 'COMPLETE') {
                    const done = { scope: record.scope, status: 'COMPLETED', requestId: record.requestId,
                        requestedAtMs: record.requestedAtMs, deadlineAtMs: record.deadlineAtMs,
                        completedAtMs: now(), stage: 'COMPLETE' };
                    await checkpoint(ref, claimed, done, now());
                    return { claimed: true, status: 'COMPLETED' };
                }
            }
            await checkpoint(ref, claimed, { stage: record.stage, stageIndex: record.stageIndex,
                consecutiveFailures: 0 }, now());
            return { claimed: true, status: 'IN_PROGRESS', stage: record.stage };
        } catch (error) {
            const retryAt = now();
            const consecutiveFailures = (claimed.consecutiveFailures ?? 0) + 1;
            const exhausted = consecutiveFailures >= MAX_RETRIES;
            try {
                await checkpoint(ref, claimed, { status: exhausted ? 'MANUAL_REVIEW' : 'RETRY',
                    consecutiveFailures, stage: claimed.stage, stageIndex: claimed.stageIndex }, retryAt);
            } catch {
                // The other worker's lease wins; do not overwrite its state.
            }
            if (exhausted) return { claimed: true, status: 'MANUAL_REVIEW' };
            throw error;
        }
    }

    async function processDue({ maximumAccounts = 3 } = {}) {
        if (!rollout) return { processed: 0, outcomes: [] };
        const timestamp = now();
        const candidates = rollout.mode === 'pilot' ? [...rollout.owners].map(ownerRef)
            : (await db.collection(ACCOUNT_EXIT_COLLECTION)
                .where('nextAttemptAtMs', '<=', timestamp).limit(Math.min(maximumAccounts * 8, 24)).get())
                .docs.map(candidate => candidate.ref);
        const outcomes = [];
        for (const ref of candidates) {
            if (outcomes.length >= maximumAccounts) break;
            try {
                const processed = await processOne(ref);
                if (processed.claimed) outcomes.push({ status: processed.status });
            } catch {
                outcomes.push({ status: 'RETRY' });
            }
        }
        return { processed: outcomes.length, outcomes };
    }

    return { request, status, resumeCloudBackup, assertActive, assertActiveOwner, processOne, processDue };
}
