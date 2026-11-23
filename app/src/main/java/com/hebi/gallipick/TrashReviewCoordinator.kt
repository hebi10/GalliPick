package com.hebi.gallipick

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import com.hebi.gallipick.domain.DuplicateGroup
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.SelectionPolicy
import com.hebi.gallipick.domain.SimilarPhotoGroup
import com.hebi.gallipick.domain.TrashIdentity
import com.hebi.gallipick.ui.CleanupOperationResult
import com.hebi.gallipick.ui.CleanupResultStatus
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class TrashReview(
    val items: List<MediaItem>,
    val restore: Boolean,
    val excluded: Int,
    val safetyGroups: List<DuplicateGroup>,
    val similarSafetyGroups: List<SimilarPhotoGroup>,
    val kept: List<MediaItem>,
    val selectionSnapshot: Set<String>,
)

internal class TrashReviewCoordinator(
    private val vm: AppViewModel,
    private val savedState: SavedStateHandle,
    private val scope: CoroutineScope,
) {
    private val repository = vm.repository
    private val _pending = MutableStateFlow<TrashReview?>(null)
    val pending = _pending.asStateFlow()
    private val _result = MutableStateFlow(savedState.get<Bundle>(TRASH_RESULT)?.toTrashResult())
    val result = _result.asStateFlow()

    private var approval: TrashApproval? =
        savedState.get<ArrayList<String>>(TRASH_APPROVAL)?.let(::restoreTrashApproval)
    private var checking = false
    private var verifying = false
    private var reviewGeneration = 0L

    fun invalidateReview() {
        _pending.value = null
        reviewGeneration++
    }

    fun onSelectionChanged(selected: Set<String>) {
        val request = _pending.value ?: return
        if (!request.restore && request.selectionSnapshot != selected) {
            invalidateReview()
            vm.showMessage("선택이 변경되어 확인창을 닫았습니다. 이동할 파일을 다시 확인해 주세요.")
        }
    }

    fun dismissReview() {
        _pending.value = null
    }

    fun dismissResult() {
        setResult(null)
    }

    fun review(items: List<MediaItem>, restore: Boolean, isResumed: () -> Boolean) {
        if (items.isEmpty()) return
        if (checking || _pending.value != null || approval != null) {
            vm.showMessage("앞선 파일 확인 또는 Android 확인을 먼저 완료해 주세요.")
            return
        }
        if (items.size > SelectionPolicy.MAX_SELECTION_SIZE) {
            vm.showMessage("한 번에 최대 500개까지 처리할 수 있습니다. 선택 수를 줄여 주세요.")
            return
        }
        setResult(null)
        val requested = if (restore) items else vm.selectedItemsForReview(items)
        val safetyGroups = if (restore) emptyList() else vm.selectionSafetyGroups(requested)
        val similarSafetyGroups = if (restore) emptyList() else vm.selectionSafetySimilarGroups(requested)
        val generation = reviewGeneration
        val selectionSnapshot = vm.ui.value.selected.toSet()
        checking = true
        scope.launch {
            try {
                val verified = revalidate(requested, restore)
                if (verified.isEmpty() || (similarSafetyGroups.isNotEmpty() && verified.size != requested.size)) {
                    vm.showMessage("처리할 파일이 없습니다. 파일 변경·접근 권한·즐겨찾기·보호 상태를 확인해 주세요.")
                    vm.refresh()
                    return@launch
                }
                val selectedUris = verified.mapTo(hashSetOf()) { it.uri }
                val kept = safetyGroups.map { group ->
                    repository.confirmedSurvivingCopy(group, selectedUris, vm.ui.value.duplicateKeepers[group.hash])
                } + similarSafetyGroups.map { group ->
                    repository.confirmedSimilarSurvivor(group, selectedUris)
                }
                when {
                    !reviewIsCurrent(generation, isResumed) ->
                        vm.showMessage("접근 범위 확인 후 파일을 다시 선택해 주세요.")
                    !restore && vm.ui.value.selected != selectionSnapshot ->
                        vm.showMessage("선택이 변경되었습니다. 이동할 파일을 다시 확인해 주세요.")
                    kept.any { it == null } -> {
                        vm.showMessage("중복 묶음에는 변경되지 않은 사본이 한 개 이상 남아 있어야 합니다. 다시 검사하고 선택해 주세요.")
                        vm.refresh()
                    }
                    else -> _pending.value = TrashReview(
                        verified, restore, items.size - verified.size, safetyGroups, similarSafetyGroups,
                        kept.filterNotNull().distinctBy { it.uri }, selectionSnapshot,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                vm.showMessage("파일 상태를 확인하지 못했습니다. 새로고침 후 다시 시도해 주세요.")
            } finally {
                checking = false
            }
        }
    }

    fun proceed(isResumed: () -> Boolean, launchPrompt: (List<MediaItem>, Boolean) -> Unit) {
        val request = _pending.value ?: return
        _pending.value = null
        val generation = reviewGeneration
        checking = true
        scope.launch {
            try {
                val current = revalidate(request.items, request.restore)
                val selectedUris = current.mapTo(hashSetOf()) { it.uri }
                val survivorsValid = request.safetyGroups.all { repository.hasSurvivingCopy(it, selectedUris) } &&
                    request.similarSafetyGroups.all { repository.confirmedSimilarSurvivor(it, selectedUris) != null }
                when {
                    !reviewIsCurrent(generation, isResumed) ->
                        vm.showMessage("파일 접근 상태가 변경되었습니다. 새로고침 후 다시 선택해 주세요.")
                    !request.restore && vm.ui.value.selected != request.selectionSnapshot ->
                        vm.showMessage("선택이 변경되었습니다. 이동할 파일을 다시 확인해 주세요.")
                    !survivorsValid -> {
                        vm.showMessage("남겨 둘 중복 사본이 변경되거나 사라졌습니다. 다시 검사한 뒤 선택해 주세요.")
                        vm.refresh()
                    }
                    current.size != request.items.size -> {
                        if (!request.restore) vm.clearSelection()
                        vm.showMessage("확인 중 파일 상태가 변경되었습니다. 새로고침 후 다시 선택해 주세요.")
                        vm.refresh()
                    }
                    else -> {
                        val approvalRequest = TrashApprovalRequest(
                            UUID.randomUUID().toString(), request.restore,
                            current.map { item ->
                                TrashIdentity(item.uri, item.id, item.volume, item.kind, item.sizeBytes, item.volumeVersion)
                            },
                        )
                        setApproval(TrashApproval.Awaiting(approvalRequest))
                        launchPrompt(current, request.restore)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                setApproval(null)
                vm.showMessage("Android 휴지통 요청을 시작하지 못했습니다. 파일과 접근 권한을 확인해 주세요.")
                val bytes = trashRequestBytes(request.items.map { it.sizeBytes })
                setResult(CleanupOperationResult.failed(request.restore, request.items.size, bytes))
            } finally {
                checking = false
            }
        }
    }

    fun onApprovalResult(approved: Boolean) {
        when (val decision = approval.decide(approved)) {
            TrashApprovalDecision.Ignore -> Unit
            is TrashApprovalDecision.Cancel -> {
                val request = decision.request
                setResult(CleanupOperationResult.cancelled(request.restore, request.identities.size, request.sizeBytes))
                setApproval(null)
                vm.dismissMessage()
                vm.refresh()
            }
            is TrashApprovalDecision.Verify -> {
                setApproval(decision.approval)
                resumeVerification()
            }
        }
    }

    fun resumeVerification() {
        val request = (approval as? TrashApproval.Verifying)?.request ?: return
        if (verifying) return
        verifying = true
        vm.clearSelection()
        setResult(null)
        vm.showMessage("Android 승인을 받았습니다. 실제 파일 상태를 확인하고 있습니다.")
        scope.launch {
            try {
                val outcome = repository.inspectTrashOutcome(request.identities, request.restore)
                if (outcome.completed > 0) {
                    try {
                        val saved = repository.recordConfirmedCleanup(
                            request.operationId, request.restore, request.identities.size, outcome,
                        )
                        if (saved) vm.dismissMessage()
                        else vm.showMessage("파일 처리는 확인되었지만 정리 기록을 저장하지 못했습니다.")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        vm.showMessage("파일 처리는 확인되었지만 정리 기록 저장에 실패했습니다.")
                    }
                } else {
                    vm.dismissMessage()
                }
                setResult(CleanupOperationResult.verified(outcome, request.restore, request.identities.size, request.sizeBytes))
                setApproval(null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                setResult(CleanupOperationResult.unknown(request.restore, request.identities.size, request.sizeBytes))
                setApproval(null)
                vm.showMessage("요청은 승인되었으나 결과를 확인하지 못했습니다. 파일 목록과 접근 권한을 확인해 주세요.")
            } finally {
                verifying = false
                vm.refresh()
            }
        }
    }

    private suspend fun revalidate(items: List<MediaItem>, restore: Boolean): List<MediaItem> =
        if (restore) repository.revalidateForRestore(items) else repository.revalidate(items)

    private fun reviewIsCurrent(generation: Long, isResumed: () -> Boolean): Boolean =
        generation == reviewGeneration && repository.catalogReady.value && isResumed()

    private fun setApproval(value: TrashApproval?) {
        approval = value
        savedState[TRASH_APPROVAL] = value?.save()
    }

    private fun setResult(value: CleanupOperationResult?) {
        _result.value = value
        savedState[TRASH_RESULT] = value?.toSavedBundle()
    }

    private companion object {
        const val TRASH_APPROVAL = "trash-approval"
        const val TRASH_RESULT = "trash-result"
    }
}

private fun CleanupOperationResult.toSavedBundle() = Bundle().apply {
    putString("status", status.name)
    putBoolean("restore", restore)
    putInt("requested", requestedCount)
    putInt("completed", completedCount)
    putInt("unchanged", unchangedCount)
    putInt("unknown", unknownCount)
    putLong("requestedBytes", requestedBytes)
    putLong("completedBytes", confirmedCompletedBytes)
}

private fun Bundle.toTrashResult(): CleanupOperationResult? {
    val status = CleanupResultStatus.entries.firstOrNull { it.name == getString("status") } ?: return null
    return CleanupOperationResult(
        status, getBoolean("restore"), getInt("requested"), getInt("completed"),
        getInt("unchanged"), getInt("unknown"), getLong("requestedBytes"), getLong("completedBytes"),
    )
}
