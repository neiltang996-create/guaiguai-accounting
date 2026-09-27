package com.family.ledger.auto.rules

/**
 * 转账文案解析：从「转出说明 / 转账说明」里反推出**转出资产**。
 *
 * 真机样例（2026-09-26，支付宝余额宝转出到银行卡）：
 * ```
 * 转出说明 → 余额宝-转出到银行卡
 * 转入账户 → 中国工商银行(8804)
 * ```
 * 转入资产直接取「转入账户」那一行；转出资产没有单独一行，只能从这句说明里拆：
 * `余额宝-转出到银行卡` → 转出 = `余额宝`（后半句「转出到银行卡」只说明去向是银行卡，
 * 具体哪张卡由「转入账户」给出）。
 *
 * 纯逻辑，可单测。
 */
object TransferText {

    /** 分隔符：连字符/箭头/「转出到」这类动词。 */
    private val SPLIT = Regex("""[-−—–~～>→]|转出到|转入到|转出至|转入至|转出至|转到|转出|转入|提现到|充值到""")

    /** 只说明「去向类型」而不是账户名的词，不能当转出资产。 */
    private val GENERIC = setOf(
        "银行卡", "卡", "储蓄卡", "信用卡", "余额", "零钱", "账户", "银行", "支付宝", "微信",
        "转出", "转入", "提现", "充值", "到银行卡", "到余额宝", "到零钱",
    )

    /** 以「转入/充值/存入」开头的整句说明：后面那个账户是**目标**，不是转出账户。 */
    private val IN_VERB_PREFIX = Regex("^(转入到|转入至|转入|充值到|充值|存入到|存入|收款到)")

    /** 拆成片段：`余额宝-转出到银行卡` → `[余额宝, 银行卡]`。 */
    fun parts(note: String?): List<String> {
        val t = note?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return t.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 转出资产线索：取第一个「像账户名」的片段。
     * `余额宝-转出到银行卡` → `余额宝`；`转出到银行卡` → null（只知道去向类型）。
     */
    /**
     * 转出资产线索：取第一个「像账户名」的片段。
     * `余额宝-转出到银行卡` → `余额宝`；`转出到银行卡` → null（只知道去向类型）；
     * `转入到余额宝` → null（那是**目标**账户，不是转出账户）。
     */
    fun outAssetOf(note: String?): String? {
        val t = note?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (IN_VERB_PREFIX.containsMatchIn(t)) return null
        return parts(t).firstOrNull { isAccountLike(it) }
    }

    /**
     * 去向账户线索：只看**分隔符之后**的片段。
     * `中国工商银行(8804)-转入到余额宝` → `余额宝`；
     * `余额宝-转出到银行卡` → null（后半句只说了「银行卡」，具体哪张卡要由「转入账户」给出）。
     */
    fun inAssetOf(note: String?): String? {
        val parts = parts(note)
        if (parts.size < 2) return null
        return parts.drop(1).lastOrNull { isAccountLike(it) }
    }

    /** 是否是「像账户名」的片段：非空、不是通用去向词、不是纯数字。 */
    fun isAccountLike(part: String): Boolean {
        val p = part.trim()
        if (p.isEmpty() || p in GENERIC) return false
        if (p.all { it.isDigit() }) return false
        return true
    }
}
