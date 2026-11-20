import { createHash } from 'node:crypto';
import { authorize, BackupError, createRequestLimiter, PILOT_MAX_OWNER_FILES, PRODUCTION_BACKUP_PROTOCOL_VERSION,
    PRODUCTION_MAX_FILE_BYTES, PRODUCTION_MAX_OWNER_FILES } from './backup.js';
import { productionMonetizationCatalog, productionNewSaleCatalog,
    verifiedMonetizationBenefits } from './monetization-entitlements.js';
import { cloudAccountAccess } from './cloud-retention-policy.js';
import { ADMIN_MANUAL_GRANTS_COLLECTION, manualGrantDocumentId,
    trustedManualGrant } from './admin-manual-policy.js';

export const PILOT_ACCOUNT_QUOTA_BYTES = 1024 ** 3;
export const PLAY_BILLING_PACKAGE = 'com.hebi.gallipick';
export const PRODUCTION_PROJECT = 'gallipick-production';
const MAX_CURRENT_TOKENS = 20;
// Existing Cloud annual contracts remain valid; 2026-11-20 00:00 KST onward is new-sale blocked.
const CLOUD_ANNUAL_LEGACY_CUTOFF_MS = Date.parse('2026-11-20T00:00:00+09:00');
const HASH = /^[a-f0-9]{64}$/;
const PRODUCT_ID = /^[a-z0-9][a-z0-9_.]{0,149}$/;
const BASE_PLAN_ID = /^[a-z0-9][a-z0-9-]{0,62}$/;
const PURCHASE_TOKEN = /^[\x21-\x7e]{8,4096}$/;
const TEST_EMAIL = /^[a-z0-9]+(?:[._+-][a-z0-9]+)*@[a-z0-9]+(?:[.-][a-z0-9]+)*\.[a-z]{2,63}$/;
const ACCEPTED_SUBSCRIPTION_STATES = new Set(['SUBSCRIPTION_STATE_ACTIVE', 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD',
    'SUBSCRIPTION_STATE_CANCELED']);
const INACTIVE_SUBSCRIPTION_STATES = new Set(['SUBSCRIPTION_STATE_PAUSED', 'SUBSCRIPTION_STATE_ON_HOLD',
    'SUBSCRIPTION_STATE_EXPIRED']);
