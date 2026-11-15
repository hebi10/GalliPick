package com.hebi.gallipick.data.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal data class CloudAccountIdentity(val uid: String, val displayName: String?, val email: String?)

internal enum class CloudAccountOperation { CHOOSING_ACCOUNT, SIGNING_OUT }

internal enum class CloudAccountNotice {
    CANCELLED, NO_GOOGLE_ACCOUNT, GOOGLE_UNAVAILABLE, NETWORK_ERROR,
    SIGN_IN_FAILED, SIGN_OUT_FAILED, CREDENTIAL_CLEAR_FAILED,
}

internal data class CloudAccountStatus(
    val operation: CloudAccountOperation? = null,
    val notice: CloudAccountNotice? = null,
)

internal class CloudAccountFailure(val notice: CloudAccountNotice) : Exception()

internal interface CloudAccountBackend {
    val account: StateFlow<CloudAccountIdentity?>
    suspend fun signIn(googleIdToken: String)
    fun signOut()
    suspend fun clearGoogleState()
}

internal class CloudAccountSession(private val backend: CloudAccountBackend?) {
    val available = backend != null
    val account: StateFlow<CloudAccountIdentity?> = backend?.account ?: MutableStateFlow(null)

    private val operationLock = Mutex()
    private val currentStatus = MutableStateFlow(CloudAccountStatus())
    val status = currentStatus.asStateFlow()

    suspend fun chooseAccount(getGoogleIdToken: suspend () -> String) =
        runOperation(CloudAccountOperation.CHOOSING_ACCOUNT, CloudAccountNotice.SIGN_IN_FAILED) { connected ->
            if (connected.account.value != null) connected.clearGoogleState()
            val googleIdToken = getGoogleIdToken()
            // The Firebase exchange can finish after the account chooser's Activity is recreated.
            withContext(NonCancellable) { connected.signIn(googleIdToken) }
        }

    suspend fun signOut() =
        runOperation(CloudAccountOperation.SIGNING_OUT, CloudAccountNotice.SIGN_OUT_FAILED) { connected ->
            withContext(NonCancellable) {
                connected.signOut()
                connected.clearGoogleState()
            }
        }

    private suspend fun runOperation(
        operation: CloudAccountOperation,
        fallbackNotice: CloudAccountNotice,
        perform: suspend (CloudAccountBackend) -> Unit,
    ) {
        val connected = backend ?: return
        if (!operationLock.tryLock()) return
        currentStatus.value = CloudAccountStatus(operation = operation)
        try {
            perform(connected)
            currentStatus.value = CloudAccountStatus()
        } catch (error: CancellationException) {
            currentStatus.value = CloudAccountStatus()
            throw error
        } catch (error: CloudAccountFailure) {
            currentStatus.value = CloudAccountStatus(notice = error.notice)
        } catch (_: Exception) {
            currentStatus.value = CloudAccountStatus(notice = fallbackNotice)
        } finally {
            operationLock.unlock()
        }
    }
}
