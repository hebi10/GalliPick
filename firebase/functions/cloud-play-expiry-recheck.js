import { BackupError } from './backup.js';
import { CLOUD_PURGE_WINDOW_MS } from './cloud-retention-policy.js';
import { cloudPurgeReview, CLOUD_PURGE_REVIEW_COLLECTION, CLOUD_PURGE_SOURCES } from './cloud-expiry-purge-review.js';
import { trustedManualGrant, manualGrantDocumentId, ADMIN_MANUAL_GRANTS_COLLECTION } from './admin-manual-policy.js';
import { PRODUCTION_PROJECT } from './cloud-billing.js';

const OWNER = /^[a-f0-9]{64}$/;
const HASH = /^[a-f0-9]{64}$/;
const validTime = value => Number.isSafeInteger(value) && value >= 0;
const SCOPES = Object.freeze({
    live: 'gallipickProductionBilling',
    'license-test': 'gallipickProductionLicenseBilling',
});
const hold = status => ({ status, deletionAllowed: false });

export function evaluateFreshPlayExpiry({
    owner, mode, candidate, billing, otherBilling, exit, liveManual, licenseManual, play, nowMs,
}) {
    if (!OWNER.test(owner ?? '') || !Object.hasOwn(SCOPES, mode) ||
        !validTime(nowMs) || !candidate || candidate.status !== 'NEEDS_PLAY_RECHECK' ||
        candidate.owner !== owner || candidate.sourceScope !== SCOPES[mode] ||
        !HASH.test(candidate.purchaseTokenHash ?? '') ||
        !validTime(candidate.verifiedExpiryAtMs) || !validTime(candidate.deletionDueAtMs) ||
        !Number.isSafeInteger(candidate.billingRevision)) {
        return hold('INCOMPLETE_EVIDENCE');
    }
    if (billing?.revision !== candidate.billingRevision ||
        billing?.cloudLifecycle?.tokenHash !== candidate.purchaseTokenHash ||
        billing?.cloudLifecycle?.expiresAtMs !== candidate.verifiedExpiryAtMs ||
        candidate.deletionDueAtMs !== candidate.verifiedExpiryAtMs + CLOUD_PURGE_WINDOW_MS) {
        return hold('STALE_BILLING_REVISION');
    }

    let decision;
    try {
        decision = cloudPurgeReview({
            owner, mode, billing, otherBilling, accountExit: exit, nowMs,
        });
    } catch {
        return hold('INCOMPLETE_EVIDENCE');
    }
    if (decision.status !== 'NEEDS_PLAY_RECHECK') return hold(decision.status);
    if ([
        trustedManualGrant(liveManual, nowMs, owner, 'live'),
        trustedManualGrant(licenseManual, nowMs, owner, 'license-test'),
    ].some(grant => grant?.mayReadCloud || grant?.mayUploadCloud)) {
        return hold('ACTIVE_MANUAL_ACCESS');
    }
    if (!play) return hold('PLAY_RECHECK_UNAVAILABLE');
    if (play?.externalAccountIdentifiers?.obfuscatedExternalAccountId !== owner)
        return hold('PLAY_OWNERSHIP_UNVERIFIED');
    const licenseReceipt = play.testPurchase && typeof play.testPurchase === 'object' &&
        !Array.isArray(play.testPurchase);
    if ((mode === 'live' && play.testPurchase != null) ||
        (mode === 'license-test' && !licenseReceipt)) {
        return hold('PLAY_MODE_MISMATCH');
    }
    if (play.subscriptionState !== 'SUBSCRIPTION_STATE_EXPIRED') {
        return hold('PLAY_NOT_VERIFIED_EXPIRED');
    }
    const items = play.lineItems;
    if (!Array.isArray(items) || items.length !== 1) return hold('PLAY_LINEITEMS_UNVERIFIED');
    const item = items[0];
    if (item?.productId !== billing?.cloudLifecycle?.productId ||
        item?.offerDetails?.basePlanId !== billing?.cloudLifecycle?.basePlanId) {
        return hold('PLAY_PRODUCT_CHANGED');
    }
    const expiry = typeof item.expiryTime === 'string' ? Date.parse(item.expiryTime) : NaN;
    if (!validTime(expiry)) return hold('PLAY_EXPIRY_UNVERIFIED');
    if (expiry > nowMs - CLOUD_PURGE_WINDOW_MS) return hold('PLAY_RETENTION_NOT_COMPLETE');
    // Evidence only. Even a confirmed expiry is NEVER a deletion authorization.
    return {
        status: 'PLAY_EXPIRED_CONFIRMED', deletionAllowed: false,
        checkedAtMs: nowMs, verifiedPlayExpiryAtMs: expiry,
    };
}

