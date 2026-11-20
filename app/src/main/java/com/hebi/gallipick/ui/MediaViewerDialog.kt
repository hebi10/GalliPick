package com.hebi.gallipick.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun MediaViewerDialog(onDismiss: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            val entrance = remember { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(
                visibleState = entrance,
                enter = slideInVertically(animationSpec = tween(durationMillis = 240)) { height -> height },
                exit = ExitTransition.None,
            ) {
                content(Modifier.fillMaxSize().safeDrawingPadding())
            }
        }
    }
}
