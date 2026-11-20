package com.hebi.gallipick.data.cloud

import android.app.Activity

internal enum class CloudExitScope { ACCOUNT, CLOUD }

internal enum class CloudExitState {
    NONE, REQUESTED, IN_PROGRESS, RUNNING, RETRY, MANUAL_REVIEW, COMPLETED, RESUMED
}

internal data class CloudExitSnapshot(
    val state: CloudExitState,
    val scope: CloudExitScope? = null,
    val requestedAtMs: Long? = null,
    val deadlineAtMs: Long? = null,
    val completedAtMs: Long? = null,
) {
    val accountRemovalPending: Boolean
        get() = scope == CloudExitScope.ACCOUNT && state != CloudExitState.NONE

    val deletionPending: Boolean
        get() = state in setOf(
            CloudExitState.REQUESTED, CloudExitState.IN_PROGRESS,
            CloudExitState.RUNNING, CloudExitState.RETRY, CloudExitState.MANUAL_REVIEW,
        )
}

internal interface CloudAccountExit {
    suspend fun status(uid: String): CloudExitSnapshot
    suspend fun request(activity: Activity, uid: String, scope: CloudExitScope): CloudExitSnapshot
    suspend fun resumeCloudBackup(activity: Activity, uid: String): CloudExitSnapshot
}
