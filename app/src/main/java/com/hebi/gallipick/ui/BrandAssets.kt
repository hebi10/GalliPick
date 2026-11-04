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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.hebi.gallipick.R

/** Generated scene artwork is packed into five adjacent 104x104 cells. */
internal enum class GalliScene(val position: Int) {
    ORGANIZE(0), SEARCH(1), PROTECT(2), COMPLETE(3), EMPTY(4),
}

@Composable
internal fun GalliIllustration(scene: GalliScene, modifier: Modifier = Modifier) {
    val atlas = ImageBitmap.imageResource(R.drawable.gp_design_atlas)
    Image(
        painter = BitmapPainter(
            image = atlas,
            srcOffset = IntOffset(scene.position * 104, 0),
            srcSize = IntSize(104, 104),
        ),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

/** Navigation cells begin at y=104 and each occupy 48x48. */
@Composable
internal fun GalliNavigationIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    val index = when (icon) {
        Icons.Rounded.Home -> 0
        Icons.Rounded.GridView -> 1
        Icons.Rounded.CopyAll -> 2
        Icons.Rounded.CleaningServices -> 3
        Icons.Rounded.Settings -> 4
        else -> null
    }
    if (index == null) {
        NativeIcon(icon, contentDescription = null, modifier = modifier, tint = Ink)
        return
    }
    val atlas = ImageBitmap.imageResource(R.drawable.gp_design_atlas)
    Image(
        painter = BitmapPainter(
            image = atlas,
            srcOffset = IntOffset(index * 48, 104),
            srcSize = IntSize(48, 48),
        ),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
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
    if (drawable != null && tint != OnPrimary) {
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
