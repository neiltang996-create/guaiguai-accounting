package com.family.ledger.ui.stats

import com.family.ledger.data.db.entity.*
import org.junit.Assert.*
import org.junit.Test

class StatsFlowTest {
    private val txn = TxnEntity(id="one", bookId="family", type=TxnType.INCOME, amount=10000,
        fee=200, occurredAt=1, createdByDeviceId="test", createdAt=1, updatedAt=1)
    @Test fun incomeAndExpenseUseNetAmountsAndExcludeNonFinancialAdjustments() {
        val income = txn.copy(type=TxnType.INCOME)
        val expense = txn.copy(id="two", type=TxnType.EXPENSE, coupon=300)
        val rows = listOf(income, expense, txn.copy(deleted=true), txn.copy(excludeFromStats=true),
            txn.copy(type=TxnType.REFUND), txn.copy(type=TxnType.TRANSFER), txn.copy(type=TxnType.BALANCE_ADJUST))
        assertEquals(listOf(income), StatsFlow.INCOME.entries(rows))
        assertEquals(9800L, StatsFlow.INCOME.amount(income))
        assertEquals(listOf(expense, txn.copy(type=TxnType.REFUND)), StatsFlow.EXPENSE.entries(rows))
        assertEquals(-10000L, StatsFlow.EXPENSE.amount(txn.copy(type=TxnType.REFUND)))
        assertEquals(9900L, StatsFlow.EXPENSE.amount(expense))
    }
    @Test fun chartDoesNotLoseTheTailOfMoreThanEightCategories() {
        val items = (1..20).map { "category$it" to it.toLong() }
        val slices = StatsFlow.chartSlices(items)
        assertEquals(8, slices.size)
        assertEquals(items.sumOf { it.second }, slices.sumOf { it.second })
        assertEquals(items.take(8), StatsFlow.chartSlices(items.take(8)))
    }
}
