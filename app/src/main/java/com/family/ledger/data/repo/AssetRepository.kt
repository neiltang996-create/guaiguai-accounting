package com.family.ledger.data.repo

import com.family.ledger.core.Ids
import androidx.room.withTransaction
import com.family.ledger.data.SettingsStore
import com.family.ledger.data.db.AppDatabase
import com.family.ledger.data.db.entity.*
import com.family.ledger.data.sync.SyncJournal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class AssetWithBalance(
    val asset: AssetEntity,
    val balance: Long,
) {
    val isFamily: Boolean get() = asset.ownerType == OwnerType.FAMILY
    /** 展示金额：信用卡欠款显示为正。 */
    val displayBalance: Long get() = BalanceCalculator.displayBalance(asset, balance)
}

/**
 * 资产 / 共享资产仓库。
 *
 * 余额永远由流水推导，写入路径只有两条：
 *   1. 记账（LedgerRepository 写 txn）
 *   2. 校准（reconcile 写锚点 + 校准流水）
 * 没有任何「直接设置余额」的 API —— 这是双端不冲突的根本保证。
 */
class AssetRepository(
    private val db: AppDatabase,
    private val journal: SyncJournal,
    private val settings: SettingsStore,
) {
    private val assetDao get() = db.assetDao()
    private val anchorDao get() = db.balanceAnchorDao()
    private val txnDao get() = db.txnDao()

    fun observeAllWithBalance(): Flow<List<AssetWithBalance>> =
        combine(assetDao.observeAll(), anchorDao.observeAll(), txnDao.observeBalanceRows()) { assets, anchors, rows ->
            val balances = BalanceCalculator.balancesFor(assets, anchors, rows)
            assets.map { AssetWithBalance(it, balances[it.id] ?: it.openingBalance) }
        }

    fun observeActiveWithBalance(): Flow<List<AssetWithBalance>> =
        combine(assetDao.observeActive(), anchorDao.observeAll(), txnDao.observeBalanceRows()) { assets, anchors, rows ->
            val balances = BalanceCalculator.balancesFor(assets, anchors, rows)
            assets.map { AssetWithBalance(it, balances[it.id] ?: it.openingBalance) }
        }

    fun observeFamilyAssets(): Flow<List<AssetWithBalance>> =
        observeActiveWithBalance().map { list -> list.filter { it.isFamily } }

    fun observePersonalAssets(): Flow<List<AssetWithBalance>> =
        observeActiveWithBalance().map { list -> list.filter { !it.isFamily } }

    /** 家庭净资产（只算计入净值的资产）。 */
    fun observeNetWorth(): Flow<Long> =
        observeActiveWithBalance().map { list ->
            list.filter { it.asset.includeInNetWorth }
                .sumOf { BalanceCalculator.netWorthContribution(it.asset, it.balance) }
        }

    suspend fun all(): List<AssetEntity> = assetDao.all()
    suspend fun byId(id: String): AssetEntity? = assetDao.byId(id)
    suspend fun byName(name: String): AssetEntity? = assetDao.byName(name)

    suspend fun upsert(asset: AssetEntity, journalIt: Boolean = true) = db.withTransaction {
        assetDao.upsert(asset)
        if (journalIt) journal.record(asset)
    }

    suspend fun create(
        name: String,
        type: AssetType,
        ownerType: OwnerType,
        familyId: String?,
        openingBalanceCents: Long = 0L,
        groupName: String? = null,
        iconKey: String? = null,
        note: String? = null,
        sharedForFamily: Boolean = ownerType == OwnerType.FAMILY,
    ): AssetEntity {
        val now = System.currentTimeMillis()
        val asset = AssetEntity(
            id = Ids.newId("a-"),
            name = name,
            ownerType = ownerType,
            ownerUserId = if (ownerType == OwnerType.USER) settings.myMemberId else null,
            ownerFamilyId = if (ownerType == OwnerType.FAMILY) (familyId ?: settings.familyId) else null,
            type = type,
            groupName = groupName ?: if (ownerType == OwnerType.FAMILY) GROUP_FAMILY else GROUP_PERSONAL,
            openingBalance = openingBalanceCents,
            sharedForFamily = sharedForFamily,
            iconKey = iconKey,
            note = note,
            createdAt = now,
            updatedAt = now,
        )
        upsert(asset)
        return asset
    }

    suspend fun archive(assetId: String, archived: Boolean = true) {
        val a = assetDao.byId(assetId) ?: return
        upsert(a.copy(archived = archived, updatedAt = System.currentTimeMillis()))
    }

    suspend fun update(asset: AssetEntity) = upsert(asset.copy(updatedAt = System.currentTimeMillis()))

    /** 校准：以真实余额为准插入锚点，并留一笔可审计的校准流水。 */
    suspend fun reconcile(assetId: String, realBalanceCents: Long, note: String? = null): BalanceAnchorEntity? = db.withTransaction {
        val asset = assetDao.byId(assetId) ?: return@withTransaction null
        val balances = BalanceCalculator.balancesFor(
            listOf(asset),
            anchorDao.forAsset(assetId),
            txnDao.balanceRows(),
        )
        val current = balances[assetId] ?: asset.openingBalance
        val delta = BalanceCalculator.reconciliationDelta(current, realBalanceCents)
        val now = System.currentTimeMillis()
        val anchor = BalanceAnchorEntity(
            id = Ids.newId("an-"),
            assetId = assetId,
            realBalance = realBalanceCents,
            at = now,
            note = note,
            createdByDeviceId = settings.deviceId,
            createdAt = now,
            updatedAt = now,
        )
        anchorDao.upsert(anchor)
        journal.record(anchor)

        if (delta != null) {
            val book = db.bookDao().all().firstOrNull()
            if (book != null) {
                val adjust = TxnEntity(
                    id = Ids.newId("t-"),
                    bookId = book.id,
                    type = TxnType.BALANCE_ADJUST,
                    amount = kotlin.math.abs(delta),
                    occurredAt = now,
                    assetId = assetId,
                    note = note ?: "余额校准",
                    excludeFromStats = true,
                    source = TxnSource.MANUAL,
                    createdByDeviceId = settings.deviceId,
                    createdAt = now,
                    updatedAt = now,
                )
                txnDao.upsert(adjust)
                journal.record(adjust)
            }
        }
        anchor
    }

    companion object {
        const val GROUP_FAMILY = "家庭共享资产"
        const val GROUP_PERSONAL = "个人资产"
    }
}
