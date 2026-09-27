package com.family.ledger.auto.rules

import com.family.ledger.auto.Channel
import com.family.ledger.auto.Direction
import com.family.ledger.auto.Origin
import com.family.ledger.auto.PaySignal
import com.family.ledger.auto.PaymentTextParser

/**
 * **页面规则 → 结构化账单**：先识别 pageType，再跑该页面的规则拿字段字典，
 * 最后映射成我们自己的 [PaySignal]（钱迹在这一步产出 `BillInfo`）。
 *
 * 纯 Kotlin（只用 [NodeSnapshot] 与纯模型），所以整条链路都能用 JSON fixture 单测：
 * 不需要真机、不需要 adb，是这套重构最大的收益。
 *
 * 识别不出来时返回 null；无障碍整页文本不套用通知解析，避免把列表汇总误当成单笔付款。
 */
object BillPageParser {

    /** 无障碍入口只接受明确的单笔页面规则；整页文本可能是总览金额，不走通知解析兜底。 */
    fun parseSignal(packageName: String, root: NodeSnapshot?, now: Long = System.currentTimeMillis()): PaySignal? =
        parse(packageName, root, now)?.let { toSignal(it, packageName, Origin.ACCESSIBILITY) }

    /** 页面解析结果。 */
    data class Parsed(
        val pageType: String,
        val amountCents: Long,
        val direction: Direction,
        val merchant: String?,
        /** 页面上直接给出的付款方式（钱迹的 `firstAsset`）。有它就不用猜资产。 */
        val assetHint: String?,
        /** 转账的**转入资产**线索（「转入账户」那一行）；非转账为 null。 */
        val toAssetHint: String? = null,
        val channelLabel: String?,
        /** 优惠券抵扣合计（分，正数）。 */
        val couponCents: Long,
        val orderId: String?,
        val timeText: String?,
        val occurredAt: Long,
        val rawText: String,
        val fields: Map<String, Any?>,
    ) {
        /** 是否是自己账户之间的转账。 */
        val isTransfer: Boolean get() = direction == Direction.TRANSFER

        /** 排障用：`amount=4500 merchant=美团 asset=余额宝 coupon=50`。 */
        fun fieldsSummary(): String = fields.entries
            .filter { it.value != null }
            .joinToString(" ") { "${it.key}=${it.value}" }
    }

    /** 页面文本是否与支付/账务有关（用于「未识别页面要不要 dump 节点树」的过滤）。 */
    fun isPaymentLike(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return false
        return PAY_HINTS.any { t.contains(it) }
    }

    /**
     * 识别 + 取值。
     *
     * @param packageName 事件来源包名（决定用哪个 App 的规则集）
     * @param root 当前窗口的节点树（纯数据快照）
     * @param now 事件时间，页面时间解析不出时用它兜底
     * @return 解析成功返回 [Parsed]；页面不认识、或取不到金额、或页面状态是「关闭/失败」时返回 null
     */
    fun parse(packageName: String?, root: NodeSnapshot?, now: Long = System.currentTimeMillis()): Parsed? {
        if (root == null) return null
        val all = root.flatten()
        val page = PageRecognizer.recognize(packageName, root, all) ?: return null
        val fields = page.applyRules(root, all)

        // 「交易关闭 / 支付失败 / 已取消」这类页面不是一笔账，别记
        if (Values.isClosedOrFailed(fields[FieldNames.BILL_TYPE] as? String)) return null

        val amount = (fields[FieldNames.AMOUNT] as? Long)?.takeIf { it > 0L } ?: return null

        val timeText = fields[FieldNames.TIME] as? String
        val occurredAt = Values.parseTimeMs(timeText)
            ?.takeIf { it in EARLIEST_RECEIPT_MS..(now + MAX_TIME_AHEAD_MS) }
            ?: now

        val direction = resolveDirection(fields, page.defaultDirection)
        val transferNote = fields[FieldNames.TRANSFER_NOTE] as? String

        // 转出资产：页面的「交易方式/付款方式」优先；转账页没有这一行，
        // 从「转出说明」（`余额宝-转出到银行卡`）里拆出转出账户。
        val assetHint = cleanField(fields[FieldNames.FIRST_ASSET] as? String)
            ?: if (direction == Direction.TRANSFER) cleanField(TransferText.outAssetOf(transferNote)) else null

        // A partially loaded/native bank row must not become a historical bill dated "now".
        if (page.pageType == "IcbcBillDetail" &&
            (cleanField(fields[FieldNames.MERCHANT] as? String) == null ||
                Values.parseTimeMs(timeText) != occurredAt ||
                assetHint?.replace(" ", "")?.matches(Regex("[0-9]{4,6}[*xX•·]+[0-9]{4}")) != true)) return null

        // 转入资产：转账页的「转入账户」；拿不到再退回转账说明的后半段。
        val toAssetHint = if (direction == Direction.TRANSFER) {
            cleanField(fields[FieldNames.TO_ASSET] as? String)
                ?: cleanField(TransferText.inAssetOf(transferNote))
        } else {
            null
        }

        val channel = Channel.of(packageName)
        return Parsed(
            pageType = page.pageType,
            amountCents = amount,
            direction = direction,
            // 转账页没有「商户」：对方账户就是转入账户，用它当商户名（列表里看得懂）
            merchant = cleanField(fields[FieldNames.MERCHANT] as? String)
                ?: toAssetHint.takeIf { direction == Direction.TRANSFER },
            assetHint = if (page.pageType == "IcbcBillDetail") assetHint?.let { hint ->
                Regex("[0-9]{4}$").find(hint.replace(" ", ""))?.value?.let { "工商银行($it)" }
            } else assetHint,
            toAssetHint = toAssetHint,
            channelLabel = PaymentTextParser.buildChannelLabel(assetHint, channel),
            couponCents = (fields[FieldNames.COUPON] as? Long)?.takeIf { it > 0L } ?: 0L,
            orderId = cleanField(fields[FieldNames.ORDER_ID] as? String),
            timeText = timeText,
            occurredAt = occurredAt,
            rawText = root.textDump(),
            fields = fields,
        )
    }

