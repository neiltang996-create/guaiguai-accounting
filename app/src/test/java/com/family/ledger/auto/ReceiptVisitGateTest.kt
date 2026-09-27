package com.family.ledger.auto

import org.junit.Assert.*
import org.junit.Test

class ReceiptVisitGateTest {
    @Test fun partiallyLoadedPageMustSettleBeforeItCanCreatePending() {
        val gate = ReceiptSettler()
        assertFalse(gate.ready("success-without-time", 0))
        assertFalse(gate.ready("detail-without-time", 150))
        assertFalse(gate.ready("complete-receipt", 300))
        assertFalse(gate.ready("complete-receipt", 500))
        assertTrue(gate.ready("complete-receipt", 800))
        assertFalse(gate.ready(null, 900))
        assertFalse(gate.ready("complete-receipt", 1000))
    }
    @Test fun dismissAndRepeatedContentEventsDoNotReopenCurrentReceipt() {
        val gate = ReceiptVisitGate()
        val token = gate.enter("receipt-A")!!
        repeat(100) { assertNull(gate.enter("receipt-A")) }
        assertTrue(gate.isCurrent(token))
    }
    @Test fun returningFromListImmediatelyAllowsSameReceiptWithoutCooldown() {
        val gate = ReceiptVisitGate()
        repeat(10) {
            val token = gate.enter("receipt-A")!!
            gate.leave()
            assertFalse(gate.isCurrent(token))
        }
    }
    @Test fun navigatingAwayInvalidatesDelayedPresentation() {
        val gate = ReceiptVisitGate()
        val first = gate.enter("receipt-A")!!
        val second = gate.enter("receipt-B")!!
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
        gate.leave()
        assertFalse(gate.isCurrent(second))
    }
}
