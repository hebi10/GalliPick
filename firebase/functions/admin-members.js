import { createHash } from 'node:crypto';
import { BackupError } from './backup.js';
import { PRODUCTION_PROJECT } from './cloud-billing.js';
import { cloudAccountAccess } from './cloud-retention-policy.js';
import { productionMonetizationCatalog, verifiedMonetizationBenefits } from './monetization-entitlements.js';
import { adminAllowlist, authorizeGalliPickAdmin } from './admin-overview.js';

export const ADMIN_MEMBERS_PAGE_SIZE = 15;
const PAGE_TOKEN_MAX_LENGTH = 2048;
const MAX_TIME = Number.MAX_SAFE_INTEGER;
const catalog = productionMonetizationCatalog(true);
const hash = value => createHash('sha256').update(value).digest('hex');
const validTimestamp = value => Number.isSafeInteger(value) && value >= 0 && value <= MAX_TIME;

function validateInput(input) {
    if (input === undefined || input === null) return null;
    if (typeof input !== 'object' || Array.isArray(input) ||
        Object.keys(input).some(key => key !== 'pageToken')) {
        throw new BackupError('invalid-argument', 'Only an issued member page token is accepted.');
    }
    const token = input.pageToken;
    if (token == null) return null;
    if (typeof token !== 'string' || token.length < 1 || token.length > PAGE_TOKEN_MAX_LENGTH ||
        /\s|[\u0000-\u001f\u007f]/.test(token)) {
        throw new BackupError('invalid-argument', 'The member pagination token is invalid.');
    }
    return token;
}