    /** 页面解析结果 → 统一信号（后续去重/落库/通知/浮窗全都不用改）。 */
    fun toSignal(
        parsed: Parsed,
        packageName: String,
        origin: Origin = Origin.ACCESSIBILITY,
    ): PaySignal = PaySignal(
        sourcePackage = packageName,
        amountCents = parsed.amountCents,
        merchant = parsed.merchant,
        rawText = parsed.rawText,
        occurredAt = parsed.occurredAt,
        channel = Channel.of(packageName),
        direction = parsed.direction,
        // 页面直接给出的付款方式 → 优先于「关键词猜资产」
        suggestedAccountHint = parsed.assetHint,
        // 转账的转入资产（页面的「转入账户」）
        toAccountHint = parsed.toAssetHint,
        channelLabel = parsed.channelLabel,
        orderId = parsed.orderId,
        origin = origin,
        pageType = parsed.pageType,
        couponCents = parsed.couponCents,
        receiptTimeMillis = Values.parseTimeMs(parsed.timeText)?.takeIf { it == parsed.occurredAt },
    )

    // ---------- 内部 ----------

    private fun resolveDirection(fields: Map<String, Any?>, fallback: Direction): Direction {
        Values.directionOf(fields[FieldNames.BILL_TYPE] as? String)?.let { return it }
        return when (fields[FieldNames.SIGN]) {
            "-", "−" -> Direction.PAYMENT
            "+" -> Direction.INCOME
            else -> fallback
        }
    }

    /**
     * 字段值清洗：去首尾空白、只取第一行、丢掉明显是标签或金额的脏值。
     * 规则引擎也不保证 100% 干净，脏值进了账本比没值更糟。
     */
    private fun cleanField(raw: String?): String? {
        val v = raw?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (Labels.isLabel(v)) return null
        if (Values.looksLikeMoney(v)) return null
        // 状态值（真机转账页：金额下面的下一个文本就是「交易成功」）和时间都不能当账户名/商户名
        if (Values.isStatusLike(v)) return null
        if (Values.timeTextOf(v) == v) return null
        return v.take(MAX_FIELD_LEN)
    }

    private const val MAX_FIELD_LEN = 40
    // Browsing historical receipts must preserve their transaction date for duplicate detection.
    private const val EARLIEST_RECEIPT_MS = 946_684_800_000L // 2000-01-01 UTC
    private const val MAX_TIME_AHEAD_MS = 24L * 60 * 60 * 1000

    private val PAY_HINTS = listOf("支付", "付款", "收款", "账单", "退款", "转账", "消费", "金额", "元")
}
