package com.hebi.gallipick.domain

import org.junit.Assert.*
import org.junit.Test

class SelectionPolicyTest {
    @Test fun revalidationPreservesOnlyUnchangedAccessibleUnprotectedFiles() {
        val selected = listOf(media("same"), media("changed"), media("revoked"), media("favorite"), media("protected"))
        val current = listOf(selected[0], selected[1].copy(generationModified = 2),
            selected[3].copy(isFavorite = true), selected[4].copy(isProtected = true))
        assertEquals(listOf(selected[0]), SelectionPolicy.retain(selected, current))
    }

    @Test fun bulkSelectionCapsAt500AndExcludesProtectedCopies() {
        val items = (1..503).map { media("$it") }
        val result = SelectionPolicy.candidates(listOf(items[0].copy(isProtected = true)) + items.drop(1))
        assertEquals(500, result.size)
        assertEquals("2", result.first().uri)
        assertEquals("501", result.last().uri)
    }

    @Test fun repeatedUrisCannotUseUpTheSelectionLimit() {
        val a = media("a")
        assertEquals(listOf(a, media("b")), SelectionPolicy.candidates(listOf(a, a, media("b"))))
    }

    @Test fun matchingUriButDifferentBytesIsNeverKeptAsASelection() {
        val item = media("a")
        assertTrue(SelectionPolicy.retain(listOf(item), listOf(item.copy(sizeBytes = item.sizeBytes + 1))).isEmpty())
        assertTrue(SelectionPolicy.retain(listOf(item), listOf(item.copy(isTrashed = true))).isEmpty())
        assertTrue(SelectionPolicy.retain(listOf(item.copy(volumeVersion = "old")), listOf(item.copy(volumeVersion = "new"))).isEmpty())
    }
}
