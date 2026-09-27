package com.family.ledger.data.repo

import com.family.ledger.core.Ids
import androidx.room.withTransaction
import com.family.ledger.core.FixedPeople
import com.family.ledger.data.db.AppDatabase
import com.family.ledger.data.db.dao.*
import com.family.ledger.data.db.entity.*
import com.family.ledger.data.sync.SyncJournal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** UI / 自动记账 / 导入 共用的「新建一笔账」草稿。 */
data class TxnDraft(
    var type: TxnType = TxnType.EXPENSE,
    var amountCents: Long = 0L,
    var occurredAt: Long = System.currentTimeMillis(),
    var bookId: String,
    var assetId: String? = null,
    var toAssetId: String? = null,
    var categoryId: String? = null,
    var subCategoryId: String? = null,
    var recorderMemberId: String? = null,
    var payerMemberId: String? = null,
    var consumerMemberId: String? = null,
    var tagIds: List<String> = emptyList(),
    var note: String? = null,
    var merchant: String? = null,
    var reimbursable: Boolean = false,
    var feeCents: Long = 0L,
    var couponCents: Long = 0L,
    var excludeFromStats: Boolean = false,
    var relatedTxnId: String? = null,
    var recoveryKind: String? = null,
    var imagePaths: List<String> = emptyList(),
    var currency: String = "CNY",
    var source: TxnSource = TxnSource.MANUAL,
    var sourceFingerprint: String? = null,
    var transactionId: String? = null,

)

/**
 * 记账的唯一业务入口。
 * UI、自动记账确认、钱迹 CSV 导入、周期记账都必须走这里，
 * 以保证「写流水 + 记 oplog」永远成对发生。
 */
