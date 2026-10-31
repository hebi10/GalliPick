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
    val scan: ScanState = ScanState(),
    val selected: Set<String> = emptySet(),
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
    private val selectedDuplicateGroups = mutableMapOf<String, DuplicateGroup>()
    private var catalogVerified = false

    init {
        viewModelScope.launch {
            repository.media.collect { items ->
                if (!catalogVerified) return@collect
                _ui.update { it.copy(items = items, selected = it.selected.intersect(items.map { item -> item.uri }.toSet())) }
                updateFiltered()
            }
        }
        viewModelScope.launch { repository.trashed.collect { items -> if (catalogVerified) _ui.update { it.copy(trashed = items) } } }
        viewModelScope.launch { repository.protectedFolders.collect { paths -> _ui.update { it.copy(protectedFolders = paths) } } }
        viewModelScope.launch { repository.duplicates.collect { groups -> if (catalogVerified) _ui.update { it.copy(duplicates = groups) } } }
        viewModelScope.launch { repository.scan.collect { scan -> _ui.update { it.copy(scan = scan) } } }
    }

    fun setAccess(access: MediaAccess) {
        catalogVerified = false
        filterJob?.cancel()
        selectedDuplicateGroups.clear()
        _ui.update { it.copy(access = access, items = emptyList(), trashed = emptyList(),
            filtered = emptyList(), duplicates = emptyList(), selected = emptySet()) }
    }

    fun refresh() {
        val previous = refreshJob
        previous?.cancel()
        catalogVerified = false
        filterJob?.cancel()
        _ui.update { it.copy(items = emptyList(), trashed = emptyList(), filtered = emptyList(), duplicates = emptyList()) }
        refreshJob = viewModelScope.launch {
            previous?.join()
            _ui.update { it.copy(loading = true) }
            try {
                repository.refresh()
                val items = repository.media.first()
                val trashed = repository.trashed.first()
                val duplicates = repository.duplicates.first()
                val stat = withContext(Dispatchers.IO) { StatFs(Environment.getDataDirectory().path) }
                catalogVerified = true
                _ui.update { it.copy(items = items, trashed = trashed, duplicates = duplicates,
                    selected = it.selected.intersect(items.map { item -> item.uri }.toSet()),
                    totalStorageBytes = stat.totalBytes, freeStorageBytes = stat.availableBytes) }
                updateFiltered()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { showMessage("파일을 불러오지 못했습니다. 접근 권한을 확인한 뒤 새로고침해 주세요.")
            } finally { _ui.update { it.copy(loading = false) } }
        }
    }

    fun setFilter(filter: MediaFilter) {
        _ui.update { it.copy(filter = filter) }
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
        val item = _ui.value.items.find { it.uri == uri } ?: return
        if (item.isProtected || item.isFavorite) {
            showMessage("즐겨찾기·보호 파일은 휴지통 선택에서 제외됩니다.")
            return
        }
        _ui.update { it.copy(selected = if (uri in it.selected) it.selected - uri else it.selected + uri) }
        if (uri in _ui.value.selected) {
            _ui.value.duplicates.filter { group -> group.items.any { it.uri == uri } }
                .forEach { selectedDuplicateGroups[it.hash] = it }
        }
    }
    fun clearSelection() {
        _ui.update { it.copy(selected = emptySet()) }
        selectedDuplicateGroups.clear()
    }
    fun selectAllFiltered() {
        val uris = _ui.value.filtered.filterNot { it.isFavorite || it.isProtected }.map { it.uri }.toSet()
        _ui.value.duplicates.filter { group -> group.items.any { it.uri in uris } }
            .forEach { selectedDuplicateGroups[it.hash] = it }
        _ui.update { it.copy(selected = it.selected + uris) }
    }
    fun selectionSafetyGroups(items: List<MediaItem>): List<DuplicateGroup> {
        val uris = items.map { it.uri }.toSet()
        return (selectedDuplicateGroups.values + _ui.value.duplicates).distinctBy { it.hash }
            .filter { group -> group.items.any { it.uri in uris } }
    }
    fun selectRecommendedDuplicates() {
        val uris = _ui.value.duplicates.flatMap(DuplicatePolicy::deletionCandidates).map { it.uri }.toSet()
        selectedDuplicateGroups.clear()
        _ui.value.duplicates.forEach { selectedDuplicateGroups[it.hash] = it }
        _ui.update { it.copy(selected = uris) }
    }
    fun setProtected(item: MediaItem, protected: Boolean) = task {
        repository.setProtected(item.uri, protected)
        if (protected) _ui.update { it.copy(selected = it.selected - item.uri) }
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
