package com.family.ledger.auto.rules
import com.family.ledger.auto.*
import org.junit.Assert.*
import org.junit.Test
class IcbcReceiptTest {
    private fun nativePage() = NodeTree.decode(requireNotNull(javaClass.getResource("/auto-nodes/icbc_native_sample.json")).readText())
    @Test fun syntheticNativeRowsKeepSummaryCardAndHistoricalTimeDespiteDeepLabelWrappers() {
        val signal = requireNotNull(BillPageParser.parseSignal(PayPackages.ICBC, nativePage(), 1790480000000L))
        assertEquals(1625L, signal.amountCents)
        assertEquals("ETC代扣", signal.merchant)
        assertEquals("工商银行(8801)", signal.suggestedAccountHint)
        assertEquals(Values.parseTimeMs("2024-04-12 10:20:29"), signal.receiptTimeMillis)
        assertEquals(signal.receiptTimeMillis, signal.occurredAt)
    }
    @Test fun incompleteNativeRowsNeverBorrowAnotherRowOrUseCurrentTime() {
        fun withoutValue(node: NodeSnapshot): NodeSnapshot = node.copy(
            text = if (node.display == "2024-04-12 10:20:29") null else node.text,
            contentDescription = if (node.display == "2024-04-12 10:20:29") null else node.contentDescription,
            children = node.children.map(::withoutValue))
        assertNull(BillPageParser.parseSignal(PayPackages.ICBC, withoutValue(nativePage()), 1790480000000L))
    }
    private val rows = listOf("明细详情", "-16.25", "交易时间", "2024-04-12", "记账时间", "2024-04-12 10:20:28",
        "交易卡号", "6229****8801", "业务摘要", "ETC 代扣", "交易国家或地区简称", "CHN", "交易场所", "示例城市支行营业室",
        "交易金额", "16.25", "记账金额", "16.25", "记账币种", "人民币", "查询完整交易卡号或账户")
    private fun parse(texts: List<String>) = BillPageParser.parseSignal(PayPackages.ICBC,
        NodeSnapshot(children=texts.map { NodeSnapshot(className="TextView", text=it) }), 1790442000000L)
    @Test fun screenshotTranscriptionExtractsOneDebitAndMaskedAccount() {
        val parsed = requireNotNull(parse(rows))
        assertEquals(1625L, parsed.amountCents)
        assertEquals(Direction.PAYMENT, parsed.direction)
        assertEquals("ETC 代扣", parsed.merchant)
        assertEquals("工商银行(8801)", parsed.suggestedAccountHint)
        assertEquals(Values.parseTimeMs("2024-04-12 10:20:28"), parsed.occurredAt)
    }
    @Test fun creditUsesPlusSignAndSummaryNeverBecomesTransaction() {
        assertEquals(Direction.INCOME, parse(rows.map { if(it=="-16.25") "+16.25" else it })?.direction)
        assertNull(parse(listOf("收支明细", "-16.25", "ETC 代扣", "2024-04-12", "人民币")))
        assertNull(parse(rows.filter { it != "明细详情" }))
        assertNull(parse(rows.map { if(it=="人民币") "美元" else it }))
    }
    @Test fun screenshotCannotAttachToAnotherMerchantOrTransaction() {
        val first = requireNotNull(parse(rows))
        assertTrue(ReceiptCapturePolicy.sameReceipt(first, first))
        assertFalse(ReceiptCapturePolicy.sameReceipt(first, first.copy(merchant="另一商户")))
        assertFalse(ReceiptCapturePolicy.sameReceipt(first, first.copy(amountCents=2000)))
        assertFalse(ReceiptCapturePolicy.sameReceipt(first, first.copy(suggestedAccountHint="工商银行(1234)")))
        assertFalse(ReceiptCapturePolicy.sameReceipt(first, first.copy(receiptTimeMillis=first.receiptTimeMillis!!+1000)))
        assertEquals("商户全称", OcrTextNormalizer.normalize("|商戶全称"))
        assertEquals("支付方式", OcrTextNormalizer.normalize("支付万式"))
        assertEquals("6229****了801", OcrTextNormalizer.normalize("6229****了801"))
    }

    @Test fun nativeChatAndLoginAreNeverOcrTargets() {
        assertFalse(ReceiptCapturePolicy.mayOcr(PayPackages.WECHAT, "com.tencent.mm.ui.LauncherUI"))
        assertFalse(ReceiptCapturePolicy.mayOcr(PayPackages.ICBC, "com.icbc.LoginWebActivity"))
        assertTrue(ReceiptCapturePolicy.mayOcr(PayPackages.WECHAT, "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI"))
        assertFalse(ReceiptCapturePolicy.mayOcr("random.app", "BillDetailActivity"))
    }
}
