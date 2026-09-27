package com.family.ledger.auto

import com.family.ledger.data.db.entity.PendingBillEntity
import com.family.ledger.data.db.entity.TxnSource
import com.family.ledger.data.db.entity.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PendingBillCodec` 单测。
 *
 * 实体表结构被 lead 冻结（待确认账单没有 direction/channel 列），
 * 所以「方向」靠 rawText 二次解析还原、「付款渠道」靠流水备注前缀持久化。
 * 这里把这两条约定钉死，避免以后改备注格式时静默失效。
 */
class PendingBillCodecTest {

    @Test
    fun `备注带上渠道并可还原`() {
        val note = PendingBillCodec.noteFor("微信零钱", flagged = false)
        assertTrue(note.startsWith("自动记账"))
        assertEquals("微信零钱", PendingBillCodec.channelLabelOfNote(note))
    }

    @Test
    fun `可能重复标记不影响渠道还原`() {
        val note = PendingBillCodec.noteFor("支付宝·花呗", flagged = true)
        assertEquals("支付宝·花呗", PendingBillCodec.channelLabelOfNote(note))
    }

    @Test
    fun `没有渠道线索时备注干净`() {
        val note = PendingBillCodec.noteFor(null, flagged = false)
        assertEquals("自动记账", note)
        assertNull(PendingBillCodec.channelLabelOfNote(note))
    }

    @Test
    fun `用户手工备注不会被误认为自动记账`() {
        assertNull(PendingBillCodec.channelLabelOfNote("午饭"))
        assertNull(PendingBillCodec.channelLabelOfNote(null))
        assertNull(PendingBillCodec.channelLabelOfNote(""))
    }

    @Test
    fun `方向映射到交易类型`() {
        assertEquals(TxnType.EXPENSE, PendingBillCodec.txnTypeOf(Direction.PAYMENT))
        assertEquals(TxnType.REFUND, PendingBillCodec.txnTypeOf(Direction.REFUND))
        assertEquals(TxnType.INCOME, PendingBillCodec.txnTypeOf(Direction.INCOME))
        // 转账**绝不能**落成支出：那会把「自己两个账户挪钱」算进支出统计
        assertEquals(TxnType.TRANSFER, PendingBillCodec.txnTypeOf(Direction.TRANSFER))
        assertNotEquals(TxnType.EXPENSE, PendingBillCodec.txnTypeOf(Direction.TRANSFER))
    }

    @Test
    fun `标记能带回转账方向与转入账户`() {
        val raw = "账单详情 1.00 交易成功 转出说明 余额宝-转出到银行卡 转入账户 中国工商银行(8804)"
        val encoded = PendingBillCodec.encodeRawText(
            raw,
            mapOf(
                PendingBillCodec.KEY_PAGE to "AlipayTransferOut",
                PendingBillCodec.KEY_DIRECTION to Direction.TRANSFER.name,
                PendingBillCodec.KEY_ASSET_HINT to "余额宝",
                PendingBillCodec.KEY_TO_ACCOUNT to "中国工商银行(8804)",
            ),
        )
        val p = pending(rawText = encoded, amount = 100)
        assertEquals(Direction.TRANSFER, PendingBillCodec.directionOf(p))
        assertEquals("中国工商银行(8804)", PendingBillCodec.toAssetHintOf(p.rawText))
        assertEquals("余额宝", PendingBillCodec.assetHintOf(p.rawText))
        assertEquals("AlipayTransferOut", PendingBillCodec.pageTypeOf(p.rawText))
        assertEquals(raw, PendingBillCodec.cleanRawText(encoded))
    }

    @Test
    fun `没有标记时转入账户为空`() {
        assertNull(PendingBillCodec.toAssetHintOf("你向 美团 付款45.00元"))
    }

    @Test
    fun `渠道映射到交易来源`() {
        assertEquals(TxnSource.AUTO_ALIPAY, PendingBillCodec.txnSourceOf(Channel.ALIPAY))
        assertEquals(TxnSource.AUTO_WECHAT, PendingBillCodec.txnSourceOf(Channel.WECHAT))
        assertEquals(TxnSource.AUTO_OTHER, PendingBillCodec.txnSourceOf(Channel.UNIONPAY))
        assertEquals(TxnSource.AUTO_OTHER, PendingBillCodec.txnSourceOf(Channel.UNKNOWN))
    }

    @Test
    fun `待确认账单能还原出信号与方向`() {
        val p = pending(rawText = "你向 美团 付款45.00元", amount = 4500)
        val signal = PendingBillCodec.signalOf(p)
        assertNotNull(signal)
        assertEquals(Direction.PAYMENT, signal!!.direction)
        assertEquals("美团", signal.merchant)
        assertEquals(Direction.PAYMENT, PendingBillCodec.directionOf(p))
        assertNull(PendingBillCodec.channelLabelOf(p))
    }

    @Test
    fun `退款账单能还原成退款方向`() {
        val p = pending(rawText = "退款成功：¥45.00 已退回余额宝", amount = 4500)
        assertEquals(Direction.REFUND, PendingBillCodec.directionOf(p))
        assertEquals(TxnType.REFUND, PendingBillCodec.txnTypeOf(PendingBillCodec.directionOf(p)))
        assertEquals("支付宝·余额宝", PendingBillCodec.channelLabelOf(p))
    }

    // ---------- 页面规则引擎的遗留字段（rawText 标记） ----------

    @Test
    fun `没有额外字段时不写标记`() {
        val raw = "你向 美团 付款45.00元"
        assertEquals(raw, PendingBillCodec.encodeRawText(raw, emptyMap()))
        assertEquals(emptyMap<String, String>(), PendingBillCodec.extrasOf(raw))
        assertEquals(raw, PendingBillCodec.cleanRawText(raw))
    }

    @Test
    fun `标记可写入读出并剥除`() {
        val raw = "支付成功 ¥45.00 美团"
        val encoded = PendingBillCodec.encodeRawText(
            raw,
            mapOf(
                PendingBillCodec.KEY_PAGE to "AlipayPaySuccess",
                PendingBillCodec.KEY_DIRECTION to Direction.REFUND.name,
                PendingBillCodec.KEY_COUPON to "50",
                PendingBillCodec.KEY_CHANNEL_LABEL to "支付宝·余额宝",
            ),
        )
        assertTrue(encoded.startsWith(raw))
        assertEquals(raw, PendingBillCodec.cleanRawText(encoded))
        assertEquals("AlipayPaySuccess", PendingBillCodec.pageTypeOf(encoded))
        assertEquals(50L, PendingBillCodec.couponOf(encoded))
        assertEquals("支付宝·余额宝", PendingBillCodec.channelLabelOf(pending(rawText = encoded, amount = 4500)))
    }

    @Test
    fun `标记里的方向优先于二次解析`() {
        // 账单详情页的纯文本可能解析不出方向，标记里存的才是真相
        val raw = "账单详情 ¥45.00 美团"
        val encoded = PendingBillCodec.encodeRawText(
            raw,
            mapOf(
                PendingBillCodec.KEY_PAGE to "AlipayBillDetail",
                PendingBillCodec.KEY_DIRECTION to Direction.REFUND.name,
            ),
        )
        val p = pending(rawText = encoded, amount = 4500)
        assertEquals(Direction.REFUND, PendingBillCodec.directionOf(p))
    }

    @Test
    fun `带标记的文本仍能二次解析（幂等）`() {
        val raw = "你向 美团 付款45.00元"
        val encoded = PendingBillCodec.encodeRawText(
            raw,
            mapOf(PendingBillCodec.KEY_PAGE to "AlipayPaySuccess"),
        )
        val signal = PendingBillCodec.signalOf(pending(rawText = encoded, amount = 4500))
        assertNotNull("标记不能污染二次解析", signal)
        assertEquals(4500L, signal!!.amountCents)
        assertEquals("美团", signal.merchant)
        assertEquals(Direction.PAYMENT, PendingBillCodec.directionOf(pending(rawText = encoded, amount = 4500)))
    }

    @Test
    fun `标记里的非法字符被清理不会破坏解析`() {
        val encoded = PendingBillCodec.encodeRawText(
            "¥1.00 测试",
            mapOf(PendingBillCodec.KEY_CHANNEL_LABEL to "支付宝|余额宝⟧注入"),
        )
        assertEquals(1, PendingBillCodec.extrasOf(encoded).size)
        assertEquals("支付宝/余额宝)注入", PendingBillCodec.extrasOf(encoded)[PendingBillCodec.KEY_CHANNEL_LABEL])
        assertEquals("¥1.00 测试", PendingBillCodec.cleanRawText(encoded))
    }

    @Test
    fun `没有标记时优惠券为 0`() {
        assertEquals(0L, PendingBillCodec.couponOf("支付成功 ¥45.00"))
        assertNull(PendingBillCodec.pageTypeOf("支付成功 ¥45.00"))
    }

    private fun pending(rawText: String, amount: Long) = PendingBillEntity(
        id = "pb-test",
        sourcePackage = PayPackages.ALIPAY,
        source = TxnSource.AUTO_ALIPAY,
        amount = amount,
        merchant = "美团",
        occurredAt = 1_700_000_000_000L,
        rawText = rawText,
        fingerprint = "fp-test",
        createdAt = 0L,
        updatedAt = 0L,
    )
}
