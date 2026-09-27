package com.family.ledger.ui.stats

import com.family.ledger.data.db.entity.TxnEntity
import com.family.ledger.data.db.entity.TxnType

enum class StatsFlow(val label: String, private val type: TxnType) {
    EXPENSE("支出", TxnType.EXPENSE), INCOME("收入", TxnType.INCOME);

    fun entries(txns: List<TxnEntity>): List<TxnEntity> =
        txns.filter { !it.deleted && !it.excludeFromStats && (it.type == type || (this == EXPENSE && it.type == TxnType.REFUND)) }

    fun amount(txn: TxnEntity): Long = when (this) {
        EXPENSE -> if (txn.type == TxnType.REFUND) -txn.amount else txn.amount + txn.fee - txn.coupon
        INCOME -> txn.amount - txn.fee
    }

    companion object {
        /** 超过八类时合并尾部，使图例、扇区与合计使用同一个分母。 */
        fun chartSlices(items: List<Pair<String, Long>>): List<Pair<String, Long>> =
            if (items.size <= 8) items else items.take(7) + ("其余分类" to items.drop(7).sumOf { it.second })
    }
}
