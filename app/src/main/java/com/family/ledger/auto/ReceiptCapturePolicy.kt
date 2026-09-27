package com.family.ledger.auto

/** Unknown/native chat, authentication and password screens are not OCR fallback targets. */
object ReceiptCapturePolicy {
    fun sameReceipt(a: PaySignal, b: PaySignal): Boolean {
        if (a.sourcePackage != b.sourcePackage || a.amountCents != b.amountCents || a.direction != b.direction) return false
        if (a.suggestedAccountHint != null && b.suggestedAccountHint != null && a.suggestedAccountHint != b.suggestedAccountHint) return false
        if (a.orderId != null && b.orderId != null) return a.orderId == b.orderId
        if (a.receiptTimeMillis != null && b.receiptTimeMillis != null) return a.receiptTimeMillis == b.receiptTimeMillis &&
            (a.merchant == null || b.merchant == null || a.merchant.replace(" ", "") == b.merchant.replace(" ", ""))
        return !a.merchant.isNullOrBlank() && a.merchant.replace(" ", "") == b.merchant?.replace(" ", "")
    }

    fun mayOcr(pkg: String, activity: String?): Boolean {
        val name = activity?.lowercase() ?: return false
        if (listOf("password", "login", "auth", "keyboard").any { name.contains(it) }) return false
        return when (pkg) {
            PayPackages.WECHAT -> name.contains(".plugin.webview.ui.tools.")
            PayPackages.ALIPAY, PayPackages.ALIPAY_RC -> listOf("h5activity", "h5transactivity", "bill", "payresult").any { name.contains(it) }
            PayPackages.UNIONPAY, PayPackages.UNIONPAY_TSM, PayPackages.ICBC ->
                listOf("detail", "bill", "webview", "cordova", "webactivity").any { name.contains(it) }
            else -> false
        }
    }
}
