package com.hebi.gallipick.data

import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal data class MediaSnapshot(
    val items: List<MediaItem>,
    val volumeVersions: Map<String, String>,
)

/** A failed/partial provider read must never masquerade as an authoritative empty catalog. */
internal class CatalogReadException(cause: Throwable) : IOException(
    "파일 목록을 확인하지 못했습니다. 접근 권한을 확인하고 다시 시도해 주세요.", cause,
)

internal class MediaSnapshotLoader(
    private val volumes: () -> Set<String>,
    private val version: (String) -> String,
    private val query: suspend (String, MediaKind) -> List<MediaItem>,
) {
    suspend fun load(): MediaSnapshot = try {
        val versions = linkedMapOf<String, String>()
        val items = mutableListOf<MediaItem>()
        for (volume in volumes().sorted()) {
            coroutineContext.ensureActive()
            val before = version(volume)
            check(before.isNotBlank()) { "MediaStore returned an empty volume version" }
            for (kind in MediaKind.entries) {
                coroutineContext.ensureActive()
                items += query(volume, kind).map { it.copy(volumeVersion = before) }
            }
            check(version(volume) == before) { "MediaStore volume changed during query" }
            versions[volume] = before
        }
        check(volumes() == versions.keys) { "MediaStore volumes changed during query" }
        check(versions.all { (volume, expected) -> version(volume) == expected }) {
            "MediaStore volume changed before snapshot completed"
        }
        MediaSnapshot(items.distinctBy { it.uri }, versions)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        throw CatalogReadException(exception)
    }
}
