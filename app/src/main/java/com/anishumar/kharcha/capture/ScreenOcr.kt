package com.anishumar.kharcha.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

/**
 * Reads the screen as an image, for apps that expose little or no text to accessibility
 * (Swiggy order pages, card checkout totals). Runs on the phone; the screenshot is never saved.
 */
object ScreenOcr {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Calls [done] on the main thread with the screen's text in reading order, or null. */
    fun read(service: AccessibilityService, done: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { done(null); return }
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                    val buffer = shot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (bitmap == null) { done(null); return }
                    // skip the status bar so its clock isn't mistaken for the payment time
                    val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
                    val top = if (id > 0) service.resources.getDimensionPixelSize(id).coerceAtMost(bitmap.height / 4) else 0
                    val body = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bitmap.height - top)
                    recognizer.process(InputImage.fromBitmap(body, 0))
                        .addOnSuccessListener { done(rows(it)) }
                        .addOnFailureListener { done(null) }
                }

                // e.g. a FLAG_SECURE screen (most payment apps), which accessibility text already covers
                override fun onFailure(errorCode: Int) = done(null)
            })
    }

    /** Lines that sit at the same height become one row, left to right: "To Pay      ₹344". */
    private fun rows(result: com.google.mlkit.vision.text.Text): String {
        val lines = result.textBlocks.flatMap { it.lines }
            .mapNotNull { l -> l.boundingBox?.let { it to l.text } }
            .sortedBy { it.first.centerY() }
        val rows = mutableListOf<MutableList<Pair<android.graphics.Rect, String>>>()
        for (line in lines) {
            val row = rows.lastOrNull()
            val sameRow = row != null && abs(row.first().first.centerY() - line.first.centerY()) < line.first.height() * 0.6
            if (sameRow) row!!.add(line) else rows.add(mutableListOf(line))
        }
        return rows.joinToString("\n") { r -> r.sortedBy { it.first.left }.joinToString("   ") { it.second } }
    }
}
