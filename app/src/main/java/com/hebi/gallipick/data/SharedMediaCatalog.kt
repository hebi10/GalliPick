package com.hebi.gallipick.data

import com.hebi.gallipick.domain.DuplicateGroup
import com.hebi.gallipick.domain.MediaItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transform

/** One coherent permission-verified catalog for an atomic view-model publication. */
data class VerifiedCatalog(
    val items: List<MediaItem>,
    val trashed: List<MediaItem>,
    val duplicates: List<DuplicateGroup>,
)

/** Keep per-file persistence immediate; only expensive downstream work is rate-limited. */
internal fun <T> Flow<T>.coalesceUpdates(intervalMillis: Long = 250): Flow<T> =
    conflate().transform {
        emit(it)
        delay(intervalMillis)
    }

/** One process-wide set of Room subscriptions, also used by short-lived worker repositories. */
internal class SharedMediaCatalog(
    rows: Flow<List<CachedMedia>>,
    protection: Flow<List<ProtectedMedia>>,
    folders: Flow<List<ProtectedFolder>>,
    epochs: Flow<List<MediaVolumeVersion>>,
    hashes: Flow<List<CachedHash>>,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutableReady = MutableStateFlow(false)
    private val mutableError = MutableStateFlow<String?>(null)
    val ready = mutableReady.asStateFlow()
    val error = mutableError.asStateFlow()
    private val sharing = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0)

    private data class Content(
        val rows: List<CachedMedia>,
        val items: List<MediaItem>,
        val versions: Map<String, String>,
    )

    private val sharedFolders = folders.distinctUntilChanged().shareIn(scope, sharing, replay = 1)
    val protectedFolders: Flow<Set<String>> = sharedFolders.map { values -> values.mapTo(hashSetOf()) { it.path } }

    private val content = combine(
        rows.distinctUntilChanged(), protection.distinctUntilChanged(),
        sharedFolders, epochs.distinctUntilChanged(),
    ) { cached, protectedItems, protectedFolders, versions ->
        val itemUris = protectedItems.mapTo(hashSetOf()) { it.uri }
        val paths = protectedFolders.map { it.path }
        val versionsByVolume = versions.associate { it.volume to it.version }
        Content(cached, cached.map {
            it.item(MediaCachePolicy.isProtected(it.uri, it.relativePath, itemUris, paths))
                .copy(volumeVersion = versionsByVolume[it.volume].orEmpty())
        }, versionsByVolume)
    }.distinctUntilChanged().flowOn(dispatcher).shareIn(scope, sharing, replay = 1)

    private val visible = combine(ready, content) { readable, current ->
        if (readable) current.items else emptyList()
    }.distinctUntilChanged()

    val media: Flow<List<MediaItem>> = visible.map { items -> items.filterNot { it.isTrashed } }
    val trashed: Flow<List<MediaItem>> = visible.map { items -> items.filter { it.isTrashed } }

    private data class GroupResult(val content: Content, val groups: List<DuplicateGroup>)

    private val computedGroups = combine(
        content, hashes.distinctUntilChanged().coalesceUpdates(),
    ) { current, cachedHashes ->
        val volumes = current.rows.associate { it.uri to it.volume }
        val valid = cachedHashes.asSequence().filter {
            it.volumeVersion.isNotBlank() && volumes[it.uri]?.let(current.versions::get) == it.volumeVersion
        }.associate { (it.uri to it.fingerprint) to it.sha256 }
        GroupResult(current, MediaCachePolicy.duplicateGroups(current.items, valid))
    }.flowOn(dispatcher).shareIn(scope, sharing, replay = 1)

    val duplicates: Flow<List<DuplicateGroup>> = combine(ready, content, computedGroups) { readable, current, result ->
        // A new permission snapshot must never expose the previous computation's replay.
        if (readable && result.content == current) result.groups else emptyList()
    }.distinctUntilChanged()

    suspend fun snapshot(): VerifiedCatalog = combine(ready, content, computedGroups) { readable, current, result ->
        if (readable && result.content == current) VerifiedCatalog(
            items = current.items.filterNot { it.isTrashed },
            trashed = current.items.filter { it.isTrashed },
            duplicates = result.groups,
        ) else null
    }.first { it != null }!!

    fun markRefreshing() {
        mutableReady.value = false
        mutableError.value = null
    }

    fun markReady() {
        mutableError.value = null
        mutableReady.value = true
    }

    fun markFailed(message: String) {
        mutableReady.value = false
        mutableError.value = message
    }

    suspend fun awaitSnapshot(rows: List<CachedMedia>, versions: Map<String, String>) {
        val expected = rows.associateBy { it.uri }
        content.first { it.versions == versions && it.rows.associateBy { row -> row.uri } == expected }
    }
}
