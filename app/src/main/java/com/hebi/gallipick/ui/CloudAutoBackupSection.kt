package com.hebi.gallipick.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CLOUD_BACKUP_MAX_BYTES
import com.hebi.gallipick.data.cloud.CloudAutoBackupFolder
import com.hebi.gallipick.data.cloud.CloudAutoBackupSettings
import com.hebi.gallipick.data.cloud.CloudAutoBackupStatus
import com.hebi.gallipick.data.cloud.CloudBackup
import com.hebi.gallipick.data.cloud.CloudBackupAccess
import com.hebi.gallipick.data.cloud.CloudBackupNamespace
import com.hebi.gallipick.data.cloud.canAutoBackUp
import com.hebi.gallipick.data.cloud.hasFullAutoBackupPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CloudAutoBackupSection(connection: CloudAccountConnection, state: AppUiState, requestAccess: () -> Unit) {
    val backup = connection.backup ?: return
    val account by connection.session.account.collectAsStateWithLifecycle()
    val uid = account?.uid ?: return
    val access = backup.accessFor(uid) ?: return
    key(uid) { CloudAutoBackupControls(connection, backup, access, uid, state, requestAccess) }
}

@Composable
private fun CloudAutoBackupControls(
    connection: CloudAccountConnection,
    backup: CloudBackup,
    access: CloudBackupAccess,
    uid: String,
    state: AppUiState,
    requestAccess: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { CloudAutoBackupSettings(context) }
    val updates = remember(settings, uid, access.namespace) { settings.observe(uid, access.namespace) }
    val active by updates.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val permission = state.access == MediaAccess.FULL && hasFullAutoBackupPermission(context)
    val maximumOriginalBytes = minOf(access.maximumOriginalBytes, CLOUD_BACKUP_MAX_BYTES)
    val uploadAllowed = access.allowsUpload(System.currentTimeMillis())
    val storage = if (access.namespace == CloudBackupNamespace.PRODUCTION) "클라우드 보관함" else "테스트 보관함"
    val limitLabel = cloudOriginalLimitLabel(maximumOriginalBytes)
    val folders = remember(state.items, permission, uploadAllowed, maximumOriginalBytes) {
        if (!permission || !uploadAllowed) emptyList()
        else state.items.asSequence().filter { it.canAutoBackUp(maximumOriginalBytes) }
            .mapNotNull { CloudAutoBackupFolder.from(it) }.distinct()
            .sortedWith(compareBy<CloudAutoBackupFolder> { it.relativePath }.thenBy { it.volume }).toList()
    }
    var choosing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CloudAutoBackupFolder?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().galliCardSurface(AppSurface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("자동 백업", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Text("데이터 사용량 제한이 없는 Wi-Fi에 연결되어 충전 중일 때 선택한 폴더를 백업합니다. 원본의 촬영 위치 정보도 포함됩니다.",
            color = Muted, style = MaterialTheme.typography.bodySmall)
        active?.let { schedule ->
            Text("예약 폴더 · ${schedule.consent.folder.label()}")
            Text("이 기기에서 확인 · ${schedule.verified.size}개", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (schedule.skipped.isNotEmpty()) {
                Text("읽지 못한 원본 · ${schedule.skipped.size}개. 수동 백업에서 확인할 수 있습니다.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(schedule.status.label(), color = Muted, style = MaterialTheme.typography.bodySmall)
            ActionButton("자동 백업 끄기", {
                scope.launch {
                    saving = true
                    try {
                        error = if (settings.disable(uid, access.namespace)) null else "자동 백업 설정을 저장하지 못했습니다."
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        error = "자동 백업 설정을 저장하지 못했습니다."
                    } finally {
                        saving = false
                    }
                }
            }, Modifier.fillMaxWidth(), enabled = !saving)
        } ?: Text("자동 백업 꺼짐", color = Muted, style = MaterialTheme.typography.bodySmall)

        when {
            !uploadAllowed -> CloudStatusMessage("새 자동 백업을 추가할 수 없습니다. 구독 상태와 남은 공간을 확인해 주세요.")
            !permission -> {
                Text("폴더 자동 백업을 설정하려면 사진·동영상 전체 및 원본 접근 권한을 허용해 주세요.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                ActionButton("접근 권한 확인", requestAccess, enabled = !saving)
            }
            state.loading -> Text("기기 폴더를 확인하는 중입니다.", color = Muted)
            state.catalogError != null -> Text("기기 폴더를 확인하지 못했습니다.", color = Muted)
            folders.isEmpty() -> Text("현재 폴더에 백업할 수 있는 ${limitLabel} 이하 원본이 없습니다.", color = Muted)
            else -> ActionButton(if (active == null) "자동 백업 폴더 선택" else "자동 백업 폴더 변경", {
                choosing = true
            }, Modifier.fillMaxWidth(), enabled = !saving)
        }
        if (saving) Text("자동 백업 설정 확인 중…", color = Muted)
        error?.let { Text(it, color = Muted) }
    }

    if (choosing && uploadAllowed) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("자동 백업할 폴더") },
            text = {
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(folders) { folder ->
                        Text(folder.label(), Modifier.fillMaxWidth().clickable {
                            selected = folder
                            choosing = false
                        }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton({ choosing = false }) { Text("닫기") } },
        )
    }
    selected?.takeIf { uploadAllowed }?.let { folder ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("이 폴더의 자동 백업을 켤까요?") },
            text = { Text("${folder.label()}에서 ${limitLabel} 이하 원본과 촬영 정보를 현재 Google 계정의 ${storage}으로 보냅니다. 현재 폴더와 나중에 이 폴더에 추가되는 파일이 포함됩니다.") },
            confirmButton = {
                TextButton({
                    selected = null
                    scope.launch {
                        saving = true
                        error = null
                        try {
                            if (access.namespace == CloudBackupNamespace.DEVELOPMENT) backup.listOriginals(uid)
                            else connection.refreshBackupAccess(uid)
                            val enabled = settings.enable(uid, folder, access.namespace) {
                                connection.session.account.value?.uid == uid && hasFullAutoBackupPermission(context) &&
                                    backup.accessFor(uid)?.let {
                                        it.namespace == access.namespace && it.allowsUpload(System.currentTimeMillis())
                                    } == true
                            }
                            if (!enabled) {
                                error = "자동 백업을 설정하지 못했습니다. 권한과 폴더를 확인해 주세요."
                            }
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (_: Exception) {
                            error = "보관함에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요."
                        } finally {
                            saving = false
                        }
                    }
                }, enabled = permission && folder in folders && !saving) { Text("자동 백업 켜기") }
            },
            dismissButton = { TextButton({ selected = null }) { Text("취소") } },
        )
    }
}

private fun CloudAutoBackupFolder.label(): String = "${relativePath.trimEnd('/')} ($volume)"

private fun CloudAutoBackupStatus.label(): String = when (this) {
    CloudAutoBackupStatus.WAITING -> "Wi-Fi와 충전 조건을 기다리는 중입니다."
    CloudAutoBackupStatus.VERIFIED -> "최근 실행에서 원본 보관을 확인했습니다."
    CloudAutoBackupStatus.NO_NEW_FILES -> "최근 실행에서 자동 백업할 새 파일이 없었습니다."
    CloudAutoBackupStatus.PERMISSION_REQUIRED -> "미디어 접근 권한이 바뀌어 백업을 보류했습니다."
    CloudAutoBackupStatus.WIFI_AND_CHARGING_REQUIRED -> "Wi-Fi나 충전 조건이 바뀌어 백업을 보류했습니다."
    CloudAutoBackupStatus.CLOUD_UNAVAILABLE -> "보관함 연결을 기다리고 있습니다."
    CloudAutoBackupStatus.LIMIT_REACHED -> "백업 한도에 도달했습니다."
    CloudAutoBackupStatus.RETRY_SCHEDULED -> "연결이 불안정했습니다. 조건을 확인한 뒤 다시 시도합니다."
    CloudAutoBackupStatus.ERROR -> "최근 실행에서 원본 보관을 확인하지 못했습니다."
}
