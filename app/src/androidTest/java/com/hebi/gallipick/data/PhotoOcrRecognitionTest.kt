package com.hebi.gallipick.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoOcrRecognitionTest {
    @Test fun bundledLatinModelRecognizesRealRenderedTextOffline() {
        val bitmap = Bitmap.createBitmap(1000, 350, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 110f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText("RECEIPT 2026", 40f, 210f, paint)
            val model = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val result = Tasks.await(model.process(InputImage.fromBitmap(bitmap, 0)),
                    40, TimeUnit.SECONDS)
                assertTrue("Bundled on-device Latin OCR must recognize a rendered label",
                    result.text.isNotBlank())
            } finally {
                model.close()
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun bundledKoreanModelCanInitializeAndProcessLocalBitmap() {
        val bitmap = Bitmap.createBitmap(1000, 350, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 110f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            canvas.drawText("서울 여행", 40f, 210f, paint)
            val model = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            try {
                val result = Tasks.await(model.process(InputImage.fromBitmap(bitmap, 0)),
                    40, TimeUnit.SECONDS)
                assertTrue("Korean OCR must process an offline bitmap", result.text.isNotBlank())
            } finally {
                model.close()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
