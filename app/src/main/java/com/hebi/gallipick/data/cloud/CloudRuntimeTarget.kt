package com.hebi.gallipick.data.cloud

internal data class CloudRuntimeTarget(
    val projectId: String,
    val functionsEndpoint: String,
    val storageBucket: String,
)

/**
 * Transport destinations are fixed to trusted Firebase projects. An unknown
 * configuration cannot turn app authentication tokens into network requests.
 */
internal fun cloudRuntimeFor(projectId: String): CloudRuntimeTarget? = when (projectId) {
    "gallipick-dev" -> CloudRuntimeTarget(
        projectId = projectId,
        functionsEndpoint = "https://asia-northeast3-gallipick-dev.cloudfunctions.net",
        storageBucket = "gallipick-dev.firebasestorage.app",
    )
    "gallipick-production" -> CloudRuntimeTarget(
        projectId = projectId,
        functionsEndpoint = "https://asia-northeast3-gallipick-production.cloudfunctions.net",
        storageBucket = "gallipick-production.firebasestorage.app",
    )
    else -> null
}
