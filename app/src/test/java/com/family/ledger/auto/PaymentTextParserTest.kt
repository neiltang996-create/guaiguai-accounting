package com.family.ledger.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PaymentTextParser` 单测：真实通知样例 + 误报拦截。
 *
 * 解析器是纯 Kotlin（零 Android 依赖），因此这些用例全部跑在 JVM 上。
 */
class PaymentTextParserTest {

    private val now = 1_700_000_000_000L

    private fun parse(text: String, pkg: String = PayPackages.ALIPAY) =
        PaymentTextParser.parse(text, pkg, now)

    // ---------- 任务要求的真实样例 ----------

    @Test
    fun `支付宝 你向美团付款`() {
        val s = parse("你向 美团 付款45.00元", PayPackages.ALIPAY)
        assertNotNull(s)
        assertEquals(4500L, s!!.amountCents)
        assertEquals("美团", s.merchant)
        assertEquals(Direction.PAYMENT, s.direction)
        assertEquals(Channel.ALIPAY, s.channel)
    }

    @Test
    fun `微信支付 向星巴克付款`() {
        val s = parse("微信支付：向 星巴克 付款 32.00 元", PayPackages.WECHAT)
        assertNotNull(s)
        assertEquals(3200L, s!!.amountCents)
        assertEquals("星巴克", s.merchant)
        assertEquals(Channel.WECHAT, s.channel)
        assertEquals(Direction.PAYMENT, s.direction)
    }

    @Test
    fun `微信支付标题加金额的付款通知`() {
        val s = parse("微信支付 45.00元", PayPackages.WECHAT)
        assertNotNull(s)
        assertEquals(4500L, s!!.amountCents)
        assertEquals(Direction.PAYMENT, s.direction)
    }

    @Test
    fun `微信非支付会话的消费文案不产出信号`() {
        assertNull(parse("服务通知：本次消费45.00元", PayPackages.WECHAT))
    }

    @Test
    fun `支付宝 花呗付款成功带负号`() {
        val s = parse("支付宝：花呗 付款成功 -¥192.00", PayPackages.ALIPAY)
        assertNotNull(s)
        assertEquals(19200L, s!!.amountCents)
        assertEquals(Direction.PAYMENT, s.direction)
        assertEquals("花呗", s.suggestedAccountHint)
        assertEquals("支付宝·花呗", s.channelLabel)
    }

    @Test
    fun `退款成功退回余额宝`() {
        val s = parse("退款成功：¥45.00 已退回余额宝", PayPackages.ALIPAY)
        assertNotNull(s)
        assertEquals(4500L, s!!.amountCents)
        assertEquals(Direction.REFUND, s.direction)
        assertEquals("余额宝", s.suggestedAccountHint)
    }

    // ---------- 金额格式 ----------

    @Test
    fun `千分位与全角符号`() {
        val s = parse("付款成功：￥1,234.00", PayPackages.ALIPAY)
        assertEquals(123400L, s!!.amountCents)
    }

    @Test
    fun `多个金额时选紧邻支付动词的那个`() {
        val s = parse("您的余额¥1,234.00，本次支付¥45.00，感谢使用", PayPackages.ALIPAY)
        assertEquals(4500L, s!!.amountCents)
    }

    @Test
    fun `裸金额仅在成功标记下接受`() {
        val ok = parse("支付成功，已扣款 45.00", PayPackages.ALIPAY)
        assertEquals(4500L, ok!!.amountCents)
        assertNull(parse("订单金额 45.00 待确认", PayPackages.ALIPAY))
    }

    // ---------- 账户线索与渠道 ----------

    @Test
    fun `微信零钱线索`() {
        val s = parse("微信支付：向 星巴克 付款 32.00 元，已从零钱扣除", PayPackages.WECHAT)
        assertEquals("零钱", s!!.suggestedAccountHint)
        assertEquals("微信零钱", s.channelLabel)
    }

    @Test
    fun `银行卡尾号线索`() {
        val s = parse("您尾号1234的储蓄卡消费¥88.00", PayPackages.UNIONPAY)
        assertEquals(8800L, s!!.amountCents)
        assertEquals("储蓄卡(1234)", s.suggestedAccountHint)
        assertEquals("储蓄卡(1234)", s.channelLabel)
        assertEquals(Channel.UNIONPAY, s.channel)
    }

    @Test
    fun `交易订单号抽取`() {
        val s = parse("交易订单号9000000000000000000005 你向 美团 付款45.00元", PayPackages.ALIPAY)
        assertEquals("9000000000000000000005", s!!.orderId)
    }

    @Test
    fun `商户冒号格式`() {
        val s = parse("支付宝：商户：肯德基 付款成功 ¥36.50", PayPackages.ALIPAY)
        assertEquals("肯德基", s!!.merchant)
        assertEquals(3650L, s.amountCents)
    }

    @Test
    fun `商户横线格式`() {
        val s = parse("星巴克-付款成功 ¥32.00", PayPackages.ALIPAY)
        assertEquals("星巴克", s!!.merchant)
    }

    @Test
    fun `收款到账为收入`() {
        val s = parse("微信支付：收款到账通知 ¥88.00", PayPackages.WECHAT)
        assertEquals(8800L, s!!.amountCents)
        assertEquals(Direction.INCOME, s.direction)
    }

    @Test
    fun `花呗消费已入账仍算支出`() {
        val s = parse("支付宝：花呗 消费提醒 ¥192.00 已入账", PayPackages.ALIPAY)
        assertNotNull(s)
        assertEquals(19200L, s!!.amountCents)
        assertEquals("「已入账」不能把支出误判成收入", Direction.PAYMENT, s.direction)
    }

    @Test
    fun `单独的入账标记算收入`() {
        val s = parse("微信支付：零钱入账 ¥50.00", PayPackages.WECHAT)
        assertNotNull(s)
        assertEquals(5000L, s!!.amountCents)
        assertEquals(Direction.INCOME, s.direction)
    }

    @Test
    fun `支付宝账单详情页可识别`() {
        val s = parse("支付宝 账单详情 美团 支出 45.00元 付款方式 余额宝 交易订单号 9000000000000000000005")
        assertNotNull(s)
        assertEquals(4500L, s!!.amountCents)
        assertEquals(Direction.PAYMENT, s.direction)
        assertEquals("余额宝", s.suggestedAccountHint)
        assertEquals("9000000000000000000005", s.orderId)
    }

    @Test
    fun `银行短信式支出可识别`() {
        val s = parse("您尾号1234的储蓄卡消费人民币45.00元，余额1234.56元", PayPackages.UNIONPAY)
        assertNotNull(s)
        assertEquals("应取消费金额而不是余额", 4500L, s!!.amountCents)
        assertEquals("储蓄卡(1234)", s.suggestedAccountHint)
        assertEquals(Direction.PAYMENT, s.direction)
    }

    // ---------- 误报拦截 ----------

    @Test
    fun `验证码不产出信号`() {
        assertNull(parse("您的验证码是 123456，5分钟内有效"))
        assertNull(parse("校验码 654321，请勿泄露"))
    }

    @Test
    fun `广告不产出信号`() {
        assertNull(parse("【支付宝】双十一大促，全场¥9.9起，点击下载APP"))
        assertNull(parse("支付宝红包到账 ¥5.00，立即领取"))
    }

    @Test
    fun `未完成与失败态不产出信号`() {
        assertNull(parse("待支付：¥45.00 请尽快完成付款"))
        assertNull(parse("支付失败，金额¥45.00，请重试"))
        assertNull(parse("订单已取消，退款¥45.00将原路退回"))
    }

    @Test
    fun `金额为零不产出信号`() {
        assertNull(parse("付款成功：¥0.00"))
        assertNull(parse("支付成功 0.00元"))
    }

    @Test
    fun `支付宝App名里的支付二字不算交易动词`() {
        // 「支付宝」本身含「支付」，如果门禁只匹配「支付」二字，广告就会被误判成支出
        assertNull(parse("支付宝 恭喜您获得¥5.00红包，点击领取"))
        assertNull(parse("支付宝 限时活动 全场¥9.9 点击查看"))
        assertNull(parse("支付宝 会员积分¥100.00即将过期"))
    }

    @Test
    fun `纯聊天消息不产出信号`() {
        assertNull(parse("在吗？晚上一起吃饭，帮我付一下款"))
        assertNull(parse("帮我微信支付 ¥50", PayPackages.WECHAT))
        assertNull(parse("转账给你 ¥50.00 记得还我", PayPackages.WECHAT))
    }

    @Test
    fun `微信聊天里的金额不产出信号`() {
        assertNull(parse("张三：这个月房租 ¥1500.00 该交了", PayPackages.WECHAT))
    }

    @Test
    fun `空文本与超短文本安全返回`() {
        assertNull(parse(""))
        assertNull(parse("嗯"))
    }

    @Test
    fun `超长文本被截断且仍可解析`() {
        val long = "付款成功 ¥12.34 你向 美团 " + "填充内容".repeat(600)
        val s = parse(long, PayPackages.ALIPAY)
        assertNotNull(s)
        assertEquals(1234L, s!!.amountCents)
        assertTrue(s.rawText.length <= PaymentTextParser.MAX_TEXT)
        // 截断后的文本必须幂等：再次解析结果一致
        val again = PaymentTextParser.parse(s.rawText, PayPackages.ALIPAY, now)
        assertEquals(s.amountCents, again?.amountCents)
    }
}
