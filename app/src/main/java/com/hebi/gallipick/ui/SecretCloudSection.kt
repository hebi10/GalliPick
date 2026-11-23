package com.hebi.gallipick.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudBackupException
import com.hebi.gallipick.data.cloud.CloudBackupResult
import com.hebi.gallipick.data.cloud.CloudOriginal
import com.hebi.gallipick.data.cloud.CloudSecretSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
internal fun SecretCloudSection(
    connection: CloudAccountConnection,
    state: AppUiState,
    requestAccess: () -> Unit,
) {
    val observedAccount by connection.session.account.collectAsStateWithLifecycle()
    val accountStatus by connection.session.status.collectAsStateWithLifecycle()
    val account = observedAccount
    val vault = connection.secretVault
    when {
        accountStatus.operation != null -> CloudAccountCard { CloudStatusMessage("계정 연결 상태를 확인하고 있습니다.") }
        account == null -> CloudAccountCard { CloudStatusMessage("Google 계정으로 로그인해 주세요.") }
        vault == null -> CloudAccountCard { CloudStatusMessage("현재 시크릿 클라우드 서버를 이용할 수 없습니다.") }
        else -> key(connection, account.uid, vault) {
            if (SecureVaultWindow()) {
                SecretVaultAccess(connection, account.uid, vault::configured, vault::unlock) { session, lock ->
                    SecretVaultGallery(session, account.uid, account.transferAccountLabel, state, requestAccess, lock)
                }
            }
            else CloudAccountCard { CloudStatusMessage("이 화면에서 시크릿 클라우드를 안전하게 열 수 없습니다.") }
        }
    }
}

@Composable
internal fun SecretCloudPasswordSettings(
    connection: CloudAccountConnection?,
    onClose: () -> Unit,
    onRegistered: () -> Unit,
    readConfigured: (suspend (String) -> Boolean)? = connection?.secretVault?.let { it::configured },
    openSession: (suspend (String, String, Boolean) -> CloudSecretSession)? = connection?.secretVault?.let { it::unlock },
) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxHeight().widthIn(max = 660.dp).fillMaxWidth().imePadding()
                .verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = GalliUiMetrics.touchTarget),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction("뒤로", Icons.AutoMirrored.Outlined.ArrowBack, onClick = onClose)
                Text("비밀번호 설정", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            if (connection == null || !connection.session.available || readConfigured == null || openSession == null) {
                CloudAccountCard { CloudStatusMessage("현재 시크릿 클라우드 서버를 이용할 수 없습니다.") }
                return@Column
            }
            val account by connection.session.account.collectAsStateWithLifecycle()
            val accountStatus by connection.session.status.collectAsStateWithLifecycle()
            val currentAccount = account
            if (currentAccount == null || accountStatus.operation != null) {
                val activity = LocalView.current.context.vaultActivity()
                val accountScope = LocalLifecycleOwner.current.lifecycleScope
                CloudAccountCard {
                    CloudStatusMessage("비밀번호를 설정하려면 Google 계정으로 로그인해 주세요.")
                    CloudPrimaryAction("Google로 로그인", onClick = {
                        if (activity != null) accountScope.launch { connection.chooseAccount(activity) }
                    }, enabled = activity != null && accountStatus.operation == null)
                    accountStatus.displayText(currentAccount != null)?.let { CloudStatusMessage(it) }
                }
            } else key(connection, currentAccount.uid) {
                if (SecureVaultWindow()) {
                    SecretVaultAccess(connection, currentAccount.uid, readConfigured, openSession, onRegistered) { _, _ -> }
                } else CloudAccountCard {
                    CloudStatusMessage("이 화면에서 시크릿 클라우드를 안전하게 열 수 없습니다.")
                }
            }
        }
    }
}

private fun Context.vaultActivity(): Activity? = generateSequence(this) { context ->
    (context as? ContextWrapper)?.baseContext?.takeUnless { it === context }
}.filterIsInstance<Activity>().firstOrNull()

