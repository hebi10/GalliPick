import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.hebi.gallipick';
const PROJECT = 'gallipick-production';
const REGION = 'asia-northeast3';
const SERVICE_ACCOUNT = 'gallipick-billing@gallipick-production.iam.gserviceaccount.com';
const API_PLAY = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE}/subscriptions`;
const API_CLOUD = `https://cloudfunctions.googleapis.com/v2/projects/${PROJECT}/locations/${REGION}/functions`;
const MAX_ORIGINAL = 268_435_456;

export const PRODUCT_PRICE_POLICY = Object.freeze({
  gallipick_ad_free: { monthly: 1000, annual: 9900 },
  gallipick_cloud_10gb: { monthly: 1900 },
  gallipick_cloud_50gb: { monthly: 8900 },
  gallipick_cloud_100gb: { monthly: 15900 },
});
export const REQUIRED_FUNCTIONS = Object.freeze(['getCloudPlan', 'verifyCloudSubscription']);

const validMap = value => Boolean(value && typeof value === 'object' && !Array.isArray(value));
const status = (ok, unavailable = false) => unavailable ? 'UNVERIFIED' : ok ? 'PASS' : 'FAIL';
const plain = (state, detail) => ({ state, detail });

function krwAmount(value) {
  if (!validMap(value) || value.currencyCode !== 'KRW') return null;
  const units = Number(value.units);
  const nanos = Number(value.nanos ?? 0);
  return Number.isSafeInteger(units) && units >= 0 && Number.isSafeInteger(nanos) &&
    nanos === 0 ? units : null;
}

function evaluateRequiredPlan(productId, planId, expectedPrice, subscription) {
  const plans = subscription?.basePlans;
  const matching = Array.isArray(plans) ? plans.filter(plan => plan?.basePlanId === planId) : [];
  if (matching.length !== 1) return plain('FAIL', 'REQUIRED_BASE_PLAN_NOT_FOUND');
  const plan = matching[0];
  if (plan.state !== 'ACTIVE') return plain('FAIL', 'BASE_PLAN_NOT_ACTIVE');
  if (plan.autoRenewingBasePlanType?.billingPeriodDuration !== (planId === 'monthly' ? 'P1M' : 'P1Y'))
    return plain('FAIL', 'NOT_APPROVED_AUTO_RENEWAL_PERIOD');
  const korean = Array.isArray(plan.regionalConfigs)
    ? plan.regionalConfigs.filter(region => region?.regionCode === 'KR') : [];
  if (korean.length !== 1 || korean[0].newSubscriberAvailability !== true)
    return plain('FAIL', 'NOT_AVAILABLE_TO_NEW_KOREAN_SUBSCRIBERS');
  const krw = krwAmount(korean[0].price);
  if (krw === null) return plain('UNVERIFIED', 'KRW_PRICE_UNVERIFIED');
  return plain(krw === expectedPrice ? 'PASS' : 'FAIL',
    krw === expectedPrice ? 'ACTIVE_KRW_PRICE_MATCH' : 'KRW_PRICE_DIFFERS_FROM_APPROVED_PLAN');
}

function evaluateCloudAnnual(subscription) {
  const plans = subscription?.basePlans;
  const matching = Array.isArray(plans) ? plans.filter(plan => plan?.basePlanId === 'annual') : [];
  if (matching.length === 0) return plain('PASS', 'ANNUAL_NOT_CONFIGURED');
  if (matching.length !== 1) return plain('FAIL', 'DUPLICATE_ANNUAL_BASE_PLAN');
  const plan = matching[0];
  if (plan.state === 'INACTIVE' || plan.state === 'DRAFT') return plain('PASS', 'ANNUAL_NOT_ACTIVE');
  if (plan.state !== 'ACTIVE') return plain('UNVERIFIED', 'ANNUAL_STATE_NOT_VERIFIED');
  const available = (Array.isArray(plan.regionalConfigs) &&
    plan.regionalConfigs.some(region => region?.newSubscriberAvailability === true)) ||
    plan.otherRegionsConfig?.newSubscriberAvailability === true;
  return available ? plain('FAIL', 'CLOUD_ANNUAL_STILL_AVAILABLE_FOR_NEW_SUBSCRIBERS')
    : plain('PASS', 'ACTIVE_LEGACY_ANNUAL_NOT_AVAILABLE_TO_NEW_SUBSCRIBERS');
}

