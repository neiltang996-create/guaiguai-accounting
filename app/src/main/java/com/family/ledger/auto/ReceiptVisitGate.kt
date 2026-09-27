package com.family.ledger.auto

/** One presentation per page visit. Dismissing our panel does not count as leaving the receipt. */
class ReceiptVisitGate {
    private var receipt: String? = null
    @Volatile private var generation = 0L

    fun enter(key: String): Long? {
        if (receipt == key) return null
        receipt = key
        generation++
        return generation
    }

    fun leave() {
        if (receipt != null) {
            receipt = null
            generation++
        }
    }

    fun isCurrent(token: Long): Boolean = generation == token

    companion object {
        fun key(signal: PaySignal): String = listOf(signal.sourcePackage, signal.pageType,
            signal.direction.name, signal.orderId, signal.receiptTimeMillis,
            signal.amountCents, signal.merchant).joinToString("|")
    }
}

/** WebView details arrive in fragments. Require stable transaction fields before handing them off. */
class ReceiptSettler(private val settleMs: Long = 450L) {
    private var candidate: String? = null
    private var since = 0L
    fun ready(key: String?, now: Long): Boolean {
        if (candidate != key) { candidate = key; since = now; return false }
        return key != null && now - since >= settleMs
    }
}
