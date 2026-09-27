package com.family.ledger.attachments
import com.family.ledger.auto.PendingBillCodec
import org.junit.Assert.*
import org.junit.Test
class ReceiptReferenceTest {
    @Test fun screenshotReferenceRoundTripsWithoutTruncationAndCannotEscapePrivateDirectory() {
        val ref = ReceiptReference.of(byteArrayOf(1, 2, 3))
        assertEquals(64, ReceiptReference.hash(ref)!!.length)
        val raw = PendingBillCodec.encodeRawText("单笔账单", mapOf("image" to ref))
        assertEquals(ref, PendingBillCodec.extrasOf(raw)["image"])
        for (bad in listOf("../../secret.jpg", "/sdcard/test.jpg", "https://example.com/a.jpg", ref+"/../secret")) assertNull(ReceiptReference.hash(bad))
        assertEquals(listOf(ref), ReceiptReference.fromPaths("$ref,$ref,../../secret"))
    }
}
