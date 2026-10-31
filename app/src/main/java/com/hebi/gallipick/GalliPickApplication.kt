package com.hebi.gallipick

import android.app.Application
import com.hebi.gallipick.data.MediaRepository

class GalliPickApplication : Application() {
    val repository: MediaRepository by lazy { MediaRepository(this) }
}
