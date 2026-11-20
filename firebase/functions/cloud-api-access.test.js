import assert from 'node:assert/strict';
import test from 'node:test';
import { cloudOperationAccess } from './cloud-api-access.js';

test('every public Cloud operation has an explicit least-privilege access class', () => {
    for (const action of ['list', 'download', 'status']) {
        assert.equal(cloudOperationAccess(action), 'read', action);
    }
    for (const action of ['begin', 'finish', 'getPart']) {
        assert.equal(cloudOperationAccess(action), 'write', action);
    }
    for (const action of ['getPlan', 'verify', null, '__proto__', 'delete', 'cancel']) {
        assert.equal(cloudOperationAccess(action), null);
    }
});

test('secret Cloud reads and writes cannot bypass the same lifecycle guard', () => {
    for (const action of ['status', 'unlock', 'listBackups', 'getBackupDownload', 'getBackupStatus']) {
        assert.equal(cloudOperationAccess(action, true), 'read');
    }
    for (const action of ['setup', 'beginBackup', 'finishBackup', 'getBackupPart']) {
        assert.equal(cloudOperationAccess(action, true), 'write');
    }
    for (const action of [null, '', 'delete', 'verify', 'getPlan', 'constructor']) {
        assert.equal(cloudOperationAccess(action, true), null);
    }
});
