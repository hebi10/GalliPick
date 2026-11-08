package com.hebi.gallipick.ui

import androidx.compose.foundation.Image
import androidx.compose.material3.Icon as NativeIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.hebi.gallipick.R

// Keep the isolated mascot PNGs for expressive, content-specific states.
internal enum class GalliScene {
    ORGANIZE, SEARCH, PROTECT, COMPLETE, EMPTY,
}

@Composable
internal fun GalliIllustration(scene: GalliScene, modifier: Modifier = Modifier) {
    val drawable = when (scene) {
        GalliScene.ORGANIZE -> R.drawable.gp_cat_organize
        GalliScene.SEARCH -> R.drawable.gp_cat_search
        GalliScene.PROTECT -> R.drawable.gp_cat_protect
        GalliScene.COMPLETE -> R.drawable.gp_cat_complete
        GalliScene.EMPTY -> R.drawable.gp_cat_empty
    }
    Image(
        painter = painterResource(drawable),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
internal fun GalliBrandMark(modifier: Modifier = Modifier) {
    NativeIcon(
        painter = painterResource(R.drawable.gp_brand_cat),
        contentDescription = null,
        modifier = modifier,
        tint = Ink,
    )
}

@Composable
internal fun GalliMascot(modifier: Modifier = Modifier) {
    GalliIllustration(GalliScene.ORGANIZE, modifier)
}

// Call sites use Material Outlined vectors for navigation and actions.
// Mascot illustrations and 10-B home shortcuts remain separate assets.
@Composable
internal fun GalliNavigationIcon(icon: ImageVector, modifier: Modifier = Modifier, selected: Boolean = false) {
    NativeIcon(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier,
        tint = if (selected) Primary else Muted,
    )
}

@Composable
internal fun GalliIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Ink,
) {
    NativeIcon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}
