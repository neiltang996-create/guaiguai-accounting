package com.family.ledger.sync

import com.family.ledger.data.db.entity.SyncOpEntity
import com.family.ledger.data.sync.EntityCodec
import kotlinx.serialization.json.Json

/**
 * 实体落库抽象。
 *
 * SyncEngine 只依赖这个接口，所以合并 / 应用逻辑是**纯 Kotlin**（不碰 Android、不碰 Room），
 * 单测里换成内存实现即可（见 `SyncEngineTest`）。
 *
 * 注意接口里刻意**没有** [com.family.ledger.data.sync.SyncJournal]：
 * 应用远端 op 时绝不能再记本地 oplog，否则两端会把对方的 op 再记一遍，
 * 对方的日志被自己下载后又被推回去 —— 无限回环。这里只做 DAO 级 upsert。
 */
interface SyncEntityStore {
    suspend fun <T> atomic(block: suspend () -> T): T = block()


    /** 本机已知的全部 op（含从对方下载并留存的 op）。 */
    suspend fun localOps(): List<SyncOpEntity>

    /** 保存一条 op（仅写 sync_op 表，不占用本地 seq、不写 oplog）。 */
    suspend fun putOp(op: SyncOpEntity)

    /** 读取本地实体当前值（找不到返回 null）。 */
    suspend fun existingEntity(table: String, id: String): Any?

    /** 按实体类型分发到对应 DAO 的 upsert。 */
    suspend fun upsertEntity(entity: Any)

    /** 读游标（不存在返回 0）。 */
    suspend fun cursor(key: String): Long

    suspend fun putCursor(key: String, value: Long)
}

/**
 * oplog 合并引擎（last-writer-wins）。
 *
 * 版本时钟用 [SyncOpEntity.hlc]：谁的 hlc 大谁赢；hlc 相同用 deviceId 字典序决胜
 * （两边规则一致 ⇒ 最终收敛到同一份数据）；deviceId 也相同则 seq 大者赢
 * （同一设备同一毫秒记了两笔，重放安全）。
 *
 * 幂等：同一条 op 应用多少次，结果都一样，第二次起直接跳过。
 */
