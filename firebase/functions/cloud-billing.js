import { createHash } from 'node:crypto';
import { authorize, BackupError } from './backup.js';

export const PILOT_ACCOUNT_QUOTA_BYTES = 1024 ** 3;
export const PLAY_BILLING_PACKAGE = 'com.hebi.gallipick';
const MAX_CURRENT_TOKENS = 20;
const HASH = /^[a-f0-9]{64}$/;
const PRODUCT_ID = /^[a-z0-9][a-z0-9_.]{0,149}$/;
const BASE_PLAN_ID = /^[a-z0-9][a-z0-9-]{0,62}$/;
const PURCHASE_TOKEN = /^[\x21-\x7e]{8,4096}$/;
const ACCEPTED_SUBSCRIPTION_STATES = new Set(['SUBSCRIPTION_STATE_ACTIVE', 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD',
    'SUBSCRIPTION_STATE_CANCELED']);
const INACTIVE_SUBSCRIPTION_STATES = new Set(['SUBSCRIPTION_STATE_PENDING', 'SUBSCRIPTION_STATE_PAUSED',
    'SUBSCRIPTION_STATE_ON_HOLD', 'SUBSCRIPTION_STATE_EXPIRED', 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED']);
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

function inspectPurchase(purchase, owner, products, now) {
    if (!purchase?.testPurchase || typeof purchase.testPurchase !== 'object' || Array.isArray(purchase.testPurchase)
        || purchase.externalAccountIdentifiers?.obfuscatedExternalAccountId !== owner) {
        throw new BackupError('permission-denied', 'The Play test purchase is not linked to this cloud account.');
    }
    const [item] = Array.isArray(purchase.lineItems) ? purchase.lineItems : [];
    const plan = item && products.find(candidate => candidate.productId === item.productId
        && candidate.basePlanId === item.offerDetails?.basePlanId);
    if (!plan || purchase.lineItems.length !== 1 || !item.autoRenewingPlan || item.prepaidPlan) {
        throw new BackupError('failed-precondition', 'The purchase does not match an enabled test plan.');
    }
    const active = ACCEPTED_SUBSCRIPTION_STATES.has(purchase.subscriptionState);
    const expiresAtMs = Date.parse(item.expiryTime);
    const eligible = active && expiresAtMs > now;
    const acknowledgement = purchase.acknowledgementState;
    if ((!active && !INACTIVE_SUBSCRIPTION_STATES.has(purchase.subscriptionState))
        || (active && !Number.isFinite(expiresAtMs)) || (eligible && acknowledgement !== ACK_PENDING && acknowledgement !== ACK_COMPLETE)) {
        throw new BackupError('failed-precondition', 'The Play subscription status could not be verified.');
    }
    const linked = purchase.linkedPurchaseToken;
    if (linked != null && (typeof linked !== 'string' || !PURCHASE_TOKEN.test(linked))) {
        throw new BackupError('failed-precondition', 'The Play subscription replacement could not be verified.');
    }
    return {
        plan,
        entitlement: eligible ? { ...plan, expiresAtMs, checkedAtMs: now } : null,
        acknowledge: eligible && acknowledgement === ACK_PENDING,
        linkedHash: typeof linked === 'string' ? hash(linked) : null,
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
    const ownerRef = owner => db.doc(`gallipickCloudBillingAccounts/${owner}`);
    const tokenRef = tokenHash => db.doc(`gallipickCloudBillingTokens/${tokenHash}`);
    const usageRef = owner => db.doc(`gallipickBackupAccounts/${owner}`);
    const pilotOwners = new Set([...pilotUsers].map(hash));

    async function getPlanForOwner(owner) {
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

    function persist(owner, tokenHash, revision, purchase) {
        return db.runTransaction(async transaction => {
            const account = ownerRef(owner);
            const token = tokenRef(tokenHash);
            const current = (await transaction.get(account)).data();
            const binding = (await transaction.get(token)).data();
            if (current?.revision !== revision) throw new BackupError('aborted', 'A newer billing check completed. Try again.');
            if (binding && binding.owner !== owner) throw new BackupError('permission-denied', 'The purchase belongs to another account.');
            if (binding?.retiredBy) return false;
            const prior = purchase.entitlement && purchase.linkedHash ? tokenRef(purchase.linkedHash) : null;
            const oldBinding = prior ? (await transaction.get(prior)).data() : null;
            if (oldBinding && (oldBinding.owner !== owner || (oldBinding.retiredBy && oldBinding.retiredBy !== tokenHash))) {
                throw new BackupError('permission-denied', 'The previous subscription belongs to another purchase.');
            }

            const time = now();
            const entitlements = Object.fromEntries(Object.entries(current.entitlements ?? {}).filter(([key, item]) =>
                HASH.test(key) && Number.isSafeInteger(item?.expiresAtMs) && item.expiresAtMs > time));
            if (purchase.entitlement && purchase.entitlement.expiresAtMs > time) entitlements[tokenHash] = purchase.entitlement;
            else delete entitlements[tokenHash];
            if (prior) delete entitlements[purchase.linkedHash];
            if (Object.keys(entitlements).length > MAX_CURRENT_TOKENS) {
                throw new BackupError('resource-exhausted', 'Too many test subscriptions are active.');
            }
            transaction.set(account, { revision, entitlements });
            transaction.set(token, { owner });
            if (prior) transaction.set(prior, { owner, retiredBy: tokenHash });
            return purchase.acknowledge && Object.hasOwn(entitlements, tokenHash);
        });
    }

    async function reconcile(owner, purchaseToken) {
        const tokenHash = hash(purchaseToken);
        const revision = await nextRevision(owner);
        const purchase = inspectPurchase(await play.get(purchaseToken), owner, settings.products, now());
        if (purchase.linkedHash === tokenHash) throw new BackupError('data-loss', 'The Play subscription replacement is invalid.');
        if (await persist(owner, tokenHash, revision, purchase)) {
            try {
                await play.acknowledge(purchaseToken, purchase.plan.productId);
            } catch {
                throw new BackupError('unavailable', 'The purchase was recorded. Retry to confirm it with Play.');
            }
        }
    }

    return {
        getPlan(auth) { return getPlanForOwner(authorize(auth, pilotUsers)); },

        async verify(auth, input) {
            const owner = authorize(auth, pilotUsers);
            if (!settings.enabled) throw new BackupError('failed-precondition', 'The Play billing test is disabled.');
            const purchaseToken = input?.purchaseToken;
            if (typeof purchaseToken !== 'string' || !PURCHASE_TOKEN.test(purchaseToken)) {
                throw new BackupError('invalid-argument', 'A valid Play purchase token is required.');
            }
            await reconcile(owner, purchaseToken);
            return getPlanForOwner(owner);
        },

        // Invoke only from the dedicated Play Pub/Sub subscription, never from an HTTP client.
        async reconcileNotification(notification) {
            if (!settings.enabled || notification?.packageName !== PLAY_BILLING_PACKAGE) return false;
            const token = notification.subscriptionNotification?.purchaseToken
                ?? (notification.voidedPurchaseNotification?.productType === 1 ? notification.voidedPurchaseNotification.purchaseToken : null);
            if (typeof token !== 'string' || !PURCHASE_TOKEN.test(token)) return false;
            const binding = (await tokenRef(hash(token)).get()).data();
            if (binding?.retiredBy) return false;
            const owner = binding?.owner ?? (await play.get(token))?.externalAccountIdentifiers?.obfuscatedExternalAccountId;
            if (!pilotOwners.has(owner)) return false;
            // Re-fetch after reserving the revision. The notification is only a refresh signal.
            await reconcile(owner, token);
            return true;
        },

        // Read this in the same transaction as new reservations and before any writes.
        async uploadQuota(transaction, owner, pilotLimit) {
            if (!settings.enabled) return pilotLimit;
            const account = (await transaction.get(ownerRef(owner))).data();
            const plan = currentPlan(account, settings.products, now());
            return plan ? Math.min(plan.quotaBytes, pilotLimit) : 0;
        },
    };
}
