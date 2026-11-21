package com.hebi.gallipick.data.cloud

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
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
    val runtime = cloudRuntimeFor(projectId)
    if (runtime == null || listOf(appId, apiKey, projectId, webClientId).any { it.isBlank() }) {
        return CloudAccountConnection(CloudAccountSession(null)) {
            throw CloudAccountFailure(CloudAccountNotice.GOOGLE_UNAVAILABLE)
        }
    }

    val options = FirebaseOptions.Builder()
        .setApplicationId(appId)
        .setApiKey(apiKey)
        .setProjectId(projectId)
        .build()
    val firebaseApp = FirebaseApp.initializeApp(context, options, "gallipick-cloud-${runtime.projectId}")
    val credentials = CredentialManager.create(context)
    val auth = FirebaseAuth.getInstance(firebaseApp)
    val session = CloudAccountSession(FirebaseCloudAccountBackend(auth, credentials))
    val transport = CloudBackupTransport(
        endpoint = runtime.functionsEndpoint,
        buckets = setOf(runtime.storageBucket),
        productionProtocol = runtime.backupUsesServerPlan,
    )
    val backupAccess = if (runtime.backupEnabled || runtime.backupUsesServerPlan) {
        CloudBackupAccessState(development = !runtime.backupUsesServerPlan) { auth.currentUser?.uid }.also { state ->
            auth.addAuthStateListener { state.clearForAccountChange() }
        }
    } else null
    val backup = if (runtime.backupEnabled || backupAccess != null) {
        CloudBackup(
            context = context,
            account = session.account,
            currentUid = { auth.currentUser?.uid },
            tokenFor = { uid -> backupIdToken(auth, uid) },
            transport = transport,
            productionAccess = backupAccess,
        )
    } else null
    val billing = PlayCloudBilling(
        context,
        { auth.currentUser?.uid },
        { uid -> backupIdToken(auth, uid) },
        transport,
        testPurchasesOnly = runtime.projectId == "gallipick-dev",
        backupAccess = backupAccess,
    )
    val vault = if (backup != null) CloudSecretVault(transport, { uid -> backupIdToken(auth, uid) }) { vaultToken ->
        CloudBackup(
            context = context,
            account = session.account,
            currentUid = { auth.currentUser?.uid },
            tokenFor = { uid -> backupIdToken(auth, uid) },
            transport = CloudBackupTransport(
                endpoint = runtime.functionsEndpoint,
                buckets = setOf(runtime.storageBucket),
                productionProtocol = runtime.backupUsesServerPlan,
                vaultSessionToken = vaultToken,
            ),
            productionAccess = backupAccess,
        )
    } else null
    val accountExit = if (runtime.backupUsesServerPlan) FirebaseCloudAccountExit(
        currentUid = { auth.currentUser?.uid },
        idTokenFor = { uid, force -> backupIdToken(auth, uid, force) },
        reauthenticate = { activity, uid -> reauthenticateGoogle(auth, credentials, webClientId, activity, uid) },
        transport = transport,
    ) else null
    return CloudAccountConnection(session, backup, billing = billing, productionBackupAccess = backupAccess,
        secretVault = vault, accountExit = accountExit) { activity ->
        googleIdToken(credentials, activity, webClientId)
    }
}

private suspend fun backupIdToken(auth: FirebaseAuth, uid: String, forceRefresh: Boolean = false): String {
    val user = auth.currentUser?.takeIf { it.uid == uid } ?: failBackup(CloudBackupResult.ACCOUNT_CHANGED)
    return suspendCancellableCoroutine { continuation ->
        user.getIdToken(forceRefresh).addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            val token = if (task.isSuccessful) task.result?.token else null
            when {
                auth.currentUser?.uid != uid -> continuation.resumeWithException(CloudBackupException(CloudBackupResult.ACCOUNT_CHANGED))
                !token.isNullOrBlank() -> continuation.resume(token)
                task.exception is FirebaseNetworkException -> continuation.resumeWithException(
                    cloudBackupNetworkError(CloudBackupNetworkFailure.CONNECTION),
                )
                else -> continuation.resumeWithException(CloudBackupException(CloudBackupResult.TEST_ACCESS_REQUIRED))
            }
        }
    }
}

private suspend fun reauthenticateGoogle(
    auth: FirebaseAuth, manager: CredentialManager, webClientId: String, activity: Activity, uid: String,
) {
    val user = auth.currentUser?.takeIf { it.uid == uid } ?: failBackup(CloudBackupResult.ACCOUNT_CHANGED)
    val idToken = googleIdToken(manager, activity, webClientId)
    if (auth.currentUser?.uid != uid) failBackup(CloudBackupResult.ACCOUNT_CHANGED)
    suspendCancellableCoroutine<Unit> { continuation ->
        user.reauthenticate(GoogleAuthProvider.getCredential(idToken, null)).addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            when {
                auth.currentUser?.uid != uid ->
                    continuation.resumeWithException(CloudBackupException(CloudBackupResult.ACCOUNT_CHANGED))
                task.isSuccessful -> continuation.resume(Unit)
                else -> continuation.resumeWithException(CloudAccountFailure(CloudAccountNotice.FIREBASE_SIGN_IN_FAILED))
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
    } catch (_: GetCredentialException) {
        throw CloudAccountFailure(CloudAccountNotice.GOOGLE_CREDENTIAL_FAILED)
    }
    if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        throw CloudAccountFailure(CloudAccountNotice.GOOGLE_CREDENTIAL_FAILED)
    }
    return try {
        GoogleIdTokenCredential.createFrom(credential.data).idToken
    } catch (_: GoogleIdTokenParsingException) {
        throw CloudAccountFailure(CloudAccountNotice.GOOGLE_CREDENTIAL_FAILED)
    }
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
                    val notice = when (val error = result.exception) {
                        is FirebaseNetworkException -> CloudAccountNotice.NETWORK_ERROR
                        is FirebaseAuthException -> firebaseGoogleSignInFailure(error.errorCode)
                        else -> CloudAccountNotice.FIREBASE_SIGN_IN_FAILED
                    }
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