export function createCloudPlayExpiryRecheckService({
    db, play, vaults, project, now = Date.now,
}) {
    if (project !== PRODUCTION_PROJECT || !db || typeof db.collection !== 'function' ||
        typeof db.doc !== 'function' || !play || typeof play.get !== 'function' ||
        !vaults || !CLOUD_PURGE_SOURCES.every(item =>
            vaults[item.mode] && typeof vaults[item.mode].decrypt === 'function')) {
        throw new BackupError('failed-precondition', 'Rechecking Cloud expiry requires production-scoped Play and KMS.');
    }

    async function review({ maximumPerMode = 3 } = {}) {
        if (!Number.isSafeInteger(maximumPerMode) || maximumPerMode < 1 || maximumPerMode > 5) {
            throw new BackupError('invalid-argument', 'Play recheck batches must be small.');
        }
        const timestamp = now();
        if (!validTime(timestamp)) throw new BackupError('failed-precondition', 'Invalid server time.');
        const results = [];
        for (const { scope, mode } of CLOUD_PURGE_SOURCES) {
            const list = await db.collection(CLOUD_PURGE_REVIEW_COLLECTION)
                .where('status', '==', 'NEEDS_PLAY_RECHECK')
                .where('sourceScope', '==', scope).limit(maximumPerMode).get();
            for (const document of list.docs) {
                const candidate = document.data();
                const owner = candidate?.owner;
                if (!OWNER.test(owner ?? '') || document.id !== `${scope}-${owner}`) {
                    results.push(hold('INCOMPLETE_EVIDENCE'));
                    continue;
                }
                const otherScope = mode === 'live' ? SCOPES['license-test'] : SCOPES.live;
                const tokenHash = candidate.purchaseTokenHash;
                if (!HASH.test(tokenHash ?? '')) {
                    results.push(hold('INCOMPLETE_EVIDENCE'));
                    continue;
                }
                const paths = [
                    `${scope}Accounts/${owner}`,
                    `${otherScope}Accounts/${owner}`,
                    `gallipickAccountExitRequests/${owner}`,
                    `${ADMIN_MANUAL_GRANTS_COLLECTION}/${manualGrantDocumentId(owner, 'live')}`,
                    `${ADMIN_MANUAL_GRANTS_COLLECTION}/${manualGrantDocumentId(owner, 'license-test')}`,
                    `${scope}Tokens/${tokenHash}`,
                    `${scope}TokenEscrows/${tokenHash}`,
                ];
                const docs = await Promise.all(paths.map(path => db.doc(path).get()));
                const [billing, otherBilling, exit, liveManual, licenseManual, binding, escrow] =
                    docs.map(snapshot => snapshot.data());
                const args = {
                    owner, mode, candidate, billing, otherBilling, exit, liveManual,
                    licenseManual, nowMs: timestamp,
                };
                const preflight = evaluateFreshPlayExpiry(args);
                if (preflight.status !== 'PLAY_RECHECK_UNAVAILABLE') {
                    results.push(preflight);
                    continue;
                }
                if (binding?.owner !== owner || binding.retiredBy || binding.invalidated ||
                    escrow?.owner !== owner || escrow?.tokenHash !== tokenHash ||
                    escrow?.mode !== mode) {
                    results.push(hold('TOKEN_ESCROW_MISSING_OR_INVALID'));
                    continue;
                }
                try {
                    // Decrypted token exists only in trusted server memory during this call.
                    const purchaseToken = await vaults[mode].decrypt({ record: escrow, owner, tokenHash });
                    const playRecord = await play.get(purchaseToken);
                    results.push(evaluateFreshPlayExpiry({ ...args, play: playRecord }));
                } catch {
                    // Errors from Play/KMS can contain purchase token in provider URLs.
                    results.push(hold('PLAY_RECHECK_UNAVAILABLE'));
                }
            }
        }
        return {
            checkedAtMs: timestamp, scanned: results.length,
            statuses: Object.fromEntries([...new Set(results.map(item => item.status))].map(status =>
                [status, results.filter(item => item.status === status).length])),
            deletionExecuted: false, deletionAllowed: false,
        };
    }

    return { review };
}
