package com.hebi.gallipick.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.DateField
import com.hebi.gallipick.domain.MediaDateGroups
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.MediaQuery
import com.hebi.gallipick.domain.SortDirection
import com.hebi.gallipick.domain.SortField
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class DateGroupedFilesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun oldDownloadsRenderDateSectionsInFilesGrid() {
        val first = day(2025, Calendar.JANUARY, 2)
        val second = day(2025, Calendar.JANUARY, 5)
        val files = listOf(
            item(1, second),
            item(2, first),
        )
        val filter = MediaFilter(
            downloadsOnly = true, groupByDate = true, dateField = DateField.ADDED,
            fromDateMs = 1L, toDateMs = second,
            sortField = SortField.ADDED, sortDirection = SortDirection.ASCENDING,
        )
        val state = AppUiState(
            access = MediaAccess.FULL, items = files, filtered = MediaQuery.apply(files, filter),
            filter = filter,
        )
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 760.dp)) {
                        FilesScreenContent(
                            state = state, wide = false, trash = {}, open = {}, requestAccess = {},
                            actions = FilesScreenActions(
                                setFilter = {}, selectAll = {}, toggleSelection = {}, clearSelection = {},
                                savePreset = {}, deletePreset = {}, setProtected = { _, _ -> }, refresh = {},
                            ),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("추가일 2025.01.02").assertExists()
        compose.onNodeWithText("추가일 2025.01.05").assertExists()
    }

    private fun item(id: Long, added: Long) = MediaItem(
        uri = "content://media/external/images/media/${990000 + id}",
        id = id, volume = "external_primary", displayName = "download-$id.jpg",
        kind = MediaKind.PHOTO, mimeType = "image/jpeg", relativePath = "Download/",
        sizeBytes = 1024, width = 100, height = 100, durationMs = 0,
        dateTakenMs = 0, dateAddedMs = added, dateModifiedMs = added,
    )

    private fun day(year: Int, month: Int, date: Int): Long = Calendar.getInstance().apply {
        clear()
        set(year, month, date, 12, 0, 0)
    }.timeInMillis
}
