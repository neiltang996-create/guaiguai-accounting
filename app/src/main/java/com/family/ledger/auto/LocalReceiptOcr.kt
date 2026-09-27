package com.family.ledger.auto

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.family.ledger.auto.rules.NodeSnapshot
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bundled on-device model: no cloud OCR, no image persistence until a receipt is recognized. */
class LocalReceiptOcr(private val service: AccessibilityService? = null) {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun capture(windowId: Int): Bitmap? {
        val service = service ?: return null
        if (Build.VERSION.SDK_INT < 30) return null
        return suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hardware ->
                            hardware.copy(Bitmap.Config.ARGB_8888, false).also { hardware.recycle() }
                        }
                    } finally { buffer.close() }
                    if (continuation.isActive) continuation.resume(bitmap) else bitmap?.recycle()
                }
                override fun onFailure(errorCode: Int) {
                    android.util.Log.w("AutoBillDiag", "screenshot unavailable code=$errorCode window=$windowId")
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            // Window capture avoids including overlays or another app in split-screen mode.
            if (Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(windowId, service.mainExecutor, callback)
            else service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
        }
    }

    private val numericRecognizer = TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)

    private suspend fun recognize(bitmap: Bitmap, reader: com.google.mlkit.vision.text.TextRecognizer): com.google.mlkit.vision.text.Text =
        suspendCancellableCoroutine { continuation ->
            reader.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener {
                if (continuation.isActive) continuation.resume(it)
            }.addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        }

    suspend fun read(bitmap: Bitmap, pkg: String): NodeSnapshot {
        val result = recognize(bitmap, recognizer)
        val lines = result.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        val ordered = lines.sortedBy { it.boundingBox!!.centerY() }.toMutableList()
        val children = mutableListOf<NodeSnapshot>()
        while (ordered.isNotEmpty()) {
            val first = ordered.removeAt(0)
            val y = first.boundingBox!!.centerY()
            val row = mutableListOf(first)
            val sameRow = ordered.filter { kotlin.math.abs(it.boundingBox!!.centerY() - y) < first.boundingBox!!.height() * 0.6 }
            ordered.removeAll(sameRow.toSet()); row.addAll(sameRow)
            for (line in row.sortedBy { it.boundingBox!!.left }) {
                var text = OcrTextNormalizer.normalize(line.text)
                val compact = text.replace(" ", "")
                val maskedCard = compact.take(4).all { it.isDigit() } && compact.contains('*')
                val orderNumber = compact.matches(Regex("[0-9]{8,}"))
                if (maskedCard || orderNumber) {
                    val box = line.boundingBox!!
                    val left = (box.left - 12).coerceAtLeast(0)
                    val top = (box.top - 8).coerceAtLeast(0)
                    val crop = Bitmap.createBitmap(bitmap, left, top,
                        (box.right + 12).coerceAtMost(bitmap.width) - left,
                        (box.bottom + 8).coerceAtMost(bitmap.height) - top)
                    val enlarged = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
                    try {
                        val digits = recognize(enlarged, numericRecognizer).text.replace(Regex("\\s+"), "")
                        if ((maskedCard && digits.matches(Regex("[0-9]{4,6}[*xX•·]+[0-9]{4}"))) ||
                            (orderNumber && digits.matches(Regex("[0-9]{8,}")))) text = digits
                    } finally { enlarged.recycle(); if (crop !== bitmap) crop.recycle() }
                }
                children += NodeSnapshot(className = "android.widget.TextView", text = text)
            }
        }
        return NodeSnapshot(packageName = pkg, children = children)
    }
    fun close() { recognizer.close(); numericRecognizer.close() }
}
