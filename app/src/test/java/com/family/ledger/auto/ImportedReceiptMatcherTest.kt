package com.family.ledger.auto

import com.family.ledger.data.db.entity.*
import org.junit.Assert.*
import org.junit.Test

class ImportedReceiptMatcherTest {
    private val time = 1_790_000_000_000L
    private val signal = PaySignal(sourcePackage = PayPackages.WECHAT, amountCents = 14800,
        merchant = "示例烧烤店", rawText = "", occurredAt = time, channel = Channel.WECHAT,
        direction = Direction.PAYMENT, origin = Origin.ACCESSIBILITY, pageType = "WeChatBillDetail",
        receiptTimeMillis = time, suggestedAccountHint = "邮储银行信用卡(8802)")
    private val record = TxnEntity(id = "imported", bookId = "family", type = TxnType.EXPENSE,
        amount = 14800, occurredAt = time + 2000, assetId = "card8802", payerMemberId = "liu",
        note = "示例烧烤店", source = TxnSource.IMPORT_QIANJI,
        createdByDeviceId = "import", createdAt = time + 10000, updatedAt = time + 10000)
    private fun match(rows: List<TxnEntity> = listOf(record), s: PaySignal = signal,
                      asset: String? = "card8802", person: String = "liu") =
        ImportedReceiptMatcher.match(s, asset, person, rows)

    @Test fun importedReceiptWithTwoSecondRecordingDelayIsAlreadyRecorded() {
        assertEquals("imported", match()?.id)
        assertEquals("imported", match(listOf(record.copy(amount = 15000, coupon = 200)))?.id)
    }
    @Test fun missingOrDifferentTransactionEvidenceCannotSuppressNewBill() {
        assertNull(match(asset = null))
        assertNull(match(asset = "other"))
        assertNull(match(person = "tang"))
        assertNull(match(s = signal.copy(receiptTimeMillis = null)))
        assertNull(match(s = signal.copy(origin = Origin.NOTIFICATION)))
        assertNull(match(s = signal.copy(direction = Direction.INCOME)))
        assertNull(match(s = signal.copy(merchant = "另一家店")))
        assertNull(match(s = signal.copy(amountCents = 15000)))
        assertNull(match(listOf(record.copy(occurredAt = time + 4000))))
        assertNull(match(listOf(record.copy(source = TxnSource.MANUAL))))
        assertNull(match(listOf(record.copy(deleted = true))))
    }
    @Test fun twoMatchingOldBillsAreAmbiguous() {
        assertNull(match(listOf(record, record.copy(id = "another"))))
    }
}
