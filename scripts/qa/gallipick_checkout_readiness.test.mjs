import assert from 'node:assert/strict';
import test from 'node:test';
import {
  PRODUCT_PRICE_POLICY, REQUIRED_FUNCTIONS,
  evaluatePlaySubscription, evaluateCloudFunction, evaluateCheckoutReadiness,
} from './gallipick_checkout_readiness.mjs';

const PACKAGE = 'com.hebi.gallipick';
const PROJECT = 'gallipick-production';
const REGION = 'asia-northeast3';

const validPlan = (id, price, { state = 'ACTIVE', available = true } = {}) => ({
  basePlanId: id,
  state,
  autoRenewingBasePlanType: { billingPeriodDuration: id === 'monthly' ? 'P1M' : 'P1Y' },
  regionalConfigs: [{ regionCode: 'KR', newSubscriberAvailability: available,
    price: { currencyCode: 'KRW', units: String(price), nanos: 0 } }],
  otherRegionsConfig: { newSubscriberAvailability: false },
});

function subscription(productId, overrides = {}) {
  const pricing = PRODUCT_PRICE_POLICY[productId];
  const plans = Object.entries(pricing).map(([id, price]) => validPlan(id, price));
  if (productId !== 'gallipick_ad_free') plans.push(validPlan('annual', 99_000, { state: 'DRAFT' }));
  return { packageName: PACKAGE, productId, basePlans: plans, ...overrides };
}

function runtime(name, overrides = {}) {
  const env = {
    GALLIPICK_PRODUCTION_AD_FREE_ENABLED: 'true',
    GALLIPICK_PRODUCTION_BILLING_MODE: 'live',
    GALLIPICK_PRODUCTION_BILLING_TEST_UIDS: '',
    GALLIPICK_PRODUCTION_BILLING_TEST_EMAILS: '',
    GALLIPICK_PRODUCTION_CLOUD_ENABLED: 'true',
    GALLIPICK_PRODUCTION_CLOUD_LIVE_ENABLED: 'true',
    GALLIPICK_PRODUCTION_CLOUD_RETENTION_ENABLED: 'true',
    GALLIPICK_PRODUCTION_CLOUD_UPLOADS_PAUSED: 'false',
    GALLIPICK_PRODUCTION_CLOUD_MAX_ORIGINAL_BYTES: '268435456',
    ...overrides,
  };
  return {
    name: `projects/${PROJECT}/locations/${REGION}/functions/${name}`,
    state: 'ACTIVE',
    serviceConfig: {
      serviceAccountEmail: 'gallipick-billing@gallipick-production.iam.gserviceaccount.com',
      environmentVariables: env,
    },
  };
}

test('a passing fixture has Cloud monthly-only and ad-free monthly/annual in Korea', () => {
  const play = Object.fromEntries(Object.keys(PRODUCT_PRICE_POLICY).map(key =>
    [key, subscription(key)]));
  const cloud = Object.fromEntries(REQUIRED_FUNCTIONS.map(name => [name, runtime(name)]));
  const audit = evaluateCheckoutReadiness(play, cloud);
  assert.equal(audit.checkoutConfigurationReady, true);
  assert.equal(audit.playProducts.length, 4);
  assert.equal(audit.cloudFunctions.length, 2);
  assert.equal(audit.playProducts.every(p => p.ready), true);
  assert.equal(audit.cloudFunctions.every(p => p.ready), true);
  assert.equal(JSON.stringify(audit).includes('GALLIPICK_PRODUCTION_CLOUD_MAX_ORIGINAL_BYTES'), false);
});

test('Play Cloud annual ACTIVE for any new subscribers is a hard stop', () => {
  for (const product of Object.keys(PRODUCT_PRICE_POLICY).filter(x => x !== 'gallipick_ad_free')) {
    const bad = subscription(product);
    bad.basePlans.at(-1).state = 'ACTIVE';
    bad.basePlans.at(-1).regionalConfigs[0].newSubscriberAvailability = true;
    const result = evaluatePlaySubscription(product, bad);
    assert.equal(result.ready, false);
    assert.equal(result.checks.annualBlocked.detail,
      'CLOUD_ANNUAL_STILL_AVAILABLE_FOR_NEW_SUBSCRIBERS');

    bad.basePlans.at(-1).regionalConfigs[0].newSubscriberAvailability = false;
    bad.basePlans.at(-1).otherRegionsConfig.newSubscriberAvailability = true;
    assert.equal(evaluatePlaySubscription(product, bad).ready, false);
    bad.basePlans.at(-1).otherRegionsConfig.newSubscriberAvailability = false;
    assert.equal(evaluatePlaySubscription(product, bad).ready, true);
  }
});

