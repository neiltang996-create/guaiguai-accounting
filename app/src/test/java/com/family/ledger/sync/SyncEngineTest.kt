package com.family.ledger.sync

import com.family.ledger.data.db.entity.CategoryEntity
import com.family.ledger.data.db.entity.FamilyEntity
import com.family.ledger.data.db.entity.SyncOpEntity
import com.family.ledger.data.db.entity.TxnEntity
import com.family.ledger.data.db.entity.TxnType
import com.family.ledger.data.sync.EntityCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SyncEngine（LWW 合并）纯逻辑单测。
 *
 * 覆盖：新 hlc 覆盖旧 hlc / 相同 hlc 用 deviceId 决胜 / 旧 op 不覆盖新值 /
 * 重复应用幂等 / 应用远端 op 不产生新的本地 op（不回环）/ 游标去重 / 两端收敛。
 */
class SyncEngineTest {

    private companion object {
        const val DEV_A = "dev-a"
        const val DEV_B = "dev-b"
        const val TXN_ID = "t-1"
    }

    /** 内存版 store：SyncEngine 不依赖 Android，所以这里就能完整验证合并逻辑。 */
    private class FakeStore : SyncEntityStore {
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

        fun entity(id: String): TxnEntity = entities["txn/$id"] as TxnEntity
    }

    private fun txn(note: String, updatedAt: Long, id: String = TXN_ID, amount: Long = 1000L): TxnEntity =
        TxnEntity(
            id = id,
            bookId = "b-1",
            type = TxnType.EXPENSE,
            amount = amount,
            occurredAt = updatedAt,
            note = note,
            createdByDeviceId = DEV_A,
            createdAt = updatedAt,
            updatedAt = updatedAt,
        )

