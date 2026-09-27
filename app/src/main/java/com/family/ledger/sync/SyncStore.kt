package com.family.ledger.sync

import com.family.ledger.data.db.AppDatabase
import androidx.room.withTransaction
import com.family.ledger.data.db.entity.*
import com.family.ledger.data.sync.EntityCodec

/**
 * [SyncEntityStore] 的 Room 实现。
 *
 * 这里是「应用远端 op」的唯一落库路径：**直接调 DAO 的 upsert，绝不经过 repository / journal**。
 * 若这里记了 oplog，两端会互相把对方的 op 再记一遍，形成无限回环。
 */
class RoomSyncEntityStore(private val db: AppDatabase) : SyncEntityStore {
    override suspend fun <T> atomic(block: suspend () -> T): T = db.withTransaction { block() }

    override suspend fun localOps(): List<SyncOpEntity> = db.syncDao().allOps()

    override suspend fun putOp(op: SyncOpEntity) = db.syncDao().upsertOp(op)

    override suspend fun existingEntity(table: String, id: String): Any? = when (table) {
        EntityCodec.T_DEVICE -> db.deviceDao().byId(id)
        EntityCodec.T_FAMILY -> db.familyDao().all().firstOrNull { it.id == id }
        EntityCodec.T_MEMBER -> db.familyMemberDao().allIncludingDeleted().firstOrNull { it.id == id }
        EntityCodec.T_BOOK -> db.bookDao().byId(id)
        EntityCodec.T_ASSET -> db.assetDao().byId(id)
        EntityCodec.T_ANCHOR -> db.balanceAnchorDao().allIncludingDeleted().firstOrNull { it.id == id }
        EntityCodec.T_CATEGORY -> db.categoryDao().allIncludingDeleted().firstOrNull { it.id == id }
        EntityCodec.T_TAG -> db.tagDao().allIncludingDeleted().firstOrNull { it.id == id }
        EntityCodec.T_TXN -> db.txnDao().byId(id)
        EntityCodec.T_MERCHANT_RULE -> db.merchantRuleDao().all().firstOrNull { it.id == id }
        else -> null
    }

    override suspend fun upsertEntity(entity: Any) {
        when (entity) {
            is DeviceEntity -> db.deviceDao().upsert(entity)
            is FamilyEntity -> db.familyDao().upsert(entity)
            is FamilyMemberEntity -> db.familyMemberDao().upsert(entity)
            is BookEntity -> db.bookDao().upsert(entity)
            is AssetEntity -> db.assetDao().upsert(entity)
            is BalanceAnchorEntity -> db.balanceAnchorDao().upsert(entity)
            is CategoryEntity -> db.categoryDao().upsert(entity)
            is TagEntity -> db.tagDao().upsert(entity)
            is TxnEntity -> db.txnDao().upsert(entity)
            is MerchantRuleEntity -> db.merchantRuleDao().upsert(entity)
            else -> Unit // 不参与同步的类型直接忽略
        }
    }

    override suspend fun cursor(key: String): Long =
        db.syncDao().getState(key)?.toLongOrNull() ?: 0L

    override suspend fun putCursor(key: String, value: Long) {
        db.syncDao().putState(SyncStateEntity(key, value.toString(), System.currentTimeMillis()))
    }
}
