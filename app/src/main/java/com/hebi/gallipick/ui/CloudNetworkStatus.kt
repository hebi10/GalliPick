package com.hebi.gallipick.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

internal data class CloudNetworkStatus(
    val online: Boolean = false,
    val unmetered: Boolean = false,
)

/** A validated network is required; captive-portal Wi-Fi is treated as offline. */
internal fun cloudNetworkStatus(internet: Boolean, validated: Boolean, unmetered: Boolean) =
    CloudNetworkStatus(online = internet && validated, unmetered = internet && validated && unmetered)

private fun readNetwork(manager: ConnectivityManager?): CloudNetworkStatus {
    val active = manager?.activeNetwork ?: return CloudNetworkStatus()
    val caps = manager.getNetworkCapabilities(active) ?: return CloudNetworkStatus()
    return cloudNetworkStatus(
        internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )
}

/** One lifecycle-scoped network observer, never a per-thumbnail network monitor. */
@Composable
internal fun rememberCloudNetworkStatus(enabled: Boolean): CloudNetworkStatus {
    val context = LocalContext.current.applicationContext
    val manager = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    var status by remember(manager) {
        mutableStateOf(runCatching { readNetwork(manager) }.getOrDefault(CloudNetworkStatus()))
    }
    DisposableEffect(manager, enabled) {
        if (!enabled || manager == null) {
            onDispose {}
        } else {
            val main = Handler(Looper.getMainLooper())
            val callback = object : ConnectivityManager.NetworkCallback() {
                private fun update() {
                    status = runCatching { readNetwork(manager) }.getOrDefault(CloudNetworkStatus())
                }
                override fun onAvailable(network: Network) = update()
                override fun onLost(network: Network) = update()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
            }
            val registered = runCatching {
                manager.registerDefaultNetworkCallback(callback, main)
            }.isSuccess
            status = runCatching { readNetwork(manager) }.getOrDefault(CloudNetworkStatus())
            onDispose {
                if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
            }
        }
    }
    return if (enabled) status else CloudNetworkStatus()
}
