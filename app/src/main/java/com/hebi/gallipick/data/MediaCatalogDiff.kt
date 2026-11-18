package com.hebi.gallipick.data

/**
 * Diff between two complete, permission-verified MediaStore snapshots.
 * This is not a partial scan: missing URIs must be deleted from the local catalog.
 */
internal data class MediaCatalogDiff(
    val changed: List<CachedMedia>,
    val removedUris: List<String>,
    val invalidatedProofUris: List<String>,
) {
    val isEmpty: Boolean get() = changed.isEmpty() && removedUris.isEmpty()

    companion object {
        fun between(previous: List<CachedMedia>, current: List<CachedMedia>): MediaCatalogDiff {
            // MediaStoreReader already deduplicates URIs. Last row wins if a direct caller passes duplicates.
            val previousByUri = previous.associateBy { it.uri }
            val currentByUri = current.associateBy { it.uri }
            val changed = currentByUri.values.filter { item -> previousByUri[item.uri] != item }
            val removed = previousByUri.keys.filterNot(currentByUri::containsKey)
            val invalidated = changed.mapNotNull { item ->
                val old = previousByUri[item.uri] ?: return@mapNotNull null
                item.uri.takeIf {
                    old.id != item.id || old.volume != item.volume || old.kind != item.kind ||
                        old.mimeType != item.mimeType || old.fingerprint != item.fingerprint ||
                        old.width != item.width || old.height != item.height
                }
            }
            return MediaCatalogDiff(changed, removed, invalidated)
        }
    }
}