export function evaluatePlaySubscription(productId, resource) {
  const expected = PRODUCT_PRICE_POLICY[productId];
  if (!expected) return { productId, ready: false, checks: { product: plain('UNVERIFIED', 'UNKNOWN_PRODUCT') } };
  if (!validMap(resource) || resource.productId !== productId || resource.packageName !== PACKAGE) {
    return { productId, ready: false, checks: { product: plain('UNVERIFIED', 'PRODUCT_NOT_VERIFIED') } };
  }
  const checks = {};
  for (const [plan, amount] of Object.entries(expected)) {
    checks[plan] = evaluateRequiredPlan(productId, plan, amount, resource);
  }
  if (productId !== 'gallipick_ad_free') checks.annualBlocked = evaluateCloudAnnual(resource);
  return { productId, ready: Object.values(checks).every(value => value.state === 'PASS'), checks };
}

export function evaluateCloudFunction(functionName, metadata) {
  if (!REQUIRED_FUNCTIONS.includes(functionName)) {
    return { functionName, ready: false, checks: { function: plain('UNVERIFIED', 'NOT_APPROVED_FUNCTION') } };
  }
  const expectedName = `projects/${PROJECT}/locations/${REGION}/functions/${functionName}`;
  if (!validMap(metadata) || metadata.name !== expectedName || !validMap(metadata.serviceConfig)) {
    return { functionName, ready: false, checks: { function: plain('UNVERIFIED', 'FUNCTION_METADATA_NOT_VERIFIED') } };
  }
  const settings = metadata.serviceConfig.environmentVariables;
  const env = validMap(settings) ? settings : {};
  const checks = {
    deployed: plain(status(metadata.state === 'ACTIVE'), 'FUNCTION_DEPLOYED_STATE'),
    serviceAccount: plain(status(metadata.serviceConfig.serviceAccountEmail === SERVICE_ACCOUNT),
      'DEDICATED_BILLING_SERVICE_ACCOUNT'),
    billingEnabled: plain(status(env.GALLIPICK_PRODUCTION_AD_FREE_ENABLED === 'true'),
      'PRODUCTION_BILLING_ENABLED'),
    billingLive: plain(status(env.GALLIPICK_PRODUCTION_BILLING_MODE === 'live'),
      'BILLING_MODE_LIVE'),
    testAllowlistEmpty: plain(status(!env.GALLIPICK_PRODUCTION_BILLING_TEST_UIDS?.trim() &&
      !env.GALLIPICK_PRODUCTION_BILLING_TEST_EMAILS?.trim()), 'NO_TEST_AUDIENCE_IN_LIVE_MODE'),
    cloudEnabled: plain(status(env.GALLIPICK_PRODUCTION_CLOUD_ENABLED === 'true'),
      'PRODUCTION_CLOUD_ENABLED'),
    cloudLiveEnabled: plain(status(env.GALLIPICK_PRODUCTION_CLOUD_LIVE_ENABLED === 'true'),
      'PRODUCTION_CLOUD_LIVE_ENABLED'),
    retentionEnabled: plain(status(env.GALLIPICK_PRODUCTION_CLOUD_RETENTION_ENABLED === 'true'),
      'CLOUD_RECOVERY_POLICY_ENABLED'),
    uploadsUnpaused: plain(status(env.GALLIPICK_PRODUCTION_CLOUD_UPLOADS_PAUSED === 'false'),
      'CLOUD_CHECKOUT_NOT_PAUSED'),
    originalLimit: plain(status(/^[1-9][0-9]{0,8}$/.test(env.GALLIPICK_PRODUCTION_CLOUD_MAX_ORIGINAL_BYTES ?? '') &&
      Number(env.GALLIPICK_PRODUCTION_CLOUD_MAX_ORIGINAL_BYTES) <= MAX_ORIGINAL),
      'BOUNDED_ORIGINAL_SIZE'),
  };
  if (!validMap(settings)) {
    for (const [key, item] of Object.entries(checks)) {
      if (key !== 'deployed' && key !== 'serviceAccount') {
        checks[key] = plain('UNVERIFIED', item.detail);
      }
    }
  }
  // These results intentionally never include raw environment variables.
  return { functionName, ready: Object.values(checks).every(item => item.state === 'PASS'), checks };
}

