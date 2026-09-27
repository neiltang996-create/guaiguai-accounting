package com.family.ledger.ui.components

import com.family.ledger.core.Money
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 记账页自定义键盘的输入状态机 —— 直接决定入库金额，必须有测试兜住。
 */
class AmountInputTest {

    private fun type(vararg keys: String): String {
        var s = ""
        keys.forEach { k ->
            s = when (k) {
                "." -> AmountInput.appendDot(s)
                "<" -> AmountInput.backspace(s)
                else -> AmountInput.appendDigit(s, k)
            }
        }
        return s
    }

    @Test
    fun `整数输入不产生前导零`() {
        assertEquals("5", type("0", "5"))
        assertEquals("1234567", type("1", "2", "3", "4", "5", "6", "7"))
    }

    @Test
    fun `最多两位小数`() {
        assertEquals("12.34", type("1", "2", ".", "3", "4"))
        // 第三位小数被忽略
        assertEquals("12.34", type("1", "2", ".", "3", "4", "5"))
    }

    @Test
    fun `小数点只能有一个且自动补零`() {
        assertEquals("0.", type("."))
        assertEquals("0.5", type(".", "5"))
        assertEquals("3.", type("3", "."))
        // 第二个小数点被忽略
        assertEquals("3.5", type("3", ".", ".", "5"))
    }

    @Test
    fun `退格可以删空`() {
        assertEquals("", type("<"))
        assertEquals("1", type("1", "2", "<"))
        assertEquals("", type("1", "<", "<"))
    }

    @Test
    fun `整数位有上限`() {
        val s = type("9", "9", "9", "9", "9", "9", "9", "9", "9")
        assertEquals(7, s.length)
    }

    @Test
    fun `输入串能被 Money 正确解析为分`() {
        assertEquals(1234L, Money.parseToCents(type("1", "2", ".", "3", "4")))
        // 先按「.」再按「5」：键盘自动补零得到 "0.5"（0.5 元），即 50 分
        assertEquals("0.5", type(".", "5"))
        assertEquals(50L, Money.parseToCents(type(".", "5")))
        assertEquals(0L, Money.parseToCents(type(".")))
        assertEquals(0L, Money.parseToCents(""))
        assertEquals(100000000L, Money.parseToCents(type("1", "0", "0", "0", "0", "0", "0")))
    }

    @Test
    fun `展示与解析可逆`() {
        var s = type("8", "8", "8", ".", "0", "5")
        assertEquals("888.05", s)
        assertEquals("¥888.05", Money.format(Money.parseToCents(s)))
        s = type("1", "0", "0", "0")
        assertEquals("¥1,000", Money.format(Money.parseToCents(s)))
    }
}
