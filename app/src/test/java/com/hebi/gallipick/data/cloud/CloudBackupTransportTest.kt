package com.hebi.gallipick.data.cloud

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudBackupTransportTest {
    @Test
    fun forbiddenWithoutACallableBodyRemainsAnAccountRestriction() {
        listOf(null, "", "<html>Forbidden</html>").forEach { body ->
            val error = assertThrows(CloudBackupException::class.java) {
                readCloudBackupCallableResult(HttpURLConnection.HTTP_FORBIDDEN, 128, { body?.byteInputStream() }, {})
            }
            assertEquals(CloudBackupResult.TEST_ACCESS_REQUIRED, error.result)
            assertTrue(error.permissionDenied)
        }
    }

    @Test
    fun unauthorizedRemainsARecoverableSignInCheck() {
        val error = assertThrows(CloudBackupException::class.java) {
            readCloudBackupCallableResult(HttpURLConnection.HTTP_UNAUTHORIZED, 128, { null }, {})
        }
        assertEquals(CloudBackupResult.TEST_ACCESS_REQUIRED, error.result)
        assertFalse(error.permissionDenied)
    }

    @Test
    fun callableTimeoutsAndServiceFailuresHaveSeparateReasons() {
        val cases = mapOf(
            HttpURLConnection.HTTP_CLIENT_TIMEOUT to CloudBackupNetworkFailure.TIMEOUT,
            HttpURLConnection.HTTP_GATEWAY_TIMEOUT to CloudBackupNetworkFailure.TIMEOUT,
            HttpURLConnection.HTTP_UNAVAILABLE to CloudBackupNetworkFailure.SERVICE,
        )
        cases.forEach { (status, reason) ->
            val error = assertThrows(CloudBackupException::class.java) {
                readCloudBackupCallableResult(status, 128, { null }, {})
            }
            assertEquals(CloudBackupResult.NETWORK_ERROR, error.result)
            assertEquals(reason, error.networkFailure)
        }
    }

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
