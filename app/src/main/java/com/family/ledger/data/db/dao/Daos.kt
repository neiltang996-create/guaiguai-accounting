package com.family.ledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.family.ledger.data.db.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FamilyDao {
    @Upsert suspend fun upsert(f: FamilyEntity)
    @Query("SELECT * FROM family WHERE deleted = 0 LIMIT 1")
    suspend fun current(): FamilyEntity?
    @Query("SELECT * FROM family WHERE deleted = 0 LIMIT 1")
    fun observeCurrent(): Flow<FamilyEntity?>
    @Query("SELECT * FROM family")
    suspend fun all(): List<FamilyEntity>
}

@Dao
interface FamilyMemberDao {
    @Query("SELECT * FROM family_member WHERE id = :id AND deleted = 0")
    suspend fun byId(id: String): FamilyMemberEntity?

    @Upsert suspend fun upsert(m: FamilyMemberEntity)
    @Upsert suspend fun upsertAll(ms: List<FamilyMemberEntity>)
    @Query("SELECT * FROM family_member WHERE deleted = 0 AND id IN ('PERSON_A','PERSON_B') ORDER BY id DESC")
    fun observeAll(): Flow<List<FamilyMemberEntity>>
    @Query("SELECT * FROM family_member WHERE deleted = 0 AND id IN ('PERSON_A','PERSON_B') ORDER BY id DESC")
    suspend fun all(): List<FamilyMemberEntity>
    /**
     * 本机使用者。
     *
     * **必须按 deviceId 查，不能按 isMe 查。**
     * 原因：`isMe` 是实体字段，会随 oplog 同步到对方手机上；
     * 而对方手机自己 bootstrap 时也会建一行 `isMe = true`。
     * 两边同步一合并，两台手机的本地表里都会有**两行 isMe = 1**，
     * 那时 `WHERE isMe = 1 LIMIT 1` 可能返回配偶那一行 ——
     * 于是「改我的昵称」会改到对方，还会把错误的名字同步回去。
     * 本机身份的唯一可靠依据是 `settings.deviceId`（不入同步）。
     */
    @Query("SELECT * FROM family_member WHERE deviceId = :deviceId AND deleted = 0 LIMIT 1")
    suspend fun byDeviceId(deviceId: String): FamilyMemberEntity?

    /** 兜底：老数据里 deviceId 可能为空，此时退回 isMe 标记。 */
    @Query("SELECT * FROM family_member WHERE isMe = 1 AND deleted = 0 LIMIT 1")
    suspend fun meByFlag(): FamilyMemberEntity?
    @Query("SELECT * FROM family_member WHERE deleted = 0 AND displayName = :name LIMIT 1")
    suspend fun byName(name: String): FamilyMemberEntity?
    @Query("SELECT * FROM family_member")
    suspend fun allIncludingDeleted(): List<FamilyMemberEntity>
}

@Dao
interface BookDao {
    @Upsert suspend fun upsert(b: BookEntity)
    @Query("SELECT * FROM book WHERE deleted = 0 AND archived = 0 ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<BookEntity>>
    @Query("SELECT * FROM book WHERE deleted = 0 ORDER BY sortOrder, createdAt")
    suspend fun all(): List<BookEntity>
    @Query("SELECT * FROM book WHERE id = :id")
    suspend fun byId(id: String): BookEntity?
    @Query("SELECT * FROM book")
    suspend fun allIncludingDeleted(): List<BookEntity>
}

@Dao
interface AssetDao {
    @Upsert suspend fun upsert(a: AssetEntity)
    @Upsert suspend fun upsertAll(list: List<AssetEntity>)