class LedgerRepository(
    private val db: AppDatabase,
    private val journal: SyncJournal,
    private val settings: com.family.ledger.data.SettingsStore,
) {
    private val txnDao get() = db.txnDao()
    private val bookDao get() = db.bookDao()
    private val memberDao get() = db.familyMemberDao()
    private val ruleDao get() = db.merchantRuleDao()
    private val logDao get() = db.autoBillLogDao()

    // ---------- 查询 ----------

    fun observeRecent(limit: Int = 500): Flow<List<TxnEntity>> = txnDao.observeRecent()
    fun observeBetween(from: Long, to: Long): Flow<List<TxnEntity>> = txnDao.observeBetween(from, to)
    fun observeByBook(bookId: String): Flow<List<TxnEntity>> = txnDao.observeByBook(bookId)
    suspend fun all(): List<TxnEntity> = txnDao.all()
    suspend fun byId(id: String): TxnEntity? = txnDao.byId(id)
    suspend fun search(q: String): List<TxnEntity> = txnDao.search(q)
    suspend fun count(): Int = txnDao.count()

    fun observeStatsBetween(from: Long, to: Long): Flow<List<TxnEntity>> =
        txnDao.observeRecent().map { all -> ExpenseRecoveries.forStatistics(all).filter { it.occurredAt >= from && it.occurredAt < to } }

    fun observeMonthTotal(from: Long, to: Long): Flow<MonthTotals> =
        observeStatsBetween(from, to).map { MonthTotals.from(it) }

    // ---------- 写入 ----------

    suspend fun save(draft: TxnDraft): TxnEntity = db.withTransaction {
        require(draft.amountCents > 0) { "金额必须大于零" }
        require(draft.feeCents >= 0 && draft.couponCents in 0..draft.amountCents) { "手续费或优惠金额无效" }
        require(draft.recorderMemberId == null || draft.recorderMemberId in FixedPeople.names)
        require(draft.payerMemberId == null || draft.payerMemberId in FixedPeople.names)
        require(draft.consumerMemberId == null || draft.consumerMemberId in FixedPeople.names || draft.consumerMemberId == FixedPeople.FAMILY)
        if (draft.type == TxnType.TRANSFER || draft.type == TxnType.REPAYMENT) {
            require(draft.assetId != null && draft.toAssetId != null && draft.assetId != draft.toAssetId) { "转出与转入资产必须不同且均已选择" }
        }
        if (draft.type == TxnType.REFUND) {
            val origin = draft.relatedTxnId?.let { txnDao.byId(it) } ?: error("请先关联原支出账单")
            ExpenseRecoveries.validate(origin, txnDao.all(), draft.amountCents, draft.occurredAt)
            validateRecoveryAsset(draft.assetId, origin.currency)
            require(draft.recoveryKind == null || draft.recoveryKind in RecoveryKind.entries.map { it.name })
            draft.bookId = origin.bookId; draft.currency = origin.currency
            draft.categoryId = origin.categoryId; draft.subCategoryId = origin.subCategoryId
            draft.payerMemberId = origin.payerMemberId; draft.consumerMemberId = origin.consumerMemberId
            draft.merchant = origin.merchant
            draft.feeCents = 0; draft.couponCents = 0; draft.excludeFromStats = origin.excludeFromStats
        }
        val now = System.currentTimeMillis()
        // 按 deviceId 认「我」，不要用 isMe（会随同步串台，见 FamilyMemberDao.byDeviceId）
        val me = settings.myMemberId?.let { memberDao.byId(it) }
            ?: error("请先选择当前使用者")
        val txn = TxnEntity(
            id = draft.transactionId ?: Ids.newId("t-"),
            bookId = draft.bookId,
            type = draft.type,
            amount = draft.amountCents,
            currency = draft.currency,
            occurredAt = draft.occurredAt,
            assetId = draft.assetId,
            toAssetId = draft.toAssetId,
            categoryId = draft.categoryId,
            subCategoryId = draft.subCategoryId,
            recorderMemberId = draft.recorderMemberId ?: me?.id,
            payerMemberId = draft.payerMemberId ?: draft.recorderMemberId ?: me?.id,
            consumerMemberId = draft.consumerMemberId ?: draft.recorderMemberId ?: me?.id,
            tagIds = draft.tagIds.takeIf { it.isNotEmpty() }?.joinToString(","),
            note = draft.note,
            reimbursable = draft.reimbursable,
            fee = draft.feeCents,
            coupon = draft.couponCents,
            excludeFromStats = draft.excludeFromStats,
            relatedTxnId = draft.relatedTxnId,
            recoveryKind = draft.recoveryKind,
            imagePaths = draft.imagePaths.takeIf { it.isNotEmpty() }?.joinToString(","),
            source = draft.source,
            manuallyConfirmed = true,
            sourceFingerprint = draft.sourceFingerprint,
            merchant = draft.merchant,
            createdByDeviceId = settings.deviceId,
            createdAt = now,
            updatedAt = now,
        )
        txnDao.upsert(txn)
        journal.record(txn)
        txn
    }

    /** 直接落库一笔完整实体（导入 / 同步 / 自动记账使用）。 */
    suspend fun upsert(txn: TxnEntity, journalIt: Boolean = true) = db.withTransaction {
        txnDao.upsert(txn)
        if (journalIt) journal.record(txn)
    }

    suspend fun update(txn: TxnEntity) = db.withTransaction {
        val previous = txnDao.byId(txn.id) ?: error("账单不存在")
        require(!previous.deleted) { "账单已删除" }
        require(txn.type == previous.type && txn.relatedTxnId == previous.relatedTxnId) { "请从原账单管理退款或报销" }
        require(txn.amount > 0 && txn.fee >= 0 && txn.coupon in 0..txn.amount) { "金额无效" }
        if (txn.type == TxnType.EXPENSE) {
            require(ExpenseRecoveries.paid(txn) >= ExpenseRecoveries.recovered(txn, txnDao.all())) { "支出不能少于已退款与报销金额，请先撤销相应记录" }
        }
        if (txn.type == TxnType.REFUND && txn.relatedTxnId != null) {
            val origin = txnDao.byId(txn.relatedTxnId) ?: error("原支出不存在")
            ExpenseRecoveries.validate(origin, txnDao.all().filterNot { it.id == txn.id }, txn.amount, txn.occurredAt)
            validateRecoveryAsset(txn.assetId, origin.currency)
        }
        val updated = txn.copy(updatedAt = System.currentTimeMillis())
        txnDao.upsert(updated)
        journal.record(updated)
    }

    /** 软删除，保留可同步的墓碑。 */
    suspend fun softDelete(txnId: String) = db.withTransaction {
        val t = txnDao.byId(txnId) ?: return@withTransaction
        require(t.type != TxnType.EXPENSE || ExpenseRecoveries.linked(t, txnDao.all()).isEmpty()) { "请先在原账单内撤销退款与报销记录，再删除支出" }
        val updated = t.copy(deleted = true, updatedAt = System.currentTimeMillis())
        txnDao.upsert(updated)
        journal.recordDelete(updated)
    }

    private suspend fun validateRecoveryAsset(assetId: String?, currency: String) {
        val asset = assetId?.let { db.assetDao().byId(it) } ?: error("请选择到账账户")
        require(!asset.deleted && !asset.archived) { "到账账户已停用，请重新选择" }
        require(asset.currency == currency) { "到账账户币种必须与原支出一致" }
    }

    suspend fun createRecoveryFor(originTxnId: String, kind: RecoveryKind, amountCents: Long,
        assetId: String, occurredAt: Long = System.currentTimeMillis(), note: String? = null): TxnEntity =
        save(TxnDraft(type = TxnType.REFUND, amountCents = amountCents, bookId = "",
            assetId = assetId, occurredAt = occurredAt, relatedTxnId = originTxnId,
            recoveryKind = kind.name, note = note, source = TxnSource.REFUND_LINK))

    /** 补关联旧退款，保留原有现金流水、图片与 ID，避免二次加余额。 */
    suspend fun linkRecovery(recoveryId: String, originId: String, kind: RecoveryKind) = db.withTransaction {
        val t = txnDao.byId(recoveryId) ?: error("退款记录不存在")
        require(!t.deleted && t.type == TxnType.REFUND) { "请选择退款记录" }
        val origin = txnDao.byId(originId) ?: error("原支出不存在")
        ExpenseRecoveries.validate(origin, txnDao.all().filterNot { it.id == t.id }, t.amount, t.occurredAt)
        require(t.currency == origin.currency) { "币种不同，无法关联" }
        val updated = t.copy(relatedTxnId = origin.id, recoveryKind = kind.name, bookId = origin.bookId,
            categoryId = origin.categoryId, subCategoryId = origin.subCategoryId,
            excludeFromStats = origin.excludeFromStats, updatedAt = System.currentTimeMillis())
        txnDao.upsert(updated); journal.record(updated)
    }

    // ---------- 统计 ----------

    /** 统计口径：排除「不计收支」、排除转账/还款/校准。 */
    data class MonthTotals(
        val expense: Long,
        val income: Long,
        val refund: Long,
        val net: Long,
        val count: Int,
    ) {
        companion object {
            fun from(list: List<TxnEntity>): MonthTotals {
                var expense = 0L; var income = 0L; var refund = 0L; var count = 0
                for (t in list) {
                    if (t.deleted || t.excludeFromStats) continue
                    when (t.type) {
                        TxnType.EXPENSE -> { expense += t.amount + t.fee - t.coupon; count++ }
                        TxnType.INCOME -> { income += t.amount - t.fee; count++ }
                        TxnType.REFUND -> { refund += t.amount; count++ }
                        else -> {}
                    }
                }
                return MonthTotals(expense - refund, income, refund, net = income + refund - expense, count = count)
            }
        }
    }

    // ---------- 商户分类学习 ----------

    /**
     * 学习「这个商户应该记到哪个分类」。
     * 用户在待确认页改动分类后调用，下次同商户自动带出。
     */
    suspend fun learnMerchant(merchant: String?, categoryId: String?, subCategoryId: String?, assetId: String?, bookId: String? = null, consumerId: String? = null) {
        val pattern = normalizeMerchant(merchant) ?: return
        val now = System.currentTimeMillis()
        val existing = ruleDao.byPattern(pattern)
        ruleDao.upsert(
            MerchantRuleEntity(
                id = existing?.id ?: Ids.newId("mr-"),
                pattern = pattern,
                categoryId = categoryId ?: existing?.categoryId,
                subCategoryId = subCategoryId ?: existing?.subCategoryId,
                assetId = assetId ?: existing?.assetId,
                bookId = bookId ?: existing?.bookId,
                consumerId = consumerId ?: existing?.consumerId,
                hits = (existing?.hits ?: 0) + 1,
                lastUsedAt = now,
                updatedAt = now,
            )
        )
    }

    /** 猜分类：先查学习规则，再回退到占位。 */
    suspend fun guessCategory(merchantText: String?, useExpenseOverride: Boolean = true): MerchantRuleEntity? {
        val p = normalizeMerchant(merchantText) ?: return null
        val learned = ruleDao.bestMatch(merchantText.orEmpty(), p)
        if (!useExpenseOverride || !hasCategoryOverride(merchantText)) return learned
        // 用户指定的分类优先于历史学习；付款账户、人物和账本仍沿用原有判断。
        return db.withTransaction {
            val categories = db.categoryDao()
            val now = System.currentTimeMillis()
            suspend fun ensureCategory(name: String, parentId: String?): CategoryEntity {
                val existing = if (parentId == null) categories.topLevelByName("EXPENSE", name)
                    else categories.childByName(parentId, name)
                if (existing != null && !existing.archived && !existing.deleted) return existing
                // 两台手机首次识别时得到同一 ID，离线后同步也不会生成两份分类。
                val id = existing?.id ?: "c-rule-" + java.util.UUID.nameUUIDFromBytes(
                    ("EXPENSE|" + parentId.orEmpty() + "|" + name).toByteArray(Charsets.UTF_8))
                val category = existing?.copy(archived = false, deleted = false, updatedAt = now)
                    ?: CategoryEntity(id = id, name = name, parentId = parentId, kind = "EXPENSE",
                        sortOrder = (categories.all().filter { it.parentId == parentId }.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                        updatedAt = now)
                categories.upsert(category); journal.record(category)
                return category
            }
            val top = ensureCategory("电瓶车", null)
            val child = ensureCategory("充电", top.id)
            (learned ?: MerchantRuleEntity(id = "rule-sample-charging-charging", pattern = p, updatedAt = now))
                .copy(categoryId = top.id, subCategoryId = child.id)
        }
    }

    suspend fun logAutoBill(
        action: String,
        pendingBillId: String? = null,
        txnId: String? = null,
        matchingMode: String? = null,
        requestId: String? = null,
        entryJson: String = "{}",
    ) {
        logDao.insert(
            AutoBillLogEntity(
                id = Ids.newId("log-"),
                timeMs = System.currentTimeMillis(),
                action = action,
                pendingBillId = pendingBillId,
                txnId = txnId,
                matchingMode = matchingMode,
                requestId = requestId,
                entryJson = entryJson,
            )
        )
        logDao.trimTo(1000)
    }

    companion object {
        /** 用户明确指定的商户用途；归一化兼容 OCR 空格和公司名称后缀。 */
        fun hasCategoryOverride(merchant: String?): Boolean =
            normalizeMerchant(merchant)?.let { it == normalizeMerchant(com.family.ledger.BuildConfig.CHARGING_MERCHANT) } == true

        /** 归一化商户名，去掉门店号/空格/常见后缀，提高匹配率。 */
        fun normalizeMerchant(raw: String?): String? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            val cleaned = s
                .replace(Regex("[（(].*?[)）]"), "")
                .replace(Regex("\\s+"), "")
                .replace(Regex("[0-9]{4,}"), "")
                .replace("有限公司", "")
                .replace("有限责任公司", "")
                .trim()
            return cleaned.ifEmpty { s }.take(24)
        }
    }
}
