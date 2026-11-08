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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.DuplicateGroup
import com.hebi.gallipick.domain.SimilarPhotoGroup
import com.hebi.gallipick.domain.SelectionPolicy
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.TrashIdentity
import com.hebi.gallipick.domain.TrashOutcome
import com.hebi.gallipick.ui.CleanupOperationResult
import com.hebi.gallipick.ui.GalliPickApp
import com.hebi.gallipick.ui.GalliPickTheme
import com.hebi.gallipick.ui.TrashReviewDialog
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
          val uiState by vm.ui.collectAsState()
          val useDarkTheme = uiState.themeMode.isDark(isSystemInDarkTheme())
          val view = LocalView.current
          SideEffect {
              WindowCompat.getInsetsController(window, view).apply {
                  isAppearanceLightStatusBars = !useDarkTheme
                  isAppearanceLightNavigationBars = !useDarkTheme
              }
          }
          GalliPickTheme(themeMode = uiState.themeMode) {
            var pending by remember { mutableStateOf<TrashReview?>(null) }
            var restored by rememberSaveable { mutableStateOf(false) }
            var submitted by rememberSaveable { mutableStateOf("") }
            var operationResult by remember { mutableStateOf<CleanupOperationResult?>(null) }
            var reviewChecking by remember { mutableStateOf(false) }
            var reviewGeneration by remember { mutableLongStateOf(0L) }
            val catalogReady by vm.repository.catalogReady.collectAsState()
            LaunchedEffect(catalogReady) {
                if (!catalogReady) { pending = null; reviewGeneration++ }
            }
            LaunchedEffect(uiState.selected) {
                val request = pending
                if (request != null && !request.restore && request.selectionSnapshot != uiState.selected) {
                    pending = null
                    reviewGeneration++
                    vm.showMessage("선택이 변경되어 확인창을 닫았습니다. 이동할 파일을 다시 확인해 주세요.")
                }
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
                val identities = decodeTrashRequest(submitted)
                val wasRestore = restored
                val requestBytes = identities.fold(0L) { total, item ->
                    val size = item.sizeBytes.coerceAtLeast(0L)
                    if (size > Long.MAX_VALUE - total) Long.MAX_VALUE else total + size
                }
                submitted = ""
                if (result.resultCode == Activity.RESULT_OK) {
                    vm.clearSelection()
                    reviewChecking = true
                    operationResult = null
                    vm.showMessage("Android 승인을 받았습니다. 실제 파일 상태를 확인하고 있습니다.")
                    lifecycleScope.launch {
                        var checked: CleanupOperationResult? = null
                        try {
                            checked = if (identities.isEmpty()) {
                                CleanupOperationResult.unknown(wasRestore, 0, requestBytes)
                            } else {
                                val outcome = vm.repository.inspectTrashOutcome(identities, wasRestore)
                                CleanupOperationResult.verified(outcome, wasRestore, identities.size, requestBytes)
                            }
                            vm.dismissMessage()
                        } catch (e: CancellationException) { throw e
                        } catch (_: Exception) {
                            checked = CleanupOperationResult.unknown(wasRestore, identities.size, requestBytes)
                            vm.showMessage("요청은 승인되었으나 결과를 확인하지 못했습니다. 파일 목록과 접근 권한을 확인해 주세요.")
                        } finally {
                            reviewChecking = false
                            vm.refresh()
                            operationResult = checked
                        }
                    }
                } else {
                    reviewChecking = false
                    operationResult = CleanupOperationResult.cancelled(wasRestore, identities.size, requestBytes)
                    vm.dismissMessage()
                    vm.refresh()
                }
            }

            fun review(items: List<MediaItem>, restore: Boolean) {
                if (items.isEmpty()) return
                if (reviewChecking || pending != null || submitted.isNotEmpty()) {
                    vm.showMessage("앞선 파일 확인 또는 Android 확인을 먼저 완료해 주세요.")
                    return
                }
                if (items.size > SelectionPolicy.MAX_SELECTION_SIZE) {
                    vm.showMessage("한 번에 최대 500개까지 처리할 수 있습니다. 선택 수를 줄여 주세요.")
                    return
                }
                operationResult = null
                val requested = if (restore) items else vm.selectedItemsForReview(items)
                val safetyGroups = if (restore) emptyList() else vm.selectionSafetyGroups(requested)
                val similarSafetyGroups = if (restore) emptyList() else vm.selectionSafetySimilarGroups(requested)
                val generation = reviewGeneration
                val selectionSnapshot = vm.ui.value.selected.toSet()
                reviewChecking = true
                lifecycleScope.launch {
                    try {
                        val verified = if (restore) vm.repository.revalidateForRestore(requested) else vm.repository.revalidate(requested)
                        if (verified.isEmpty() || (similarSafetyGroups.isNotEmpty() && verified.size != requested.size)) {
                            vm.showMessage("처리할 파일이 없습니다. 파일 변경·접근 권한·즐겨찾기·보호 상태를 확인해 주세요.")
                            vm.refresh()
                        } else {
                            val selectedUris = verified.map { it.uri }.toSet()
                            var survivorsValid = true
                            val kept = mutableListOf<MediaItem>()
                            for (group in safetyGroups) {
                                val survivor = vm.repository.confirmedSurvivingCopy(group, selectedUris, vm.ui.value.duplicateKeepers[group.hash])
                                if (survivor == null) survivorsValid = false else kept += survivor
                            }
                            for (group in similarSafetyGroups) {
                                val survivor = vm.repository.confirmedSimilarSurvivor(group, selectedUris)
                                if (survivor == null) survivorsValid = false else kept += survivor
                            }
                            if (generation != reviewGeneration || !vm.repository.catalogReady.value || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                vm.showMessage("접근 범위 확인 후 파일을 다시 선택해 주세요.")
                            } else if (!restore && vm.ui.value.selected != selectionSnapshot) {
                                vm.showMessage("선택이 변경되었습니다. 이동할 파일을 다시 확인해 주세요.")
                            } else if (survivorsValid) pending = TrashReview(verified, restore, items.size - verified.size, safetyGroups, similarSafetyGroups, kept.distinctBy { it.uri }, selectionSnapshot)
                            else {
                                vm.showMessage("중복 묶음에는 변경되지 않은 사본이 한 개 이상 남아 있어야 합니다. 다시 검사하고 선택해 주세요.")
                                vm.refresh()
                            }
                        }
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { vm.showMessage("파일 상태를 확인하지 못했습니다. 새로고침 후 다시 시도해 주세요.")
                    } finally { reviewChecking = false }
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
                result = operationResult,
                onDismissResult = { operationResult = null },
            )

            pending?.let { request ->
                TrashReviewDialog(
                    moving = request.items, kept = request.kept, restore = request.restore, excluded = request.excluded,
                    onDismiss = { pending = null },
                    onProceed = {
                            pending = null
                            val generation = reviewGeneration
                            reviewChecking = true
                            lifecycleScope.launch {
                                try {
                                    val current = if (request.restore) vm.repository.revalidateForRestore(request.items) else vm.repository.revalidate(request.items)
                                    val selectedUris = current.map { it.uri }.toSet()
                                    var survivorsValid = true
                                    for (group in request.safetyGroups) {
                                        if (!vm.repository.hasSurvivingCopy(group, selectedUris)) survivorsValid = false
                                    }
                                    for (group in request.similarSafetyGroups) {
                                        if (vm.repository.confirmedSimilarSurvivor(group, selectedUris) == null) survivorsValid = false
                                    }
                                    if (generation != reviewGeneration || !vm.repository.catalogReady.value || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                        vm.showMessage("파일 접근 상태가 변경되었습니다. 새로고침 후 다시 선택해 주세요.")
                                    } else if (!request.restore && vm.ui.value.selected != request.selectionSnapshot) {
                                        vm.showMessage("선택이 변경되었습니다. 이동할 파일을 다시 확인해 주세요.")
                                    } else if (!survivorsValid) {
                                        vm.showMessage("남겨 둘 중복 사본이 변경되거나 사라졌습니다. 다시 검사한 뒤 선택해 주세요.")
                                        vm.refresh()
                                    } else if (current.size != request.items.size) {
                                        if (!request.restore) vm.clearSelection()
                                        vm.showMessage("확인 중 파일 상태가 변경되었습니다. 새로고침 후 다시 선택해 주세요.")
                                        vm.refresh()
                                    } else {
                                        restored = request.restore
                                        val sender = MediaStore.createTrashRequest(contentResolver, current.map { Uri.parse(it.uri) }, !request.restore)
                                        submitted = encodeTrashRequest(current)
                                        trashLauncher.launch(IntentSenderRequest.Builder(sender.intentSender).build())
                                    }
                                } catch (e: CancellationException) { throw e
                                } catch (_: Exception) {
                                    submitted = ""
                                    vm.showMessage("Android 휴지통 요청을 시작하지 못했습니다. 파일과 접근 권한을 확인해 주세요.")
                                    val totalBytes = request.items.fold(0L) { sum, item ->
                                        val size = item.sizeBytes.coerceAtLeast(0L)
                                        if (size > Long.MAX_VALUE - sum) Long.MAX_VALUE else sum + size
                                    }
                                    operationResult = CleanupOperationResult.failed(request.restore, request.items.size, totalBytes)
                                } finally { reviewChecking = false }
                            }
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

private data class TrashReview(
    val items: List<MediaItem>, val restore: Boolean, val excluded: Int,
    val safetyGroups: List<DuplicateGroup>,
    val similarSafetyGroups: List<SimilarPhotoGroup>,
    val kept: List<MediaItem>, val selectionSnapshot: Set<String>,
)

private fun encodeTrashRequest(items: List<MediaItem>): String = JSONArray().apply {
    items.forEach { item -> put(JSONObject().apply {
        put("u", item.uri); put("i", item.id); put("v", item.volume); put("k", item.kind.name)
        put("s", item.sizeBytes); put("e", item.volumeVersion)
    }) }
}.toString()

private fun decodeTrashRequest(value: String): List<TrashIdentity> = try {
    if (value.isBlank()) emptyList() else JSONArray(value).let { array ->
        (0 until array.length().coerceAtMost(SelectionPolicy.MAX_SELECTION_SIZE)).map { index -> array.getJSONObject(index).let {
            TrashIdentity(it.getString("u"), it.getLong("i"), it.getString("v"), MediaKind.valueOf(it.getString("k")), it.getLong("s"), it.getString("e"))
        } }
    }
} catch (_: Exception) { emptyList() }

private fun trashOutcomeMessage(result: TrashOutcome, restore: Boolean): String {
    if (result.unchanged == 0 && result.unknown == 0) return if (restore) "${result.completed}개를 복원했습니다." else "${result.completed}개를 휴지통으로 이동했습니다. 실제 여유 공간은 영구 제거 후 달라질 수 있습니다."
    val action = if (restore) "복원" else "이동"
    return "$action 확인 ${result.completed}개 · 미${action} ${result.unchanged}개 · 확인 불가 ${result.unknown}개. 파일 목록과 접근 권한을 다시 확인해 주세요."
}
