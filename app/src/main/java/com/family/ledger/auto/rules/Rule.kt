package com.family.ledger.auto.rules

import java.util.Collections
import java.util.IdentityHashMap

/**
 * 规则 DSL · **字段名**（对齐钱迹 `BillInfo` 的字段语义：`amount` / `merchantName` / `firstAsset` / `fee` …）。
 *
 * 取值结果是一个 `Map<String, Any?>`（钱迹也是 HashMap），再由 [BillPageParser] 映射成我们的 `PaySignal`。
 */
object FieldNames {
    const val AMOUNT = "amount"
    const val MERCHANT = "merchant"
    const val FIRST_ASSET = "firstAsset"
    const val COUPON = "coupon"
    const val ORDER_ID = "orderId"
    const val TIME = "timeStr"
    const val BILL_TYPE = "billType"
    const val SIGN = "sign"
    const val REMARK = "maybeRemark"

    /** 转账/还款的**转入账户**（钱迹的 `inAsset`）：`转入账户 → 中国工商银行(8804)`。 */
    const val TO_ASSET = "toAsset"

    /** 转账说明（`余额宝-转出到银行卡`），用来反推**转出资产**。 */
    const val TRANSFER_NOTE = "transferNote"
}

/** 字段值的解析方式。 */
enum class FieldKind {
    /** 原样文本。 */
    TEXT,

    /** 金额 → `Long`（分）；同一字段**先声明的规则先赢**。 */
    AMOUNT,

    /**
     * 优惠券抵扣 → `Long`（分，**恒为正**）。
     *
     * 与 [AMOUNT] 的区别是**累加**：钱迹把每条券抽成负数再求和，我们在这里直接累加正数。
     * 同一个锚点节点被多条券规则命中（关键词互相包含，如「到店支付红包」⊃「红包」）只算一次。
     */
    COUPON,

    /** 订单号（抽 ≥8 位数字）。 */
    ORDER_ID,

    /** 时间字符串（`2026-09-25 13:03:38`）。 */
    TIME,

    /** 状态文本（`支付成功` / `退款成功` / `已关闭`），用于判定资金方向。 */
    BILL_TYPE,

    /** 金额符号（`-` / `+`）。 */
    SIGN,

    /** 付款方式/资产文本（与 TEXT 同义，单独列出来是为了日志与语义清晰）。 */
    ASSET,
}

/**
 * 规则 DSL · 一条规则 = **(匹配器, 提取器, 字段, 解析方式)**。
 *
 * 语义（与钱迹一致）：
 *  - 匹配器在**整棵树的节点**里找锚点，可能命中多个；
 *  - 提取器从锚点出发找到「值所在的节点」；
 *  - 非累加字段**第一个非空值胜出**（先声明的规则优先），累加字段把每次命中加起来。
 *
 * @param root 当前窗口的根节点（保留给「整页级」提取器，目前提取器只用 [all]）
 */
data class Rule(
    val matcher: Matcher,
    val extractor: Extractor,
    val field: String,
    val kind: FieldKind = FieldKind.TEXT,
) {

    fun apply(
        root: NodeSnapshot,
        all: List<NodeSnapshot>,
        out: MutableMap<String, Any?>,
    ): Unit = apply(root, all, out, newAnchorSet())

    /**
     * @param seenCouponAnchors 本次解析共用的「已计入优惠券的锚点」集合，跨规则去重
     */
    fun apply(
        root: NodeSnapshot,
        all: List<NodeSnapshot>,
        out: MutableMap<String, Any?>,
        seenCouponAnchors: MutableSet<NodeSnapshot>,
    ) {
        val anchors = all.filter { matcher.matches(it) }
        if (anchors.isEmpty()) return

        if (kind == FieldKind.COUPON) {
            for (anchor in anchors) {
                if (anchor in seenCouponAnchors) continue
                val node = extractor.extract(anchor, all) ?: continue
                // 「优惠」标签和「银行立减优惠¥2.00」值节点可能指向同一笔优惠。
                // 按节点身份同时记住标签和值，两个独立的同金额优惠仍会分别累计。
                if (node in seenCouponAnchors) {
                    seenCouponAnchors.add(anchor)
                    continue
                }
                val cents = Values.parseCouponCents(node.display) ?: continue
                seenCouponAnchors.add(anchor)
                seenCouponAnchors.add(node)
                out[field] = ((out[field] as? Long) ?: 0L) + cents
            }
            return
        }

        for (anchor in anchors) {
            val node = extractor.extract(anchor, all) ?: continue
            val value = resolve(node) ?: continue
            if (!out.containsKey(field) || out[field] == null) out[field] = value
            return
        }
    }

    /** 把「值节点」变成字段值；取不到返回 null。 */
    private fun resolve(node: NodeSnapshot): Any? {
        val text = node.display
        return when (kind) {
            FieldKind.AMOUNT -> Values.parseAmountCents(text)?.let { kotlin.math.abs(it) }
            FieldKind.COUPON -> Values.parseCouponCents(text)
            FieldKind.ORDER_ID -> Values.digitsOf(text)
            FieldKind.TIME -> Values.timeTextOf(text)
            FieldKind.BILL_TYPE -> text
            FieldKind.SIGN -> Values.signOf(text)
            FieldKind.TEXT, FieldKind.ASSET -> text?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    companion object {
        /** 跨规则共享的锚点集合（按对象身份去重）。 */
        fun newAnchorSet(): MutableSet<NodeSnapshot> =
            Collections.newSetFromMap(IdentityHashMap())
    }
}
