package com.hebi.gallipick.data.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CloudAccountSessionTest {
    @Test
    fun missingConfigurationDoesNotOpenTheAccountPicker() = runTest {
        val session = CloudAccountSession(null)

        session.chooseAccount { error("Picker must stay closed") }

        assertFalse(session.available)
        assertNull(session.account.value)
        assertEquals(CloudAccountStatus(), session.status.value)
    }

    @Test
    fun cancelledSwitchKeepsTheExistingAccountAndCanBeRetried() = runTest {
        val backend = TestBackend(first)
        val session = CloudAccountSession(backend)

        session.chooseAccount { throw CloudAccountFailure(CloudAccountNotice.CANCELLED) }

        assertEquals(first, session.account.value)
        assertEquals(CloudAccountNotice.CANCELLED, session.status.value.notice)
        assertNull(session.status.value.operation)

        session.chooseAccount { "second-test-credential" }

        assertEquals(second, session.account.value)
        assertEquals(2, backend.clears)
        assertEquals(CloudAccountStatus(), session.status.value)
    }

    @Test
    fun failedSwitchKeepsTheCurrentAccountAndShowsTheNetworkError() = runTest {
        val backend = TestBackend(first).apply {
            signInFailure = CloudAccountNotice.NETWORK_ERROR
        }
        val session = CloudAccountSession(backend)

        session.chooseAccount { "second-test-credential" }

        assertEquals(first, session.account.value)
        assertEquals(CloudAccountNotice.NETWORK_ERROR, session.status.value.notice)
    }

    @Test
    fun signOutRemovesTheSessionEvenIfThePickerCannotClearItsState() = runTest {
        val backend = TestBackend(first).apply { failClear = true }
        val session = CloudAccountSession(backend)

        session.signOut()

        assertNull(session.account.value)
        assertEquals(1, backend.clears)
        assertEquals(CloudAccountNotice.CREDENTIAL_CLEAR_FAILED, session.status.value.notice)
    }

    @Test
    fun aPendingExchangePreventsAnotherPickerOrAnOverlappingSignOut() = runTest {
        val backend = TestBackend(first)
        val session = CloudAccountSession(backend)
        val picking = CompletableDeferred<Unit>()
        val selected = CompletableDeferred<String>()
        val choosing = launch {
            session.chooseAccount {
                picking.complete(Unit)
                selected.await()
            }
        }
        picking.await()

        session.chooseAccount { error("A second picker must stay closed") }
        session.signOut()

        assertEquals(first, session.account.value)
        assertEquals(CloudAccountOperation.CHOOSING_ACCOUNT, session.status.value.operation)

        selected.complete("second-test-credential")
        choosing.join()

        assertEquals(second, session.account.value)
        assertEquals(CloudAccountStatus(), session.status.value)
    }

    private class TestBackend(existing: CloudAccountIdentity?) : CloudAccountBackend {
        override val account = MutableStateFlow(existing)
        var clears = 0
        var failClear = false
        var signInFailure: CloudAccountNotice? = null

        override suspend fun signIn(googleIdToken: String) {
            signInFailure?.let { throw CloudAccountFailure(it) }
            account.value = second
        }

        override fun signOut() {
            account.value = null
        }

        override suspend fun clearGoogleState() {
            clears++
            if (failClear) throw CloudAccountFailure(CloudAccountNotice.CREDENTIAL_CLEAR_FAILED)
        }
    }

    private companion object {
        val first = CloudAccountIdentity("uid-first", "첫 계정", "first@example.test")
        val second = CloudAccountIdentity("uid-second", "둘째 계정", "second@example.test")
    }
}