    @Query("SELECT * FROM asset WHERE deleted = 0 AND archived = 0 ORDER BY sortOrder, createdAt")
    fun observeActive(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM asset WHERE deleted = 0 ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM asset WHERE deleted = 0 ORDER BY sortOrder, createdAt")
    suspend fun all(): List<AssetEntity>

    @Query("SELECT * FROM asset WHERE id = :id")
    suspend fun byId(id: String): AssetEntity?

    @Query("SELECT * FROM asset WHERE deleted = 0 AND name = :name LIMIT 1")
    suspend fun byName(name: String): AssetEntity?

    /** 家庭共享资产。 */
    @Query("SELECT * FROM asset WHERE deleted = 0 AND archived = 0 AND ownerType = 'FAMILY' ORDER BY sortOrder, createdAt")
    fun observeFamilyAssets(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM asset")
    suspend fun allIncludingDeleted(): List<AssetEntity>
}

@Dao
interface BalanceAnchorDao {
    @Upsert suspend fun upsert(a: BalanceAnchorEntity)
    @Query("SELECT * FROM balance_anchor WHERE deleted = 0")
    fun observeAll(): Flow<List<BalanceAnchorEntity>>
    @Query("SELECT * FROM balance_anchor WHERE deleted = 0")
    suspend fun all(): List<BalanceAnchorEntity>
    @Query("SELECT * FROM balance_anchor WHERE deleted = 0 AND assetId = :assetId ORDER BY at DESC LIMIT 1")
    suspend fun latestFor(assetId: String): BalanceAnchorEntity?
    @Query("SELECT * FROM balance_anchor WHERE deleted = 0 AND assetId = :assetId ORDER BY at DESC")
    suspend fun forAsset(assetId: String): List<BalanceAnchorEntity>
    @Query("SELECT * FROM balance_anchor")
    suspend fun allIncludingDeleted(): List<BalanceAnchorEntity>
}

@Dao
interface CategoryDao {
    @Upsert suspend fun upsert(c: CategoryEntity)
    @Upsert suspend fun upsertAll(list: List<CategoryEntity>)
    @Query("SELECT * FROM category WHERE deleted = 0 AND archived = 0 ORDER BY kind, sortOrder, name")
    fun observeAll(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM category WHERE deleted = 0 AND archived = 0 ORDER BY kind, sortOrder, name")
    suspend fun all(): List<CategoryEntity>
    @Query("SELECT * FROM category WHERE deleted = 0 AND kind = :kind AND parentId IS NULL AND archived = 0 ORDER BY sortOrder, name")
    suspend fun topLevel(kind: String): List<CategoryEntity>
    @Query("SELECT * FROM category WHERE deleted = 0 AND parentId = :parentId ORDER BY sortOrder, name")
    suspend fun children(parentId: String): List<CategoryEntity>
    @Query("SELECT * FROM category WHERE deleted = 0 AND kind = :kind AND name = :name AND parentId IS NULL LIMIT 1")
    suspend fun topLevelByName(kind: String, name: String): CategoryEntity?
    @Query("SELECT * FROM category WHERE deleted = 0 AND parentId = :parentId AND name = :name LIMIT 1")
    suspend fun childByName(parentId: String, name: String): CategoryEntity?
    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int
    @Query("SELECT * FROM category")
    suspend fun allIncludingDeleted(): List<CategoryEntity>
}

@Dao
interface TagDao {
    @Upsert suspend fun upsert(t: TagEntity)
    @Query("SELECT * FROM tag WHERE deleted = 0 ORDER BY name")
    fun observeAll(): Flow<List<TagEntity>>
    @Query("SELECT * FROM tag WHERE deleted = 0 ORDER BY name")
    suspend fun all(): List<TagEntity>
    @Query("SELECT * FROM tag WHERE deleted = 0 AND name = :name LIMIT 1")
    suspend fun byName(name: String): TagEntity?
    @Query("SELECT * FROM tag")
    suspend fun allIncludingDeleted(): List<TagEntity>
}

/** 供余额推导使用的轻量投影。 */
data class TxnBalanceRow(
    val id: String,
    val type: TxnType,
    val amount: Long,
    val fee: Long,
    val coupon: Long,
    val assetId: String?,
    val toAssetId: String?,
    val occurredAt: Long,
    val excludeFromStats: Boolean,
)

@Dao
interface TxnDao {
    @Upsert suspend fun upsert(t: TxnEntity)
    @Upsert suspend fun upsertAll(list: List<TxnEntity>)
    @Update suspend fun update(t: TxnEntity)

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun byId(id: String): TxnEntity?

    @Query("SELECT * FROM txn WHERE deleted = 0 ORDER BY occurredAt DESC, createdAt DESC")
    fun observeRecent(): Flow<List<TxnEntity>>

    @Query("SELECT * FROM txn WHERE deleted = 0 AND occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt DESC, createdAt DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<TxnEntity>>

    @Query("SELECT * FROM txn WHERE deleted = 0 AND bookId = :bookId ORDER BY occurredAt DESC, createdAt DESC")
    fun observeByBook(bookId: String): Flow<List<TxnEntity>>

    @Query("SELECT * FROM txn WHERE deleted = 0 ORDER BY occurredAt DESC, createdAt DESC")
    suspend fun all(): List<TxnEntity>

    @Query("SELECT * FROM txn")
    suspend fun allIncludingDeleted(): List<TxnEntity>

    @Query("SELECT * FROM txn WHERE externalId = :externalId LIMIT 1")
    suspend fun byExternalId(externalId: String): TxnEntity?

    @Query("SELECT * FROM txn WHERE sourceFingerprint = :fp LIMIT 1")
    suspend fun byFingerprint(fp: String): TxnEntity?

    @Query("SELECT id, type, amount, fee, coupon, assetId, toAssetId, occurredAt, excludeFromStats FROM txn WHERE deleted = 0")
    suspend fun balanceRows(): List<TxnBalanceRow>

    @Query("SELECT id, type, amount, fee, coupon, assetId, toAssetId, occurredAt, excludeFromStats FROM txn WHERE deleted = 0")
    fun observeBalanceRows(): Flow<List<TxnBalanceRow>>

    @Query("SELECT COUNT(*) FROM txn WHERE deleted = 0")
    suspend fun count(): Int

    @Query(
        "SELECT * FROM txn WHERE deleted = 0 AND (note LIKE '%' || :q || '%' OR merchant LIKE '%' || :q || '%') " +
            "ORDER BY occurredAt DESC LIMIT 200"
    )
    suspend fun search(q: String): List<TxnEntity>

    @Query("DELETE FROM txn WHERE id = :id")
    suspend fun hardDelete(id: String)
}

@Dao
interface PendingBillDao {
    @Query("SELECT * FROM pending_bill WHERE status IN ('PENDING','DUPLICATE')") suspend fun allPending(): List<PendingBillEntity>

    @Upsert suspend fun upsert(p: PendingBillEntity)
    @Query("SELECT * FROM pending_bill WHERE status = 'PENDING' ORDER BY occurredAt DESC")
    fun observePending(): Flow<List<PendingBillEntity>>
    @Query("SELECT * FROM pending_bill WHERE status = 'PENDING' ORDER BY occurredAt DESC")
    suspend fun pending(): List<PendingBillEntity>
    @Query("SELECT * FROM pending_bill WHERE id = :id")
    suspend fun byId(id: String): PendingBillEntity?
    @Query("SELECT * FROM pending_bill WHERE fingerprint = :fp LIMIT 1")
    suspend fun byFingerprint(fp: String): PendingBillEntity?
    @Query("SELECT * FROM pending_bill WHERE sourcePackage = :sourcePackage AND amount = :amount AND occurredAt = :occurredAt")
    suspend fun receiptCandidates(sourcePackage: String, amount: Long, occurredAt: Long): List<PendingBillEntity>
    @Query("SELECT COUNT(*) FROM pending_bill WHERE fingerprint = :fp")
    suspend fun countFingerprint(fp: String): Int
    @Query("SELECT * FROM pending_bill ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PendingBillEntity>
    @Query("SELECT * FROM pending_bill")
    suspend fun all(): List<PendingBillEntity>
}

@Dao
interface AutoBillLogDao {
    @Query("DELETE FROM auto_bill_log WHERE id NOT IN (SELECT id FROM auto_bill_log ORDER BY timeMs DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(l: AutoBillLogEntity)
    @Query("SELECT * FROM auto_bill_log ORDER BY timeMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AutoBillLogEntity>>
    @Query("SELECT * FROM auto_bill_log ORDER BY timeMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AutoBillLogEntity>
    @Query("DELETE FROM auto_bill_log WHERE timeMs < :before")
    suspend fun trimBefore(before: Long)
}

@Dao
interface MerchantRuleDao {
    @Upsert suspend fun upsert(r: MerchantRuleEntity)
    @Query("SELECT * FROM merchant_rule WHERE pattern = :pattern LIMIT 1")
    suspend fun byPattern(pattern: String): MerchantRuleEntity?
    @Query("SELECT * FROM merchant_rule ORDER BY hits DESC")
    suspend fun all(): List<MerchantRuleEntity>
    @Query("SELECT * FROM merchant_rule ORDER BY hits DESC")
    fun observeAll(): Flow<List<MerchantRuleEntity>>
    @Query("SELECT * FROM merchant_rule WHERE pattern = :pattern OR :text LIKE '%' || pattern || '%' ORDER BY hits DESC LIMIT 1")
    suspend fun bestMatch(text: String, pattern: String): MerchantRuleEntity?
}

@Dao
interface SyncDao {
    @Query("SELECT MAX(seq) FROM sync_op WHERE deviceId = :deviceId") fun observeOwnMaxSeq(deviceId: String): Flow<Long?>
    @Query("SELECT MAX(seq) FROM sync_op WHERE deviceId = :deviceId") suspend fun maxSeqFor(deviceId: String): Long?
    @Query("SELECT MAX(hlc) FROM sync_op") suspend fun maxClock(): Long?

    @Upsert suspend fun upsertOp(op: SyncOpEntity)
    @Upsert suspend fun upsertOps(ops: List<SyncOpEntity>)
    @Query("SELECT * FROM sync_op WHERE opId = :opId")
    suspend fun opById(opId: String): SyncOpEntity?
    @Query("SELECT * FROM sync_op WHERE deviceId != :deviceId AND seq > :sinceSeq ORDER BY seq ASC LIMIT :limit")
    suspend fun opsFromOtherDevices(deviceId: String, sinceSeq: Long, limit: Int): List<SyncOpEntity>
    @Query("SELECT * FROM sync_op ORDER BY seq ASC")
    suspend fun allOps(): List<SyncOpEntity>
    @Query("SELECT COUNT(*) FROM sync_op")
    suspend fun opCount(): Int
    @Query("SELECT MAX(seq) FROM sync_op")
    suspend fun maxSeq(): Long?

    @Upsert suspend fun putState(s: SyncStateEntity)
    @Query("SELECT value FROM sync_state WHERE key = :key")
    suspend fun getState(key: String): String?
    @Query("SELECT * FROM sync_state")
    suspend fun allState(): List<SyncStateEntity>
}

@Dao
interface DeviceDao {
    @Upsert suspend fun upsert(device: DeviceEntity)
    @Query("SELECT * FROM device WHERE id = :id") suspend fun byId(id: String): DeviceEntity?
    @Query("SELECT * FROM device WHERE deleted = 0 ORDER BY createdAt") fun observeAll(): Flow<List<DeviceEntity>>
    @Query("SELECT * FROM device") suspend fun all(): List<DeviceEntity>
}
