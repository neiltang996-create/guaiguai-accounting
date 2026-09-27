package com.family.ledger.data.repo

import com.family.ledger.data.db.entity.*

/** 退款和报销都使用退回流水；资金到账与支出统计日期相互独立。 */
enum class RecoveryKind(val label: String) { REFUND("退款"), REIMBURSEMENT("报销") }

object ExpenseRecoveries {
    fun kind(txn: TxnEntity) = RecoveryKind.entries.firstOrNull { it.name == txn.recoveryKind } ?: RecoveryKind.REFUND
    fun paid(origin: TxnEntity): Long = origin.amount + origin.fee - origin.coupon
    fun linked(origin: TxnEntity, all: List<TxnEntity>) = all.filter {
        !it.deleted && it.type == TxnType.REFUND && it.relatedTxnId == origin.id
    }.sortedByDescending { it.occurredAt }
    // 旧导入的已报销金额只有汇总；保留统计信息，不虚构收款账户或再次增加余额。
    fun historicalReimbursement(origin: TxnEntity, all: List<TxnEntity>): Long =
        if (origin.reimbursedAmount <= 0) 0L else (origin.reimbursedAmount - linked(origin, all).filter { kind(it) == RecoveryKind.REIMBURSEMENT }.sumOf { it.amount }).coerceAtLeast(0)
    fun recovered(origin: TxnEntity, all: List<TxnEntity>): Long =
        linked(origin, all).sumOf { it.amount } + historicalReimbursement(origin, all)
    fun remaining(origin: TxnEntity, all: List<TxnEntity>): Long = paid(origin) - recovered(origin, all)

    fun validate(origin: TxnEntity, all: List<TxnEntity>, amount: Long, occurredAt: Long) {
        require(!origin.deleted && origin.type == TxnType.EXPENSE) { "请选择有效的原支出账单" }
        require(amount > 0) { "金额必须大于零" }
        require(amount <= remaining(origin, all)) { "退款与报销合计不能超过原支出实付金额" }
        require(occurredAt >= origin.occurredAt) { "到账时间不能早于原支出时间" }
    }

    /** 仅供统计的投影，绝不回写数据库或用于余额计算。先关联全量流水，再按日期筛选。 */
    fun forStatistics(all: List<TxnEntity>): List<TxnEntity> {
        val active = all.filterNot { it.deleted }
        val origins = active.filter { it.type == TxnType.EXPENSE }.associateBy { it.id }
        val projected = active.map { t ->
            val origin = if (t.type == TxnType.REFUND) origins[t.relatedTxnId] else null
            if (origin == null) t else t.copy(
                occurredAt = origin.occurredAt, bookId = origin.bookId, currency = origin.currency,
                categoryId = origin.categoryId, subCategoryId = origin.subCategoryId,
                assetId = origin.assetId, payerMemberId = origin.payerMemberId,
                consumerMemberId = origin.consumerMemberId, recorderMemberId = origin.recorderMemberId,
                excludeFromStats = origin.excludeFromStats,
            )
        }
        return projected + origins.values.mapNotNull { origin ->
            val historical = historicalReimbursement(origin, active)
            if (historical == 0L) null else origin.copy(id = "historical-reimbursement:" + origin.id,
                type = TxnType.REFUND, amount = historical, fee = 0, coupon = 0,
                relatedTxnId = origin.id, recoveryKind = RecoveryKind.REIMBURSEMENT.name)
        }
    }

    /** 关联退回明细集中在原账单内；无关联的历史退款仍可查看、补关联。 */
    fun bills(all: List<TxnEntity>): List<TxnEntity> {
        val origins = all.filter { !it.deleted && it.type == TxnType.EXPENSE }.map { it.id }.toSet()
        return all.filter { !it.deleted && it.type != TxnType.BALANCE_ADJUST &&
            !(it.type == TxnType.REFUND && it.relatedTxnId in origins) }
    }
}
