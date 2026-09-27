package com.family.ledger.auto

import com.family.ledger.data.db.entity.TxnEntity
import com.family.ledger.data.db.entity.TxnSource
import com.family.ledger.data.db.entity.TxnType
import kotlin.math.abs

/** 钱迹导入记录没有支付订单号。只用明确详情时间和完整交易字段匹配，存在歧义时交回疑似重复流程。 */
object ImportedReceiptMatcher {
    const val TIME_TOLERANCE_MS = 3_000L

    fun match(signal: PaySignal, observedAssetId: String?, memberId: String?,
              records: List<TxnEntity>): TxnEntity? {
        if (signal.origin != Origin.ACCESSIBILITY || signal.pageType?.endsWith("BillDetail") != true ||
            signal.receiptTimeMillis != signal.occurredAt || observedAssetId == null || memberId.isNullOrBlank()) return null
        val merchant = normalize(signal.merchant).takeIf { it.isNotEmpty() } ?: return null
        val type = when (signal.direction) {
            Direction.PAYMENT -> TxnType.EXPENSE
            Direction.INCOME -> TxnType.INCOME
            else -> return null // 转账和退款需要额外的关联信息，不能凭单边金额合并。
        }
        return records.filter { txn ->
            !txn.deleted && txn.source == TxnSource.IMPORT_QIANJI && txn.currency == "CNY" &&
                txn.type == type && txn.assetId == observedAssetId &&
                (txn.payerMemberId ?: txn.recorderMemberId) == memberId &&
                abs(txn.occurredAt - signal.occurredAt) <= TIME_TOLERANCE_MS &&
                (if (type == TxnType.EXPENSE) txn.amount + txn.fee - txn.coupon else txn.amount - txn.fee) == signal.amountCents &&
                normalize(txn.merchant?.takeIf { it.isNotBlank() } ?: txn.note) == merchant
        }.singleOrNull()
    }

    private fun normalize(value: String?): String = value.orEmpty().trim().replace(Regex("\\s+"), "")
}
