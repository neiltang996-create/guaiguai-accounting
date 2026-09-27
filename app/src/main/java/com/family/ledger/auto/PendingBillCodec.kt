package com.family.ledger.auto

import com.family.ledger.data.db.entity.PendingBillEntity
import com.family.ledger.data.db.entity.TxnEntity
import com.family.ledger.data.db.entity.TxnSource
import com.family.ledger.data.db.entity.TxnType

/**
 * 待确认账单 ↔ 交易 的字段映射与恢复。
 *
 * 实体表结构由 lead 冻结（`PendingBillEntity` 没有 direction / channel / coupon 列），
 * 所以这里用「原始文本 + 解析器」把方向与付款渠道还原出来：
 * `rawText` 一定是解析器截断后的文本，二次解析结果与首次完全一致（幂等）。
 * 付款渠道另外写进流水备注前缀（`自动记账 · 微信零钱`），供后续去重与用户查看。
 *
 * **页面规则引擎**抽到的字段（pageType / 方向 / 优惠券 / 付款方式）在实体里同样没有列，
 * 而且规则是**跑在节点树上**的 —— 只留纯文本没法二次运行规则。
 * 于是这类字段以机器可读标记跟在 `rawText` 末尾（`⟦auto:page=…|dir=…|coupon=…|ch=…|to=…⟧`）：
 *  - [encodeRawText] 写入（没有可存字段时**原样返回**，通知路线不受影响）；
 *  - [cleanRawText] 读出前先剥掉，保证 [signalOf] 的二次解析与首次一致；
 *  - [directionOf] / [channelLabelOf] / [couponOf] / [pageTypeOf] 优先取标记，取不到再回退到二次解析。
 */
object PendingBillCodec {

    const val NOTE_PREFIX = "自动记账"
    private const val NOTE_SEP = " · "
    private const val NOTE_FLAG = "（可能重复）"

    /** 标记的起止符（刻意用生僻符号，正常页面文本里不会出现）。 */
    private const val MARK_START = "\u27E6auto:"
    private const val MARK_END = "\u27E7"
    private val MARK_RX = Regex("""\s*\u27E6auto:([^\u27E7]*)\u27E7\s*$""")

    /** 标记里的字段名。 */
    const val KEY_PAGE = "page"
    const val KEY_DIRECTION = "dir"
    const val KEY_COUPON = "coupon"
    const val KEY_CHANNEL_LABEL = "ch"
    const val KEY_ASSET_HINT = "asset"

    /** 转账的转入资产线索（页面的「转入账户」）。 */
    const val KEY_TO_ACCOUNT = "to"

    /** 流水备注：`自动记账` / `自动记账 · 微信零钱` / `…（可能重复）`。 */
    fun noteFor(channelLabel: String?, flagged: Boolean): String = buildString {
        append(NOTE_PREFIX)
        if (!channelLabel.isNullOrBlank()) append(NOTE_SEP).append(channelLabel)
        if (flagged) append(NOTE_FLAG)
    }

    /** 从流水备注里取回付款渠道。 */
    fun channelLabelOfNote(note: String?): String? {
        val n = note?.trim().orEmpty()
        if (!n.startsWith(NOTE_PREFIX)) return null
        var rest = n.removePrefix(NOTE_PREFIX)
        if (!rest.startsWith(NOTE_SEP)) return null
        rest = rest.removePrefix(NOTE_SEP)
        val cut = rest.indexOf(NOTE_FLAG)
        return (if (cut >= 0) rest.substring(0, cut) else rest).trim().ifBlank { null }
    }

    // ---------- rawText 里的机器可读标记 ----------

    /** 把字段拼成标记并附在文本末尾；[extras] 为空时**原样返回**（老路径零变化）。 */
    fun encodeRawText(text: String, extras: Map<String, String>): String {
        if (extras.isEmpty()) return text
        val body = extras.entries
            .filter { it.value.isNotBlank() }
            .joinToString("|") { (k, v) -> "$k=${sanitize(v)}" }
        if (body.isEmpty()) return text
        return text + "\n$MARK_START$body$MARK_END"
    }

    /** 剥掉标记，得到「可以二次解析」的干净文本。 */
    fun cleanRawText(rawText: String): String = rawText.replace(MARK_RX, "")

    /** 读出标记里的字段（没有标记返回空表）。 */
    fun extrasOf(rawText: String): Map<String, String> {
        val body = MARK_RX.find(rawText)?.groupValues?.get(1) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in body.split('|')) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            out[part.substring(0, idx)] = part.substring(idx + 1)
        }
        return out
    }

    /** 优惠券抵扣合计（分，正数）；没有就是 0。 */
    fun couponOf(rawText: String): Long =
        extrasOf(rawText)[KEY_COUPON]?.toLongOrNull()?.takeIf { it > 0L } ?: 0L

    /** 页面类型（用于日志与排障）。 */
    fun pageTypeOf(rawText: String): String? = extrasOf(rawText)[KEY_PAGE]

    /** 页面直接给出的付款方式（钱迹的 `firstAsset`）。 */
    fun assetHintOf(rawText: String): String? = extrasOf(rawText)[KEY_ASSET_HINT]

    /** 转账的转入资产线索（页面的「转入账户」），非转账为 null。 */
    fun toAssetHintOf(rawText: String): String? = extrasOf(rawText)[KEY_TO_ACCOUNT]

    private fun sanitize(value: String): String =
        value.replace('|', '/').replace('\u27E6', '(').replace('\u27E7', ')').replace('\n', ' ').take(128)

    // ---------- 恢复 ----------

    /** 二次解析：始终基于**剥掉标记后**的文本，保证与首次解析一致（幂等）。 */
    fun signalOf(p: PendingBillEntity): PaySignal? =
        PaymentTextParser.parse(cleanRawText(p.rawText), p.sourcePackage, p.occurredAt)

    /** 资金方向：标记（规则引擎写在节点树上）优先，其次二次解析，最后默认支出。 */
    fun directionOf(p: PendingBillEntity): Direction {
        extrasOf(p.rawText)[KEY_DIRECTION]?.let { name ->
            Direction.entries.firstOrNull { it.name == name }?.let { return it }
        }
        return signalOf(p)?.direction ?: Direction.PAYMENT
    }

    /** 付款渠道细节：标记优先（账单详情页的纯文本可能解析不出渠道），其次二次解析。 */
    fun channelLabelOf(p: PendingBillEntity): String? =
        extrasOf(p.rawText)[KEY_CHANNEL_LABEL]?.takeIf { it.isNotBlank() } ?: signalOf(p)?.channelLabel

    fun channelLabelOf(txn: TxnEntity): String? = channelLabelOfNote(txn.note)

    /**
     * 方向 → 交易类型。
     *
     * 转账**不能**落到 `EXPENSE`：那会把「自己两个账户之间挪钱」算成支出，
     * 用户账本里凭空多出一笔支出（用户实测反馈的正是这个问题）。
     */
    fun txnTypeOf(direction: Direction): TxnType = when (direction) {
        Direction.PAYMENT -> TxnType.EXPENSE
        Direction.REFUND -> TxnType.REFUND
        Direction.INCOME -> TxnType.INCOME
        Direction.TRANSFER -> TxnType.TRANSFER
    }

    fun txnSourceOf(channel: Channel): TxnSource = when (channel) {
        Channel.ALIPAY -> TxnSource.AUTO_ALIPAY
        Channel.WECHAT -> TxnSource.AUTO_WECHAT
        Channel.UNIONPAY, Channel.UNKNOWN -> TxnSource.AUTO_OTHER
    }
}
