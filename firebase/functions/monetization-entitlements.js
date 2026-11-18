// Verified server-side subscription records only; never consume a client-declared purchase.
export const AD_FREE_PRODUCT_ID = 'gallipick_ad_free';
export const AD_FREE_BASE_PLANS = Object.freeze(['monthly', 'annual']);

const PRODUCT_ID = /^[a-z0-9][a-z0-9_.]{0,149}$/;
const BASE_PLAN_ID = /^[a-z0-9][a-z0-9-]{0,62}$/;

/**
 * The cloud catalog is intentionally external: production sizes and prices have
 * not yet been approved. The caller must provide a bounded capacity policy.
 */
export function validateMonetizationCatalog(products, maxCloudBytes) {
    if (!Array.isArray(products) || products.length < 1 || products.length > 20
        || !Number.isSafeInteger(maxCloudBytes) || maxCloudBytes < 0) {
        throw new Error('An approved bounded subscription catalog is required.');
    }
    const unique = new Set();
    return products.map(product => {
        const { productId, basePlanId, kind, quotaBytes } = product ?? {};
        if (typeof productId !== 'string' || !PRODUCT_ID.test(productId)
            || typeof basePlanId !== 'string' || !BASE_PLAN_ID.test(basePlanId)
            || (kind !== 'AD_FREE' && kind !== 'CLOUD')) {
            throw new Error('Invalid subscription product definition.');
        }
        const key = `${productId}:${basePlanId}`;
        if (unique.has(key)) throw new Error('Duplicate product and base-plan pair.');
        unique.add(key);
        if (kind === 'AD_FREE') {
            if (productId !== AD_FREE_PRODUCT_ID || !AD_FREE_BASE_PLANS.includes(basePlanId)
                || quotaBytes !== 0) {
                throw new Error('Ad removal must not grant storage or arbitrary product benefits.');
            }
        } else if (productId === AD_FREE_PRODUCT_ID ||
            !Number.isSafeInteger(quotaBytes) || quotaBytes < 1 || quotaBytes > maxCloudBytes) {
            throw new Error('Invalid approved cloud storage capacity.');
        }
        return Object.freeze({ productId, basePlanId, kind, quotaBytes });
    });
}

/**
 * Expects records read from one server-verified UID's entitlement document.
 * Pending/expired/malformed records and unknown catalog offers grant nothing.
 * Independent active cloud and ad-removal records never overwrite each other.
 */
export function verifiedMonetizationBenefits(entitlements, catalog, now) {
    if (!Number.isSafeInteger(now) || now < 0 || !Array.isArray(catalog)) {
        throw new Error('Invalid trusted entitlement evaluation context.');
    }
    const plans = new Map(catalog.map(item => [`${item.productId}:${item.basePlanId}`, item]));
    let cloudPlan = null;
    let standalone = null;
    for (const raw of Array.isArray(entitlements) ? entitlements : []) {
        if (!raw || !Number.isSafeInteger(raw.expiresAtMs) || raw.expiresAtMs <= now
            || !Number.isSafeInteger(raw.checkedAtMs) || raw.checkedAtMs > now
            || !Number.isSafeInteger(raw.quotaBytes)) continue;
        const plan = plans.get(`${raw.productId}:${raw.basePlanId}`);
        if (!plan || raw.quotaBytes !== plan.quotaBytes) continue;
        if (plan.kind === 'CLOUD') {
            if (!cloudPlan || plan.quotaBytes > cloudPlan.quotaBytes ||
                (plan.quotaBytes === cloudPlan.quotaBytes && raw.expiresAtMs > cloudPlan.expiresAtMs)) {
                cloudPlan = { productId: plan.productId, quotaBytes: plan.quotaBytes, expiresAtMs: raw.expiresAtMs };
            }
        } else if (!standalone || raw.expiresAtMs > standalone.expiresAtMs) {
            standalone = { productId: plan.productId, expiresAtMs: raw.expiresAtMs };
        }
    }
    return {
        cloudProductId: cloudPlan?.productId ?? null,
        cloudExpiresAtMs: cloudPlan?.expiresAtMs ?? null,
        quotaBytes: cloudPlan?.quotaBytes ?? 0,
        adFreeProductId: standalone?.productId ?? null,
        adFreeExpiresAtMs: standalone?.expiresAtMs ?? null,
        adsRemoved: Boolean(cloudPlan || standalone),
        // Never sell duplicate ad removal to an account already covered by Cloud.
        showStandaloneAdFreeUpsell: !(cloudPlan || standalone),
    };
}
