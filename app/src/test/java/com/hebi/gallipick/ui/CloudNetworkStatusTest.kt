package com.hebi.gallipick.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudNetworkStatusTest {
    @Test fun validatedNetworksAreRequiredForAutomaticCloudRequests() {
        assertEquals(CloudNetworkStatus(), cloudNetworkStatus(false, false, false))
        assertEquals(CloudNetworkStatus(), cloudNetworkStatus(true, false, true))
        assertEquals(CloudNetworkStatus(online = true, unmetered = false),
            cloudNetworkStatus(true, true, false))
        assertEquals(CloudNetworkStatus(online = true, unmetered = true),
            cloudNetworkStatus(true, true, true))
    }
}