export function evaluateCheckoutReadiness(playResources, cloudFunctions) {
  const products = Object.keys(PRODUCT_PRICE_POLICY).map(id =>
    evaluatePlaySubscription(id, playResources?.[id]));
  const functions = REQUIRED_FUNCTIONS.map(id =>
    evaluateCloudFunction(id, cloudFunctions?.[id]));
  return {
    policy: 'GP-CHECKOUT-OPS-02',
    packageName: PACKAGE,
    region: 'KR',
    checkoutConfigurationReady: products.every(item => item.ready) && functions.every(item => item.ready),
    playProducts: products,
    cloudFunctions: functions,
    note: 'Configuration review only; not a Play purchase, billing confirmation or Android QA.',
  };
}

async function fetchJson(url, token) {
  if (!token || typeof token !== 'string' || token.length < 10) return null;
  try {
    const response = await fetch(url, {
      method: 'GET',
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
      redirect: 'error',
      signal: AbortSignal.timeout(15_000),
    });
    if (!response.ok) return null;
    return await response.json();
  } catch {
    // Never log error/URL details that could expose credentials or identity.
    return null;
  }
}

async function runReadOnlyAudit() {
  if (process.env.GITHUB_REF !== 'refs/heads/main' ||
      process.env.GITHUB_REPOSITORY !== 'hebi10/GalliPick' ||
      process.env.CHECKOUT_READONLY_APPROVED !== 'true' ||
      !/^[a-f0-9]{40}$/.test(process.env.EXPECTED_MAIN_SHA ?? '') ||
      process.env.EXPECTED_MAIN_SHA !== process.env.GITHUB_SHA) {
    throw new Error('Read-only production audit requires explicit approval and an exact main SHA.');
  }
  const playToken = process.env.PLAY_READONLY_ACCESS_TOKEN;
  const cloudToken = process.env.CLOUD_READONLY_ACCESS_TOKEN;
  const playResources = {};
  const cloudFunctions = {};
  for (const productId of Object.keys(PRODUCT_PRICE_POLICY)) {
    playResources[productId] = await fetchJson(`${API_PLAY}/${productId}`, playToken);
  }
  for (const name of REQUIRED_FUNCTIONS) {
    cloudFunctions[name] = await fetchJson(`${API_CLOUD}/${name}`, cloudToken);
  }
  const audit = evaluateCheckoutReadiness(playResources, cloudFunctions);
  const output = resolve(process.env.CHECKOUT_AUDIT_REPORT || 'checkout-readiness/report.json');
  await mkdir(resolve(output, '..'), { recursive: true });
  await writeFile(output, JSON.stringify(audit, null, 2) + '\n', { encoding: 'utf8', mode: 0o600 });
  console.log('Read-only GalliPick checkout readiness:', audit.checkoutConfigurationReady
    ? 'CONFIGURATION_CHECKS_PASS' : 'NOT_VERIFIED_OR_NOT_READY');
  console.log('Sanitized findings saved. No provider response bodies or environment values are logged.');
  if (!audit.checkoutConfigurationReady) process.exitCode = 2;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await runReadOnlyAudit();
}
