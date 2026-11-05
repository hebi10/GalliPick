package com.hebi.gallipick.ui

import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CopyAll
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon as NativeIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.hebi.gallipick.R

/** Each scene is backed by a dedicated 640x640 generated PNG. */
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

/** Dedicated 256x256 PNG icons avoid magnifying the former 48px atlas cells. */
@Composable
internal fun GalliNavigationIcon(icon: ImageVector, modifier: Modifier = Modifier, selected: Boolean = false) {
    val drawable = when (icon) {
        Icons.Rounded.Home -> R.drawable.gp_nav_home
        Icons.Rounded.GridView -> R.drawable.gp_nav_files
        Icons.Rounded.CopyAll -> R.drawable.gp_nav_duplicates
        Icons.Rounded.CleaningServices -> R.drawable.gp_nav_cleanup
        Icons.Rounded.Settings -> R.drawable.gp_nav_settings
        else -> null
    }
    // The brand PNGs have opaque backgrounds; tinting the entire bitmap makes each
    // dark-mode navigation glyph appear as a solid square. Use clear vector glyphs
    // on dark surfaces and preserve the branded PNG artwork in light mode.
    if (IsGalliDark || drawable == null) {
        NativeIcon(
            icon,
            contentDescription = null,
            modifier = modifier,
            tint = if (selected) Ink else Muted,
        )
    } else {
        Image(
            painter = painterResource(drawable),
            contentDescription = null,
            modifier = modifier.alpha(if (selected) 1f else .82f),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
internal fun GalliMascot(modifier: Modifier = Modifier) {
    GalliIllustration(GalliScene.ORGANIZE, modifier)
}

/** Keep native utility glyphs for contrast and use brand art for feature icons. */
@Composable
internal fun GalliIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Ink,
) {
    val drawable = brandDrawable(imageVector)
    // Opaque illustrated assets cannot be safely tinted as a single bitmap:
    // the full image becomes a flat rectangle, hiding its icon details.
    // Use Material vectors whenever an actual tint is needed.
    if (drawable != null && !IsGalliDark && tint == Ink) {
        Image(
            painter = painterResource(drawable),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    } else {
        NativeIcon(imageVector, contentDescription = contentDescription, modifier = modifier, tint = tint)
    }
}

private fun brandDrawable(imageVector: ImageVector): Int? = when (imageVector) {
    Icons.Rounded.Home -> R.drawable.gp_home
    Icons.Rounded.GridView -> R.drawable.gp_files
    Icons.Rounded.CopyAll -> R.drawable.gp_duplicates
    Icons.Rounded.CleaningServices -> R.drawable.gp_cleanup
    Icons.Rounded.Settings -> R.drawable.gp_settings
    Icons.Rounded.Image, Icons.Rounded.PhotoLibrary -> R.drawable.gp_photo
    Icons.Rounded.Videocam -> R.drawable.gp_video
    Icons.Rounded.DeleteOutline -> R.drawable.gp_trash
    Icons.Rounded.Shield -> R.drawable.gp_shield
    Icons.Rounded.Folder -> R.drawable.gp_folder
    else -> null
}
