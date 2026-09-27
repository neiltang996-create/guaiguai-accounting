package com.family.ledger.ui.components

/** 表单不能把空白、错误字符或溢出金额静默变成零。零和负数在余额校准中有效。 */
object FormAmount {
    fun parse(raw: String): Long? {
        val text = raw.trim()
        if (!Regex("^[+-]?(?:[0-9]+(?:\\.[0-9]{0,2})?|\\.[0-9]{1,2})$").matches(text)) return null
        return runCatching { text.toBigDecimal().movePointRight(2).longValueExact() }.getOrNull()
    }
}
