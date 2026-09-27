package com.family.ledger.data.sync

import androidx.room.withTransaction
import com.family.ledger.data.db.AppDatabase
import com.family.ledger.data.db.entity.SyncOpEntity

/** 序号与实体在同一 Room 事务内提交；远端时钟被观察后，本地新修改仍能胜出。 */
class SyncJournal(private val db: AppDatabase, private val deviceId: String) {
    suspend fun record(entity: Any, opType: String = OP_UPSERT, hlc: Long? = null) = db.withTransaction {
        val dao = db.syncDao()
        val seq = (dao.maxSeqFor(deviceId) ?: 0L) + 1L
        val clock = hlc ?: maxOf(System.currentTimeMillis(), (dao.maxClock() ?: 0L) + 1L)
        dao.upsertOp(SyncOpEntity("$deviceId:$seq", deviceId, seq, EntityCodec.tableOf(entity),
            EntityCodec.idOf(entity), opType, EntityCodec.encode(entity), clock, System.currentTimeMillis()))
    }
    suspend fun recordAll(entities: List<Any>, opType: String = OP_UPSERT) = db.withTransaction {
        entities.forEach { record(it, opType) }
    }
    suspend fun recordDelete(entity: Any) = record(entity, OP_DELETE)
    companion object {
        const val OP_UPSERT = "UPSERT"
        const val OP_DELETE = "DELETE"
    }
}
