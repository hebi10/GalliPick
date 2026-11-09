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
    val dark = IsGalliDark
    val drawable = when (scene) {
        GalliScene.ORGANIZE -> if (dark) R.drawable.gp_cat_organize_mono_dark else R.drawable.gp_cat_organize_mono
        GalliScene.SEARCH -> if (dark) R.drawable.gp_cat_search_mono_dark else R.drawable.gp_cat_search_mono
        GalliScene.PROTECT -> if (dark) R.drawable.gp_cat_protect_mono_dark else R.drawable.gp_cat_protect_mono
        GalliScene.COMPLETE -> if (dark) R.drawable.gp_cat_complete_mono_dark else R.drawable.gp_cat_complete_mono
        GalliScene.EMPTY -> if (dark) R.drawable.gp_cat_empty_mono_dark else R.drawable.gp_cat_empty_mono
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
    Image(
        painter = painterResource(R.drawable.gp_brand_cat_face),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
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