class SyncEngine(
    private val store: SyncEntityStore,
    private val json: Json = EntityCodec.json,
) {

    private var index: MutableMap<String, SyncOpEntity>? = null

    /** 是否需要（从存储重新）加载 op 索引。 */
    suspend fun refresh() {
        index = null
        opIndex()
    }

    /** 当前本地某实体的版本 op（用于诊断 / 测试）。 */
    suspend fun localVersion(table: String, id: String): SyncOpEntity? = opIndex()[keyOf(table, id)]

    /** incoming 是否比 local 新（local 为 null 表示本地没有这个实体的记录）。 */
    fun mergeOp(local: SyncOpEntity?, incoming: SyncOpEntity): Boolean =
        local == null || compareVersion(incoming, local) > 0

    /**
     * 应用一条（可能是远端的）op。
     * @return true 表示实体被更新；false 表示被 LWW 判负、已应用过、或数据不可解析。
     */
    suspend fun applyOp(incoming: SyncOpEntity): Boolean {
        if (EntityCodec.SYNCED_TABLES.none { it == incoming.entityTable }) return false
        val idx = opIndex()
        val key = keyOf(incoming.entityTable, incoming.entityId)
        val local = idx[key]
        // 已经见过同一条 op：幂等返回，避免重复写库
        if (local != null && local.opId == incoming.opId) return false

        val entity = runCatching { EntityCodec.decode(incoming.entityTable, incoming.payloadJson) }.getOrNull()
            ?: return false

        require(EntityCodec.idOf(entity) == incoming.entityId) { "同步实体 ID 与操作不一致" }
        val wins = mergeOp(local, incoming) && passesEntityGuard(local, incoming, entity)
        if (wins) store.upsertEntity(entity)

        // op 一律留痕：本地没有任何 op 记录时也要记住对方版本，后续比较才有依据。
        // 这里只是 sync_op 表 upsert，不会产生新的本地 seq，所以不会回环。
        store.putOp(incoming)
        if (local == null || compareVersion(incoming, local) > 0) idx[key] = incoming
        return wins
    }

    /**
     * 批量应用一个设备日志里的 op。
     *
     * 用 `sync_state` 里的 `remoteSeq:<deviceId>` 游标跳过已处理的 op；
     * 同一批内按 seq 升序应用，保证同一实体的多次修改按顺序落地。
     *
     * @return 实际更新了多少个实体
     */
    suspend fun applyOps(ops: List<SyncOpEntity>): Int = store.atomic {
        if (ops.isEmpty()) return@atomic 0
        refresh()
        var applied = 0
        for ((deviceId, list) in ops.groupBy { it.deviceId }) {
            val key = cursorKey(deviceId)
            val since = store.cursor(key)
            val fresh = list.filter { it.deviceId == deviceId && it.seq > since }.sortedBy { it.seq }
            // 整批预校验，坏行或未知版本不能推动游标而永久漏掉一笔账。
            fresh.forEach { op ->
                require(op.entityTable in EntityCodec.SYNCED_TABLES) { "发现未知同步表，请升级两台 App" }
                val entity = EntityCodec.decode(op.entityTable, op.payloadJson)
                require(EntityCodec.idOf(entity) == op.entityId)
            }
            for (op in fresh) if (applyOp(op)) applied++
            val maxSeq = list.maxOfOrNull { it.seq } ?: since
            if (maxSeq > since) store.putCursor(key, maxSeq)
        }
        applied
    }

    /** 解析设备日志（JSON Lines）。坏行跳过，不让一条脏数据毁掉整次同步。 */
    fun parseJournal(text: String): List<SyncOpEntity> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> json.decodeFromString(SyncOpEntity.serializer(), line) }
            .toList()

    /** 生成设备日志内容（一行一个 op）。 */
    fun toJournal(ops: List<SyncOpEntity>): String = journalText(ops, json)

    /**
     * 护栏：本地没有任何 op 记录（例如同步功能上线前写入的旧数据）时，
     * 若本地实体的 updatedAt 比远端载荷更新，就不要用远端旧值覆盖它。
     */
    private suspend fun passesEntityGuard(local: SyncOpEntity?, incoming: SyncOpEntity, entity: Any): Boolean {
        if (local != null) return true
        val existing = runCatching { store.existingEntity(incoming.entityTable, incoming.entityId) }.getOrNull()
            ?: return true
        val existingAt = runCatching { EntityCodec.updatedAtOf(existing) }.getOrDefault(0L)
        val incomingAt = runCatching { EntityCodec.updatedAtOf(entity) }.getOrDefault(0L)
        return existingAt <= incomingAt
    }

    private suspend fun opIndex(): MutableMap<String, SyncOpEntity> {
        index?.let { return it }
        val map = HashMap<String, SyncOpEntity>()
        for (op in store.localOps()) {
            val k = keyOf(op.entityTable, op.entityId)
            val cur = map[k]
            if (cur == null || compareVersion(op, cur) > 0) map[k] = op
        }
        index = map
        return map
    }

    companion object {
        fun keyOf(table: String, id: String): String = "$table/$id"

        fun cursorKey(deviceId: String): String = "remoteSeq:$deviceId"

        /** 把 op 列表编码成设备日志（JSON Lines）。 */
        fun journalText(ops: List<SyncOpEntity>, json: Json = EntityCodec.json): String =
            ops.joinToString(separator = "\n") { json.encodeToString(SyncOpEntity.serializer(), it) }

        /** 版本比较：hlc → deviceId → seq。 */
        fun compareVersion(a: SyncOpEntity, b: SyncOpEntity): Int {
            if (a.hlc != b.hlc) return a.hlc.compareTo(b.hlc)
            if (a.deviceId != b.deviceId) return a.deviceId.compareTo(b.deviceId)
            return a.seq.compareTo(b.seq)
        }
    }
}
