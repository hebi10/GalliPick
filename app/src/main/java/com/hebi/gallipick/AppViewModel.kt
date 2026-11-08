package com.hebi.gallipick

import android.app.Application
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hebi.gallipick.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class MediaAccess { NONE, PARTIAL, FULL }

data class AppUiState(
    val loading: Boolean = false,
    val access: MediaAccess = MediaAccess.NONE,
    val items: List<MediaItem> = emptyList(),
    val trashed: List<MediaItem> = emptyList(),
    val protectedFolders: Set<String> = emptySet(),
    val filtered: List<MediaItem> = emptyList(),
    val filter: MediaFilter = MediaFilter(),
    val presets: List<FilterPreset> = emptyList(),
    val duplicates: List<DuplicateGroup> = emptyList(),
    val duplicateKeepers: Map<String, String> = emptyMap(),
    val scan: ScanState = ScanState(),
    val photoAnalysis: PhotoAnalysisState = PhotoAnalysisState(),
    val selected: Set<String> = emptySet(),
    val catalogError: String? = null,
    val message: String? = null,
    val totalStorageBytes: Long = 0,
    val freeStorageBytes: Long = 0,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as GalliPickApplication).repository
    private val settings = AppSettings(application)
    private val _ui = MutableStateFlow(AppUiState(filter = settings.loadFilter(), presets = settings.loadPresets(), themeMode = settings.loadThemeMode()))
    val ui: StateFlow<AppUiState> = _ui.asStateFlow()
    private var filterJob: Job? = null
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L
    private val selectedDuplicateGroups = mutableMapOf<String, DuplicateGroup>()
    private val selectedVersions = linkedMapOf<String, MediaItem>()
    private var catalogVerified = false

    init {
        viewModelScope.launch {
            repository.catalogReady.collect { ready -> if (!ready) hideCatalog() }
        }
        viewModelScope.launch {
            repository.catalogError.collect { error -> _ui.update { it.copy(catalogError = error) } }
        }
        viewModelScope.launch {
            repository.media.collect { items ->
                if (catalogVerified && repository.catalogReady.value) publishMedia(items)
            }
        }
        viewModelScope.launch { repository.trashed.collect { items ->
            if (catalogVerified && repository.catalogReady.value) _ui.update { it.copy(trashed = items) }
        } }
        viewModelScope.launch { repository.protectedFolders.collect { paths -> _ui.update { it.copy(protectedFolders = paths) } } }
        viewModelScope.launch { repository.duplicates.collect { groups ->
            if (catalogVerified && repository.catalogReady.value) _ui.update { it.copy(duplicates = groups) }
        } }
        viewModelScope.launch { repository.scan.collect { scan -> _ui.update { it.copy(scan = scan) } } }
        viewModelScope.launch { repository.photoAnalysis.collect { analysis -> _ui.update { it.copy(photoAnalysis = analysis) } } }
    }

    private fun hideCatalog() {
        filterJob?.cancel()
        _ui.update { it.copy(items = emptyList(), trashed = emptyList(), filtered = emptyList(), duplicates = emptyList(), selected = emptySet()) }
    }

    private fun publishMedia(items: List<MediaItem>) {
        val retained = SelectionPolicy.retain(selectedVersions.values, items)
        val dropped = selectedVersions.size - retained.size
        selectedVersions.clear()
        retained.forEach { selectedVersions[it.uri] = it }
        _ui.update { it.copy(items = items, selected = selectedVersions.keys.toSet(),
            message = if (dropped > 0) "변경되었거나 접근할 수 없는 선택 ${dropped}개를 해제했습니다." else it.message) }
        updateFiltered()
    }

    fun setAccess(access: MediaAccess) {
        refreshJob?.cancel()
        refreshGeneration++
        catalogVerified = false
        if (access == MediaAccess.NONE) clearSelection()
        hideCatalog()
        _ui.update { it.copy(access = access) }
    }

    fun refresh() {
        val generation = ++refreshGeneration
        val previous = refreshJob
        previous?.cancel()
        catalogVerified = false
        hideCatalog()
        _ui.update { it.copy(loading = true, catalogError = null) }
        refreshJob = viewModelScope.launch {
            previous?.join()
            try {
                repository.refresh()
                val stat = withContext(Dispatchers.IO) { StatFs(Environment.getDataDirectory().path) }
                val snapshot = repository.verifiedCatalog()
                catalogVerified = true
                _ui.update { it.copy(trashed = snapshot.trashed, duplicates = snapshot.duplicates,
                    totalStorageBytes = stat.totalBytes, freeStorageBytes = stat.availableBytes, catalogError = null) }
                publishMedia(snapshot.items)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                val message = "파일 조회를 완료하지 못했습니다. 접근 권한과 저장소를 확인한 뒤 새로고침해 주세요."
                _ui.update { it.copy(catalogError = message, message = message) }
            } finally { if (generation == refreshGeneration) _ui.update { it.copy(loading = false) } }
        }
    }

    fun setFilter(filter: MediaFilter) {
        if (filter != _ui.value.filter) clearSelection()
        _ui.update { it.copy(filter = filter, filtered = emptyList()) }
        settings.saveFilter(filter)
        updateFiltered()
    }

    private fun updateFiltered() {
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            val snapshot = _ui.value
            val filtered = withContext(Dispatchers.Default) { MediaQuery.apply(snapshot.items, snapshot.filter) }
            _ui.update { it.copy(filtered = filtered) }
        }
    }

    fun savePreset(name: String) {
        if (name.isBlank()) return
        val presets = _ui.value.presets + settings.newPreset(name.take(50), _ui.value.filter)
        settings.savePresets(presets)
        _ui.update { it.copy(presets = presets) }
    }

    fun deletePreset(id: String) {
        val presets = _ui.value.presets.filterNot { it.id == id }
        settings.savePresets(presets)
        _ui.update { it.copy(presets = presets) }
    }

    fun applyPreset(preset: FilterPreset) = setFilter(preset.filter)

    fun setThemeMode(mode: AppThemeMode) {
        settings.saveThemeMode(mode)
        _ui.update { it.copy(themeMode = mode) }
    }

    fun toggleSelection(uri: String) {
        if (uri in selectedVersions) {
            selectedVersions.remove(uri)
            _ui.update { it.copy(selected = selectedVersions.keys.toSet()) }
            return
        }
        val item = _ui.value.items.find { it.uri == uri } ?: return
        if (item.isProtected || item.isFavorite) {
            showMessage("즐겨찾기·보호 파일은 휴지통 선택에서 제외됩니다.")
            return
        }
        if (selectedVersions.size >= SelectionPolicy.MAX_SELECTION_SIZE) {
            showMessage("한 번에 최대 500개까지 선택할 수 있습니다. 현재 선택을 먼저 처리해 주세요.")
            return
        }
        selectedVersions[uri] = item
        rememberSafety(listOf(item))
        _ui.update { it.copy(selected = selectedVersions.keys.toSet()) }
    }

    fun clearSelection() {
        selectedVersions.clear()
        selectedDuplicateGroups.clear()
        _ui.update { it.copy(selected = emptySet()) }
    }

    private fun replaceSelection(items: List<MediaItem>) {
        clearSelection()
        val candidates = SelectionPolicy.candidates(items)
        candidates.forEach { selectedVersions[it.uri] = it }
        rememberSafety(candidates)
        _ui.update { it.copy(selected = selectedVersions.keys.toSet()) }
        if (items.count { !it.isProtected && !it.isFavorite } > SelectionPolicy.MAX_SELECTION_SIZE) {
            showMessage("한 번에 최대 500개까지 처리합니다. 앞의 500개를 선택했습니다.")
        }
    }

    fun selectAllFiltered() = replaceSelection(_ui.value.filtered)

    private fun rememberSafety(items: List<MediaItem>) {
        val uris = items.mapTo(hashSetOf()) { it.uri }
        _ui.value.duplicates.filter { group -> group.items.any { it.uri in uris } }
            .forEach { selectedDuplicateGroups[it.hash] = it }
    }

    fun selectedItemsForReview(items: List<MediaItem>): List<MediaItem> = items.mapNotNull { current ->
        selectedVersions[current.uri]?.takeIf { it.fingerprint == current.fingerprint && it.volumeVersion == current.volumeVersion }
    }

    fun selectionSafetyGroups(items: List<MediaItem>): List<DuplicateGroup> {
        val uris = items.mapTo(hashSetOf()) { it.uri }
        return (selectedDuplicateGroups.values + _ui.value.duplicates).distinctBy { it.hash }
            .filter { group -> group.items.any { it.uri in uris } }
    }

    private fun currentDuplicateCandidates(): List<MediaItem> = _ui.value.let {
        DuplicateReviewPolicy.candidates(it.duplicates, it.items, it.duplicateKeepers, it.protectedFolders)
    }

    private fun duplicateSelectionReady(): Boolean {
        if (catalogVerified && repository.catalogReady.value && !_ui.value.loading && _ui.value.catalogError == null) return true
        showMessage("중복 결과를 다시 확인한 뒤 선택해 주세요.")
        return false
    }

    private fun applyDuplicateSelection(items: List<MediaItem>) {
        selectedVersions.clear()
        selectedDuplicateGroups.clear()
        SelectionPolicy.candidates(items).forEach { selectedVersions[it.uri] = it }
        rememberSafety(selectedVersions.values.toList())
        _ui.update { it.copy(selected = selectedVersions.keys.toSet()) }
    }

    fun prepareDuplicateReview() {
        if (!duplicateSelectionReady()) return
        val retained = SelectionPolicy.retain(selectedVersions.values, currentDuplicateCandidates())
        val dropped = selectedVersions.size - retained.size
        applyDuplicateSelection(retained)
        if (dropped > 0) showMessage("현재 중복 정리 대상이 아닌 ${dropped}개는 선택에서 제외했습니다.")
    }

    fun toggleDuplicateCandidateSelection(uri: String) {
        if (!duplicateSelectionReady()) return
        val eligible = currentDuplicateCandidates()
        val retained = SelectionPolicy.retain(selectedVersions.values, eligible)
        val target = eligible.firstOrNull { it.uri == uri }
        if (target == null) {
            applyDuplicateSelection(retained)
            showMessage("남길 파일·보호 파일·즐겨찾기는 선택 대상에서 제외됩니다.")
            return
        }
        if (retained.any { it.uri == uri }) applyDuplicateSelection(retained.filterNot { it.uri == uri })
        else if (retained.size < SelectionPolicy.MAX_SELECTION_SIZE) applyDuplicateSelection(retained + target)
        else showMessage("한 번에 최대 500개까지 선택할 수 있습니다. 현재 선택을 먼저 처리해 주세요.")
    }

    fun selectRecommendedDuplicates() {
        if (!duplicateSelectionReady()) return
        val eligible = currentDuplicateCandidates()
        applyDuplicateSelection(eligible)
        showMessage(if (eligible.size > SelectionPolicy.MAX_SELECTION_SIZE)
            "정리 후보 ${eligible.size}개 중 앞의 500개를 선택했습니다."
        else "정리 후보 ${selectedVersions.size}개를 선택했습니다. 아직 휴지통으로 이동하지 않았습니다.")
    }

    fun selectDuplicateGroupCandidates(group: DuplicateGroup) {
        if (!duplicateSelectionReady()) return
        val live = DuplicateReviewPolicy.liveGroups(_ui.value.duplicates, _ui.value.items).firstOrNull { it.hash == group.hash } ?: return
        val eligible = currentDuplicateCandidates()
        val retained = SelectionPolicy.retain(selectedVersions.values, eligible)
        val targetUris = live.items.mapTo(hashSetOf()) { it.uri }
        val wanted = (retained.map { it.uri } + eligible.filter { it.uri in targetUris }.map { it.uri }).distinct().size
        val chosen = DuplicateReviewPolicy.selectGroup(retained, eligible, live)
        applyDuplicateSelection(chosen)
        if (wanted > SelectionPolicy.MAX_SELECTION_SIZE) showMessage("선택 한도 500개까지 추가했습니다. 다른 묶음의 선택은 유지됩니다.")
    }

    fun chooseDuplicateKeeper(group: DuplicateGroup, uri: String) {
        if (!duplicateSelectionReady()) return
        val live = DuplicateReviewPolicy.liveGroups(_ui.value.duplicates, _ui.value.items).firstOrNull { it.hash == group.hash } ?: return
        if (live.items.none { it.uri == uri }) return
        val affected = DuplicateGroup(live.hash, (live.items + selectedDuplicateGroups[live.hash]?.items.orEmpty()).distinctBy { it.uri })
        val retained = DuplicateReviewPolicy.afterKeeperChange(selectedVersions.values, affected)
        selectedVersions.clear()
        retained.forEach { selectedVersions[it.uri] = it }
        selectedDuplicateGroups.remove(live.hash)
        _ui.update { it.copy(duplicateKeepers = it.duplicateKeepers + (live.hash to uri), selected = selectedVersions.keys.toSet(),
            message = "남길 파일을 변경했습니다. 이 묶음의 선택을 해제했으며 다른 묶음의 선택은 유지됩니다.") }
    }

    fun setProtected(item: MediaItem, protected: Boolean) = task {
        repository.setProtected(item.uri, protected)
        if (protected) {
            selectedVersions.remove(item.uri)
            _ui.update { it.copy(selected = selectedVersions.keys.toSet()) }
        }
    }
    fun protectFolder(path: String, protected: Boolean) = task { repository.setFolderProtected(path, protected) }
    fun startPhotoAnalysis() = repository.startPhotoAnalysis()
    fun pausePhotoAnalysis() = repository.pausePhotoAnalysis()
    fun resumePhotoAnalysis() = repository.resumePhotoAnalysis()

    fun startScan() = repository.startScan()
    fun pauseScan() = repository.pauseScan()
    fun resumeScan() = repository.resumeScan()
    fun clearAnalysisCache() = task { repository.clearAnalysisCache() }
    fun showMessage(message: String) { _ui.update { it.copy(message = message) } }
    fun dismissMessage() { _ui.update { it.copy(message = null) } }

    private fun task(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e
            } catch (_: Exception) { showMessage("작업을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.") }
        }
    }
}
