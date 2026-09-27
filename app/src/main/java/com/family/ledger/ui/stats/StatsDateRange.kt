package com.family.ledger.ui.stats

import com.family.ledger.core.TimeFmt
import java.time.LocalDate
import java.time.YearMonth

/** 用户选择的结束日期包含整天；数据库仍使用 [from, to) 查询。 */
internal data class StatsDateRange(val start: LocalDate, val endInclusive: LocalDate) {
    init { require(!endInclusive.isBefore(start)) }
    val from: Long get() = start.atStartOfDay(TimeFmt.ZONE).toInstant().toEpochMilli()
    val to: Long get() = endInclusive.plusDays(1).atStartOfDay(TimeFmt.ZONE).toInstant().toEpochMilli()

    companion object {
        fun month(value: YearMonth) = StatsDateRange(value.atDay(1), value.atEndOfMonth())
        fun year(value: Int) = StatsDateRange(LocalDate.of(value, 1, 1), LocalDate.of(value, 12, 31))
    }
}
