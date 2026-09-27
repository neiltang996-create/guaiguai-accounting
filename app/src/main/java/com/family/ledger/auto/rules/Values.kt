package com.family.ledger.auto.rules

import com.family.ledger.auto.Direction
import com.family.ledger.core.Money
import com.family.ledger.core.TimeFmt

/**
 * 账单页上的固定标签词。取「值」时要把它们跳过（否则会把「交易方式」当成商户名）。
 * 都是通用中文 UI 词，不是从钱迹抄的代码。
 */
object Labels {

    val UI: Set<String> = setOf(
        "交易方式", "付款方式", "支付方式", "付款账户", "支付账户", "扣款方式",
        "当前状态", "交易状态", "订单状态",
        "订单号", "交易单号", "商户单号", "交易流水号", "流水号",
        "商户名称", "商户全称", "收款方", "收款商户", "商户",
        "付款时间", "创建时间", "交易时间", "支付时间", "下单时间",
        "商品", "商品名称", "优惠", "优惠金额", "实付", "实付金额", "付款金额", "金额",
        "账单详情", "支付成功", "付款成功", "交易成功", "查看更多", "账单分类", "账单分类",
        "收款方全称", "转账说明", "转账备注", "备注",
    )

    fun isLabel(text: String): Boolean = UI.contains(text.trim())
}

/**
 * 字段值的解析工具：金额、标签剥离、订单号、时间、资金方向。
 *
 * 纯 Kotlin（复用 [Money] / [TimeFmt]），全部可单测。
 */
object Values {

    /**
     * 「整段文本就是一个金额」的判定（**锚定**）—— 必须带 `¥/￥`、或**小数**、或「元」、或负号+小数。
     *
     * 刻意不接受光秃秃的 `12`：账单页上有太多无关数字（时间、卡号尾号、订单号片段），
     * 放进来就会把「商户名 = 12」「日期 -09」这种脏数据带进账本。
     */
    private val MONEY_STRICT = Regex(
        """[¥￥]\s*-?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?\s*元?""" +   // ¥45 / ¥45.00 / ￥1,234元
            """|-?(?:\d{1,3}(?:,\d{3})+|\d+)\.\d{1,2}\s*元?""" +            // 45.00 / -0.50元 / 1,234.5
            // ★ 真机修复：原来的 `(?:\d{1,3}(?:,\d{3})+|\d+)` **只认最多 3 位**，
            //   「680元」这种 4 位数完全匹配不上 —— 正则会把「支出680元」
            //   切成 ["168","8元"] 而只认后面那段，金额被解析成 ¥8。
            //   **任何 ≥ ¥1000 的消费都会算错。** 现在两种写法都收：
            //   带逗号分组的（1,234元）或任意位纯数字的（680元）。
            """|-?(?:\d{1,3}(?:,\d{3})+|\d+)\s*元""" +              // 45元 / 680元 / 1,234元
            """|-\s*(?:\d{1,3}(?:,\d{3})+|\d+)\.\d{1,2}""",                 // -0.50（优惠政策里的负数写法，必须带小数）
    )

    /** 时间：`2026-09-25 13:03:38` / `2026-09-25 13:03` / `2026/09/25 13:03`。 */
    private val TIME = Regex("""\d{4}[-/]\d{1,2}[-/]\d{1,2}(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?""")

    private val ORDER_NO = Regex("""\d{8,}""")

    private val SEPARATORS = charArrayOf(' ', ':', '：', '\t', '·', '-', '—', '丨', '|')

    /** 在混排文本里找候选金额片段（`红包 -0.50元` → `-0.50元`）。同样修掉「最多 3 位」限制。 */
    private val MONEY_TOKEN =
        Regex("""[¥￥]?\s*-?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?\s*元?""")

    /** 整段文本**就是**一个金额（用来判断「这个节点是金额节点」）。 */
    fun looksLikeMoney(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        return t.isNotEmpty() && MONEY_STRICT.matches(t)
    }

    /**
     * 从混排文本里抽出金额片段：`红包 -0.50元` → `-0.50元`，`优惠 0.30元` → `0.30元`。
     *
     * 只认「带币种符号 / 带小数 / 带元 / 带负号」的片段，所以日期、订单号、卡号尾号都不会被误抽。
     */
    fun extractMoneyText(text: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (looksLikeMoney(t)) return t
        for (m in MONEY_TOKEN.findAll(t)) {
            val token = m.value.trim()
            if (token.isEmpty()) continue
            if (looksLikeMoney(token)) return token
        }
        return null
    }

    /** 文本里是否含金额（允许混排，如 `红包 -0.50元`）。 */
    fun containsMoney(text: String?): Boolean = extractMoneyText(text) != null

    /**
     * 解析金额（分，保留正负号）。取不到或为 0 返回 null。
     *
     * 用 [Money.parseToCents] 做真正的解析（它已经能容忍 `¥`、千分位、全角小数点、`元` 后缀）。
     */
    fun parseAmountCents(text: String?): Long? {
        val t = extractMoneyText(text) ?: return null
        val cents = Money.parseToCents(t)
        return cents.takeIf { it != 0L }
    }

