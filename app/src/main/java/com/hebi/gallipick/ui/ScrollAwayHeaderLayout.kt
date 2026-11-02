package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScrollAwayHeaderLayout(
    modifier: Modifier = Modifier,
    resetKey: Any? = Unit,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = key(resetKey) { rememberTopAppBarState(initialHeightOffsetLimit = 0f) }
    val headerScroll = key(resetKey) { rememberScrollState() }
    val behavior = TopAppBarDefaults.enterAlwaysScrollBehavior(state)
    Layout(modifier = modifier.clipToBounds(), content = {
        Box(Modifier.verticalScroll(headerScroll)) { header() }
        Box(Modifier.nestedScroll(behavior.nestedScrollConnection)) { content() }
    }) { measurables, constraints ->
        val top = measurables[0].measure(constraints.copy(minHeight = 0, maxHeight = constraints.maxHeight / 2))
        state.heightOffsetLimit = -top.height.toFloat()
        state.heightOffset = state.heightOffset.coerceIn(state.heightOffsetLimit, 0f)
        val visible = (top.height + state.heightOffset).roundToInt().coerceIn(0, top.height)
        val bodyHeight = (constraints.maxHeight - visible).coerceAtLeast(0)
        val body = measurables[1].measure(constraints.copy(minHeight = bodyHeight, maxHeight = bodyHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            top.placeRelative(0, visible - top.height)
            body.placeRelative(0, visible)
        }
    }
}
