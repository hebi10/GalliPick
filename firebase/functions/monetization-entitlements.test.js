import assert from 'node:assert/strict';
import test from 'node:test';
import {
    AD_FREE_PRODUCT_ID, productionMonetizationCatalog, validateMonetizationCatalog, verifiedMonetizationBenefits,
} from './monetization-entitlements.js';

const maximum = 500 * 1024 ** 3;
const catalog = validateMonetizationCatalog([
    { productId: AD_FREE_PRODUCT_ID, basePlanId: 'monthly', kind: 'AD_FREE', quotaBytes: 0 },
    { productId: AD_FREE_PRODUCT_ID, basePlanId: 'annual', kind: 'AD_FREE', quotaBytes: 0 },
    { productId: 'cloud_candidate', basePlanId: 'monthly', kind: 'CLOUD', quotaBytes: 100 * 1024 ** 3 },
], maximum);
const now = 1_800_000_000_000;
const proof = (product, expiry = now + 10_000) => ({
    productId: product.productId, basePlanId: product.basePlanId, quotaBytes: product.quotaBytes,
    checkedAtMs: now - 1_000, expiresAtMs: expiry,
});

test('catalog requires explicit bounded cloud storage and never turns ad removal into quota', () => {
    assert.equal(catalog.length, 3);
    assert.throws(() => validateMonetizationCatalog([], maximum));
    assert.throws(() => validateMonetizationCatalog(catalog, 0));
    assert.throws(() => validateMonetizationCatalog([
        { productId: AD_FREE_PRODUCT_ID, basePlanId: 'monthly', kind: 'AD_FREE', quotaBytes: 1 },
    ], maximum));
    assert.throws(() => validateMonetizationCatalog([
        { productId: AD_FREE_PRODUCT_ID, basePlanId: 'weekly', kind: 'AD_FREE', quotaBytes: 0 },
    ], maximum));
    assert.throws(() => validateMonetizationCatalog([
        { productId: 'cloud_candidate', basePlanId: 'monthly', kind: 'CLOUD', quotaBytes: maximum + 1 },
    ], maximum));
    assert.throws(() => validateMonetizationCatalog([catalog[0], catalog[0]], maximum));
});

test('monthly and annual standalone ad-removal plans grant no cloud storage', () => {
    for (const plan of catalog.filter(item => item.kind === 'AD_FREE')) {
        const result = verifiedMonetizationBenefits([proof(plan)], catalog, now);
        assert.equal(result.adsRemoved, true);
        assert.equal(result.quotaBytes, 0);
        assert.equal(result.cloudProductId, null);
        assert.equal(result.adFreeProductId, AD_FREE_PRODUCT_ID);
        assert.equal(result.showStandaloneAdFreeUpsell, false);
    }
});

test('an active cloud subscriber gets ad removal without paying for the standalone product', () => {
    const result = verifiedMonetizationBenefits([proof(catalog[2])], catalog, now);
    assert.equal(result.adsRemoved, true);
    assert.equal(result.cloudBasePlanId, 'monthly');
    assert.equal(result.adFreeProductId, null);
    assert.equal(result.quotaBytes, 100 * 1024 ** 3);
    assert.equal(result.showStandaloneAdFreeUpsell, false);
});

test('independent cloud and ad-removal records are evaluated without overwriting each other', () => {
    const both = verifiedMonetizationBenefits([proof(catalog[0]), proof(catalog[2])], catalog, now);
    assert.equal(both.adsRemoved, true);
    assert.equal(both.quotaBytes, 100 * 1024 ** 3);
    assert.equal(both.adFreeProductId, AD_FREE_PRODUCT_ID);
    const adOnly = verifiedMonetizationBenefits([proof(catalog[0]), proof(catalog[2], now)], catalog, now);
    assert.equal(adOnly.adsRemoved, true);
    assert.equal(adOnly.quotaBytes, 0);
});

test('overlapping cloud records never add storage quotas together', () => {
    const production = productionMonetizationCatalog(true);
    const small = production.find(item => item.productId === 'gallipick_cloud_10gb' && item.basePlanId === 'monthly');
    const large = production.find(item => item.productId === 'gallipick_cloud_100gb' && item.basePlanId === 'annual');
    const entitlements = [proof(small, now + 20_000), proof(large, now + 10_000)];
    assert.equal(verifiedMonetizationBenefits(entitlements, production, now).quotaBytes, 100_000_000_000);
    assert.equal(verifiedMonetizationBenefits(entitlements, production, now + 10_000).quotaBytes, 10_000_000_000);
    assert.equal(verifiedMonetizationBenefits(entitlements, production, now + 20_000).quotaBytes, 0);
});

test('unverified, expired, unknown and malformed records never grant benefits', () => {
    const invalid = [
        proof(catalog[2], now),
        { ...proof(catalog[0]), checkedAtMs: now + 1 },
        { ...proof(catalog[2]), checkedAtMs: -1 },
        { ...proof(catalog[0]), quotaBytes: 1 },
        { ...proof(catalog[2]), productId: 'forged_product' },
        { ...proof(catalog[0]), expiresAtMs: 'tomorrow' },
        null,
    ];
    const result = verifiedMonetizationBenefits(invalid, catalog, now);
    assert.equal(result.adsRemoved, false);
    assert.equal(result.quotaBytes, 0);
    assert.equal(result.showStandaloneAdFreeUpsell, true);
});
