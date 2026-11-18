package com.hebi.gallipick.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRuntimeTargetTest {
    @Test fun devTargetUsesExistingPrivateEndpoint() {
        val dev = requireNotNull(cloudRuntimeFor("gallipick-dev"))
        assertEquals("https://asia-northeast3-gallipick-dev.cloudfunctions.net", dev.functionsEndpoint)
        assertEquals("gallipick-dev.firebasestorage.app", dev.storageBucket)
    }

    @Test fun productionTargetNeverReusesDevelopmentStorageOrFunctions() {
        val dev = requireNotNull(cloudRuntimeFor("gallipick-dev"))
        val prod = requireNotNull(cloudRuntimeFor("gallipick-production"))
        assertTrue(prod.functionsEndpoint.startsWith("https://"))
        assertTrue(prod.functionsEndpoint != dev.functionsEndpoint)
        assertTrue(prod.storageBucket != dev.storageBucket)
    }

    @Test fun unknownProjectIsRejectedWithoutNetworkFallback() {
        assertNull(cloudRuntimeFor(""))
        assertNull(cloudRuntimeFor("gallipick-dev-copy"))
        assertNull(cloudRuntimeFor("gallipick-production.example"))
    }
}
