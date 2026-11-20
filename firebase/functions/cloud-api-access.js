// Every public Cloud operation must be classified before it reaches its handler.
const backupReads = new Set(['list', 'download', 'status']);
const backupWrites = new Set(['begin', 'finish', 'getPart']);
const secretReads = new Set(['status', 'unlock', 'listBackups', 'getBackupDownload', 'getBackupStatus']);
const secretWrites = new Set(['setup', 'beginBackup', 'finishBackup', 'getBackupPart']);

export function cloudOperationAccess(operation, secret = false) {
    const reads = secret ? secretReads : backupReads;
    const writes = secret ? secretWrites : backupWrites;
    if (reads.has(operation)) return 'read';
    if (writes.has(operation)) return 'write';
    throw new Error('Unclassified Cloud operation');
}
