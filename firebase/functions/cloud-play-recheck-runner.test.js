import assert from 'node:assert/strict';
import test from 'node:test';
import {
    productionPlayRecheckAllowed, PLAY_RECHECK_SCHEDULE, PLAY_RECHECK_MAX_PER_MODE,
} from './cloud-play-recheck-runner.js';

const approvedKey = 'projects/gallipick-production/locations/asia-northeast3/keyRings/gallipick-billing/cryptoKeys/escrow-key';
const gates = {
    GALLIPICK_PRODUCTION_AD_FREE_ENABLED: 'true',
    GALLIPICK_PRODUCTION_CLOUD_RETENTION_ENABLED: 'true',
    GALLIPICK_CLOUD_PURGE_REVIEW_ENABLED: 'true',
    GALLIPICK_PRODUCTION_PLAY_TOKEN_ESCROW_ENABLED: 'true',
    GALLIPICK_PRODUCTION_PLAY_RECHECK_ENABLED: 'true',
    GALLIPICK_PRODUCTION_PLAY_TOKEN_KMS_KEY: approvedKey,
};

test('no scheduled Play rechecks occur in dev, when disabled, or with an incomplete KMS key', () => {
    assert.equal(productionPlayRecheckAllowed('gallipick-production', gates), true);
    assert.equal(productionPlayRecheckAllowed('gallipick-dev', gates), false);
    assert.equal(productionPlayRecheckAllowed('another-project', gates), false);
    assert.equal(productionPlayRecheckAllowed('gallipick-production', {}), false);
    assert.equal(productionPlayRecheckAllowed('gallipick-production', null), false);
    for (const key of Object.keys(gates)) {
        assert.equal(productionPlayRecheckAllowed('gallipick-production', {
            ...gates, [key]: key.endsWith('_KEY') ? '' : 'false',
        }), false, key);
    }
    assert.equal(productionPlayRecheckAllowed('gallipick-production', {
        ...gates,
        GALLIPICK_PRODUCTION_PLAY_TOKEN_KMS_KEY: 'projects/another-project/locations/us-central1/keyRings/foo/cryptoKeys/bar',
    }), false);
});

test('the scheduler remains bounded and runs at most twice daily', () => {
    assert.equal(PLAY_RECHECK_SCHEDULE, 'every 12 hours');
    assert.ok(Number.isSafeInteger(PLAY_RECHECK_MAX_PER_MODE));
    assert.ok(PLAY_RECHECK_MAX_PER_MODE >= 1 && PLAY_RECHECK_MAX_PER_MODE <= 5);
});
