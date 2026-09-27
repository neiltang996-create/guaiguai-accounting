package com.family.ledger.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AssetHintMatcher` 单测：页面给出的付款方式 → 账本资产的匹配打分。
 *
 * 用的是用户**真实资产名**（`assets/qianji_seed.json`）：全角括号、银行卡尾号、渠道前缀都真实存在，
 * 所以这里就把这些情况钉死，避免「页面显示余额宝，却记到支付宝小荷包」这类错账。
 */
class AssetHintMatcherTest {

    @Test
    fun `完全一致得最高分`() {
        assertEquals(AssetHintMatcher.SCORE_EXACT, AssetHintMatcher.score("余额宝", "余额宝"))
        assertEquals(
            AssetHintMatcher.SCORE_EXACT,
            AssetHintMatcher.score("支付宝小荷包(示例日常)", "支付宝小荷包(示例日常)"),
        )
    }

    @Test
    fun `全角半角括号与空格归一化后视为一致`() {
        assertEquals(
            "页面用半角、资产用全角也要认得出来",
            AssetHintMatcher.SCORE_EXACT,
            AssetHintMatcher.score("工商银行储蓄卡（8804）", "工商银行储蓄卡(8804)"),
        )
        assertEquals(AssetHintMatcher.SCORE_EXACT, AssetHintMatcher.score("微信零钱（用户 A）", "微信零钱(用户 A)"))
    }

    @Test
    fun `银行卡按尾号四位对齐`() {
        assertEquals(
            AssetHintMatcher.SCORE_EXACT,
            AssetHintMatcher.score("招商银行信用卡(8806)", "招商银行信用卡(8806)"),
        )
        // 名字不完全一样（多了「储蓄卡」、括号也不同），靠尾号 4 位对齐
        assertEquals(
            AssetHintMatcher.SCORE_TAIL,
            AssetHintMatcher.score("建设银行（8808）", "建设银行储蓄卡(8808)"),
        )
    }

    @Test
    fun `渠道前缀不同也能通过包含匹配`() {
        assertEquals(AssetHintMatcher.SCORE_CONTAINS, AssetHintMatcher.score("微信零钱（用户 A）", "零钱"))
        assertEquals(AssetHintMatcher.SCORE_CONTAINS, AssetHintMatcher.score("支付宝小荷包(示例日常)", "小荷包"))
    }

    @Test
    fun `主体词匹配给部分分`() {
        assertEquals(
            "带尾号但尾号对不上时，至少主体词要对上",
            AssetHintMatcher.SCORE_PARTIAL,
            AssetHintMatcher.score("招商银行信用卡(9999)", "招商银行信用卡(8806)"),
        )
    }

    @Test
    fun `对不上返回 0 让兜底规则决定`() {
        assertEquals(0, AssetHintMatcher.score("支付宝小荷包(示例日常)", "花呗"))
        assertEquals(0, AssetHintMatcher.score("余额宝", "零钱"))
        assertEquals(0, AssetHintMatcher.score("余额宝", null))
        assertEquals(0, AssetHintMatcher.score("余额宝", "  "))
    }

    @Test
    fun `页面方式一定压过兜底加分`() {
        // 兜底：家庭资产 +30 / 渠道名 +20 / 成员名 +15
        val fallbackMax = 30 + 20 + 15
        assertTrue(AssetHintMatcher.SCORE_EXACT > fallbackMax)
        assertTrue(AssetHintMatcher.SCORE_TAIL > fallbackMax)
        assertTrue(AssetHintMatcher.SCORE_CONTAINS > fallbackMax)
        assertTrue(AssetHintMatcher.SCORE_PARTIAL > fallbackMax)
    }

    @Test
    fun `用户在微信零钱上能区分到人`() {
        // 页面写「零钱」时两个「微信零钱（XX）」都会命中 CONTAINS，
        // 到底选谁由 pipeline 的「本机成员名 +15」决定 —— 这里确认两者分数相同（可被打破）
        val me = AssetHintMatcher.score("微信零钱（用户 A）", "零钱")
        val spouse = AssetHintMatcher.score("微信零钱（用户 B）", "零钱")
        assertEquals(me, spouse)
    }

    @Test
    fun `normalize 统一括号空白与大小写`() {
        assertEquals("工商银行储蓄卡(8804)", AssetHintMatcher.normalize(" 工商银行储蓄卡（8804） "))
        assertEquals("abc", AssetHintMatcher.normalize("A B C"))
    }
}
