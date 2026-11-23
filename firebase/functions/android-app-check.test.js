import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

async function callWithoutTokens(call) {
    const headers = { 'content-type': 'application/json' };
    const request = {
        method: 'POST',
        headers,
        header: name => headers[name.toLowerCase()],
        body: { data: {} },
    };
    const response = Object.assign(new EventEmitter(), {
        getHeader: () => undefined,
        setHeader: () => {},
        status(code) {
            this.statusCode = code;
            return this;
        },
        send(body) {
            this.body = body;
            this.emit('finish');
        },
    });
    await call(request, response);
    return { status: response.statusCode, error: response.body.error };
}

test('Android App Check observes legacy requests by default and enforces only when enabled', async () => {
    process.env.GCLOUD_PROJECT = 'gallipick-dev';
    delete process.env.GALLIPICK_ANDROID_APP_CHECK_ENFORCE;
    const monitoring = await import('./index.js?app-check-monitoring');
    process.env.GALLIPICK_ANDROID_APP_CHECK_ENFORCE = 'true';
    const enforcing = await import('./index.js?app-check-enforcing');

    for (const name of ['beginBackup', 'finishBackup', 'listBackups', 'getBackupDownload', 'getBackupPart',
        'getBackupStatus', 'getCloudPlan', 'verifyCloudSubscription', 'secretCloud', 'requestAccountExit',
        'resumeCloudAfterDeletion', 'getAccountExitStatus']) {
        const current = await callWithoutTokens(monitoring[name]);
        assert.notEqual(current.error.message, 'Unauthenticated', name);
        const enforced = await callWithoutTokens(enforcing[name]);
        assert.equal(enforced.status, 401, name);
        assert.deepEqual(enforced.error, { message: 'Unauthenticated', status: 'UNAUTHENTICATED' }, name);
    }

    for (const name of ['getAdminOverview', 'issueAdminManualGrant', 'retryAdminFailedDeletion']) {
        assert.deepEqual(await callWithoutTokens(monitoring[name]), await callWithoutTokens(enforcing[name]), name);
    }
});