    private fun op(
        device: String,
        seq: Long,
        hlc: Long,
        entity: Any,
    ): SyncOpEntity = SyncOpEntity(
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

    private fun ownOp(seq: Long, hlc: Long, entity: Any) = op(DEV_A, seq, hlc, entity)

    // ------------------------------------------------------------ mergeOp

    @Test
    fun `新hlc覆盖旧hlc`() {
        val engine = SyncEngine(FakeStore())
        val old = ownOp(1, 100, txn("旧", 100))
        val new = op(DEV_B, 1, 200, txn("新", 200))

        assertTrue(engine.mergeOp(old, new))
        assertFalse(engine.mergeOp(new, old))
        assertTrue("本地没有该实体时应接受远端 op", engine.mergeOp(null, new))
    }

    @Test
    fun `hlc相同用deviceId字典序决胜`() {
        val engine = SyncEngine(FakeStore())
        val a = ownOp(1, 500, txn("A", 500))
        val b = op(DEV_B, 1, 500, txn("B", 500))

        assertTrue("dev-b > dev-a，远端应获胜", engine.mergeOp(a, b))
        assertFalse("dev-b > dev-a，本机版本应判负", engine.mergeOp(b, a))
    }

    // ------------------------------------------------------------ applyOp

    @Test
    fun `旧op不覆盖新值`() = runBlocking {
        val store = FakeStore()
        val engine = SyncEngine(store)

        assertTrue(engine.applyOp(op(DEV_B, 2, 300, txn("新值", 300))))
        assertFalse(engine.applyOp(op(DEV_B, 1, 100, txn("旧值", 100))))
        assertEquals("新值", store.entity(TXN_ID).note)
        assertEquals(1, store.upserts)
    }

    @Test
    fun `同一条op重复应用幂等`() = runBlocking {
        val store = FakeStore()
        val engine = SyncEngine(store)
        val incoming = op(DEV_B, 1, 200, txn("对方记的", 200))

        assertTrue(engine.applyOp(incoming))
        assertFalse(engine.applyOp(incoming))
        assertFalse(engine.applyOp(incoming))
        assertEquals("对方记的", store.entity(TXN_ID).note)
        assertEquals("只应写库一次", 1, store.upserts)
    }

    @Test
    fun `应用远端op不会产生新的本地op`() = runBlocking {
        val store = FakeStore()
        val engine = SyncEngine(store)

        engine.applyOp(op(DEV_B, 7, 200, txn("对方的账", 200)))

        // sync_op 表里只有对方那一条；按 deviceId 过滤后本机没有任何可上传的 op ⇒ 不会回环
        assertEquals(1, store.ops.size)
        assertEquals(DEV_B, store.ops.values.first().deviceId)
        assertTrue("本机不应产生新 op", store.ops.values.none { it.deviceId == DEV_A })
        assertTrue(store.ops.values.filter { it.deviceId == DEV_A }.isEmpty())
    }

    @Test
    fun `本机有更新版本时远端旧op不生效`() = runBlocking {
        val store = FakeStore()
        // 本机刚改过（hlc 400），且本机 op 已在日志里
        val mine = ownOp(5, 400, txn("本机新值", 400))
        store.putOp(mine)
        store.entities["txn/$TXN_ID"] = txn("本机新值", 400)

        val engine = SyncEngine(store)
        assertFalse(engine.applyOp(op(DEV_B, 1, 200, txn("对方旧值", 200))))
        assertEquals("本机新值", store.entity(TXN_ID).note)
    }

    @Test
    fun `无op记录但本地实体更新时不被远端旧载荷覆盖`() = runBlocking {
        val store = FakeStore()
        store.entities["txn/$TXN_ID"] = txn("本机(无op)", 900)

        val engine = SyncEngine(store)
        assertFalse(engine.applyOp(op(DEV_B, 1, 500, txn("远端旧载荷", 100))))
        assertEquals("本机(无op)", store.entity(TXN_ID).note)
    }

    @Test
    fun `未知表被忽略且不写库`() = runBlocking {
        val store = FakeStore()
        val engine = SyncEngine(store)
        val weird = op(DEV_B, 1, 100, txn("x", 100)).copy(entityTable = "no_such_table")

        assertFalse(engine.applyOp(weird))
        assertEquals(0, store.upserts)
    }

    // ------------------------------------------------------------ applyOps

    @Test
    fun `applyOps按seq升序应用并用游标去重`() = runBlocking {
        val store = FakeStore()
        val engine = SyncEngine(store)
        val batch = listOf(
            op(DEV_B, 1, 100, txn("第一版", 100)),
            op(DEV_B, 2, 300, txn("第二版", 300)),
        )

        assertEquals(2, engine.applyOps(batch))
        assertEquals("第二版", store.entity(TXN_ID).note)
        assertEquals(2L, store.cursors["remoteSeq:$DEV_B"])

        // 同一条日志再拉一次：游标已推进，不会重复应用
        assertEquals(0, engine.applyOps(batch))
        assertEquals(2L, store.cursors["remoteSeq:$DEV_B"])
    }

    @Test
    fun `applyOps乱序传入也按seq落地`() = runBlocking {
        val store = FakeStore()
        val engine = SyncEngine(store)

        engine.applyOps(
            listOf(
                op(DEV_B, 2, 300, txn("第二版", 300)),
                op(DEV_B, 1, 100, txn("第一版", 100)),
            )
        )

        assertEquals("第二版", store.entity(TXN_ID).note)
    }

    @Test
    fun `applyOps跳过游标之前的旧op`() = runBlocking {
        val store = FakeStore()
        store.cursors["remoteSeq:$DEV_B"] = 5L
        val engine = SyncEngine(store)

        val applied = engine.applyOps(
            listOf(
                op(DEV_B, 3, 100, txn("早已同步过", 100)),
                op(DEV_B, 6, 200, txn("新的", 200)),
            )
        )

        assertEquals(1, applied)
        assertEquals("新的", store.entity(TXN_ID).note)
        assertEquals(6L, store.cursors["remoteSeq:$DEV_B"])
    }

    // ------------------------------------------------------------ 收敛性

    @Test
    fun `相同hlc冲突两端收敛到同一份数据`() = runBlocking {
        val storeA = FakeStore()
        val storeB = FakeStore()
        val engineA = SyncEngine(storeA)
        val engineB = SyncEngine(storeB)

        val aOp = ownOp(1, 500, txn("A 记的", 500))
        val bOp = op(DEV_B, 1, 500, txn("B 记的", 500))
        storeA.putOp(aOp)
        storeA.entities["txn/$TXN_ID"] = txn("A 记的", 500)
        storeB.putOp(bOp)
        storeB.entities["txn/$TXN_ID"] = txn("B 记的", 500)

        engineA.applyOps(listOf(bOp))
        engineB.applyOps(listOf(aOp))

        assertEquals("B 记的", storeA.entity(TXN_ID).note)
        assertEquals("B 记的", storeB.entity(TXN_ID).note)
    }

    @Test
    fun `远端新op覆盖本机旧值并两端一致`() = runBlocking {
        val storeA = FakeStore()
        val storeB = FakeStore()
        val engineA = SyncEngine(storeA)
        val engineB = SyncEngine(storeB)

        val aOp = ownOp(1, 100, txn("A 早先记的", 100))
        storeA.putOp(aOp)
        storeA.entities["txn/$TXN_ID"] = txn("A 早先记的", 100)

        val bOp = op(DEV_B, 9, 900, txn("B 后记的", 900))
        storeB.putOp(bOp)
        storeB.entities["txn/$TXN_ID"] = txn("B 后记的", 900)

        assertEquals(1, engineA.applyOps(listOf(bOp)))
        assertEquals(0, engineB.applyOps(listOf(aOp)))
        assertEquals("B 后记的", storeA.entity(TXN_ID).note)
        assertEquals("B 后记的", storeB.entity(TXN_ID).note)
    }

    // ------------------------------------------------------------ 序列化

    @Test
    fun `parseJournal损坏时拒绝整批防止推进游标丢账`() {
        val engine = SyncEngine(FakeStore())
        val good = op(DEV_B, 1, 100, txn("好行", 100))
        val text = listOf(
            engine.toJournal(listOf(good)),
            "",
            "   ",
            "{不是 JSON",
            """{"opId":"x"}""",
        ).joinToString("\n")

        assertTrue(runCatching { engine.parseJournal(text) }.isFailure)
    }

    @Test
    fun `journalText与parseJournal可往返且保留所有字段`() {
        val engine = SyncEngine(FakeStore())
        val original = op(DEV_B, 42, 1712345678901L, txn("往返", 1712345678901L, amount = 12345L))

        val parsed = engine.parseJournal(engine.toJournal(listOf(original)))

        assertEquals(1, parsed.size)
        assertEquals(original, parsed.first())
        val decoded = EntityCodec.decode(parsed.first().entityTable, parsed.first().payloadJson) as TxnEntity
        assertEquals(12345L, decoded.amount)
        assertEquals("往返", decoded.note)
    }

    @Test
    fun `localVersion返回本地该实体的最高版本op`() = runBlocking {
        val store = FakeStore()
        val engine = SyncEngine(store)
        store.putOp(ownOp(1, 100, txn("旧", 100)))
        store.putOp(op(DEV_B, 1, 300, txn("新", 300)))

        assertEquals("$DEV_B:1", engine.localVersion("txn", TXN_ID)?.opId)
        assertNull(engine.localVersion("txn", "不存在"))
    }

    @Test
    fun `分类等其它表也能同步`() = runBlocking {
        val store = FakeStore()
        val engine = SyncEngine(store)
        val category = CategoryEntity(id = "c-1", name = "餐饮", kind = "EXPENSE", updatedAt = 700)

        assertTrue(engine.applyOp(op(DEV_B, 1, 700, category)))
        val saved = store.entities["category/c-1"] as CategoryEntity
        assertEquals("餐饮", saved.name)
    }

    @Test
    fun `family墓碑照常写入且只按id覆盖不新建行`() = runBlocking {
        val store = FakeStore()
        val engine = SyncEngine(store)
        val family = FamilyEntity(id = "fam-1", name = "我们家", createdAt = 1, updatedAt = 100)

        assertTrue(engine.applyOp(op(DEV_B, 1, 100, family)))
        // 配对后旧家庭被打墓碑同步过来：必须照常落库，否则旧家庭会在对方手机上复活
        val tombstone = family.copy(deleted = true, updatedAt = 200)
        assertTrue(engine.applyOp(op(DEV_B, 2, 200, tombstone)))

        val saved = store.entities["family/fam-1"] as FamilyEntity
        assertTrue(saved.deleted)
        assertEquals("fam-1", saved.id)
        assertEquals("只按 id 覆盖，不新建家庭行", 1, store.entities.size)
    }
}
