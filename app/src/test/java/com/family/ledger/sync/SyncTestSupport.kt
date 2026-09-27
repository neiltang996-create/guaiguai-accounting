package com.family.ledger.sync

import com.family.ledger.data.db.entity.SyncOpEntity
import com.family.ledger.data.db.entity.TxnEntity
import com.family.ledger.data.db.entity.TxnType
import com.family.ledger.data.sync.EntityCodec

/**
 * 测试公用小工具：内存版 [SyncEntityStore] / [HomeSyncState]，以及构造 op 的快捷方法。
 * 这样「拉取 → 合并 → 推送」整条链路都能在 JVM 上验证，不需要 Room、不需要 Android。
 */

/** 内存版实体存储（对应 [RoomSyncEntityStore]）。 */
class MemoryStore : SyncEntityStore {

    val entities = HashMap<String, Any>()
    val ops = LinkedHashMap<String, SyncOpEntity>()
    val cursors = HashMap<String, Long>()

    var upserts = 0
        private set

    override suspend fun localOps(): List<SyncOpEntity> = ops.values.toList()

    override suspend fun putOp(op: SyncOpEntity) {
        ops[op.opId] = op
    }

    override suspend fun existingEntity(table: String, id: String): Any? = entities["$table/$id"]

    override suspend fun upsertEntity(entity: Any) {
        upserts++
        entities[EntityCodec.tableOf(entity) + "/" + EntityCodec.idOf(entity)] = entity
    }

    override suspend fun cursor(key: String): Long = cursors[key] ?: 0L

    override suspend fun putCursor(key: String, value: Long) {
        cursors[key] = value
    }
}

/** 内存版 [HomeSyncState]（对应 `sync_state` 表）。 */
class MemoryState : HomeSyncState {
    val map = HashMap<String, String>()
    override suspend fun get(key: String): String? = map[key]
    override suspend fun put(key: String, value: String) {
        map[key] = value
    }
}

fun testTxn(
    id: String,
    note: String,
    updatedAt: Long,
    amount: Long = 1000L,
    device: String = "dev-a",
): TxnEntity = TxnEntity(
    id = id,
    bookId = "b-1",
    type = TxnType.EXPENSE,
    amount = amount,
    occurredAt = updatedAt,
    note = note,
    createdByDeviceId = device,
    createdAt = updatedAt,
    updatedAt = updatedAt,
)

fun testOp(device: String, seq: Long, hlc: Long, entity: Any): SyncOpEntity = SyncOpEntity(
    opId = "$device:$seq",
    deviceId = device,
    seq = seq,
    entityTable = EntityCodec.tableOf(entity),
    entityId = EntityCodec.idOf(entity),
    opType = "UPSERT",
    payloadJson = EntityCodec.encode(entity),
    hlc = hlc,
    createdAt = hlc,
)
