package com.hebi.gallipick.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSubscreenTest {
    @Test fun allRoutesRestoreWithTheirParentAndArguments() {
        val routes = listOf(
            AppSubscreen.Main, AppSubscreen.Trash(), AppSubscreen.Trash(fromHistory = true),
            AppSubscreen.Duplicates, AppSubscreen.FolderStorage, AppSubscreen.CleanupHistory,
            AppSubscreen.PhotoAnalysis, AppSubscreen.ReviewInbox,
        ) + PhotoReviewMode.entries.flatMap { mode ->
            listOf(AppSubscreen.PhotoResults(mode)) + PhotoCompareSource.entries.map { source ->
                AppSubscreen.PhotoCompare(mode, "group/id:ä", source)
            }
        }
        routes.forEach { route ->
            val restored = restoreAppSubscreen(route.save())
            assertEquals(route, restored)
            assertEquals(route.parent(), restored.parent())
        }
    }

    @Test fun nestedRoutesReturnToTheScreenThatOpenedThem() {
        val results = AppSubscreen.PhotoResults(PhotoReviewMode.BURST)
        val fromResults = AppSubscreen.PhotoCompare(PhotoReviewMode.BURST, "group", PhotoCompareSource.RESULTS)
        val fromInbox = fromResults.copy(source = PhotoCompareSource.INBOX)

        assertEquals(results, fromResults.parent())
        assertEquals(AppSubscreen.PhotoAnalysis, results.parent())
        assertEquals(AppSubscreen.ReviewInbox, fromInbox.parent())
        assertEquals(AppSubscreen.CleanupHistory, AppSubscreen.Trash(fromHistory = true).parent())
        assertEquals(AppSubscreen.Main, AppSubscreen.Trash().parent())
    }

    @Test fun incompleteOrUnknownSavedRouteReturnsToMain() {
        assertEquals(AppSubscreen.Main, restoreAppSubscreen(listOf("invalid")))
        assertEquals(AppSubscreen.Main, restoreAppSubscreen(listOf("photo-results", "invalid")))
        assertEquals(AppSubscreen.Main, restoreAppSubscreen(listOf("photo-compare", "BURST", "group")))
    }
}
