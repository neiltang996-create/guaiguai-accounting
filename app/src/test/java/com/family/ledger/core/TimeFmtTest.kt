package com.family.ledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeFmtTest {

    @Test
    fun `parses qianji csv timestamp`() {
        val ms = TimeFmt.parseCsv("2026-09-24 23:21:28")
        assertEquals("2026-09-24 23:21:28", TimeFmt.toCsv(ms!!))
    }

    @Test
    fun `parses shorter variants`() {
        assertEquals("2026-09-24 23:21", TimeFmt.toCsv(TimeFmt.parseCsv("2026-09-24 23:21")!!).substring(0, 16))
        assertEquals("2026-09-24", TimeFmt.toDay(TimeFmt.parseCsv("2026-09-24")!!))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(TimeFmt.parseCsv(null))
        assertNull(TimeFmt.parseCsv(""))
        assertNull(TimeFmt.parseCsv("not a date"))
    }

    @Test
    fun `day and month boundaries are local midnight`() {
        val ms = TimeFmt.parseCsv("2026-09-24 23:21:28")!!
        val day = TimeFmt.startOfDay(ms)
        assertEquals("2026-09-24 00:00:00", TimeFmt.toCsv(day))
        val month = TimeFmt.startOfMonth(ms)
        assertEquals("2026-09-01 00:00:00", TimeFmt.toCsv(month))
    }
}
