package com.family.ledger.attachments

import java.security.MessageDigest

object ReceiptReference {
    const val MAX_BYTES = 2 * 1024 * 1024
    private val pattern = Regex("receipts/([a-f0-9]{64})\\.jpg")
    fun hash(reference: String): String? = pattern.matchEntire(reference)?.groupValues?.get(1)
    fun of(bytes: ByteArray): String = "receipts/" + MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) } + ".jpg"
    fun fromPaths(paths: String?): List<String> = paths.orEmpty().split(',').filter { hash(it) != null }.distinct()
}
