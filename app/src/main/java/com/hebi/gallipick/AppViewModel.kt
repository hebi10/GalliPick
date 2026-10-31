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
    val selected: Set<String> = emptySet(),
    val catalogError: String? = null,
    val message: String? = null,
    val totalStorageBytes: Long = 0,
    val freeStorageBytes: Long = 0,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as GalliPickApplication).repository
    private val settings = AppSettings(application)
    private val _ui = MutableStateFlow(AppUiState(filter = settings.loadFilter(), presets = settings.loadPresets()))
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

    fun selectRecommendedDuplicates() = replaceSelection(_ui.value.duplicates.flatMap { group ->
        DuplicatePolicy.deletionCandidates(group, _ui.value.duplicateKeepers[group.hash])
    })

    fun chooseDuplicateKeeper(group: DuplicateGroup, uri: String) {
        val live = _ui.value.duplicates.firstOrNull { it.hash == group.hash } ?: return
        if (live.items.none { it.uri == uri }) return
        selectedVersions.remove(uri)
        _ui.update { it.copy(duplicateKeepers = it.duplicateKeepers + (group.hash to uri), selected = selectedVersions.keys.toSet()) }
    }

    fun setProtected(item: MediaItem, protected: Boolean) = task {
        repository.setProtected(item.uri, protected)
        if (protected) {
            selectedVersions.remove(item.uri)
            _ui.update { it.copy(selected = selectedVersions.keys.toSet()) }
        }
    }
    fun protectFolder(path: String, protected: Boolean) = task { repository.setFolderProtected(path, protected) }
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
