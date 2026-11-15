package com.hebi.gallipick.data.cloud

import android.app.Activity

internal class CloudAccountConnection(
    val session: CloudAccountSession,
    private val getGoogleIdToken: suspend (Activity) -> String,
) {
    suspend fun chooseAccount(activity: Activity) = session.chooseAccount { getGoogleIdToken(activity) }
}
