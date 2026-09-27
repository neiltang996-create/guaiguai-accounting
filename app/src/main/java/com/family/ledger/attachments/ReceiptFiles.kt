package com.family.ledger.attachments

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Content-addressed private attachments. References are portable across the two phones. */
class ReceiptFiles(context: Context) {
    private val directory = File(context.filesDir, "receipts").apply { mkdirs() }
    fun file(reference: String): File? = ReceiptReference.hash(reference)?.let { File(directory, "$it.jpg") }
    fun save(bitmap: Bitmap): String {
        val resized = if (bitmap.width > 1440) Bitmap.createScaledBitmap(bitmap, 1440,
            (bitmap.height * 1440L / bitmap.width).toInt(), true) else bitmap
        val bytes = ByteArrayOutputStream().use { out ->
            check(resized.compress(Bitmap.CompressFormat.JPEG, 85, out)); out.toByteArray()
        }
        if (resized !== bitmap) resized.recycle()
        val reference = ReceiptReference.of(bytes)
        put(reference, bytes)
        return reference
    }
    @Synchronized fun put(reference: String, bytes: ByteArray) {
        require(bytes.size in 4..ReceiptReference.MAX_BYTES && ReceiptReference.of(bytes) == reference)
        require(bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte())
        val destination = requireNotNull(file(reference))
        if (destination.exists()) return
        val temp = File.createTempFile("receipt-", ".tmp", directory)
        try { temp.writeBytes(bytes); check(temp.renameTo(destination)) } finally { temp.delete() }
    }
}
