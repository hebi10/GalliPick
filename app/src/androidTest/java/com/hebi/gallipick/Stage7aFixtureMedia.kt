package com.hebi.gallipick

import android.content.ContentValues
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.hebi.gallipick.domain.MediaKind
import java.io.ByteArrayOutputStream

/**
 * Reproducible test-owned Android MediaStore fixtures. These are actual decodable
 * JPEG/PNG and playable MP4 rows, not mocks of the UI. Scene art is synthetic;
 * never import personal media or ship these fixtures in the production APK.
 */
internal object Stage7aFixtureMedia {
    data class Sample(val uri: Uri, val name: String, val kind: MediaKind, val protected: Boolean = false)

    const val PHOTO_COUNT = 24
    const val VIDEO_COUNT = 6
    const val PROTECTED_COUNT = 2
    const val MIN_DUPLICATE_GROUPS = 3

    fun seed(context: Context, assets: AssetManager): List<Sample> {
        val samples = mutableListOf<Sample>()
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val mascot = context.resources.openRawResource(R.drawable.gp_mascot).use { it.readBytes() }
        val icon = context.resources.openRawResource(R.drawable.gp_photo).use { it.readBytes() }
        samples += insert(context, "EXAMPLE_mascot_original.png", "image/png", mascot, now - 28L * day)
        samples += insert(context, "EXAMPLE_mascot_copy.png", "image/png", mascot, now - 27L * day)
        samples += insert(context, "EXAMPLE_photo_icon.png", "image/png", icon, now - 26L * day)

        repeat(21) { index ->
            val seed = when (index) {
                1 -> 0
                9 -> 8
                17 -> 16
                else -> index
            }
            val screenshot = index in 4..5
            val name = if (screenshot) "EXAMPLE_Screenshot_" + index.toString().padStart(2, '0') + ".jpg"
                else "EXAMPLE_scene_" + index.toString().padStart(2, '0') + ".jpg"
            val folder = if (screenshot) "Pictures/GalliPick_EXAMPLE/Screenshots/"
                else if (index in 10..15) "Pictures/GalliPick_EXAMPLE/Trips/"
                else "Pictures/GalliPick_EXAMPLE/"
            val captureTime = now - (if (screenshot) 500L else (24 - index).toLong()) * day
            samples += insert(context, name, "image/jpeg", sceneJpeg(seed), captureTime, folder,
                protected = index == 0 || index == 10)
        }

        val baselineClip = assets.open("preview-baseline.mp4").use { it.readBytes() }
        val coastClip = assets.open("store-coast-preview.mp4").use { it.readBytes() }
        check(!baselineClip.contentEquals(coastClip)) { "Video fixture sources must be different" }
        repeat(VIDEO_COUNT) { index ->
            val clip = if (index < VIDEO_COUNT / 2) baselineClip else coastClip
            samples += insert(context, "EXAMPLE_clip_" + index.toString().padStart(2, '0') + ".mp4",
                "video/mp4", clip, now - (index + 2L) * day, "Movies/GalliPick_EXAMPLE/")
        }

        check(samples.count { it.kind == MediaKind.PHOTO } == PHOTO_COUNT)
        check(samples.count { it.kind == MediaKind.VIDEO } == VIDEO_COUNT)
        check(samples.count { it.protected } == PROTECTED_COUNT)
        return samples
    }

    fun insert(
        context: Context, name: String, mimeType: String, bytes: ByteArray,
        capturedAt: Long, folder: String = "Pictures/GalliPick_EXAMPLE/",
        protected: Boolean = false,
    ): Sample {
        val video = mimeType.startsWith("video/")
        val collection = if (video)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.DATE_TAKEN, capturedAt)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (!video) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                check(options.outWidth > 0 && options.outHeight > 0) { "Not a decodable test image: " + name }
                put(MediaStore.MediaColumns.WIDTH, options.outWidth)
                put(MediaStore.MediaColumns.HEIGHT, options.outHeight)
            }
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(collection, values)) { "Cannot insert: " + name }
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            val publish = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (video) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    check(duration != null && duration > 0 && width != null && width > 0 && height != null && height > 0) {
                        "Not a playable test video: " + name
                    }
                    publish.put(MediaStore.MediaColumns.DURATION, duration)
                    publish.put(MediaStore.MediaColumns.WIDTH, width)
                    publish.put(MediaStore.MediaColumns.HEIGHT, height)
                } finally {
                    retriever.release()
                }
            }
            check(resolver.update(uri, publish, null, null) == 1) { "Cannot publish: " + name }
            return Sample(uri, name, if (video) MediaKind.VIDEO else MediaKind.PHOTO, protected)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    /** Small, varied scenic illustrations encoded as genuine JPEG test media. */
    fun sceneJpeg(seed: Int): ByteArray {
        val portrait = seed % 3 == 0
        val width = if (portrait) 480 else 720
        val height = if (portrait) 720 else 480
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val upper = Color.rgb(80 + (seed * 13) % 100, 135 + (seed * 7) % 100, 168 + (seed * 9) % 70)
            val lower = Color.rgb(230, 194 - (seed * 4) % 70, 148 + (seed * 5) % 90)
            paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), upper, lower, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            paint.color = Color.argb(190, 255, 244, 192)
            val sunX = width * (0.18f + (seed % 5) * 0.14f)
            canvas.drawCircle(sunX, height * 0.22f, width * 0.08f, paint)
            val distant = Path().apply {
                moveTo(0f, height * 0.66f)
                lineTo(width * 0.22f, height * (0.31f + (seed % 4) * 0.05f))
                lineTo(width * 0.53f, height * 0.62f)
                lineTo(width * 0.77f, height * (0.36f + (seed % 3) * 0.04f))
                lineTo(width.toFloat(), height * 0.63f)
                lineTo(width.toFloat(), height.toFloat())
                lineTo(0f, height.toFloat())
                close()
            }
            paint.color = Color.rgb(53 + (seed * 3) % 60, 112 + (seed * 11) % 65, 116 + (seed * 5) % 60)
            canvas.drawPath(distant, paint)
            val foreground = Path().apply {
                moveTo(0f, height * 0.78f)
                lineTo(width * 0.31f, height * 0.68f)
                lineTo(width * 0.6f, height * 0.81f)
                lineTo(width.toFloat(), height * 0.67f)
                lineTo(width.toFloat(), height.toFloat())
                lineTo(0f, height.toFloat())
                close()
            }
            paint.color = Color.rgb(27 + seed % 25, 69 + (seed * 7) % 68, 67 + (seed * 3) % 40)
            canvas.drawPath(foreground, paint)
            if (seed % 2 == 0) {
                paint.color = Color.rgb(27, 72 + seed % 60, 104 + seed % 80)
                canvas.drawRect(0f, height * 0.82f, width.toFloat(), height.toFloat(), paint)
                paint.strokeWidth = 2f
                for (i in 0..12) {
                    paint.color = Color.argb(80, 238, 245, 233)
                    val y = height * (0.83f + i * 0.014f)
                    canvas.drawLine(0f, y, width.toFloat(), y, paint)
                }
            } else {
                paint.color = Color.rgb(30, 71 + seed % 40, 50)
                for (i in 0..6) {
                    val x = width * (i * 0.17f + 0.04f)
                    val trunkY = height * (0.69f + (i % 3) * 0.03f)
                    paint.strokeWidth = 9f
                    canvas.drawLine(x, trunkY, x, trunkY - height * 0.12f, paint)
                    canvas.drawCircle(x, trunkY - height * 0.15f, width * 0.028f, paint)
                }
            }
            return ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream))
                stream.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
