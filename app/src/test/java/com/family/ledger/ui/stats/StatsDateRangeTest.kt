package com.family.ledger.ui.stats

import com.family.ledger.core.TimeFmt
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class StatsDateRangeTest {
    private fun midnight(date: LocalDate) = date.atStartOfDay(TimeFmt.ZONE).toInstant().toEpochMilli()

    @Test fun leapMonthIncludesFebruary29ButNotMarch1() {
        val range = StatsDateRange.month(YearMonth.of(2024, 2))
        assertEquals(midnight(LocalDate.of(2024, 2, 1)), range.from)
        assertEquals(midnight(LocalDate.of(2024, 3, 1)), range.to)
        assertEquals(LocalDate.of(2024, 2, 29), range.endInclusive)
    }

    @Test fun yearRangeIncludesDecember31WithoutNextYear() {
        val range = StatsDateRange.year(2025)
        assertEquals(midnight(LocalDate.of(2025, 1, 1)), range.from)
        assertEquals(midnight(LocalDate.of(2026, 1, 1)), range.to)
    }

    @Test fun sameDayCustomRangeIncludesLastMillisecond() {
        val day = LocalDate.of(2026, 9, 26)
        val range = StatsDateRange(day, day)
        val last = day.atTime(23, 59, 59, 999_000_000).atZone(TimeFmt.ZONE).toInstant().toEpochMilli()
        assertTrue(last in range.from until range.to)
        assertFalse(midnight(day.plusDays(1)) in range.from until range.to)
        assertEquals(midnight(day), range.from)
    }

    @Test fun customRangeCanCrossYearBoundary() {
        val range = StatsDateRange(LocalDate.of(2025, 12, 28), LocalDate.of(2026, 1, 5))
        assertEquals(midnight(LocalDate.of(2025, 12, 28)), range.from)
        assertEquals(midnight(LocalDate.of(2026, 1, 6)), range.to)
    }

    @Test(expected = IllegalArgumentException::class)
    fun reversedRangeIsRejected() {
        StatsDateRange(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 1))
    }
}