@Composable
private fun SecureVaultWindow(): Boolean {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
        ?: view.context.vaultActivity()?.window
        ?: return false
    DisposableEffect(window) {
        val secure = WindowManager.LayoutParams.FLAG_SECURE
        val alreadySecure = window.attributes.flags and secure != 0
        window.addFlags(secure)
        onDispose {
            if (!alreadySecure) window.clearFlags(secure)
        }
    }
    return true
}

private class VaultUnlockRequest(val password: String, val setup: Boolean)

@Composable
private fun SecretVaultAccess(
    connection: CloudAccountConnection,
    uid: String,
    readConfigured: suspend (String) -> Boolean,
    openSession: suspend (String, String, Boolean) -> CloudSecretSession,
    onRegistered: (() -> Unit)? = null,
    gallery: @Composable (CloudSecretSession, () -> Unit) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var configured by remember { mutableStateOf<Boolean?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var probeError by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var unlock by remember { mutableStateOf<VaultUnlockRequest?>(null) }
    var opened by remember { mutableStateOf<CloudSecretSession?>(null) }
    var unlockError by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val settingsRegistration = onRegistered != null
    val registeredCallback by rememberUpdatedState(onRegistered)

    fun lock() {
        unlock = null
        password = ""
        confirmation = ""
        unlockError = null
        val session = opened
        opened = null
        session?.close()
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    foreground = false
                    lock()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            lock()
        }
    }

    LaunchedEffect(foreground, retry) {
        if (!foreground) return@LaunchedEffect
        configured = null
        probeError = null
        try {
            val registered = readConfigured(uid)
            currentCoroutineContext().ensureActive()
            if (foreground && connection.session.account.value?.uid == uid) configured = registered
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (foreground && connection.session.account.value?.uid == uid) {
                probeError = error.vaultSettingsUnavailableMessage().takeIf { settingsRegistration }
                    ?: "시크릿 클라우드 서버에 연결할 수 없습니다."
            }
        }
    }

    fun isCurrentRequest(request: VaultUnlockRequest): Boolean =
        unlock === request && foreground && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            connection.session.account.value?.uid == uid

    LaunchedEffect(unlock) {
        val request = unlock ?: return@LaunchedEffect
        var candidate: CloudSecretSession? = null
        try {
            val session = openSession(uid, request.password, request.setup)
            candidate = session
            currentCoroutineContext().ensureActive()
            if (!isCurrentRequest(request)) return@LaunchedEffect
            if (settingsRegistration) {
                session.close()
                candidate = null
                configured = true
                registeredCallback?.invoke()
            } else if (session.expiresAtElapsedMs <= SystemClock.elapsedRealtime()) {
                unlockError = "잠금 해제 시간이 만료됐습니다. 다시 시도해 주세요."
            } else {
                configured = true
                opened = session
                candidate = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentRequest(request)) return@LaunchedEffect
            val failure = error as? CloudBackupException
            if (settingsRegistration && (failure == null || failure.result == CloudBackupResult.FAILED ||
                    failure.result == CloudBackupResult.NETWORK_ERROR)) {
                // The server may have committed setup before the response was lost or another device won the race.
                val registered = try {
                    readConfigured(uid)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                currentCoroutineContext().ensureActive()
                if (!isCurrentRequest(request)) return@LaunchedEffect
                if (registered) {
                    configured = true
                    return@LaunchedEffect
                }
            }
            unlockError = if (settingsRegistration && failure?.permissionDenied == true) {
                "현재 계정은 시크릿 클라우드 비밀번호를 설정할 수 없습니다."
            } else {
                vaultUnlockMessage(error, request.setup)
            }
        } finally {
            candidate?.close()
            if (unlock === request) unlock = null
        }
    }

    val session = opened
    LaunchedEffect(session) {
        session ?: return@LaunchedEffect
        delay((session.expiresAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        if (opened === session) lock()
    }
    if (session != null && foreground) {
        key(session) { gallery(session, ::lock) }
        return
    }

    val setup = configured == false
    val withinPasswordLimit = password.length <= 256 && password.toByteArray(Charsets.UTF_8).size <= 256
    val validPassword = withinPasswordLimit && Character.codePointCount(password, 0, password.length) >= 8
    val confirmed = !setup || password == confirmation
    CloudAccountCard {
        val statusError = probeError
        when {
            statusError != null -> {
                CloudStatusMessage(statusError)
                TextAction("다시 시도", { retry++ })
            }
            configured == null -> {
                CloudStatusMessage("시크릿 클라우드 설정을 확인하고 있습니다.")
                if (settingsRegistration) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Primary, trackColor = Panel)
            }
            settingsRegistration && !setup -> CloudStatusMessage("비밀번호가 설정되어 있습니다.")
            else -> {
                if (!settingsRegistration) {
                    Text(if (setup) "비밀번호 설정" else "보관함 잠금 해제", style = MaterialTheme.typography.titleMedium)
                }
                if (setup) {
                    Text("8자 이상의 비밀번호를 입력해 주세요.", color = Muted)
                    Text("비밀번호는 앱의 보관함 접근을 보호합니다. 서버 원본은 Google 관리 키로 암호화해 저장합니다.",
                        color = Muted)
                }
                VaultPasswordField(
                    password, { password = it; unlockError = null }, "비밀번호", enabled = unlock == null,
                )
                if (setup) VaultPasswordField(
                    confirmation, { confirmation = it; unlockError = null }, "비밀번호 확인",
                    enabled = unlock == null, invalid = confirmation.isNotEmpty() && !confirmed,
                )
                if (setup && confirmation.isNotEmpty() && !confirmed) {
                    CloudStatusMessage("비밀번호가 일치하지 않습니다.")
                }
                if (!withinPasswordLimit) CloudStatusMessage("비밀번호가 너무 깁니다. 문자 수를 줄여 주세요.")
                unlockError?.let { CloudStatusMessage(it) }
                ActionButton(
                    if (unlock != null) "확인 중" else if (setup) "비밀번호 설정" else "잠금 해제",
                    onClick = {
                        unlock = VaultUnlockRequest(password, setup)
                        password = ""
                        confirmation = ""
                        unlockError = null
                        focus.clearFocus()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    filled = true,
                    enabled = foreground && validPassword && confirmed && unlock == null,
                )
                if (unlock != null) TextAction("취소", onClick = ::lock)
                else if (setup && unlockError != null) TextAction("설정 상태 다시 확인", {
                    lock()
                    retry++
                })
            }
        }
    }
}

private fun Exception.vaultSettingsUnavailableMessage(): String? =
    if (this is CloudBackupException && permissionDenied) "이 계정은 시크릿 클라우드를 이용할 수 없습니다."
    else null

@Composable
private fun VaultPasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    invalid: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = PasswordVisualTransformation(),
    )
}

@Composable
private fun SecretVaultGallery(
    session: CloudSecretSession,
    uid: String,
    accountLabel: String,
    state: AppUiState,
    requestAccess: () -> Unit,
    onLock: () -> Unit,
) {
    val backup = session.backup
    val revision by backup.catalogRevision.collectAsStateWithLifecycle()
    var originals by remember { mutableStateOf(emptyList<CloudOriginal>()) }
    var mediaType by remember { mutableStateOf(VaultMediaType.ALL) }
    var selected by remember { mutableStateOf<CloudOriginal?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var listError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision, refresh) {
        loading = true
        listError = null
        try {
            val current = backup.listOriginals(uid).filter {
                it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/")
            }
            currentCoroutineContext().ensureActive()
            originals = current
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            originals = emptyList()
            selected = null
            listError = when ((error as? CloudBackupException)?.result) {
                CloudBackupResult.ACCOUNT_CHANGED, CloudBackupResult.TEST_ACCESS_REQUIRED -> {
                    onLock()
                    null
                }
                else -> "시크릿 보관함을 불러오지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요."
            }
        } finally {
            loading = false
        }
    }
    val photos = remember(originals, backup) { originals.filter(backup::canPreviewPhoto) }
    val visibleOriginals = remember(originals, mediaType) {
        val prefix = mediaType.prefix
        originals.filter { prefix == null || it.mimeType.startsWith(prefix) }
    }
    val access = backup.accessFor(uid)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CloudAccountCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("시크릿 보관함", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextAction("잠그기", onLock)
            }
            when {
                loading -> CloudStatusMessage("보관함을 확인하고 있습니다.")
                listError != null -> {
                    Text(checkNotNull(listError), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Muted)
                    TextAction("다시 시도", { refresh++ })
                }
                originals.isEmpty() -> CloudStatusMessage("보관한 사진과 동영상이 없습니다.")
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VaultMediaType.entries.forEach { type ->
                            FilterChip(
                                selected = mediaType == type, onClick = { mediaType = type },
                                label = { Text(type.label, maxLines = 1) },
                            )
                        }
                    }
                    if (visibleOriginals.isEmpty()) CloudStatusMessage("이 유형의 원본이 없습니다.")
                    else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visibleOriginals, key = CloudOriginal::uploadId) { original ->
                            val video = original.mimeType.startsWith("video/")
                            val canOpen = if (video) backup.canPlayPrivateVideo(original) else backup.canPreviewPhoto(original)
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(original.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                Text(if (video) "동영상" else "사진",
                                    style = MaterialTheme.typography.bodySmall, color = Muted)
                                if (canOpen) TextAction(if (video) "앱에서 재생" else "사진 미리보기", { selected = original })
                                else Text("이 원본은 현재 미리보기를 지원하지 않습니다.",
                                    style = MaterialTheme.typography.bodySmall, color = Muted)
                                HorizontalDivider(color = Line)
                            }
                        }
                    }
                }
            }
            if (!loading && listError == null) TextAction("목록 새로고침", { refresh++ })
        }
        if (access != null && access.allowsManualUpload(System.currentTimeMillis())) {
            CloudBackupControls(backup, uid, access, state, requestAccess, accountLabel, privateVault = true)
        } else {
            CloudAccountCard { CloudStatusMessage("현재 이용권으로 새 시크릿 원본을 업로드할 수 없습니다.") }
        }
    }
    selected?.let { original ->
        if (original.mimeType.startsWith("video/")) {
            SecretCloudVideoViewer(uid, original, backup, dismiss = { selected = null })
        } else {
            CloudPhotoViewer(uid, original, photos, backup, choose = { selected = it }, dismiss = { selected = null })
        }
    }
}

private enum class VaultMediaType(val label: String, val prefix: String?) {
    ALL("전체", null), PHOTO("사진", "image/"), VIDEO("동영상", "video/"),
}

private fun vaultUnlockMessage(error: Exception, setup: Boolean): String {
    val failure = error as? CloudBackupException
    return when (failure?.result) {
        CloudBackupResult.TEST_ACCESS_REQUIRED -> if (setup) "비밀번호를 설정하지 못했습니다. 설정 상태를 다시 확인해 주세요."
            else "잠금을 해제하지 못했습니다. 비밀번호를 확인해 주세요."
        CloudBackupResult.QUOTA_REACHED -> "요청 횟수가 많습니다. 잠시 후 다시 시도해 주세요."
        CloudBackupResult.ACCOUNT_CHANGED -> "계정이 변경됐습니다. 다시 로그인해 주세요."
        else -> "시크릿 클라우드 서버에 연결할 수 없습니다. 다시 시도해 주세요."
    }
}
