import assert from 'node:assert/strict';
import test from 'node:test';
import { createPlaySubscriptions } from './play-subscriptions.js';

test('uses the fixed GalliPick package for subscriptions v2 lookups and server acknowledgement', async () => {
    const requests = [];
    const play = createPlaySubscriptions(async () => ({ async request(options) {
        requests.push(options);
        return { data: { verified: true } };
    } }));
    const token = 'synthetic/test+token';
    assert.deepEqual(await play.get(token), { verified: true });
    await play.acknowledge(token, 'cloud_test');
    const root = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.hebi.gallipick/purchases';
    assert.deepEqual(requests, [
        { method: 'GET', url: `${root}/subscriptionsv2/tokens/synthetic%2Ftest%2Btoken`, timeout: 10_000 },
        { method: 'POST', data: {}, url: `${root}/subscriptions/cloud_test/tokens/synthetic%2Ftest%2Btoken:acknowledge`, timeout: 10_000 },
    ]);
});

test('does not expose upstream token-bearing request details in a Play error', async () => {
    const token = 'synthetic-provider-failure-token';
    const play = createPlaySubscriptions(async () => ({ async request({ url }) {
        throw Object.assign(new Error(url), { response: { status: 503 } });
    } }));
    await assert.rejects(play.get(token), error => error.code === 'unavailable' && !error.message.includes(token));
});

test('identifies a permanently unavailable subscription only on Play lookups', async () => {
    const play = createPlaySubscriptions(async () => ({ async request({ url }) {
        throw Object.assign(new Error(url), { response: { status: 410 } });
    } }));
    const token = 'synthetic-permanently-invalid-token';
    await assert.rejects(play.get(token), error => error.code === 'not-found' && !error.message.includes(token));
    await assert.rejects(play.acknowledge(token, 'cloud_test'), error => error.code === 'failed-precondition');
});
