package com.family.ledger.auto

import com.family.ledger.core.Money

/**
 * 付款文本 → [PaySignal]。
 *
 * **纯 Kotlin、零 Android 依赖**：无障碍页面文本、通知标题/正文、以后可能的分享文本/OCR 文本
 * 都走同一个解析器，保证入口一致、单测可覆盖。
 *
 * 设计原则：**宁可漏报，不可误报**。凡是「未支付 / 支付失败 / 验证码 / 广告」一律拦掉，
 * 金额必须带 ¥ / ￥ / 元 币种标记（或文本里有明确的成功标记时才接受裸金额）。
 *
 * 门禁先于解析的思路参考了 MIT 许可的开源项目 `FuShengDuoBuYu/AutoBookKeepingBeta`
 * （`NotificationBillParser` 的 IGNORE_PATTERN）。本文件为独立实现，未复制其代码，
 * 规则全部针对本项目的支付宝/微信/云闪付通知样本自行编写与维护。
 */
object PaymentTextParser {

    /** 解析文本长度上限：无障碍 dump 可能很长，截断后仍可幂等重解析。 */
    const val MAX_TEXT = 2000

    // ---------- 金额 ----------

    /** ¥12.34 / ￥1,234.00 / 12.34元；group1=¥形式，group2=元形式。 */
    private val AMOUNT_RX = Regex("""[¥￥]\s*(\d[\d,]*(?:\.\d{1,2})?)|(\d[\d,]*(?:\.\d{1,2})?)\s*元""")

    /** 无币种标记的兜底金额（只在有「成功」类强标记时启用，降低误报）。 */
    private val BARE_AMOUNT_RX = Regex("""(?<![\d.])(\d{1,7}\.\d{2})(?![\d])""")

    // ---------- 门禁 ----------

    /** 硬拦截：出现即拒绝，与是否含「成功」无关。 */
    private val HARD_IGNORE_RX = Regex(
        "验证码|校验码|动态密码|短信验证|安全码|" +
            "点击下载|立即下载|点击链接|立即领取|点击领取|恭喜|领取红包|邀请好友|注册送|抽奖|广告|开通会员|" +
            "退款申请已受理|申请退款"
    )

    /** 软拦截：未完成的交易状态；除非文本里同时出现成功标记。 */
    private val SOFT_IGNORE_RX = Regex(
        "待支付|待付款|等待付款|未支付|尚未支付|支付失败|付款失败|交易失败|支付未完成|" +
            "已取消|取消支付|支付超时|超时未支付|已关闭|支付中|处理中"
    )

    /** 交易成功类标记。 */
    private val SUCCESS_RX = Regex("成功|已付款|已支付|已完成|到账|已退回|已汇入")

    // ---------- 语义 ----------

    private val PAY_VERB_RX = Regex("支付|付款|已付|扣款|消费|转账|交易|支出|花呗|余额宝")
    private val REFUND_RX = Regex("退款|退回|返还|退单|已退")

    /**
     * 交易动词门禁。
     *
     * **注意不能直接匹配「支付」**：「支付宝」这个 App 名本身就含「支付」，
     * 会让「限时活动 全场¥9.9」这类广告混进来。
     * 这里用负向前瞻把「支付宝」里的那一次出现排除，其余「支付」仍算交易动词。
     */
    private val TX_VERB_RX = Regex("付款|支付(?!宝)|消费|扣款|支出|买单|转出|提现|代扣|转账|收款|缴费|充值")

    /** 强进账标记：出现即认为是收入。 */
    private val INCOME_STRONG_RX = Regex("收款|到账|收入|转入|汇入")

    /** 弱进账标记：「已入账」也可能是支出记账完成，需要看有没有支出动词。 */
    private val INCOME_WEAK_RX = Regex("入账")

    /** 支出动词：用于「已入账」这类歧义文本的仲裁。 */
    private val EXPENSE_RX = Regex("消费|付款|扣款|支出|买单|转出|提现|代扣|支付成功|付款成功|已付款|已支付")

    /**
     * 微信的严格门禁：微信通知里混着大量聊天消息，必须有确凿标记才认。
     *
     * 最后一条针对「标题就是『微信支付』，正文只有金额」的付款通知：
     * 拼接时标题在最前，所以用 `^微信支付…金额` 限定；聊天消息开头是联系人昵称，不会命中。
     */
    private val WECHAT_STRONG_RX = Regex(
        "微信支付[^。；;]{0,16}(?:付款|支付|收款|转账|到账|支出|消费|扣款)" +
            "|支付成功|付款成功|已付款|已支付|成功付款|成功支付|交易成功" +
            "|收款到账|收款成功|到账通知|退款成功|已退回" +
            "|向[^，,。；;\\s]{1,20}(?:付款|支付|转账)" +
            "|^微信支付[^。；;]{0,16}(?:¥[\\d,]|[\\d,]+(?:\\.\\d{1,2})?\\s*元)"
    )

