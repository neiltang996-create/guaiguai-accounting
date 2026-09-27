package com.family.ledger.auto.rules

import com.family.ledger.auto.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段值解析单测：金额、标签剥离、订单号、时间、方向、失败状态。
 *
 * 这些函数是规则的「最后一公里」—— 抽错值比抽不到值更糟（会把脏数据写进账本），
 * 所以这里对边界抠得比较细。
 */
class ValuesTest {

    // ---------- 金额 ----------

    @Test
    fun `金额支持币种符号千分位与元后缀`() {
        assertEquals(4500L, Values.parseAmountCents("¥45.00"))
        assertEquals(4500L, Values.parseAmountCents("￥45"))
        assertEquals(123450L, Values.parseAmountCents("¥1,234.50"))
        assertEquals(4500L, Values.parseAmountCents("45.00元"))
        assertEquals(4500L, Values.parseAmountCents("45元"))
    }

    @Test
    fun `负数金额保留符号`() {
        assertEquals(-50L, Values.parseAmountCents("-0.50元"))
        assertEquals(-4500L, Values.parseAmountCents("-45.00"))
        assertEquals(50L, Values.parseCouponCents("-0.50元"))
    }

    @Test
    fun `光秃秃的整数不算金额`() {
        // 支付页上到处是无关数字（时间、卡号尾号、订单号片段），不能当成金额
        assertNull(Values.parseAmountCents("12"))
        assertNull(Values.parseAmountCents("2026"))
        assertNull(Values.parseAmountCents("美团"))
        assertNull(Values.parseAmountCents(null))
        assertFalse(Values.looksLikeMoney("12"))
    }

    @Test
    fun `日期不是金额`() {
        assertNull(Values.parseAmountCents("2024-04-11"))
        assertFalse(Values.looksLikeMoney("2024-04-11 13:03:38"))
    }

    @Test
    fun `零金额视为取不到`() {
        assertNull(Values.parseAmountCents("¥0.00"))
        assertNull(Values.parseAmountCents("0.00"))
    }

    @Test
    fun `混排文本里能抽出金额`() {
        assertEquals("-0.50元", Values.extractMoneyText("红包 -0.50元"))
        assertEquals("0.30元", Values.extractMoneyText("优惠 0.30元"))
        assertEquals("¥45.00", Values.extractMoneyText("已支付 ¥45.00"))
        assertEquals(-50L, Values.parseAmountCents("红包 -0.50元"))
        assertEquals(30L, Values.parseCouponCents("优惠 0.30元"))
        assertTrue(Values.containsMoney("红包 -0.50元"))
        assertFalse(Values.containsMoney("有红包可用"))
        assertNull("订单号不能被当成金额", Values.extractMoneyText("订单号 9000000000000000000004"))
    }

    // ---------- 标签剥离 ----------

    @Test
    fun `剥掉已知标签前缀`() {
        assertEquals("美团", Values.stripLabel("商户名称：美团"))
        assertEquals("余额宝", Values.stripLabel("付款方式 余额宝"))
        assertEquals("9000000000000000000004", Values.stripLabel("订单号 9000000000000000000004"))
        assertEquals("9000000000000000000003", Values.stripLabel("订单号：9000000000000000000003"))
    }

    @Test
    fun `纯标签返回 null`() {
        assertNull(Values.stripLabel("交易方式"))
        assertNull(Values.stripLabel("优惠"))
        assertNull(Values.stripLabel(""))
        assertNull(Values.stripLabel(null))
    }

    @Test
    fun `时间里的冒号不会被当成标签分隔`() {
        assertNull("13:03:38 的冒号前是数字，不是标签", Values.stripLabel("13:03:38"))
        assertNull(Values.stripLabel("2024-04-11 13:03:38"))
    }

    @Test
    fun `普通值不会被误剥`() {
        assertNull(Values.stripLabel("美团"))
        assertNull(Values.stripLabel("全家便利店"))
    }

    // ---------- 订单号 / 时间 ----------

    @Test
    fun `订单号抽连续数字`() {
        assertEquals("9000000000000000000004", Values.digitsOf("订单号 9000000000000000000004"))
        assertEquals("4200001234", Values.digitsOf("单号 4200001234"))
        assertNull("太短的不算订单号", Values.digitsOf("订单号 123"))
        assertNull(Values.digitsOf("美团"))
    }

    @Test
    fun `时间字符串与 epoch`() {
        assertEquals("2024-04-11 13:03:38", Values.timeTextOf("付款时间 2024-04-11 13:03:38"))
        assertEquals("2024-04-11", Values.timeTextOf("2024-04-11"))
        assertNull(Values.timeTextOf("今天"))
        val epoch = Values.parseTimeMs("2024-04-11 13:03:38")
        assertTrue(epoch != null && epoch > 0)
        assertNull(Values.parseTimeMs("今天下午"))
    }

    // ---------- 方向 ----------

    @Test
    fun `退款优先于支付`() {
        assertEquals(Direction.REFUND, Values.directionOf("退款成功"))
        assertEquals(Direction.REFUND, Values.directionOf("已退款"))
        assertEquals(Direction.REFUND, Values.directionOf("已退回余额宝"))
    }

    @Test
    fun `收入与支出`() {
        assertEquals(Direction.INCOME, Values.directionOf("收款成功"))
        assertEquals(Direction.INCOME, Values.directionOf("已到账"))
        assertEquals(Direction.PAYMENT, Values.directionOf("支付成功"))
        assertEquals(Direction.PAYMENT, Values.directionOf("交易成功"))
        assertNull(Values.directionOf("交易关闭"))
        assertNull(Values.directionOf(null))
    }

    @Test
    fun `符号判定`() {
        assertEquals("-", Values.signOf("-45.00"))
        assertEquals("+", Values.signOf("+45.00"))
        assertNull(Values.signOf("¥45.00"))
    }

    @Test
    fun `关闭或失败的交易不记账`() {
        assertTrue(Values.isClosedOrFailed("交易关闭"))
        assertTrue(Values.isClosedOrFailed("支付失败"))
        assertTrue(Values.isClosedOrFailed("已取消"))
        assertTrue(Values.isClosedOrFailed("待付款"))
        assertFalse(Values.isClosedOrFailed("支付成功"))
        assertFalse(Values.isClosedOrFailed("已退款"))
        assertFalse(Values.isClosedOrFailed(null))
    }

    @Test
    fun `状态值能被识别（避免被当成商户名）`() {
        // 真机转账页：金额 1.00 的下一个文本就是「交易成功」
        assertTrue(Values.isStatusLike("交易成功"))
        assertTrue(Values.isStatusLike("银行处理中"))
        assertTrue(Values.isStatusLike("已存入零钱"))
        assertFalse(Values.isStatusLike("美团"))
        assertFalse(Values.isStatusLike("中国工商银行(8804)"))
        assertFalse(Values.isStatusLike(null))
    }

    @Test
    fun `标签集合覆盖常见账单行`() {
        assertTrue(Labels.isLabel("交易方式"))
        assertTrue(Labels.isLabel("当前状态"))
        assertTrue(Labels.isLabel("商户全称"))
        assertFalse(Labels.isLabel("美团"))
        assertFalse(Labels.isLabel("余额宝"))
    }
}
