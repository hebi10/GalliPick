package com.hebi.gallipick.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalliMascotAssetTest {
    private val resources get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test fun allFiveScenesUseDistinctTransparentSquareArtwork() {
        val pairs = mascotPairs()
        assertEquals(5, pairs.size)
        assertEquals(5, pairs.map { it.second }.distinct().size)
        pairs.forEach { (oldId, monoId) ->
            val old = requireNotNull(BitmapFactory.decodeResource(resources, oldId))
            val mono = requireNotNull(BitmapFactory.decodeResource(resources, monoId))
            try {
                assertEquals(640, mono.width)
                assertEquals(640, mono.height)
                assertEquals(0, Color.alpha(mono.getPixel(0, 0)))
                assertEquals(0, Color.alpha(mono.getPixel(639, 639)))
                assertTrue("The neutral mascot must contain visible opaque pixels", countOpaque(mono) > 3000)
                assertNotEquals("Do not accidentally reference the old artwork", colorSampleHash(old), colorSampleHash(mono))
            } finally {
                old.recycle()
                mono.recycle()
            }
        }
    }

    @Test fun newArtworkContainsLessSaturatedGreenThanOriginalMascots() {
        mascotPairs().forEach { (oldId, monoId) ->
            val old = requireNotNull(BitmapFactory.decodeResource(resources, oldId))
            val mono = requireNotNull(BitmapFactory.decodeResource(resources, monoId))
            try {
                val oldGreen = greenSamples(old)
                val newGreen = greenSamples(mono)
                assertTrue("Original scene has no recognizable green accents", oldGreen > 30)
                assertTrue("Neutral mascot should suppress saturated green accents: original=$oldGreen new=$newGreen",
                    newGreen < oldGreen)
            } finally {
                old.recycle()
                mono.recycle()
            }
        }
    }

    private fun mascotPairs() = listOf(
        R.drawable.gp_cat_organize to R.drawable.gp_cat_organize_mono,
        R.drawable.gp_cat_search to R.drawable.gp_cat_search_mono,
        R.drawable.gp_cat_protect to R.drawable.gp_cat_protect_mono,
        R.drawable.gp_cat_complete to R.drawable.gp_cat_complete_mono,
        R.drawable.gp_cat_empty to R.drawable.gp_cat_empty_mono,
    )

    private fun countOpaque(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 220) count++
        }
        return count
    }

    private fun greenSamples(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
            val c = bitmap.getPixel(x, y)
            if (Color.alpha(c) < 220) continue
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            if (g > 90 && g > r * 1.2f && g > b * 1.12f) count++
        }
        return count
    }

    private fun colorSampleHash(bitmap: Bitmap): Long {
        var hash = 17L
        for (y in 0 until bitmap.height step 11) for (x in 0 until bitmap.width step 11) {
            hash = (hash * 31L + bitmap.getPixel(x, y).toLong()) and 0x7FFF_FFFF_FFFF_FFFFL
        }
        return hash
    }
}