export function maskedAdminEmail(email) {
    if (typeof email !== 'string' || email.length > 254) return null;
    const at = email.lastIndexOf('@');
    if (at < 1 || at >= email.length - 1) return null;
    const name = email.slice(0, at);
    const domain = email.slice(at + 1);
    if (!/^[a-zA-Z0-9][a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$/.test(domain)) return null;
    return `${name.slice(0, Math.min(2, name.length))}***@${domain}`;
}

function optionalTime(value) {
    if (value == null) return null;
    const millis = Date.parse(value);
    return validTimestamp(millis) ? millis : null;
}

function verifiedTime(value, field) {
    if (value == null) return null;
    if (!validTimestamp(value)) {
        throw new BackupError('failed-precondition', `Invalid ${field} in a server account record.`);
    }
    return value;
}

function safeUsage(record, mode) {
    if (record === undefined) return null;
    const bytes = record?.allocatedBytes;
    if (!Number.isSafeInteger(bytes) || bytes < 0) {
        throw new BackupError('failed-precondition', `The ${mode} Cloud allocation requires reconciliation.`);
    }
    return bytes;
}

function serverVerifiedAt(record, nowMs) {
    const candidates = Object.values(record?.entitlements ?? {}).flatMap(entry =>
        validTimestamp(entry?.checkedAtMs) && entry.checkedAtMs <= nowMs
            ? [entry.checkedAtMs] : []);
    const time = record?.cloudLifecycle?.checkedAtMs;
    if (validTimestamp(time) && time <= nowMs) candidates.push(time);
    return candidates.length ? Math.max(...candidates) : null;
}

export function verifiedAdminSubscription({ mode, billing, usage, nowMs }) {
    if (!['live', 'license-test'].includes(mode) || !validTimestamp(nowMs)) {
        throw new BackupError('failed-precondition', 'A verified subscription environment is required.');
    }
    const entitlements = billing?.entitlements;
    if (entitlements != null && (typeof entitlements !== 'object' || Array.isArray(entitlements))) {
        throw new BackupError('failed-precondition', 'Invalid verified entitlement record.');
    }
    const benefits = verifiedMonetizationBenefits(Object.values(entitlements ?? {}), catalog, nowMs);
    const historical = billing?.cloudLifecycle;
    const previousExpiry = verifiedTime(historical?.expiresAtMs, 'Cloud paid-through');
    const recovery = historical?.recoveryAllowed === true;
    let lifecycleState = 'NO_VERIFIED_CLOUD_EXPIRY';
    let recoveryEndsAtMs = null;
    let deletionDueAtMs = null;
    try {
        const lifecycle = cloudAccountAccess({
            nowMs,
            activeCloudExpiryMs: benefits.cloudExpiresAtMs,
            lastVerifiedCloudExpiryMs: previousExpiry,
            recoveryAllowed: recovery,
        });
        lifecycleState = lifecycle.state;
        recoveryEndsAtMs = lifecycle.recoveryEndsAtMs;
        deletionDueAtMs = lifecycle.deletionDueAtMs;
    } catch {
        throw new BackupError('failed-precondition', 'Cloud subscription dates need reconciliation.');
    }
    return {
        mode,
        subscriptionStatus: benefits.cloudProductId ? 'VERIFIED_CLOUD'
            : benefits.adFreeProductId ? 'VERIFIED_AD_FREE' : 'NO_VERIFIED_ACTIVE_PRODUCT',
        productId: benefits.cloudProductId ?? benefits.adFreeProductId,
        basePlanId: benefits.cloudBasePlanId ?? benefits.adFreeBasePlanId,
        paidThroughAtMs: benefits.cloudExpiresAtMs ?? benefits.adFreeExpiresAtMs,
        quotaBytes: benefits.quotaBytes,
        usedBytes: safeUsage(usage, mode),
        lastVerifiedAtMs: serverVerifiedAt(billing, nowMs),
        lifecycleState,
        recoveryEndsAtMs,
        deletionDueAtMs,
        cloudLastPaidThroughAtMs: previousExpiry,
    };
}

function statusForExit(record) {
    if (!record) return 'NONE';
    const validScopes = new Set(['ACCOUNT', 'CLOUD']);
    const validStatuses = new Set(['REQUESTED', 'IN_PROGRESS', 'RUNNING', 'RETRY',
        'MANUAL_REVIEW', 'COMPLETED', 'RESUMED']);
    return validScopes.has(record.scope) && validStatuses.has(record.status)
        ? `${record.scope}:${record.status}` : 'REQUIRES_REVIEW';
}

export function createAdminMembersService({
    db, authAdmin, project, allowedUidHashes, now = Date.now,
}) {
    if (project !== PRODUCTION_PROJECT || !db || typeof db.doc !== 'function' ||
        !authAdmin || typeof authAdmin.listUsers !== 'function') {
        throw new BackupError('failed-precondition', 'GalliPick member listing requires production Admin SDK.');
    }
    const allowed = adminAllowlist(allowedUidHashes);

    async function list(auth, input) {
        const timestamp = now();
        authorizeGalliPickAdmin(auth, allowed, timestamp);
        const pageToken = validateInput(input);
        const page = await authAdmin.listUsers(ADMIN_MEMBERS_PAGE_SIZE, pageToken ?? undefined);
        if (!page || !Array.isArray(page.users) || page.users.length > ADMIN_MEMBERS_PAGE_SIZE ||
            (page.pageToken !== undefined && (typeof page.pageToken !== 'string' ||
                page.pageToken.length > PAGE_TOKEN_MAX_LENGTH))) {
            throw new BackupError('failed-precondition', 'GalliPick account pagination is invalid.');
        }
        const members = await Promise.all(page.users.map(async user => {
            if (typeof user?.uid !== 'string' || !user.uid || user.uid.length > 128) {
                throw new BackupError('failed-precondition', 'Invalid member identity from Firebase Authentication.');
            }
            const owner = hash(user.uid);
            const refs = [
                `gallipickProductionBillingAccounts/${owner}`,
                `gallipickProductionLicenseBillingAccounts/${owner}`,
                `gallipickProductionBackupAccounts/${owner}`,
                `gallipickProductionLicenseBackupAccounts/${owner}`,
                `gallipickAccountExitRequests/${owner}`,
            ];
            const [liveBilling, licenseBilling, liveUsage, licenseUsage, exit] =
                await Promise.all(refs.map(path => db.doc(path).get()));
            return {
                ownerId: owner,
                emailMasked: maskedAdminEmail(user.email),
                disabled: user.disabled === true,
                createdAtMs: optionalTime(user.metadata?.creationTime),
                lastSignInAtMs: optionalTime(user.metadata?.lastSignInTime),
                exitStatus: statusForExit(exit.data()),
                plans: [
                    verifiedAdminSubscription({ mode: 'live', billing: liveBilling.data(),
                        usage: liveUsage.data(), nowMs: timestamp }),
                    verifiedAdminSubscription({ mode: 'license-test', billing: licenseBilling.data(),
                        usage: licenseUsage.data(), nowMs: timestamp }),
                ],
            };
        }));
        return {
            checkedAtMs: timestamp,
            members,
            nextPageToken: page.pageToken ?? null,
            pageSize: ADMIN_MEMBERS_PAGE_SIZE,
        };
    }

    return { list };
}