test('Cloud base plans with an annual duration are checked regardless of their ID', () => {
  const product = subscription('gallipick_cloud_10gb');
  const yearly = { ...validPlan('annual', 99_000), basePlanId: 'yearly-v2' };
  product.basePlans.push(yearly);

  const selling = evaluatePlaySubscription(product.productId, product);
  assert.equal(selling.ready, false);
  assert.equal(selling.checks.annualBlocked.detail,
    'CLOUD_ANNUAL_STILL_AVAILABLE_FOR_NEW_SUBSCRIBERS');

  yearly.regionalConfigs[0].newSubscriberAvailability = false;
  const legacy = evaluatePlaySubscription(product.productId, product);
  assert.equal(legacy.ready, true);
  assert.equal(legacy.checks.annualBlocked.detail, 'ACTIVE_LEGACY_ANNUAL_NOT_AVAILABLE_TO_NEW_SUBSCRIBERS');

  delete yearly.autoRenewingBasePlanType;
  yearly.prepaidBasePlanType = { billingPeriodDuration: 'P1Y' };
  yearly.otherRegionsConfig.newSubscriberAvailability = true;
  assert.equal(evaluatePlaySubscription(product.productId, product).ready, false);
});

test('Korean base-plan price, currency, location, renewal period and availability are checked', () => {
  const baseline = subscription('gallipick_cloud_10gb');
  const variants = [
    plan => { plan.regionalConfigs[0].price.units = '2900'; },
    plan => { plan.regionalConfigs[0].price.currencyCode = 'USD'; },
    plan => { plan.regionalConfigs[0].newSubscriberAvailability = false; },
    plan => { plan.autoRenewingBasePlanType.billingPeriodDuration = 'P1Y'; },
    plan => { plan.state = 'DRAFT'; },
    plan => { plan.regionalConfigs = []; },
    plan => { plan.regionalConfigs[0].price.nanos = 3; },
  ];
  for (const modify of variants) {
    const product = structuredClone(baseline);
    modify(product.basePlans[0]);
    assert.equal(evaluatePlaySubscription(product.productId, product).ready, false);
  }
  assert.equal(evaluatePlaySubscription('gallipick_cloud_50gb', null).ready, false);
  assert.equal(evaluatePlaySubscription('gallipick_cloud_50gb', subscription('gallipick_ad_free')).ready, false);
});

test('ad-free annual remains on sale while Cloud annual is not', () => {
  const adFree = evaluatePlaySubscription('gallipick_ad_free', subscription('gallipick_ad_free'));
  assert.equal(adFree.ready, true);
  assert.equal(adFree.checks.annual.state, 'PASS');
  const missing = subscription('gallipick_ad_free');
  missing.basePlans = missing.basePlans.filter(plan => plan.basePlanId !== 'annual');
  assert.equal(evaluatePlaySubscription('gallipick_ad_free', missing).ready, false);
});

test('50GB and 100GB accept the approved Korean monthly price, not the former price', () => {
  for (const [productId, currentPrice, formerPrice] of [
    ['gallipick_cloud_50gb', 8900, 5900],
    ['gallipick_cloud_100gb', 15900, 10900],
  ]) {
    const product = subscription(productId, { basePlans: [validPlan('monthly', currentPrice)] });
    assert.equal(evaluatePlaySubscription(productId, product).ready, true);
    product.basePlans = [validPlan('monthly', formerPrice)];
    assert.equal(evaluatePlaySubscription(productId, product).checks.monthly.detail,
      'KRW_PRICE_DIFFERS_FROM_APPROVED_PLAN');
  }
});

test('functions must be live, approved, uploads-enabled, and have a dedicated service account', () => {
  for (const name of REQUIRED_FUNCTIONS) {
    assert.equal(evaluateCloudFunction(name, runtime(name)).ready, true);
    for (const field of [
      ['GALLIPICK_PRODUCTION_BILLING_MODE', 'license-test'],
      ['GALLIPICK_PRODUCTION_AD_FREE_ENABLED', 'false'],
      ['GALLIPICK_PRODUCTION_CLOUD_ENABLED', 'false'],
      ['GALLIPICK_PRODUCTION_CLOUD_LIVE_ENABLED', 'false'],
      ['GALLIPICK_PRODUCTION_CLOUD_RETENTION_ENABLED', 'false'],
      ['GALLIPICK_PRODUCTION_CLOUD_UPLOADS_PAUSED', 'true'],
      ['GALLIPICK_PRODUCTION_BILLING_TEST_UIDS', 'someone'],
      ['GALLIPICK_PRODUCTION_CLOUD_MAX_ORIGINAL_BYTES', '268435457'],
    ]) {
      const result = evaluateCloudFunction(name, runtime(name, { [field[0]]: field[1] }));
      assert.equal(result.ready, false, field.join('='));
    }
    assert.equal(evaluateCloudFunction(name, { ...runtime(name), state: 'FAILED' }).ready, false);
    assert.equal(evaluateCloudFunction(name, {
      ...runtime(name), serviceConfig: { ...runtime(name).serviceConfig,
        serviceAccountEmail: 'unapproved@example.iam.gserviceaccount.com' },
    }).ready, false);
    const unknown = evaluateCloudFunction(name, null);
    assert.equal(unknown.ready, false);
    assert.equal(unknown.checks.function.state, 'UNVERIFIED');
  }
});

test('missing Cloud/Play credentials cannot be interpreted as launch readiness', () => {
  const audit = evaluateCheckoutReadiness({}, {});
  assert.equal(audit.checkoutConfigurationReady, false);
  assert.equal(audit.playProducts.every(p => !p.ready), true);
  assert.equal(audit.cloudFunctions.every(p => !p.ready), true);
  assert.equal(JSON.stringify(audit).includes('access_token'), false);
});