    /** 优惠券抵扣额（分，**恒为正**：表示抵扣了多少）。 */
    fun parseCouponCents(text: String?): Long? = parseAmountCents(text)?.let { kotlin.math.abs(it) }

    /**
     * 从「标签 + 值」的整行文本里剥出值：
     * `商户名称：美团` → `美团`；`付款方式 余额宝` → `余额宝`；纯值原样返回 null（交给别的分支）。
     */
    fun stripLabel(text: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // 已知标签前缀：只认**最长**的那个，否则「商户名称」会被短标签「商户」截成「名称」
        val longest = Labels.UI.filter { t.startsWith(it) }.maxByOrNull { it.length }
        if (longest != null) {
            val rest = t.removePrefix(longest).trimStart(*SEPARATORS).trim()
            if (rest.isNotEmpty() && !Labels.isLabel(rest)) return rest
        }
        // 冒号分隔：`商户名称：美团` / `收款方: 美团`；排除时间（`13:03:38` 的冒号前是数字）
        val idx = t.indexOfFirst { it == '：' || it == ':' }
        if (idx > 0) {
            val head = t.substring(0, idx)
            val tail = t.substring(idx + 1).trimStart(*SEPARATORS).trim()
            if (tail.isNotEmpty() && head.any { it.isLetter() }) return tail
        }
        // 空格分隔且首词是已知标签：`订单号 2026…`
        val firstToken = t.substringBefore(' ')
        if (firstToken != t && Labels.isLabel(firstToken)) {
            val rest = t.substringAfter(' ').trimStart(*SEPARATORS).trim()
            if (rest.isNotEmpty()) return rest
        }
        return null
    }

    /** 订单号 / 交易单号（一串 ≥8 位数字）。 */
    fun digitsOf(text: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ORDER_NO.find(t)?.value
    }

    /** 页面上的时间字符串（`2026-09-25 13:03:38`）。 */
    fun timeTextOf(text: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = t.replace('年', '-').replace('月', '-').replace("日", " ")
            .replace('：', ':').replace(Regex("\\s+"), " ")
        return TIME.find(normalized)?.value?.replace(Regex("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})")) {
            "${it.groupValues[1]}-${it.groupValues[2].padStart(2, '0')}-${it.groupValues[3].padStart(2, '0')}"
        }?.replace(Regex(" (\\d):")) { " 0${it.groupValues[1]}:" }
    }

    /** 时间字符串 → epoch millis（解析不了返回 null，调用方用「现在」兜底）。 */
    fun parseTimeMs(text: String?): Long? = TimeFmt.parseCsv(timeTextOf(text))

    /**
     * 资金方向：先看退款，再看收入，最后才是支付。
     * 顺序很重要 —— 「退款成功」里也有「成功」二字。
     */
    fun directionOf(text: String?): Direction? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            t.contains("退款") || t.contains("退回") || t.contains("已退") -> Direction.REFUND
            t.contains("收入") || t.contains("到账") || t.contains("收款") || t.contains("进账") ->
                Direction.INCOME
            t.contains("支付成功") || t.contains("付款成功") || t.contains("交易成功") ||
                t.contains("已支付") || t.contains("支出") || t.contains("付款") || t.contains("支付") ->
                Direction.PAYMENT
            else -> null
        }
    }

    /** 金额前的正负号（账单详情页常用 `-45.00` / `+45.00`）。 */
    fun signOf(text: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            t.startsWith("-") || t.startsWith("−") -> "-"
            t.startsWith("+") -> "+"
            else -> null
        }
    }

    /**
     * 「交易关闭 / 支付失败 / 已取消 / 待付款」这类状态不是一笔已经发生的账，
     * 识别到了也不能记 —— 否则用户账本里会多出一堆没付成功的单子。
     */
    fun isClosedOrFailed(statusText: String?): Boolean {
        val t = statusText?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return CLOSED_OR_FAILED.any { t.contains(it) }
    }

    /**
     * 是否是**状态值**（`交易成功` / `付款成功` / `银行处理中`…）。
     *
     * 账单页上到处是「标签 → 状态」行，取商户名时很容易把状态值当成商户
     * （真机转账页实测：金额 `1.00` 的下一个文本就是 `交易成功`）。这里统一挡掉。
     */
    fun isStatusLike(text: String?): Boolean {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return STATUS_WORDS.any { t.contains(it) }
    }

    private val CLOSED_OR_FAILED = listOf(
        "交易关闭", "已关闭", "订单关闭", "支付失败", "付款失败", "交易失败",
        "已取消", "交易取消", "未支付", "待付款", "待支付", "已撤销", "支付超时",
    )

    /** 各渠道的状态文案（含转账进度里的「银行处理中」）。 */
    private val STATUS_WORDS = listOf(
        "交易成功", "支付成功", "付款成功", "转账成功", "收款成功", "还款成功",
        "退款成功", "已退款", "部分退款", "交易关闭", "已关闭", "交易失败",
        "等待付款", "等待确认", "银行处理中", "处理中", "已到账", "到账成功", "已存入零钱",
    )
}
