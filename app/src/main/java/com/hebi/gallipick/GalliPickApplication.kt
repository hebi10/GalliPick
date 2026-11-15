package com.hebi.gallipick

import android.app.Application
import com.hebi.gallipick.data.MediaRepository
import com.hebi.gallipick.data.cloud.createCloudAccountConnection

class GalliPickApplication : Application() {
    val repository: MediaRepository by lazy { MediaRepository(this) }
    internal val cloudAccount by lazy { createCloudAccountConnection(this) }
}
