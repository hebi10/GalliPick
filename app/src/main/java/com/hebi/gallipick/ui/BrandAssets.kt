package com.hebi.gallipick.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.R

/** Uses the brand artwork for primary destinations and keeps utility glyphs native. */
@Composable
internal fun GalliIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Ink,
) {
    val drawable = brandDrawable(imageVector)
    if (drawable != null && tint != Color.White) {
        Image(
            painter = painterResource(drawable),
            contentDescription = contentDescription,
            modifier = modifier.size(24.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        // Small white glyphs need the single-color silhouette for contrast.
        NativeIcon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = tint,
        )
    }
}

@Composable
internal fun GalliMascot(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.gp_mascot),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
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
