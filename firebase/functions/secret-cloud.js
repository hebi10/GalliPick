import { createHash, randomBytes, scrypt, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';
import { BackupError } from './backup.js';

const deriveKey = promisify(scrypt);
const keyBytes = 32;
const scryptOptions = { N: 32768, r: 8, p: 3, maxmem: 64 * 1024 * 1024 };
const maximumPasswordBytes = 256;
const sessionDurationMs = 15 * 60 * 1000;
const attemptWindowMs = 15 * 60 * 1000;
const maximumAttempts = 5;
const hashPattern = /^[a-f0-9]{64}$/;
const tokenPattern = /^[A-Za-z0-9_-]{43}$/;
const backupOperations = {
    beginBackup: 'begin',
    finishBackup: 'finish',
    listBackups: 'list',
    getBackupDownload: 'download',
    getBackupPart: 'getPart',
    getBackupStatus: 'status',
};

const tokenDigest = token => createHash('sha256').update(token).digest('hex');
const decodeHash = hash => Buffer.from(hash, 'hex');
const denied = () => new BackupError('permission-denied', 'The secret cloud vault is locked.');
const inconsistent = () => new BackupError('failed-precondition', 'The secret cloud vault needs reconciliation.');

function validPassword(password) {
    return typeof password === 'string' && password.length >= 8 && password.length <= maximumPasswordBytes
        && password.isWellFormed() && Buffer.byteLength(password) <= maximumPasswordBytes && [...password].length >= 8;
}

function credentialFor(record) {
    const credential = record.credential;
    if (credential === undefined) return null;
    if (credential?.version !== 1 || typeof credential.salt !== 'string' || !hashPattern.test(credential.salt)
        || typeof credential.hash !== 'string' || !hashPattern.test(credential.hash)) throw inconsistent();
    return credential;
}

function reserveAttempt(attempts, now) {
    if (attempts !== undefined && (!Number.isSafeInteger(attempts?.startedAtMs) || attempts.startedAtMs < 0
        || !Number.isSafeInteger(attempts.count) || attempts.count < 1 || attempts.count > maximumAttempts)) {
        throw inconsistent();
    }
    if (attempts === undefined || now - attempts.startedAtMs >= attemptWindowMs) {
        return { startedAtMs: now, count: 1 };
    }
    if (attempts.count >= maximumAttempts) {
        throw new BackupError('resource-exhausted', 'Too many unlock attempts. Try again later.');
    }
    return { ...attempts, count: attempts.count + 1 };
}

function checkSession(record, token, now) {
    const credential = credentialFor(record);
    const { session } = record;
    if (!credential || !Number.isSafeInteger(session?.expiresAtMs) || session.expiresAtMs <= now
        || typeof session.digest !== 'string' || !hashPattern.test(session.digest)
        || !timingSafeEqual(decodeHash(session.digest), decodeHash(tokenDigest(token)))) throw denied();
}

export function createSecretCloud({ db, authorizeOwner, backup, resumable, credentialCollection, now = Date.now }) {
    async function openSession(ref, password, setup) {
        if (!validPassword(password)) {
            throw new BackupError('invalid-argument', 'Enter a password of at least eight characters and at most 256 bytes.');
        }
        const previous = await db.runTransaction(async transaction => {
            const record = (await transaction.get(ref)).data() ?? {};
            const credential = credentialFor(record);
            if (setup && credential) throw new BackupError('already-exists', 'The secret cloud vault is already configured.');
            if (!setup && !credential) throw denied();
            transaction.set(ref, { ...record, attempts: reserveAttempt(record.attempts, now()) });
            return { credential, sessionDigest: record.session?.digest };
        });

        // Keep the expensive derivation outside the retriable Firestore transaction.
        const salt = setup ? randomBytes(keyBytes) : decodeHash(previous.credential.salt);
        const key = await deriveKey(password, salt, keyBytes, scryptOptions);
        if (!setup && !timingSafeEqual(decodeHash(previous.credential.hash), key)) throw denied();
        const credential = setup ? { version: 1, salt: salt.toString('hex'), hash: key.toString('hex') } : previous.credential;
        const token = randomBytes(keyBytes).toString('base64url');
        const digest = tokenDigest(token);

        await db.runTransaction(async transaction => {
            const record = (await transaction.get(ref)).data() ?? {};
            const current = credentialFor(record);
            if (setup && current) throw new BackupError('already-exists', 'The secret cloud vault is already configured.');
            if (!setup && (current?.hash !== credential.hash || current.salt !== credential.salt
                || record.session?.digest !== previous.sessionDigest)) {
                throw new BackupError('aborted', 'A newer secret cloud session was created. Try again.');
            }
            transaction.set(ref, { ...record, credential, session: { digest, expiresAtMs: now() + sessionDurationMs } });
        });
        return { token, expiresInMs: sessionDurationMs };
    }

    return {
        async handle(auth, input) {
            const owner = authorizeOwner(auth);
            const ref = db.doc(`${credentialCollection}/${owner}`);
            const { action, password, token, ...data } = input ?? {};
            if (action === 'status') return { configured: credentialFor((await ref.get()).data() ?? {}) !== null };
            if (action === 'setup' || action === 'unlock') return openSession(ref, password, action === 'setup');
            if (typeof action !== 'string' || !Object.hasOwn(backupOperations, action)) {
                throw new BackupError('invalid-argument', 'The requested secret cloud action is unsupported.');
            }
            if (typeof token !== 'string' || !tokenPattern.test(token)) throw denied();
            checkSession((await ref.get()).data() ?? {}, token, now());
            const protocol = data.cloudProtocolVersion === 2 ? resumable : backup;
            const operation = backupOperations[action];
            if (typeof protocol?.[operation] !== 'function') {
                throw new BackupError('failed-precondition', 'The secret backup protocol is unavailable.');
            }
            return protocol[operation](auth, data);
        },
    };
}
