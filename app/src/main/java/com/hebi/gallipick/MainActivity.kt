package com.hebi.gallipick

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.DuplicateGroup
import com.hebi.gallipick.domain.formatBytes
import com.hebi.gallipick.domain.SelectionPolicy
import com.hebi.gallipick.ui.GalliPickApp
import com.hebi.gallipick.ui.GalliPickTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
          GalliPickTheme {
            var pending by remember { mutableStateOf<TrashReview?>(null) }
            var restored by rememberSaveable { mutableStateOf(false) }
            var reviewGeneration by remember { mutableLongStateOf(0L) }
            val catalogReady by vm.repository.catalogReady.collectAsState()
            LaunchedEffect(catalogReady) {
                if (!catalogReady) { pending = null; reviewGeneration++ }
            }
            DisposableEffect(Unit) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_PAUSE) { pending = null; reviewGeneration++ }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                vm.setAccess(mediaAccess())
                vm.refresh()
            }
            val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    vm.clearSelection()
                    vm.showMessage(if (restored) "복원 요청이 승인되었습니다. 파일 상태를 새로고침합니다." else "휴지통 이동이 승인되었습니다. 실제 확보 공간은 영구 제거 후 달라질 수 있습니다.")
                } else vm.showMessage("요청을 취소했습니다. 파일 상태를 새로고침합니다.")
                vm.refresh()
            }

            fun review(items: List<MediaItem>, restore: Boolean) {
                if (items.isEmpty()) return
                if (items.size > SelectionPolicy.MAX_SELECTION_SIZE) {
                    vm.showMessage("한 번에 최대 500개까지 처리할 수 있습니다. 선택 수를 줄여 주세요.")
                    return
                }
                val requested = if (restore) items else vm.selectedItemsForReview(items)
                val safetyGroups = if (restore) emptyList() else vm.selectionSafetyGroups(requested)
                val generation = reviewGeneration
                lifecycleScope.launch {
                    try {
                        val verified = if (restore) vm.repository.revalidateForRestore(requested) else vm.repository.revalidate(requested)
                        if (verified.isEmpty()) {
                            vm.showMessage("처리할 파일이 없습니다. 파일 변경·접근 권한·즐겨찾기·보호 상태를 확인해 주세요.")
                            vm.refresh()
                        } else {
                            val selectedUris = verified.map { it.uri }.toSet()
                            var survivorsValid = true
                            for (group in safetyGroups) {
                                if (!vm.repository.hasSurvivingCopy(group, selectedUris)) survivorsValid = false
                            }
                            if (generation != reviewGeneration || !vm.repository.catalogReady.value || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                vm.showMessage("접근 범위 확인 후 파일을 다시 선택해 주세요.")
                            } else if (survivorsValid) pending = TrashReview(verified, restore, items.size - verified.size, safetyGroups)
                            else {
                                vm.showMessage("중복 묶음에는 변경되지 않은 사본이 한 개 이상 남아 있어야 합니다. 다시 검사하고 선택해 주세요.")
                                vm.refresh()
                            }
                        }
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { vm.showMessage("파일 상태를 확인하지 못했습니다. 새로고침 후 다시 시도해 주세요.") }
                }
            }

            GalliPickApp(
                vm = vm,
                onRequestPermission = { permissionLauncher.launch(requiredPermissions()) },
                onOpenSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                onTrash = { review(it, false) },
                onOpenMedia = { item ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(item.uri), item.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (_: Exception) { vm.showMessage("이 파일을 열 수 있는 앱이 없거나 파일 접근 권한이 없습니다.") }
                },
                onRestore = { review(it, true) },
            )

            pending?.let { request ->
                AlertDialog(
                    onDismissRequest = { pending = null },
                    title = { Text(if (request.restore) "파일 복원" else "휴지통 이동") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${request.items.size}개 · ${formatBytes(request.items.sumOf { it.sizeBytes })}")
                            if (request.excluded > 0) Text("변경되었거나 보호된 ${request.excluded}개는 제외했습니다.")
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(request.items, key = { it.uri }) { item ->
                                    Column {
                                        Text(item.displayName)
                                        Text(item.relativePath.ifBlank { "기본 폴더" }, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Text(if (request.restore) "Android 승인 후 원래 위치로 복원을 요청합니다." else "다음 Android 승인창에서 확인합니다. 휴지통 파일은 자동 삭제 시점까지 저장 공간을 차지할 수 있습니다.")
                        }
                    },
                    dismissButton = { TextButton(onClick = { pending = null }) { Text("취소") } },
                    confirmButton = {
                        TextButton(onClick = {
                            pending = null
                            val generation = reviewGeneration
                            lifecycleScope.launch {
                                try {
                                    val current = if (request.restore) vm.repository.revalidateForRestore(request.items) else vm.repository.revalidate(request.items)
                                    val selectedUris = current.map { it.uri }.toSet()
                                    var survivorsValid = true
                                    for (group in request.safetyGroups) {
                                        if (!vm.repository.hasSurvivingCopy(group, selectedUris)) survivorsValid = false
                                    }
                                    if (generation != reviewGeneration || !vm.repository.catalogReady.value || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                        vm.showMessage("파일 접근 상태가 변경되었습니다. 새로고침 후 다시 선택해 주세요.")
                                    } else if (!survivorsValid) {
                                        vm.showMessage("남겨 둘 중복 사본이 변경되거나 사라졌습니다. 다시 검사한 뒤 선택해 주세요.")
                                        vm.refresh()
                                    } else if (current.size != request.items.size) {
                                        vm.showMessage("확인 중 파일 상태가 변경되었습니다. 새로고침 후 다시 선택해 주세요.")
                                        vm.refresh()
                                    } else {
                                        restored = request.restore
                                        val sender = MediaStore.createTrashRequest(contentResolver, current.map { Uri.parse(it.uri) }, !request.restore)
                                        trashLauncher.launch(IntentSenderRequest.Builder(sender.intentSender).build())
                                    }
                                } catch (e: CancellationException) { throw e
                                } catch (_: Exception) { vm.showMessage("Android 휴지통 요청을 시작하지 못했습니다. 파일과 접근 권한을 확인해 주세요.") }
                            }
                        }) { Text(if (request.restore) "복원 요청" else "이동 요청") }
                    },
                )
            }
          }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.setAccess(mediaAccess())
        vm.refresh()
    }

    private fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun mediaAccess(): MediaAccess = when {
        Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO) -> MediaAccess.FULL
        Build.VERSION.SDK_INT >= 33 && (granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)) -> MediaAccess.PARTIAL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
        Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.FULL
        else -> MediaAccess.NONE
    }
}

private data class TrashReview(val items: List<MediaItem>, val restore: Boolean, val excluded: Int, val safetyGroups: List<DuplicateGroup>)
