package com.family.ledger.data.repo

import com.family.ledger.data.db.entity.*
import com.family.ledger.data.sync.EntityCodec
import com.family.ledger.ui.stats.StatsFlow
import org.junit.Assert.*
import org.junit.Test

class ExpenseRecoveriesTest {
    private val origin = TxnEntity(id="purchase", bookId="book", type=TxnType.EXPENSE, amount=10000,
        fee=200, coupon=1200, occurredAt=100, assetId="card", categoryId="travel", consumerMemberId="person",
        createdByDeviceId="test", createdAt=1, updatedAt=1)
    private fun recovery(id: String, amount: Long, kind: RecoveryKind = RecoveryKind.REFUND) = origin.copy(
        id=id, type=TxnType.REFUND, amount=amount, fee=0, coupon=0, occurredAt=300, assetId="wallet",
        categoryId="wrong", consumerMemberId="wrong", relatedTxnId=origin.id, recoveryKind=kind.name)
    @Test fun partialRefundAndReimbursementReduceOriginalPeriodWithoutIncomeOrMovingCash() {
        val r = recovery("refund", 2000)
        val b = recovery("reimburse", 3000, RecoveryKind.REIMBURSEMENT)
        val rows = listOf(origin, r, b)
        val projected = ExpenseRecoveries.forStatistics(rows)
        val earlier = LedgerRepository.MonthTotals.from(projected.filter { it.occurredAt < 200 })
        val later = LedgerRepository.MonthTotals.from(projected.filter { it.occurredAt >= 200 })
        assertEquals(4000L, earlier.expense); assertEquals(0L, earlier.income)
        assertEquals(-4000L, earlier.net); assertEquals(5000L, earlier.refund)
        assertEquals(0L, later.expense); assertEquals(0L, later.income)
        assertEquals(9000L, ExpenseRecoveries.paid(origin)); assertEquals(4000L, ExpenseRecoveries.remaining(origin, rows))
        assertEquals(4000L, StatsFlow.EXPENSE.entries(projected).sumOf { StatsFlow.EXPENSE.amount(it) })
        assertTrue(StatsFlow.INCOME.entries(projected).isEmpty())
        assertTrue(projected.all { it.assetId == "card" && it.categoryId == "travel" && it.consumerMemberId == "person" })
        assertEquals(300L, r.occurredAt); assertEquals("wallet", r.assetId)
        assertEquals(listOf(origin), ExpenseRecoveries.bills(rows))
    }
    @Test fun deletedRecoveryRestoresExpenseAndExcludedOriginalNeverAffectsStats() {
        val r = recovery("refund", 2000)
        assertEquals(9000L, LedgerRepository.MonthTotals.from(ExpenseRecoveries.forStatistics(listOf(origin, r.copy(deleted=true)))).expense)
        assertEquals(0L, LedgerRepository.MonthTotals.from(ExpenseRecoveries.forStatistics(listOf(origin.copy(excludeFromStats=true), r))).expense)
        // 旧 createRefundFor 的 excludeFromStats=true 不应吞掉关联退款。
        assertEquals(7000L, LedgerRepository.MonthTotals.from(ExpenseRecoveries.forStatistics(listOf(origin, r.copy(excludeFromStats=true)))).expense)
    }
    @Test fun validationUsesCombinedPaidLimitAndRejectsInvalidOriginalAndDates() {
        val rows = listOf(origin, recovery("refund", 2000), recovery("reimburse",3000,RecoveryKind.REIMBURSEMENT))
        ExpenseRecoveries.validate(origin, rows, 4000, 300)
        assertTrue(runCatching { ExpenseRecoveries.validate(origin, rows, 4001, 300) }.isFailure)
        assertTrue(runCatching { ExpenseRecoveries.validate(origin, rows, 0, 300) }.isFailure)
        assertTrue(runCatching { ExpenseRecoveries.validate(origin, rows, 100, 99) }.isFailure)
        assertTrue(runCatching { ExpenseRecoveries.validate(origin.copy(type=TxnType.INCOME), rows, 100, 300) }.isFailure)
        assertTrue(runCatching { ExpenseRecoveries.validate(origin.copy(deleted=true), rows, 100, 300) }.isFailure)
    }
    @Test fun historicalUnlinkedRefundRemainsVisibleAndNeverCountsAsIncome() {
        val orphan = recovery("orphan", 2000).copy(relatedTxnId=null)
        assertEquals(listOf(orphan), ExpenseRecoveries.bills(listOf(orphan)))
        val totals = LedgerRepository.MonthTotals.from(ExpenseRecoveries.forStatistics(listOf(orphan)))
        assertEquals(-2000L, totals.expense); assertEquals(0L, totals.income)
        assertEquals(2000L, totals.net)
    }
    @Test fun historicalReimbursementSummaryIsNotCountedTwiceWithItemizedReturns() {
        val original = origin.copy(reimbursedAmount=3000)
        val r = recovery("reimburse", 2000, RecoveryKind.REIMBURSEMENT)
        val rows = listOf(original,r)
        assertEquals(3000L, ExpenseRecoveries.recovered(original, rows))
        assertEquals(6000L, LedgerRepository.MonthTotals.from(ExpenseRecoveries.forStatistics(rows)).expense)
        assertEquals(1000L, ExpenseRecoveries.historicalReimbursement(original,rows))
        assertEquals(2, rows.size) // 汇总仅参与统计，不生成现金到账流水。
    }
    @Test fun serializationRetainsLinkKindAndAllowsLegacyMissingField() {
        val r = recovery("reimburse", 2000, RecoveryKind.REIMBURSEMENT)
        assertEquals(r, EntityCodec.decode(EntityCodec.T_TXN, EntityCodec.encode(r)))
        val old = EntityCodec.encode(r).replace("\"recoveryKind\":\"REIMBURSEMENT\",", "")
        assertEquals(RecoveryKind.REFUND, ExpenseRecoveries.kind(EntityCodec.decode(EntityCodec.T_TXN, old) as TxnEntity))
    }
    @Test fun fullRecoveryCanReachZeroAndCategoriesFollowEditedOrigin() {
        val r = recovery("refund",9000)
        val edited = origin.copy(categoryId="new-category", assetId="new-card")
        val rows = ExpenseRecoveries.forStatistics(listOf(edited,r))
        assertEquals(0L, LedgerRepository.MonthTotals.from(rows).expense)
        assertTrue(rows.all { it.categoryId == edited.categoryId && it.assetId == edited.assetId })
    }
}
