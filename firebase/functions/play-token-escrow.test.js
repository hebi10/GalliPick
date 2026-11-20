import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { BackupError } from './backup.js';
import { createKmsPurchaseTokenEscrow, tokenEscrowDocumentPath,
    CLOUD_TOKEN_ESCROW_COLLECTIONS } from './play-token-escrow.js';

const keyResource = 'projects/gallipick-production/locations/asia-northeast3/keyRings/gallipick-billing/cryptoKeys/subscription-token';
const hash = value => createHash('sha256').update(value).digest('hex');
const owner = hash('customer-a');
const anotherOwner = hash('customer-b');
const token = 'synthetic-Play-purchase-token-12345';
const tokenHash = hash(token);
const codeIs = code => error => error instanceof BackupError && error.code === code;

function kmsFixture() {
    const records = new Map();
    const calls = [];
    const client = {
        async request(options) {
            calls.push(options);
            assert.equal(options.method, 'POST');
            assert.equal(options.timeout, 10_000);
            assert.ok(options.url.startsWith('https://cloudkms.googleapis.com/v1/'));
            if (options.url.endsWith(':encrypt')) {
                const ciphertext = Buffer.from(`opaque-kms-ciphertext-${calls.length}-only`).toString('base64');
                records.set(ciphertext, { ...options.data });
                return { data: { ciphertext } };
            }
            if (options.url.endsWith(':decrypt')) {
                const encrypted = records.get(options.data.ciphertext);
                if (!encrypted || encrypted.additionalAuthenticatedData !== options.data.additionalAuthenticatedData) {
                    throw new Error('provider error including sensitive data must never escape');
                }
                return { data: { plaintext: encrypted.plaintext } };
            }
            throw new Error('unexpected KMS endpoint');
        },
    };
    const escrow = createKmsPurchaseTokenEscrow({
        project: 'gallipick-production', mode: 'live', keyResource,
        getClient: async () => client,
    });
    return { escrow, calls, records };
}

test('a production-only, mode-bound KMS key resource is mandatory', () => {
    assert.equal(tokenEscrowDocumentPath('live', tokenHash),
        `gallipickProductionBillingTokenEscrows/${tokenHash}`);
    assert.equal(tokenEscrowDocumentPath('license-test', tokenHash),
        `gallipickProductionLicenseBillingTokenEscrows/${tokenHash}`);
    assert.deepEqual(Object.keys(CLOUD_TOKEN_ESCROW_COLLECTIONS), ['live', 'license-test']);
    assert.throws(() => tokenEscrowDocumentPath('dev', tokenHash), codeIs('invalid-argument'));
    assert.throws(() => createKmsPurchaseTokenEscrow({
        project: 'gallipick-dev', mode: 'live', keyResource,
    }), codeIs('failed-precondition'));
    assert.throws(() => createKmsPurchaseTokenEscrow({
        project: 'gallipick-production', mode: 'dev', keyResource,
    }), codeIs('failed-precondition'));
    assert.throws(() => createKmsPurchaseTokenEscrow({
        project: 'gallipick-production', mode: 'live', keyResource: 'projects/other/locations/global/keyRings/a/cryptoKeys/b',
    }), codeIs('failed-precondition'));
    assert.throws(() => createKmsPurchaseTokenEscrow({
        project: 'gallipick-production', mode: 'live', keyResource: '',
    }), codeIs('failed-precondition'));
});

test('Cloud KMS binds token encryption to purchase hash, owner and live-mode AAD', async () => {
    const { escrow, calls } = kmsFixture();
    const encrypted = await escrow.encrypt({ purchaseToken: token, owner, tokenHash });
    assert.equal(encrypted.owner, owner);
    assert.equal(encrypted.mode, 'live');
    assert.equal(encrypted.tokenHash, tokenHash);
    assert.equal(encrypted.keyResource, keyResource);
    assert.equal(encrypted.formatVersion, 1);
    assert.notEqual(encrypted.ciphertext, token);
    assert.equal(JSON.stringify(encrypted).includes(token), false);
    const aad = Buffer.from(calls[0].data.additionalAuthenticatedData, 'base64').toString('utf8');
    assert.ok(aad.includes(owner));
    assert.ok(aad.includes(tokenHash));
    assert.ok(aad.includes(':live:'));
    assert.equal(await escrow.decrypt({ record: encrypted, owner, tokenHash }), token);
    assert.equal(calls.length, 2);
    assert.equal(calls[0].data.plaintext, Buffer.from(token).toString('base64'));
});

test('a copied or tampered escrow cannot be decrypted for a different owner, mode or token', async () => {
    const { escrow } = kmsFixture();
    const record = await escrow.encrypt({ purchaseToken: token, owner, tokenHash });
    for (const payload of [
        { record, owner: anotherOwner, tokenHash },
        { record, owner, tokenHash: hash('some-other-token') },
        { record: { ...record, mode: 'license-test' }, owner, tokenHash },
        { record: { ...record, formatVersion: 2 }, owner, tokenHash },
        { record: { ...record, keyResource: 'unapproved-key' }, owner, tokenHash },
        { record: { ...record, ciphertext: 'purchase-token-secret' }, owner, tokenHash },
        { record: { ...record, ciphertext: '' }, owner, tokenHash },
    ]) {
        await assert.rejects(escrow.decrypt(payload), codeIs('failed-precondition'));
    }
    const testMode = createKmsPurchaseTokenEscrow({
        project: 'gallipick-production', mode: 'license-test', keyResource,
        getClient: async () => ({ request: async () => { throw new Error('must not call KMS'); } }),
    });
    await assert.rejects(testMode.decrypt({ record, owner, tokenHash }), codeIs('failed-precondition'));
    await assert.rejects(escrow.encrypt({ purchaseToken: token, owner, tokenHash: hash('wrong') }),
        codeIs('failed-precondition'));
});

test('KMS provider failures are sanitized, never leak raw tokens or untrusted error messages', async () => {
    const escrow = createKmsPurchaseTokenEscrow({
        project: 'gallipick-production', mode: 'live', keyResource,
        getClient: async () => ({ request: async () => {
            throw new Error('provider returned original-token=' + token);
        } }),
    });
    let message = '';
    await assert.rejects(escrow.encrypt({ purchaseToken: token, owner, tokenHash }), error => {
        message = error.message;
        return codeIs('unavailable')(error);
    });
    assert.equal(message.includes(token), false);
    const invalid = createKmsPurchaseTokenEscrow({
        project: 'gallipick-production', mode: 'live', keyResource,
        getClient: async () => ({ request: async () => ({ data: { ciphertext: token } }) }),
    });
    await assert.rejects(invalid.encrypt({ purchaseToken: token, owner, tokenHash }),
        codeIs('unavailable'));
});