const UNCOMPLETED_SUBSCRIPTION_STATES = new Set(['SUBSCRIPTION_STATE_PENDING', 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED']);
const ACK_PENDING = 'ACKNOWLEDGEMENT_STATE_PENDING';
const ACK_COMPLETE = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED';
const hash = value => createHash('sha256').update(value).digest('hex');

export function billingSettings(project, env) {
    if (project !== 'gallipick-dev' || env.GALLIPICK_BILLING_ENABLED !== 'true') return { enabled: false, products: [] };
    try {
        const raw = JSON.parse(env.GALLIPICK_BILLING_TEST_PRODUCTS);
        if (!Array.isArray(raw) || raw.length < 1 || raw.length > 2) throw new Error('invalid catalog');
        const products = raw.map(({ productId, basePlanId, quotaBytes }) => {
            if (typeof productId !== 'string' || !PRODUCT_ID.test(productId) || typeof basePlanId !== 'string'
                || !BASE_PLAN_ID.test(basePlanId) || !Number.isSafeInteger(quotaBytes) || quotaBytes <= 0
                || quotaBytes > PILOT_ACCOUNT_QUOTA_BYTES) throw new Error('invalid plan');
            return { productId, basePlanId, quotaBytes };
        });
        if (new Set(products.map(plan => plan.productId)).size !== products.length) throw new Error('duplicate product');
        return { enabled: true, products };
    } catch {
        throw new BackupError('failed-precondition', 'The billing pilot product configuration is invalid.');
    }
}

export function productionBillingSettings(project, env) {
    if (project !== PRODUCTION_PROJECT || env.GALLIPICK_PRODUCTION_AD_FREE_ENABLED !== 'true') {
        return { enabled: false, products: [], mode: 'disabled', users: new Set(), emails: new Set(),
            cloudEnabled: false, retentionEnabled: false, manualGrantsEnabled: false,
            maxOriginalBytes: null, backupNamespace: null };
    }
    const mode = env.GALLIPICK_PRODUCTION_BILLING_MODE;
    const users = new Set((env.GALLIPICK_PRODUCTION_BILLING_TEST_UIDS ?? '').split(',')
        .map(uid => uid.trim()).filter(Boolean));
    const emails = new Set((env.GALLIPICK_PRODUCTION_BILLING_TEST_EMAILS ?? '').split(',')
        .map(email => email.trim()).filter(Boolean));
    const licenseAudienceSize = users.size + emails.size;
    if (!['license-test', 'live'].includes(mode) || (mode === 'license-test' && licenseAudienceSize === 0)
        || (mode === 'live' && licenseAudienceSize !== 0) || licenseAudienceSize > 10
        || [...users].some(uid => !/^[A-Za-z0-9_-]{1,128}$/.test(uid))
        || [...emails].some(email => email.length > 254 || email.indexOf('@') > 64 || !TEST_EMAIL.test(email))) {
        throw new BackupError('failed-precondition', 'The production billing configuration is invalid.');
    }
    const configuredLimit = env.GALLIPICK_PRODUCTION_CLOUD_MAX_ORIGINAL_BYTES;
    const fileLimit = /^[1-9][0-9]{0,8}$/.test(configuredLimit ?? '') ? Number(configuredLimit) : 0;
    const cloudEnabled = env.GALLIPICK_PRODUCTION_CLOUD_ENABLED === 'true'
        && (mode === 'license-test' || env.GALLIPICK_PRODUCTION_CLOUD_LIVE_ENABLED === 'true')
        && fileLimit > 0 && fileLimit <= PRODUCTION_MAX_FILE_BYTES;
    const manualGrantsEnabled = env.GALLIPICK_PRODUCTION_MANUAL_GRANTS_ENABLED === 'true';
    if (manualGrantsEnabled && cloudEnabled &&
        env.GALLIPICK_PRODUCTION_CLOUD_RETENTION_ENABLED !== 'true') {
        throw new BackupError('failed-precondition',
            'Manual Cloud benefits require the verified seven-day retention policy.');
    }
    const backupNamespace = mode === 'live' ? 'gallipickProductionBackup' : 'gallipickProductionLicenseBackup';
    const verificationProducts = productionMonetizationCatalog(true);
    return { enabled: true, products: productionNewSaleCatalog(cloudEnabled),
        verificationProducts, mode, users, emails, cloudEnabled,
        retentionEnabled: cloudEnabled && env.GALLIPICK_PRODUCTION_CLOUD_RETENTION_ENABLED === 'true',
        manualGrantsEnabled,
        maxOriginalBytes: cloudEnabled ? fileLimit : null, backupNamespace,
        replacementCheckoutEnabled: cloudEnabled && env.GALLIPICK_PRODUCTION_CLOUD_UPLOADS_PAUSED === 'false' };
}

function purchaseLine(purchase, settings, now) {
    const items = purchase.lineItems;
    const products = settings.verificationProducts ?? settings.products;
    const invalid = () => new BackupError('failed-precondition', 'The purchase does not match an enabled subscription plan.');
    if (!Array.isArray(items) || (items.length !== 1 &&
        (!settings.verificationProducts || items.length !== 2 || !purchase.linkedPurchaseToken))) throw invalid();
    const lines = items.map(item => {
        const plan = products.find(candidate => candidate.productId === item?.productId
            && candidate.basePlanId === item.offerDetails?.basePlanId);
        const knownProduct = products.some(candidate => candidate.productId === item?.productId);
        const expiresAtMs = Date.parse(item?.expiryTime);
        if (!knownProduct || item.prepaidPlan || item.deferredItemRemoval != null) throw invalid();
        return { item, plan, expiresAtMs };
    });
    const verifiedLine = line => {
        if (!line?.plan || !line.item.autoRenewingPlan) throw invalid();
        return line;
    };
    if (lines.length === 1) return verifiedLine(lines[0]);

    // Play lists the old entitlement and its scheduled replacement on the same token.
    const scheduled = lines.find(({ item }) => item.expiryTime == null);
    if (scheduled) {
        const current = verifiedLine(lines.find(line => line !== scheduled));
        if (!Number.isFinite(current.expiresAtMs)
            || current.item.deferredItemReplacement?.productId !== scheduled.item.productId
            || scheduled.item.deferredItemReplacement != null
            || (current.plan === scheduled.plan)) throw invalid();
        const scheduledReplacement = scheduled.plan ? { productId: scheduled.plan.productId,
            basePlanId: scheduled.plan.basePlanId, effectiveAtMs: current.expiresAtMs } : null;
        return { ...current, scheduledReplacement, purchasedProductId: scheduled.item.productId };
    }
    if (lines.some(({ item, expiresAtMs }) => item.deferredItemReplacement != null || !Number.isFinite(expiresAtMs))) {
        throw invalid();
    }
    const effective = lines.filter(line => line.expiresAtMs > now);
    if (effective.length > 1) throw invalid();
    // The retained historical item is never eligible once a replacement is effective.
    return verifiedLine(effective[0] ?? lines.find(line => line.plan && line.item.autoRenewingPlan));
}

function playRecurringPrice(price) {
    const units = price?.units ?? '0';
    const nanos = price?.nanos ?? 0;
    if (typeof price?.currencyCode !== 'string' || !/^[A-Z]{3}$/.test(price.currencyCode)
        || typeof units !== 'string' || !/^(0|[1-9][0-9]{0,9})$/.test(units)
        || !Number.isSafeInteger(nanos) || nanos < 0 || nanos >= 1_000_000_000 || nanos % 1_000 !== 0) return null;
    const amountMicros = Number(units) * 1_000_000 + nanos / 1_000;
    if (!Number.isSafeInteger(amountMicros) || amountMicros <= 0) return null;
    return { amountMicros, currencyCode: price.currencyCode };
}

function inspectPurchase(purchase, owner, settings, now) {
    const production = settings.mode !== undefined;
    const isTestPurchase = Boolean(purchase?.testPurchase) && typeof purchase.testPurchase === 'object'
        && !Array.isArray(purchase.testPurchase);
    const validMode = settings.mode === 'live' ? purchase?.testPurchase == null : isTestPurchase;
    const externalOwner = purchase?.externalAccountIdentifiers?.obfuscatedExternalAccountId;
    if (!purchase || typeof purchase !== 'object' || !validMode
        || (production ? externalOwner != null && externalOwner !== owner : externalOwner !== owner)) {
        throw new BackupError('permission-denied', 'The Play purchase is not linked to this account or billing environment.');
    }
    const linked = purchase.linkedPurchaseToken;
    if (linked != null && (typeof linked !== 'string' || !PURCHASE_TOKEN.test(linked))) {
        throw new BackupError('failed-precondition', 'The Play subscription replacement could not be verified.');
    }
    const { item, plan, expiresAtMs, scheduledReplacement, purchasedProductId = plan.productId } =
        purchaseLine(purchase, settings, now);
    const active = ACCEPTED_SUBSCRIPTION_STATES.has(purchase.subscriptionState);
    const uncompleted = UNCOMPLETED_SUBSCRIPTION_STATES.has(purchase.subscriptionState);
    const eligible = active && expiresAtMs > now;
    const acknowledgement = purchase.acknowledgementState;
    if ((!active && !uncompleted && !INACTIVE_SUBSCRIPTION_STATES.has(purchase.subscriptionState))
        || (active && !Number.isFinite(expiresAtMs)) || (eligible && acknowledgement !== ACK_PENDING && acknowledgement !== ACK_COMPLETE)) {
        throw new BackupError('failed-precondition', 'The Play subscription status could not be verified.');
    }
    const canReplace = settings.replacementCheckoutEnabled && eligible && externalOwner === owner
        && purchase.subscriptionState === 'SUBSCRIPTION_STATE_ACTIVE' && acknowledgement === ACK_COMPLETE
        && item.autoRenewingPlan.autoRenewEnabled === true && item.deferredItemReplacement == null;
    const entitlement = eligible ? { ...plan, expiresAtMs, checkedAtMs: now } : null;
    if (entitlement && scheduledReplacement) entitlement.scheduledReplacement = scheduledReplacement;
    return {
        purchasedProductId,
        externalOwner,
        entitlement,
        acknowledge: eligible && acknowledgement === ACK_PENDING,
        linkedHash: !uncompleted && typeof linked === 'string' ? hash(linked) : null,
        replacementSourcePrice: canReplace ? playRecurringPrice(item.autoRenewingPlan.recurringPrice) : null,
        hasAnnualCloud: production && purchase.lineItems.some(line =>
            line?.offerDetails?.basePlanId === 'annual' &&
            settings.verificationProducts?.some(product => product.kind === 'CLOUD' &&
                product.productId === line.productId && product.basePlanId === 'annual')),
        purchaseStartedAtMs: typeof purchase.startTime === 'string' ? Date.parse(purchase.startTime) : NaN,
        cloudEvidence: production && plan.kind === 'CLOUD' && Number.isSafeInteger(expiresAtMs)
            && expiresAtMs >= 0 && !uncompleted ? {
                expiresAtMs, productId: plan.productId, basePlanId: plan.basePlanId,
                recoveryAllowed: active || purchase.subscriptionState === 'SUBSCRIPTION_STATE_EXPIRED',
            } : null,
    };
}

function currentPlan(record, products, now) {
    return Object.values(record?.entitlements ?? {}).flatMap(entitlement => {
        const plan = products.find(candidate => candidate.productId === entitlement?.productId
            && candidate.basePlanId === entitlement?.basePlanId);
        if (!plan || !Number.isSafeInteger(entitlement.expiresAtMs) || entitlement.expiresAtMs <= now
            || !Number.isSafeInteger(entitlement.checkedAtMs) || entitlement.checkedAtMs > now
            || !Number.isSafeInteger(entitlement.quotaBytes)
            || entitlement.quotaBytes <= 0) return [];
        return [{ ...entitlement, quotaBytes: Math.min(plan.quotaBytes, entitlement.quotaBytes, PILOT_ACCOUNT_QUOTA_BYTES) }];
    }).sort((left, right) => right.quotaBytes - left.quotaBytes || right.expiresAtMs - left.expiresAtMs)[0] ?? null;
}

export function createBillingService({ db, play, settings, pilotUsers, now = Date.now }) {
    const production = settings.mode !== undefined;
    const scope = settings.mode === 'live' ? 'gallipickProductionBilling' : production
        ? 'gallipickProductionLicenseBilling' : 'gallipickCloudBilling';
    const ownerRef = owner => db.doc(`${scope}Accounts/${owner}`);
    const tokenRef = tokenHash => db.doc(`${scope}Tokens/${tokenHash}`);
    const backupOwnerPath = owner => `${production ? settings.backupNamespace : 'gallipickBackup'}Accounts/${owner}`;
    const usageRef = owner => db.doc(backupOwnerPath(owner));
    const manualGrantRef = owner => db.doc(`${ADMIN_MANUAL_GRANTS_COLLECTION}/${manualGrantDocumentId(owner, settings.mode)}`);
    const manualBenefit = (record, owner, time = now()) =>
        production && settings.manualGrantsEnabled
            ? trustedManualGrant(record, time, owner, settings.mode) : null;
    const users = production ? settings.users : pilotUsers;
    const emails = settings.emails ?? new Set();
    const allowedOwners = new Set([...(users ?? [])].map(hash));
    const allowedEmailHashes = new Set([...emails].map(hash));
    const countProductionRequest = createRequestLimiter(db, `${scope}RequestLimits`, 120);
    function authorizeAccount(auth) {
        const email = typeof auth?.token?.email === 'string' ? auth.token.email.toLowerCase() : null;
        const licenseEmailHash = settings.mode === 'license-test' && auth?.token?.email_verified === true
            && emails.has(email) ? hash(email) : null;
        const owner = authorize(auth, users, settings.mode === 'live' || settings.mode === 'disabled' || licenseEmailHash !== null);
        return { owner, licenseEmailHash };
    }
    const pausedProductionPlan = { enabled: false, products: [], activeProductId: null,
        activeBasePlanId: null, expiresAtMs: null, adFreeProductId: null, adFreeBasePlanId: null,
        adFreeExpiresAtMs: null, backupAvailable: false, testPurchasesOnly: settings.mode === 'license-test',
        uploadAllowed: false, obfuscatedAccountId: null, quotaBytes: 0, usedBytes: 0,
        scheduledReplacement: null, replacementSourceAllowed: false, replacementSourcePrice: null };
    const trustedProducts = settings.verificationProducts ?? settings.products;
    const productionBenefits = (account, time = now()) =>
        verifiedMonetizationBenefits(Object.values(account?.entitlements ?? {}), trustedProducts, time);

    function lifecycleForAccount(account, evaluatedAt = now()) {
        const paid = productionBenefits(account, evaluatedAt);
        const history = account?.cloudLifecycle;
        const lastExpiry = Number.isSafeInteger(history?.expiresAtMs) && history.expiresAtMs >= 0
            ? history.expiresAtMs : null;
        const requestedAt = Number.isSafeInteger(history?.deletionRequestedAtMs)
            && history.deletionRequestedAtMs >= 0 ? history.deletionRequestedAtMs : null;
        try {
            return cloudAccountAccess({
                nowMs: evaluatedAt,
                activeCloudExpiryMs: paid.cloudExpiresAtMs ?? null,
                lastVerifiedCloudExpiryMs: lastExpiry,
                recoveryAllowed: history?.recoveryAllowed === true,
                deletionRequestedAtMs: requestedAt,
            });
        } catch {
            throw new BackupError('failed-precondition', 'The Cloud retention record needs reconciliation.');
        }
    }

    async function countVerifiedMedia(owner) {
        const maximum = production ? PRODUCTION_MAX_OWNER_FILES : PILOT_MAX_OWNER_FILES;
        try {
            const result = await db.collection(`${backupOwnerPath(owner)}/originals`)
                .where('state', '==', 'VERIFIED').limit(maximum).count().get();
            const count = result.data().count;
            // A result at the limit could be truncated. Leave it out rather than presenting a partial count.
            return Number.isSafeInteger(count) && count >= 0 && count < maximum ? count : null;
        } catch {
            return null;
        }
    }

    async function getPlanForOwner(owner, checkedSource) {
        if (production) {
            if (!settings.enabled) return pausedProductionPlan;
            const [account, usage, exit, manual] = await Promise.all([ownerRef(owner).get(),
                settings.cloudEnabled ? usageRef(owner).get() : null,
                db.doc(`gallipickAccountExitRequests/${owner}`).get(),
                settings.manualGrantsEnabled ? manualGrantRef(owner).get() : null]);
            const current = account.data();
            const exitState = exit.data();
            const mayUpload = !exitState || (exitState.scope === 'CLOUD' && exitState.status === 'RESUMED');
            const evaluatedAt = now();
            const verified = productionBenefits(current, evaluatedAt);
            const manualAccess = manualBenefit(manual?.data(), owner, evaluatedAt);
            const manualCloudBytes = settings.cloudEnabled ? manualAccess?.quotaBytes ?? 0 : 0;
            const manualCanUpload = manualAccess?.mayUploadCloud === true && settings.cloudEnabled;
            const manualCanRead = manualAccess?.mayReadCloud === true && settings.cloudEnabled;
            const lifecycle = settings.retentionEnabled ? lifecycleForAccount(current, evaluatedAt) : null;
            const active = Object.entries(current?.entitlements ?? {}).filter(([key, entitlement]) =>
                HASH.test(key) && Number.isSafeInteger(entitlement?.expiresAtMs) && entitlement.expiresAtMs > evaluatedAt);
            const [activeTokenHash, activeEntitlement] = active.length === 1 && verified.adsRemoved ? active[0] : [];
            const scheduled = activeEntitlement?.scheduledReplacement;
            const scheduledReplacement = scheduled && scheduled.effectiveAtMs === activeEntitlement.expiresAtMs
                && trustedProducts.some(product => product.productId === scheduled.productId
                    && product.basePlanId === scheduled.basePlanId)
                ? { productId: scheduled.productId, basePlanId: scheduled.basePlanId, effectiveAtMs: scheduled.effectiveAtMs }
                : null;
            const replacementSourcePrice = settings.replacementCheckoutEnabled && checkedSource?.price
                && activeTokenHash === checkedSource.tokenHash && current.revision === checkedSource.revision
                && !scheduled ? checkedSource.price : null;
            const usageRecord = usage?.data();
            const usedBytes = usageRecord === undefined ? 0 : usageRecord?.allocatedBytes;
            if (!Number.isSafeInteger(usedBytes) || usedBytes < 0) {
                throw new BackupError('failed-precondition', 'The backup allocation needs reconciliation.');
            }
            const quotaBytes = settings.cloudEnabled ? Math.max(verified.quotaBytes, manualCloudBytes) : 0;
            // An already-paid Cloud subscriber keeps ad removal and is blocked from buying it twice.
            const ownedCloud = verified.cloudProductId
                ? trustedProducts.find(product => product.productId === verified.cloudProductId
                    && product.basePlanId === verified.cloudBasePlanId)
                : null;
            // The current annual Cloud contract may not be sold to new users, but a
            // Play-verified legacy subscriber must still be able to inspect it in Android.
            const legacyOwned = ownedCloud && !settings.products.some(product =>
                product.productId === ownedCloud.productId && product.basePlanId === ownedCloud.basePlanId);
            const products = legacyOwned ? [...settings.products, ownedCloud] : settings.products;
            return { ...pausedProductionPlan, enabled: true, products, obfuscatedAccountId: owner,
                activeProductId: verified.cloudProductId, activeBasePlanId: verified.cloudBasePlanId,
                expiresAtMs: verified.cloudExpiresAtMs, adFreeProductId: verified.adFreeProductId,
                adFreeBasePlanId: verified.adFreeBasePlanId, adFreeExpiresAtMs: verified.adFreeExpiresAtMs,
                backupAvailable: settings.cloudEnabled, quotaBytes, usedBytes,
                uploadAllowed: mayUpload && usedBytes < quotaBytes &&
                    (!lifecycle || lifecycle.mayUpload || manualCanUpload),
                ...(manualAccess?.active ? {
                    manualGrantKind: manualAccess.kind,
                    manualGrantExpiresAtMs: manualAccess.expiresAtMs,
                    manualGrantQuotaBytes: manualCloudBytes,
                    manualAdFreeExpiresAtMs: manualAccess.expiresAtMs,
                } : {}),
                ...(exitState?.scope === 'CLOUD' && exitState.status === 'COMPLETED'
                    ? { cloudUploadReactivationRequired: true } : {}),
                scheduledReplacement, replacementSourceAllowed: replacementSourcePrice !== null, replacementSourcePrice,
                ...(lifecycle ? { cloudLifecycleState: lifecycle.state,
                    cloudRecoveryEndsAtMs: lifecycle.recoveryEndsAtMs,
                    cloudDeletionDueAtMs: lifecycle.deletionDueAtMs,
                    cloudReadAllowed: lifecycle.mayBrowse || manualCanRead } : {}) };
        }
        const [account, usage] = await Promise.all([ownerRef(owner).get(), usageRef(owner).get()]);
        const plan = settings.enabled ? currentPlan(account.data(), settings.products, now()) : null;
        const quotaBytes = settings.enabled ? (plan?.quotaBytes ?? 0) : PILOT_ACCOUNT_QUOTA_BYTES;
        const usedBytes = usage.data()?.allocatedBytes ?? 0;
        if (!Number.isSafeInteger(usedBytes) || usedBytes < 0) {
            throw new BackupError('failed-precondition', 'The backup allocation needs reconciliation.');
        }
        return { enabled: settings.enabled, products: settings.products, activeProductId: plan?.productId ?? null,
            expiresAtMs: plan?.expiresAtMs ?? null, uploadAllowed: usedBytes < quotaBytes,
            obfuscatedAccountId: settings.enabled ? owner : null, quotaBytes, usedBytes };
    }

    function nextRevision(owner) {
        return db.runTransaction(async transaction => {
            const ref = ownerRef(owner);
            const current = (await transaction.get(ref)).data() ?? { revision: 0, entitlements: {} };
            if (!Number.isSafeInteger(current.revision) || current.revision < 0 || current.revision >= Number.MAX_SAFE_INTEGER) {
                throw new BackupError('failed-precondition', 'The billing record needs reconciliation.');
            }
            const revision = current.revision + 1;
            transaction.set(ref, { ...current, revision });
            return revision;
        });
    }

    function persist(owner, tokenHash, revision, purchase, licenseEmailHash) {
        return db.runTransaction(async transaction => {
            const account = ownerRef(owner);
            const token = tokenRef(tokenHash);
            const current = (await transaction.get(account)).data();
            const binding = (await transaction.get(token)).data();
            if (current?.revision !== revision) throw new BackupError('aborted', 'A newer billing check completed. Try again.');
            if (binding && binding.owner !== owner) throw new BackupError('permission-denied', 'The purchase belongs to another account.');
            if (binding?.retiredBy || binding?.invalidated) return false;
            if (production && purchase?.hasAnnualCloud && !binding &&
                (!Number.isSafeInteger(purchase.purchaseStartedAtMs) ||
                    purchase.purchaseStartedAtMs >= CLOUD_ANNUAL_LEGACY_CUTOFF_MS)) {
                // Older, already bound annual receipts remain valid; Play startTime is
                // the only accepted proof for an unbound historical purchase.
                throw new BackupError('failed-precondition', 'New annual Cloud purchases are no longer offered.');
            }
            if (!purchase && !binding) throw new BackupError('not-found', 'The Play subscription is no longer available.');
            const prior = purchase?.linkedHash ? tokenRef(purchase.linkedHash) : null;
            const oldBinding = prior ? (await transaction.get(prior)).data() : null;
            if (oldBinding && (oldBinding.owner !== owner || (oldBinding.retiredBy && oldBinding.retiredBy !== tokenHash))) {
                throw new BackupError('permission-denied', 'The previous subscription belongs to another purchase.');
            }
            if (production && purchase && purchase.externalOwner !== owner && !binding && !oldBinding) {
                throw new BackupError('permission-denied', 'The Play purchase is not linked to this account.');
            }

            const time = now();
            const entitlements = Object.fromEntries(Object.entries(current.entitlements ?? {}).filter(([key, item]) =>
                HASH.test(key) && Number.isSafeInteger(item?.expiresAtMs) && item.expiresAtMs > time));
            if (purchase?.entitlement && purchase.entitlement.expiresAtMs > time) entitlements[tokenHash] = purchase.entitlement;
            else delete entitlements[tokenHash];
            if (prior) delete entitlements[purchase.linkedHash];
            if (Object.keys(entitlements).length > MAX_CURRENT_TOKENS) {
                throw new BackupError('resource-exhausted', 'Too many subscriptions are active.');
            }
            let cloudLifecycle = current.cloudLifecycle;
            if (production && purchase?.cloudEvidence) {
                const evidence = purchase.cloudEvidence;
                const sameToken = cloudLifecycle?.tokenHash === tokenHash;
                const replacedToken = cloudLifecycle?.tokenHash === purchase.linkedHash;
                if (!cloudLifecycle || sameToken || replacedToken || !Number.isSafeInteger(cloudLifecycle.expiresAtMs)
                    || evidence.expiresAtMs >= cloudLifecycle.expiresAtMs) {
                    cloudLifecycle = { tokenHash, productId: evidence.productId, basePlanId: evidence.basePlanId,
                        expiresAtMs: evidence.expiresAtMs, recoveryAllowed: evidence.recoveryAllowed,
                        checkedAtMs: time };
                }
            } else if (production && !purchase && cloudLifecycle?.tokenHash === tokenHash) {
                // Play revoked or cannot find its previously bound Cloud purchase.
                cloudLifecycle = { ...cloudLifecycle, recoveryAllowed: false, checkedAtMs: time };
            }
            transaction.set(account, { revision, entitlements,
                ...(cloudLifecycle ? { cloudLifecycle } : {}) });
            const audience = licenseEmailHash === null ? { owner } : { owner, licenseEmailHash };
            transaction.set(token, purchase ? audience : { ...audience, invalidated: true });
            if (prior) transaction.set(prior, { owner, retiredBy: tokenHash });
            return purchase?.acknowledge === true && Object.hasOwn(entitlements, tokenHash);
        });
    }

    async function reconcile(owner, purchaseToken, licenseEmailHash = null) {
        const tokenHash = hash(purchaseToken);
        const revision = await nextRevision(owner);
        let result;
        try {
            result = await play.get(purchaseToken);
        } catch (error) {
            if (!(error instanceof BackupError) || error.code !== 'not-found') throw error;
            await persist(owner, tokenHash, revision, null, licenseEmailHash);
            return;
        }
        const purchase = inspectPurchase(result, owner, settings, now());
        if (purchase.linkedHash === tokenHash) throw new BackupError('data-loss', 'The Play subscription replacement is invalid.');
        if (await persist(owner, tokenHash, revision, purchase, licenseEmailHash)) {
            try {
                await play.acknowledge(purchaseToken, purchase.purchasedProductId);
            } catch {
                throw new BackupError('unavailable', 'The purchase was recorded. Retry to confirm it with Play.');
            }
        }
        return { tokenHash, revision, price: purchase.replacementSourcePrice };
    }

    return {
        async requireCloudAccess(auth, operation) {
            const { owner } = authorizeAccount(auth);
            if (production) {
                const exit = (await db.doc(`gallipickAccountExitRequests/${owner}`).get()).data();
                if (exit && (exit.scope === 'ACCOUNT' ||
                    (exit.status !== 'COMPLETED' && exit.status !== 'RESUMED') ||
                    (exit.status === 'COMPLETED' && operation === 'write'))) {
                    throw new BackupError('permission-denied', 'Cloud access requires deletion to finish and fresh backup consent.');
                }
            }
            if (production && !settings.cloudEnabled) {
                throw new BackupError('failed-precondition', 'Production Cloud backup is unavailable.');
            }
            if (!production || !settings.retentionEnabled) return;
            if (operation !== 'read' && operation !== 'write') {
                throw new BackupError('invalid-argument', 'Unknown Cloud access operation.');
            }
            const [account, manual] = await Promise.all([
                ownerRef(owner).get(),
                settings.manualGrantsEnabled ? manualGrantRef(owner).get() : null,
            ]);
            const state = lifecycleForAccount(account.data());
            const grant = manualBenefit(manual?.data(), owner);
            if ((operation === 'read' && (state.mayBrowse && state.mayDownload ||
                    settings.cloudEnabled && grant?.mayReadCloud === true))
                || (operation === 'write' && (state.mayUpload ||
                    settings.cloudEnabled && grant?.mayUploadCloud === true))) return state;
            throw new BackupError('permission-denied', 'Cloud access is not allowed for the verified subscription state.');
        },

        authorizeBackup(auth) {
            const { owner } = authorizeAccount(auth);
            if (production && !settings.cloudEnabled) {
                throw new BackupError('failed-precondition', 'Production Cloud backup is unavailable.');
            }
            return owner;
        },

        async getPlan(auth, input) {
            const { owner } = authorizeAccount(auth);
            if (production && settings.enabled) await countProductionRequest(owner);
            const plan = await getPlanForOwner(owner);
            const canCount = plan.enabled && (!production || (plan.backupAvailable
                && input?.cloudProtocolVersion === PRODUCTION_BACKUP_PROTOCOL_VERSION));
            if (!canCount || input?.includeVerifiedCount !== true) return plan;
            return { ...plan, verifiedMediaCount: await countVerifiedMedia(owner) };
        },

        async verify(auth, input) {
            const { owner, licenseEmailHash } = authorizeAccount(auth);
            if (!settings.enabled) throw new BackupError('failed-precondition', 'Play billing is disabled.');
            const purchaseToken = input?.purchaseToken;
            if (typeof purchaseToken !== 'string' || !PURCHASE_TOKEN.test(purchaseToken)) {
                throw new BackupError('invalid-argument', 'A valid Play purchase token is required.');
            }
            if (production) await countProductionRequest(owner);
            const checkedSource = await reconcile(owner, purchaseToken, licenseEmailHash);
            return getPlanForOwner(owner, input?.cloudProtocolVersion === PRODUCTION_BACKUP_PROTOCOL_VERSION
                ? checkedSource : undefined);
        },

        // Invoke only from the dedicated Play Pub/Sub subscription, never from an HTTP client.
        async reconcileNotification(notification) {
            if (!settings.enabled || notification?.packageName !== PLAY_BILLING_PACKAGE) return false;
            const token = notification.subscriptionNotification?.purchaseToken
                ?? (notification.voidedPurchaseNotification?.productType === 1 ? notification.voidedPurchaseNotification.purchaseToken : null);
            if (typeof token !== 'string' || !PURCHASE_TOKEN.test(token)) return false;
            const binding = (await tokenRef(hash(token)).get()).data();
            if (binding?.retiredBy || binding?.invalidated) return false;
            let owner = binding?.owner;
            let licenseEmailHash = binding?.licenseEmailHash;
            if (owner == null) {
                const purchase = await play.get(token);
                if (production) {
                    const prior = purchase?.linkedPurchaseToken;
                    if (typeof prior !== 'string' || !PURCHASE_TOKEN.test(prior)) return false;
                    const priorBinding = (await tokenRef(hash(prior)).get()).data();
                    owner = priorBinding?.owner;
                    licenseEmailHash = priorBinding?.licenseEmailHash;
                } else {
                    owner = purchase?.externalAccountIdentifiers?.obfuscatedExternalAccountId;
                }
            }
            const licenseEmailAllowed = settings.mode === 'license-test' && allowedEmailHashes.has(licenseEmailHash);
            if (typeof owner !== 'string' || !HASH.test(owner)
                || (settings.mode !== 'live' && !allowedOwners.has(owner) && !licenseEmailAllowed)) return false;
            if (production) {
                const exit = (await db.doc(`gallipickAccountExitRequests/${owner}`).get()).data();
                if (exit && (exit.scope === 'ACCOUNT' || (exit.status !== 'COMPLETED'
                    && exit.status !== 'RESUMED'))) return false;
            }
            // Re-fetch after reserving the revision. The notification is only a refresh signal.
            await reconcile(owner, token, licenseEmailAllowed ? licenseEmailHash : null);
            return true;
        },

        // Read this in the same transaction as new reservations and before any writes.
        async uploadQuota(transaction, owner, ownerLimit) {
            if (production && !settings.cloudEnabled) return 0;
            if (production) {
                const exit = (await transaction.get(db.doc(`gallipickAccountExitRequests/${owner}`))).data();
                if (exit && (exit.status !== 'RESUMED' ||
                    exit.scope === 'ACCOUNT')) return 0;
            }
            if (!Number.isSafeInteger(ownerLimit) || ownerLimit < 0) {
                throw new BackupError('failed-precondition', 'The backup allocation limit is invalid.');
            }
            if (!settings.enabled) return ownerLimit;
            const account = (await transaction.get(ownerRef(owner))).data();
            if (production) {
                const manual = settings.manualGrantsEnabled
                    ? (await transaction.get(manualGrantRef(owner))).data() : null;
                const grant = manualBenefit(manual, owner);
                const entitlementQuota = productionBenefits(account).quotaBytes;
                const manualQuota = settings.cloudEnabled ? grant?.quotaBytes ?? 0 : 0;
                return Math.min(Math.max(entitlementQuota, manualQuota), ownerLimit);
            }
            const plan = currentPlan(account, settings.products, now());
            return plan ? Math.min(plan.quotaBytes, ownerLimit) : 0;
        },
    };
}
