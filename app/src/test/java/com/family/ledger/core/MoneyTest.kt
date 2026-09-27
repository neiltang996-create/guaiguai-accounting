package com.family.ledger.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {

    @Test
    fun `formats cents with grouping and two decimals`() {
        assertEquals("¥0", Money.format(0))
        assertEquals("¥1", Money.format(100))
        assertEquals("¥1.05", Money.format(105))
        assertEquals("¥12.34", Money.format(1234))
        assertEquals("¥1,234.56", Money.format(123456))
        assertEquals("¥1,000,000", Money.format(100_000_000))
        assertEquals("-¥45", Money.format(-4500))
        assertEquals("-¥45.60", Money.format(-4560))
    }

    @Test
    fun `parses plain decimals`() {
        assertEquals(0L, Money.parseToCents("0"))
        assertEquals(100L, Money.parseToCents("1"))
        assertEquals(105L, Money.parseToCents("1.05"))
        assertEquals(1234L, Money.parseToCents("12.34"))
        assertEquals(-4500L, Money.parseToCents("-45.00"))
    }

    @Test
    fun `parses realistic qianji amount strings`() {
        // 钱迹 CSV 里的真实形态
        assertEquals(19200L, Money.parseToCents("192.0"))
        assertEquals(4500L, Money.parseToCents("45.0"))
        assertEquals(33960L, Money.parseToCents("339.6"))
        assertEquals(123456L, Money.parseToCents("1,234.56"))
        assertEquals(4500L, Money.parseToCents("¥45"))
        assertEquals(4500L, Money.parseToCents("￥45.00"))
        assertEquals(4500L, Money.parseToCents(" 45.00 "))
    }

    @Test
    fun `truncates beyond two decimals instead of rounding`() {
        assertEquals(1234L, Money.parseToCents("12.345"))
        assertEquals(1234L, Money.parseToCents("12.349"))
    }

    @Test
    fun `blank and garbage become zero`() {
        assertEquals(0L, Money.parseToCents(null))
        assertEquals(0L, Money.parseToCents(""))
        assertEquals(0L, Money.parseToCents("   "))
        assertEquals(0L, Money.parseToCents("-"))
        assertEquals(0L, Money.parseToCents("."))
        assertEquals(0L, Money.parseToCents("abc"))
    }

    @Test
    fun `plain string round trips through parse`() {
        for (cents in listOf(0L, 1L, 99L, 100L, 1234L, 100_000_000L, -4560L)) {
            val s = Money.toPlainString(cents)
            assertEquals("round trip failed for $cents ($s)", cents, Money.parseToCents(s))
        }
    }

    @Test
    fun `no floating point drift on repeated accumulation`() {
        // 0.1 元重复累加 10 次必须精确等于 1.00 元
        var total = 0L
        repeat(10) { total += Money.parseToCents("0.1") }
        assertEquals(100L, total)
    }
}
