import { createHash } from 'node:crypto';
import { GoogleAuth } from 'google-auth-library';
import { BackupError } from './backup.js';

// Key material stays in Cloud KMS. Firebase stores only authenticated ciphertext.
// No credential, purchase token, plaintext or provider errors enter logs.
const PROJECT = 'gallipick-production';
const KEY_RESOURCE = /^projects\/gallipick-production\/locations\/asia-northeast3\/keyRings\/[A-Za-z][A-Za-z0-9_-]{0,62}\/cryptoKeys\/[A-Za-z][A-Za-z0-9_-]{0,62}$/;
const HASH = /^[a-f0-9]{64}$/;
const TOKEN = /^[\x21-\x7e]{8,4096}$/;
const CIPHERTEXT = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;
const FORMAT_VERSION = 1;
const kmsScope = 'https://www.googleapis.com/auth/cloud-platform';
const digest = value => createHash('sha256').update(value).digest('hex');

export const CLOUD_TOKEN_ESCROW_COLLECTIONS = Object.freeze({
    live: 'gallipickProductionBillingTokenEscrows',
    'license-test': 'gallipickProductionLicenseBillingTokenEscrows',
});

export function tokenEscrowDocumentPath(mode, tokenHash) {
    const collection = CLOUD_TOKEN_ESCROW_COLLECTIONS[mode];
    if (!Object.hasOwn(CLOUD_TOKEN_ESCROW_COLLECTIONS, mode) ||
        !HASH.test(tokenHash ?? '')) {
        throw new BackupError('invalid-argument', 'An approved scoped token hash is required.');
    }
    return `${collection}/${tokenHash}`;
}

function context(owner, mode, tokenHash) {
    if (!HASH.test(owner ?? '') || !HASH.test(tokenHash ?? '') ||
        !Object.hasOwn(CLOUD_TOKEN_ESCROW_COLLECTIONS, mode)) {
        throw new BackupError('failed-precondition', 'Owner-scoped token encryption requires verified billing identity.');
    }
    // AEAD means copying ciphertext to a different account, purchase or
    // license-test/live environment never authorizes its decryption.
    return Buffer.from(`gallipick:play-token-escrow:v1:${PROJECT}:${mode}:${owner}:${tokenHash}`).toString('base64');
}

function validatedCiphertext(value) {
    return typeof value === 'string' && value.length >= 24 &&
        value.length <= 24_576 && CIPHERTEXT.test(value) &&
        Buffer.from(value, 'base64').toString('base64') === value;
}

export function createKmsPurchaseTokenEscrow({
    project, mode, keyResource,
    getClient = () => new GoogleAuth({ scopes: [kmsScope] }).getClient(),
}) {
    if (project !== PROJECT || !Object.hasOwn(CLOUD_TOKEN_ESCROW_COLLECTIONS, mode) ||
        typeof keyResource !== 'string' || !KEY_RESOURCE.test(keyResource) ||
        typeof getClient !== 'function') {
        throw new BackupError('failed-precondition', 'A production Cloud KMS encryption key is required.');
    }
    let client;
    async function request(action, data) {
        try {
            client ??= Promise.resolve().then(getClient).catch(error => {
                client = null;
                throw error;
            });
            const response = await (await client).request({
                method: 'POST',
                url: `https://cloudkms.googleapis.com/v1/${keyResource}:${action}`,
                data,
                timeout: 10_000,
            });
            return response?.data;
        } catch {
            // Never forward provider exceptions: they may embed raw request bodies.
            throw new BackupError('unavailable', 'The protected Play token cannot be processed.');
        }
    }

    return {
        mode,
        async encrypt({ purchaseToken, owner, tokenHash }) {
            if (typeof purchaseToken !== 'string' || !TOKEN.test(purchaseToken) ||
                digest(purchaseToken) !== tokenHash) {
                throw new BackupError('failed-precondition', 'A verified purchase token is required.');
            }
            const aad = context(owner, mode, tokenHash);
            const result = await request('encrypt', {
                plaintext: Buffer.from(purchaseToken, 'utf8').toString('base64'),
                additionalAuthenticatedData: aad,
            });
            if (!validatedCiphertext(result?.ciphertext)) {
                throw new BackupError('unavailable', 'Cloud KMS did not return protected ciphertext.');
            }
            return {
                formatVersion: FORMAT_VERSION, owner, mode, tokenHash, keyResource,
                ciphertext: result.ciphertext,
            };
        },
        async decrypt({ record, owner, tokenHash }) {
            const aad = context(owner, mode, tokenHash);
            if (!record || record.formatVersion !== FORMAT_VERSION ||
                record.owner !== owner || record.mode !== mode ||
                record.tokenHash !== tokenHash || record.keyResource !== keyResource ||
                !validatedCiphertext(record.ciphertext)) {
                throw new BackupError('failed-precondition', 'The stored token binding is not trusted.');
            }
            const result = await request('decrypt', {
                ciphertext: record.ciphertext,
                additionalAuthenticatedData: aad,
            });
            const b64 = result?.plaintext;
            if (typeof b64 !== 'string' || b64.length > 5_500 ||
                !CIPHERTEXT.test(b64) || Buffer.from(b64, 'base64').toString('base64') !== b64) {
                throw new BackupError('failed-precondition', 'The protected purchase token could not be verified.');
            }
            const token = Buffer.from(b64, 'base64').toString('utf8');
            if (!TOKEN.test(token) || digest(token) !== tokenHash) {
                throw new BackupError('failed-precondition', 'The stored purchase token hash does not match.');
            }
            // Only return to trusted server callers, never a Cloud Function response.
            return token;
        },
    };
}
