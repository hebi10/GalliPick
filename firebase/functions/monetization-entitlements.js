// Verified server-side subscription records only; never consume a client-declared purchase.
export const AD_FREE_PRODUCT_ID = 'gallipick_ad_free';
export const AD_FREE_BASE_PLANS = Object.freeze(['monthly', 'annual']);
export const PRODUCTION_CLOUD_MAX_BYTES = 100_000_000_000;

const PRODUCTION_CLOUD_TIERS = [
    ['gallipick_cloud_10gb', 10_000_000_000],
    ['gallipick_cloud_50gb', 50_000_000_000],
    ['gallipick_cloud_100gb', PRODUCTION_CLOUD_MAX_BYTES],
];

const PRODUCT_ID = /^[a-z0-9][a-z0-9_.]{0,149}$/;
const BASE_PLAN_ID = /^[a-z0-9][a-z0-9-]{0,62}$/;

/** Validate trusted, bounded product definitions before using them for entitlement checks. */
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

export function productionMonetizationCatalog(cloudEnabled) {
    const adFree = AD_FREE_BASE_PLANS.map(basePlanId => ({
        productId: AD_FREE_PRODUCT_ID, basePlanId, kind: 'AD_FREE', quotaBytes: 0,
    }));
    const cloud = cloudEnabled ? PRODUCTION_CLOUD_TIERS.flatMap(([productId, quotaBytes]) =>
        AD_FREE_BASE_PLANS.map(basePlanId => ({ productId, basePlanId, kind: 'CLOUD', quotaBytes }))) : [];
    return validateMonetizationCatalog([...adFree, ...cloud], cloudEnabled ? PRODUCTION_CLOUD_MAX_BYTES : 0);
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
            || !Number.isSafeInteger(raw.checkedAtMs) || raw.checkedAtMs < 0 || raw.checkedAtMs > now
            || !Number.isSafeInteger(raw.quotaBytes)) continue;
        const plan = plans.get(`${raw.productId}:${raw.basePlanId}`);
        if (!plan || raw.quotaBytes !== plan.quotaBytes) continue;
        if (plan.kind === 'CLOUD') {
            if (!cloudPlan || plan.quotaBytes > cloudPlan.quotaBytes ||
                (plan.quotaBytes === cloudPlan.quotaBytes && raw.expiresAtMs > cloudPlan.expiresAtMs)) {
                cloudPlan = { ...plan, expiresAtMs: raw.expiresAtMs };
            }
        } else if (!standalone || raw.expiresAtMs > standalone.expiresAtMs) {
            standalone = { ...plan, expiresAtMs: raw.expiresAtMs };
        }
    }
    return {
        cloudProductId: cloudPlan?.productId ?? null,
        cloudBasePlanId: cloudPlan?.basePlanId ?? null,
        cloudExpiresAtMs: cloudPlan?.expiresAtMs ?? null,
        quotaBytes: cloudPlan?.quotaBytes ?? 0,
        adFreeProductId: standalone?.productId ?? null,
        adFreeBasePlanId: standalone?.basePlanId ?? null,
        adFreeExpiresAtMs: standalone?.expiresAtMs ?? null,
        adsRemoved: Boolean(cloudPlan || standalone),
        // Never sell duplicate ad removal to an account already covered by Cloud.
        showStandaloneAdFreeUpsell: !(cloudPlan || standalone),
    };
}
