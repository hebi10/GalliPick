package com.hebi.gallipick.ui

import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices as OutlinedCleaningServices
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Home as OutlinedHome
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings as OutlinedSettings
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CopyAll
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
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
    Image(
        painter = painterResource(R.drawable.gp_photo),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
internal fun GalliMascot(modifier: Modifier = Modifier) {
    GalliIllustration(GalliScene.ORGANIZE, modifier)
}

// The reference guide uses simple, single-color outlined icons. Keep the
// original source PNGs in the repository, but use native vectors for controls:
// tinting an opaque illustration would turn its background into a solid tile.
@Composable
internal fun GalliNavigationIcon(icon: ImageVector, modifier: Modifier = Modifier, selected: Boolean = false) {
    val referenceIcon = when (icon) {
        Icons.Rounded.Home -> Icons.Outlined.OutlinedHome
        Icons.Rounded.GridView -> Icons.Outlined.PhotoLibrary
        Icons.Rounded.CopyAll -> Icons.Outlined.ContentCopy
        Icons.Rounded.CleaningServices -> Icons.Outlined.OutlinedCleaningServices
        Icons.Rounded.Settings -> Icons.Outlined.OutlinedSettings
        else -> icon
    }
    NativeIcon(
        imageVector = referenceIcon,
        contentDescription = null,
        modifier = modifier,
        tint = if (selected) (if (IsGalliDark) Ink else Primary) else Muted,
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
