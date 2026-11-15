package com.hebi.gallipick.data.cloud

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.hebi.gallipick.R
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

internal fun createCloudAccountConnection(context: Context): CloudAccountConnection {
    val appId = context.getString(R.string.cloud_firebase_app_id)
    val apiKey = context.getString(R.string.cloud_firebase_api_key)
    val projectId = context.getString(R.string.cloud_firebase_project_id)
    val webClientId = context.getString(R.string.cloud_google_web_client_id)
    if (listOf(appId, apiKey, projectId, webClientId).any { it.isBlank() }) {
        return CloudAccountConnection(CloudAccountSession(null)) {
            throw CloudAccountFailure(CloudAccountNotice.GOOGLE_UNAVAILABLE)
        }
    }

    val options = FirebaseOptions.Builder()
        .setApplicationId(appId)
        .setApiKey(apiKey)
        .setProjectId(projectId)
        .build()
    val firebaseApp = FirebaseApp.initializeApp(context, options, "gallipick-cloud-test")
    val credentials = CredentialManager.create(context)
    val auth = FirebaseAuth.getInstance(firebaseApp)
    val session = CloudAccountSession(FirebaseCloudAccountBackend(auth, credentials))
    val transport = CloudBackupTransport(
        endpoint = "https://asia-northeast3-gallipick-dev.cloudfunctions.net",
        buckets = setOf("gallipick-dev.firebasestorage.app"),
    )
    val backup = CloudBackup(
        context = context,
        account = session.account,
        currentUid = { auth.currentUser?.uid },
        tokenFor = { uid -> backupIdToken(auth, uid) },
        transport = transport,
    )
    val billing = PlayCloudBilling(context, { auth.currentUser?.uid }, { uid -> backupIdToken(auth, uid) }, transport)
    return CloudAccountConnection(session, backup, billing = billing) { activity ->
        googleIdToken(credentials, activity, webClientId)
    }
}

private suspend fun backupIdToken(auth: FirebaseAuth, uid: String): String {
    val user = auth.currentUser?.takeIf { it.uid == uid } ?: failBackup(CloudBackupResult.ACCOUNT_CHANGED)
    return suspendCancellableCoroutine { continuation ->
        user.getIdToken(false).addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            val token = if (task.isSuccessful) task.result?.token else null
            when {
                auth.currentUser?.uid != uid -> continuation.resumeWithException(CloudBackupException(CloudBackupResult.ACCOUNT_CHANGED))
                !token.isNullOrBlank() -> continuation.resume(token)
                task.exception is FirebaseNetworkException -> continuation.resumeWithException(CloudBackupException(CloudBackupResult.NETWORK_ERROR))
                else -> continuation.resumeWithException(CloudBackupException(CloudBackupResult.TEST_ACCESS_REQUIRED))
            }
        }
    }
}

private suspend fun googleIdToken(manager: CredentialManager, activity: Activity, webClientId: String): String {
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
        .build()
    val credential = try {
        manager.getCredential(activity, request).credential
    } catch (_: GetCredentialCancellationException) {
        throw CloudAccountFailure(CloudAccountNotice.CANCELLED)
    } catch (_: NoCredentialException) {
        throw CloudAccountFailure(CloudAccountNotice.NO_GOOGLE_ACCOUNT)
    } catch (_: GetCredentialProviderConfigurationException) {
        throw CloudAccountFailure(CloudAccountNotice.GOOGLE_UNAVAILABLE)
    } catch (_: GetCredentialUnsupportedException) {
        throw CloudAccountFailure(CloudAccountNotice.GOOGLE_UNAVAILABLE)
    }
    if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        throw CloudAccountFailure(CloudAccountNotice.SIGN_IN_FAILED)
    }
    return GoogleIdTokenCredential.createFrom(credential.data).idToken
}

private class FirebaseCloudAccountBackend(
    private val auth: FirebaseAuth,
    private val credentials: CredentialManager,
) : CloudAccountBackend {
    private val currentAccount = MutableStateFlow(auth.currentUser?.identity())
    override val account = currentAccount.asStateFlow()

    init {
        auth.addAuthStateListener { currentAccount.value = it.currentUser?.identity() }
    }

    override suspend fun signIn(googleIdToken: String): Unit = suspendCoroutine { continuation ->
        auth.signInWithCredential(GoogleAuthProvider.getCredential(googleIdToken, null))
            .addOnCompleteListener { result ->
                val user = if (result.isSuccessful) result.result?.user else null
                if (user != null) {
                    currentAccount.value = auth.currentUser?.identity()
                    continuation.resume(Unit)
                } else {
                    val notice = if (result.exception is FirebaseNetworkException) CloudAccountNotice.NETWORK_ERROR
                    else CloudAccountNotice.SIGN_IN_FAILED
                    continuation.resumeWithException(CloudAccountFailure(notice))
                }
            }
    }

    override fun signOut() {
        auth.signOut()
        currentAccount.value = null
    }

    override suspend fun clearGoogleState() {
        try {
            credentials.clearCredentialState(ClearCredentialStateRequest())
        } catch (_: ClearCredentialException) {
            throw CloudAccountFailure(CloudAccountNotice.CREDENTIAL_CLEAR_FAILED)
        }
    }

    private fun FirebaseUser.identity() = CloudAccountIdentity(uid, displayName, email)
}