    // ---------- 商户 ----------

    private val MERCHANT_RULES = listOf(
        Regex("""你向\s*([^，,。；;、\s]{1,24}?)\s*(?:付款|支付|转账|发起)"""),
        Regex("""向\s*([^，,。；;、\s]{1,24}?)\s*(?:付款|支付|转账)"""),
        Regex("""(?:付款给|支付给|转账给|付给)\s*([^，,。；;、\s]{1,24})"""),
        Regex("""(?:在|于)\s*([^，,。；;、\s]{1,24}?)\s*(?:消费|付款|支付)"""),
        Regex("""([^，,。；;、\s：:¥￥]{2,24}?)\s*[-—－]\s*(?:付款成功|支付成功|交易成功|已付款|已支付)"""),
        Regex("""(?:商户|商家|收款方|收款人|付款方|店铺)[：:]\s*([^，,。；;、\s]{1,24})"""),
        Regex("""([^，,。；;、\s：:¥￥]{2,24}?)\s*(?:收款到账|收款成功|已收款)"""),
    )

    private val MERCHANT_NOISE_RX = Regex(
        "[¥￥]|元|尾号|末四位|成功|失败|已付|支付|付款|收款|转账|退款|退回|" +
            "银行卡|信用卡|储蓄卡|借记卡|余额|零钱|花呗|订单|验证码|通知|账单"
    )

    private val MERCHANT_BLACKLIST = setOf(
        "微信支付", "微信", "支付宝", "云闪付", "银联", "花呗", "余额宝", "零钱", "零钱通",
        "银行卡", "信用卡", "储蓄卡", "余额", "成功", "支付", "付款", "收款", "转账",
        "商家", "商户", "对方", "好友", "该笔", "本次", "订单", "账单", "通知", "人民币", "退款",
        "你", "我", "的", "已", "笔", "消费", "交易",
    )

    // ---------- 账户线索 ----------

    private val CARD_TAIL_RX = Regex("""(?:尾号|末四位|后四位)\s*(\d{4})""")
    private val CARD_WORD_RX = Regex("信用卡|储蓄卡|借记卡|银行卡")

    /** 账户线索优先级：越具体的越靠前。 */
    private val HINT_ORDER = listOf(
        "花呗", "余额宝", "零钱通", "零钱", "云闪付", "信用卡", "储蓄卡", "借记卡", "银行卡", "余额",
    )

    // ---------- 订单号 ----------

    private val ORDER_ID_RX = Regex(
        """(?:交易订单号|商家订单号|商户单号|交易单号|订单编号|订单号|交易号|流水号)[：:\s]*([A-Za-z0-9][A-Za-z0-9\-]{5,39})"""
    )

    /**
     * 解析一条付款文本。
     *
     * @param text 通知标题/正文拼接，或无障碍读到的页面文本。
     * @param pkg 来源包名，用于渠道判定与微信聊天消息过滤。
     * @param now 事件时间。
     * @param origin 信号来自哪条通道（无障碍 / 通知监听），供去重区分「双通道上报」与「两笔真实消费」。
     * @return 识别成功返回 [PaySignal]，任何不确定的情况返回 null。
     */
    fun parse(text: String, pkg: String, now: Long, origin: Origin = Origin.UNKNOWN): PaySignal? {
        val raw = text.replace('\u00A0', ' ').replace('\u3000', ' ').trim()
        if (raw.length < 4) return null
        val t = if (raw.length > MAX_TEXT) raw.substring(0, MAX_TEXT) else raw

        // 1) 门禁：失败态 / 验证码 / 广告
        if (HARD_IGNORE_RX.containsMatchIn(t)) return null
        if (SOFT_IGNORE_RX.containsMatchIn(t) && !SUCCESS_RX.containsMatchIn(t)) return null

        // 2) 渠道与语义
        val channel = Channel.of(pkg)
        val wechatStrong = channel == Channel.WECHAT && WECHAT_STRONG_RX.containsMatchIn(t)
        if (channel == Channel.WECHAT && !wechatStrong) return null

        val hasTxVerb = TX_VERB_RX.containsMatchIn(t)
        val hasRefund = REFUND_RX.containsMatchIn(t)
        val hasIncomeStrong = INCOME_STRONG_RX.containsMatchIn(t)
        val hasIncomeWeak = INCOME_WEAK_RX.containsMatchIn(t)
        if (!hasTxVerb && !hasRefund && !hasIncomeStrong && !hasIncomeWeak &&
            !SUCCESS_RX.containsMatchIn(t) && !wechatStrong
        ) {
            return null
        }

        // 3) 金额（必须 > 0）
        val cents = pickAmount(t, allowBare = SUCCESS_RX.containsMatchIn(t)) ?: return null
        if (cents <= 0L) return null

        // 4) 方向：退款 > 强进账 > 弱进账（无支出动词时）> 支出
        val direction = when {
            hasRefund -> Direction.REFUND
            hasIncomeStrong -> Direction.INCOME
            hasIncomeWeak && !EXPENSE_RX.containsMatchIn(t) -> Direction.INCOME
            else -> Direction.PAYMENT
        }

        val hint = extractAccountHint(t)
        return PaySignal(
            sourcePackage = pkg,
            amountCents = cents,
            merchant = extractMerchant(t),
            rawText = t,
            occurredAt = now,
            channel = channel,
            direction = direction,
            suggestedAccountHint = hint,
            channelLabel = buildChannelLabel(hint, channel),
            orderId = extractOrderId(t),
            origin = origin,
        )
    }

