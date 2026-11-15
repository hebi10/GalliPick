package com.hebi.gallipick.data.cloud

import java.io.IOException
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudBackupTransportTest {
    @Test
    fun aFailedWifiConnectionNeverOpensTheDefaultRoute() {
        val url = URL(null, "https://storage.googleapis.com/example/original", object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = throw AssertionError("Default route opened")
        })
        val unavailable = IOException("Wi-Fi disconnected")

        val error = assertThrows(IOException::class.java) {
            openCloudBackupConnection(url) { target ->
                assertSame(url, target)
                throw unavailable
            }
        }
        assertSame(unavailable, error)
    }
}
