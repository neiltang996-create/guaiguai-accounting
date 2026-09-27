package com.family.ledger.auto.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TransferText` 单测：从「转出说明」里反推转出/转入资产。
 *
 * 用通用标签与虚构账户构造转账样例：
 * `转出说明 → 余额宝-转出到银行卡`、`转入账户 → 中国工商银行(8804)`。
 */
class TransferTextTest {

    @Test
    fun `合成转出说明拆出转出资产`() {
        assertEquals(listOf("余额宝", "银行卡"), TransferText.parts("余额宝-转出到银行卡"))
        assertEquals("余额宝", TransferText.outAssetOf("余额宝-转出到银行卡"))
    }

    @Test
    fun `只写去向类型时拿不到转出资产`() {
        // 「转出到银行卡」只知道去向是银行卡，具体账户得看「转入账户」那一行
        assertNull(TransferText.outAssetOf("转出到银行卡"))
        assertNull(TransferText.outAssetOf("转入到余额宝"))
        assertNull(TransferText.outAssetOf(""))
        assertNull(TransferText.outAssetOf(null))
    }

    @Test
    fun `转入说明里能拆出转出账户`() {
        assertEquals("中国工商银行(8804)", TransferText.outAssetOf("中国工商银行(8804)-转入到余额宝"))
        assertEquals("余额宝", TransferText.inAssetOf("中国工商银行(8804)-转入到余额宝"))
    }

    @Test
    fun `转出到银行卡时不把银行卡当成转入账户`() {
        // 后半段是通用词（银行卡）→ 不能当目标账户，否则会挑错资产
        assertNull(TransferText.inAssetOf("余额宝-转出到银行卡"))
    }

    @Test
    fun `兼容钱迹导出里的备注写法`() {
        // 手工合成 CSV 覆盖这种常见备注格式：「支付宝小荷包-转入」
        assertEquals("支付宝小荷包", TransferText.outAssetOf("支付宝小荷包-转入"))
        assertEquals("阿里巴巴", TransferText.outAssetOf("阿里巴巴-转入到余额宝"))
        assertEquals("支付宝小荷包(示例储蓄)", TransferText.outAssetOf("支付宝小荷包(示例储蓄)-转入到支付宝小荷包(示例日常)"))
    }

    @Test
    fun `箭头与波浪号都能当分隔符`() {
        assertEquals("余额宝", TransferText.outAssetOf("余额宝→银行卡"))
        assertEquals("余额宝", TransferText.outAssetOf("余额宝 -> 中国工商银行(8804)"))
        assertEquals("余额宝", TransferText.outAssetOf("余额宝~转出到零钱"))
    }

    @Test
    fun `纯数字与通用词不算账户`() {
        assertFalse(TransferText.isAccountLike("1234"))
        assertFalse(TransferText.isAccountLike("银行卡"))
        assertFalse(TransferText.isAccountLike("  "))
        assertTrue(TransferText.isAccountLike("余额宝"))
        assertTrue(TransferText.isAccountLike("中国工商银行(8804)"))
    }
}
