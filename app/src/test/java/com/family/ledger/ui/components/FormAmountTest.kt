package com.family.ledger.ui.components
import org.junit.Assert.*
import org.junit.Test
class FormAmountTest {
    @Test fun calibrationAcceptsZeroNegativeAndDecimalButNotInvalidOrOverflowingInput() {
        assertEquals(0L, FormAmount.parse("0"))
        assertEquals(-1625L, FormAmount.parse(" -16.25 "))
        assertEquals(50L, FormAmount.parse(".5"))
        for (value in listOf("", "-", "abc", "1.234", "1,000", "1e4", "90000000000000000000002")) assertNull(value, FormAmount.parse(value))
    }
}
