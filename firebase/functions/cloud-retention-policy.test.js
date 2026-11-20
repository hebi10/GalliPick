import assert from 'node:assert/strict';
import test from 'node:test';
import { cloudRetentionState, CLOUD_RECOVERY_WINDOW_MS, CLOUD_PURGE_WINDOW_MS } from './cloud-retention-policy.js';

const day = 24 * 60 * 60 * 1000;
const lastExpiry = 1_800_000_000_000;
const stateAt = (offset, options = {}) => cloudRetentionState({
    nowMs: lastExpiry + offset, lastVerifiedCloudExpiryMs: lastExpiry, ...options,
});

test('cancelled auto-renewal retains full service until the Play-verified paid-through expiry', () => {
    const active = stateAt(-1);
    assert.equal(active.state, 'PAID');
    assert.equal(active.mayUpload, true);
    assert.equal(active.mayDownload, true);
    assert.equal(active.deletionDueAtMs, null);
});

test('expiry gives seven days of read-only recovery, not a shortened prepaid period', () => {
    for (const time of [0, 6 * day, CLOUD_RECOVERY_WINDOW_MS - 1]) {
        const result = stateAt(time);
        assert.equal(result.state, 'READ_ONLY_RECOVERY');
        assert.equal(result.mayBrowse, true);
        assert.equal(result.mayDownload, true);
        assert.equal(result.mayUpload, false);
        assert.equal(result.recoveryEndsAtMs, lastExpiry + CLOUD_RECOVERY_WINDOW_MS);
        assert.equal(result.deletionDueAtMs, lastExpiry + CLOUD_PURGE_WINDOW_MS);
    }
    const locked = stateAt(CLOUD_RECOVERY_WINDOW_MS);
    assert.equal(locked.state, 'LOCKED_AWAITING_PURGE');
    assert.equal(locked.mayBrowse, false);
    assert.equal(locked.mayDownload, false);
});

test('the fourteenth day is a due-for-deletion marker, never proof that files were removed', () => {
    assert.equal(stateAt(CLOUD_PURGE_WINDOW_MS - 1).state, 'LOCKED_AWAITING_PURGE');
    const due = stateAt(CLOUD_PURGE_WINDOW_MS);
    assert.equal(due.state, 'PURGE_DUE');
    assert.equal(due.mayBrowse, false);
    assert.equal(due.mayDownload, false);
});

test('a renewed and server-verified paid-through date supersedes an old expiration', () => {
    const renewed = cloudRetentionState({
        nowMs: lastExpiry + 13 * day,
        lastVerifiedCloudExpiryMs: lastExpiry + 29 * day,
    });
    assert.equal(renewed.state, 'PAID');
    assert.equal(renewed.deletionDueAtMs, null);
});

test('an explicit account deletion request immediately blocks recovery, even for a paid user', () => {
    const requestedAt = lastExpiry - day;
    const requested = stateAt(-day, { deletionRequestedAtMs: requestedAt });
    assert.equal(requested.state, 'ACCOUNT_DELETION_REQUESTED');
    assert.equal(requested.mayBrowse, false);
    assert.equal(requested.mayDownload, false);
    assert.equal(requested.mayUpload, false);
    assert.equal(requested.deletionDueAtMs, requestedAt + CLOUD_PURGE_WINDOW_MS);
});

test('unknown purchases do not make up deadlines or enable cloud access', () => {
    const unknown = cloudRetentionState({ nowMs: lastExpiry });
    assert.equal(unknown.state, 'NO_VERIFIED_CLOUD_EXPIRY');
    assert.equal(unknown.deletionDueAtMs, null);
    assert.equal(unknown.mayDownload, false);
    for (const input of [
        { nowMs: -1 },
        { nowMs: Number.NaN },
        { nowMs: lastExpiry, lastVerifiedCloudExpiryMs: -1 },
        { nowMs: lastExpiry, deletionRequestedAtMs: lastExpiry + 1 },
        { nowMs: Number.MAX_SAFE_INTEGER, lastVerifiedCloudExpiryMs: lastExpiry },
    ]) assert.throws(() => cloudRetentionState(input), RangeError);
});