    // ---------- 内部实现 ----------

    /**
     * 挑金额：多个候选时优先「带币种符号」且「紧邻支付动词」的那个。
     * 例：「余额¥1,234.00，本次支付¥45.00」→ 45.00。
     */
    private fun pickAmount(text: String, allowBare: Boolean): Long? {
        var bestScore = Int.MIN_VALUE
        var bestStart = Int.MAX_VALUE
        var bestCents = 0L
        for (m in AMOUNT_RX.findAll(text)) {
            val currencyForm = m.groups[1]?.value
            val digits = currencyForm ?: m.groups[2]?.value ?: continue
            val cents = Money.parseToCents(digits)
            if (cents <= 0L) continue
            var score = if (currencyForm != null) 3 else 2
            val before = text.substring(maxOf(0, m.range.first - 12), m.range.first)
            val after = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 5))
            if (PAY_VERB_RX.containsMatchIn(before)) score += 2
            if (PAY_VERB_RX.containsMatchIn(after)) score += 1
            if (score > bestScore || (score == bestScore && m.range.first < bestStart)) {
                bestScore = score
                bestStart = m.range.first
                bestCents = cents
            }
        }
        if (bestCents > 0L) return bestCents
        if (!allowBare) return null
        val bare = BARE_AMOUNT_RX.find(text) ?: return null
        return Money.parseToCents(bare.groupValues[1]).takeIf { it > 0L }
    }

    /** 商户抽取：按「你向X付款」「X-付款成功」「商户：X」等真实通知格式依次尝试。 */
    private fun extractMerchant(text: String): String? {
        for (rule in MERCHANT_RULES) {
            val m = rule.find(text) ?: continue
            cleanMerchant(m.groupValues[1])?.let { return it }
        }
        return null
    }

    private fun cleanMerchant(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        s = s.trim(
            ' ', '，', ',', '。', '；', ';', '：', ':', '-', '—', '－', '·', '.', '~',
            '(', ')', '（', '）', '"', '\'', '【', '】', '[', ']',
        )
        s = s.removePrefix("你").removePrefix("我").removePrefix("的").trim()
        if (s.isEmpty() || s.length > 24) return null
        if (s in MERCHANT_BLACKLIST) return null
        if (MERCHANT_NOISE_RX.containsMatchIn(s)) return null
        // 纯符号不算商户（「7-11」这种含数字的店名要保留）
        if (s.none { it.isLetter() || it.isDigit() || it.code > 0x2E80 }) return null
        return s
    }

    /** 账户线索：「零钱」「余额宝」「花呗」「银行卡(0208)」。 */
    fun extractAccountHint(text: String): String? {
        val tail = CARD_TAIL_RX.find(text)
        if (tail != null) {
            val digits = tail.groupValues[1]
            val card = CARD_WORD_RX.find(text)?.value ?: "银行卡"
            return "$card($digits)"
        }
        return HINT_ORDER.firstOrNull { text.contains(it) }
    }

    /**
     * 付款渠道细节（lead 要求的 channel 字符串）：带上渠道前缀便于区分同一账户名的不同入口。
     *
     * 公开给页面规则引擎复用（`rules/BillPageParser` 用它把页面上取到的付款方式转成 channelLabel），
     * 保证「文本兜底」与「规则引擎」两条路径产出的 channelLabel 完全一致。
     */
    fun buildChannelLabel(hint: String?, channel: Channel): String? {
        if (hint.isNullOrBlank()) return null
        return when {
            channel == Channel.WECHAT && (hint == "零钱" || hint == "零钱通") -> "微信$hint"
            channel == Channel.ALIPAY && (hint == "花呗" || hint == "余额宝" || hint == "余额") -> "支付宝·$hint"
            else -> hint
        }
    }

    /** 交易订单号 / 商家订单号：能拿到就用它做去重指纹。 */
    fun extractOrderId(text: String): String? {
        val v = ORDER_ID_RX.find(text)?.groupValues?.get(1)?.trim()
        return v?.takeIf { it.length >= 6 }
    }
}
