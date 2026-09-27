package com.family.ledger.sync

import com.family.ledger.data.db.entity.TxnEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [HomeServerEngine] 单测：两台手机各自有一批 op，通过假 home server 中转后
 * **两边数据收敛一致**；重复同步幂等；应用远端 op 后本机 journal 不增长（不回环）；
 * 增量上传；服务端文件被删后自动全量重传；服务器不可达时给中文原因。
 */
class HomeServerEngineTest {

    private lateinit var server: FakeHomeServer

    @Before
    fun setUp() {
        server = FakeHomeServer(familyId = FAMILY).start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    /** 一台「手机」：自己的 store + engine，共用同一个服务器。 */
    private inner class Peer(val deviceId: String) {
        val store = MemoryStore()
        val state = MemoryState()
        val merge = SyncEngine(store)
        val transport = HomeServerTransport(server.baseUrl, FAMILY, "", maxAttempts = 2, retryDelayMs = 20)
        val engine = HomeServerEngine(
            transport = transport,
            merge = merge,
            myDeviceId = deviceId,
            state = state,
            localOps = { store.ops.values.filter { it.deviceId == deviceId }.sortedBy { it.seq } },
            knownPeers = { emptyList() },
        )

        /** 模拟本机记了一笔账：写实体 + 记 oplog（和 repository 的行为一致）。 */
        suspend fun record(seq: Long, hlc: Long, txn: TxnEntity) {
            store.putOp(testOp(deviceId, seq, hlc, txn))
            store.upsertEntity(txn)
        }

        fun txn(id: String): TxnEntity? = store.entities["txn/$id"] as TxnEntity?

        fun myOpCount(): Int = store.ops.values.count { it.deviceId == deviceId }
    }

    @Test
    fun `两台设备通过服务器中转后数据收敛一致`() = runBlocking {
        val a = Peer("dev-a")
        val b = Peer("dev-b")
        a.record(1, 100, testTxn("t-a", "A记的", 100, device = "dev-a"))
        b.record(1, 100, testTxn("t-b", "B记的", 100, device = "dev-b"))

        val first = a.engine.sync()
        assertTrue(first.serverOk)
        assertEquals(1, first.pushed)
        assertEquals(0, first.pulled)

        val second = b.engine.sync()
        assertEquals("B 应拉到 A 的账", 1, second.pulled)
        assertEquals("B 应把自己的账推上去", 1, second.pushed)

        val third = a.engine.sync()
        assertEquals("A 应拉到 B 的账", 1, third.pulled)

        assertEquals("A记的", a.txn("t-a")?.note)
        assertEquals("B记的", a.txn("t-b")?.note)
        assertEquals("B记的", b.txn("t-b")?.note)
        assertEquals("A记的", b.txn("t-a")?.note)
        assertEquals("两端实体应完全一致", a.store.entities, b.store.entities)
    }

    @Test
    fun `重复同步幂等且不会重复写服务器`() = runBlocking {
        val a = Peer("dev-a")
        val b = Peer("dev-b")
        a.record(1, 100, testTxn("t-a", "A记的", 100, device = "dev-a"))
        b.record(1, 100, testTxn("t-b", "B记的", 100, device = "dev-b"))
        a.engine.sync()
        b.engine.sync()
        a.engine.sync()

        val linesBefore = server.lineCount("dev-a") + server.lineCount("dev-b")
        val again = a.engine.sync()

        assertEquals(0, again.pulled)
        assertEquals(0, again.pushed)
        assertEquals("服务器上的日志不应再增长", linesBefore, server.lineCount("dev-a") + server.lineCount("dev-b"))
    }

    @Test
    fun `应用远端op后本机journal不增长也不回环`() = runBlocking {
        val b = Peer("dev-b")
        b.record(1, 500, testTxn("t-b", "B记的", 500, device = "dev-b"))
        b.engine.sync()

        val a = Peer("dev-a")
        val result = a.engine.sync()

        assertEquals(1, result.pulled)
        assertEquals("B记的", a.txn("t-b")?.note)
        assertEquals("应用远端 op 不能产生本机 op", 0, a.myOpCount())
        assertEquals("只应写库一次", 1, a.store.upserts)
    }

    @Test
    fun `再次同步只推送新增的op`() = runBlocking {
        val a = Peer("dev-a")
        a.record(1, 100, testTxn("t-1", "第一笔", 100, device = "dev-a"))
        assertEquals(1, a.engine.sync().pushed)
        assertEquals(1, server.lineCount("dev-a"))

        a.record(2, 200, testTxn("t-2", "第二笔", 200, device = "dev-a"))
        val result = a.engine.sync()

        assertEquals("只应推送新增的那一条", 1, result.pushed)
        assertEquals(2, server.lineCount("dev-a"))

        val repeat = a.engine.sync()
        assertEquals("没有新数据时不应再推送", 0, repeat.pushed)
        assertEquals(2, server.lineCount("dev-a"))
    }

    @Test
    fun `服务端文件被删后自动全量重传`() = runBlocking {
        val a = Peer("dev-a")
        a.record(1, 100, testTxn("t-1", "第一笔", 100, device = "dev-a"))
        a.record(2, 200, testTxn("t-2", "第二笔", 200, device = "dev-a"))
        assertEquals(2, a.engine.sync().pushed)
        assertEquals(2, server.lineCount("dev-a"))

        server.clearOps("dev-a")
        a.record(3, 300, testTxn("t-3", "第三笔", 300, device = "dev-a"))

        val result = a.engine.sync()

        assertEquals("服务端行数比本地缓存少，应从头全量重传", 3, result.pushed)
        assertEquals(3, server.lineCount("dev-a"))
    }

    @Test
    fun `服务端有重复行时只合并一次`() = runBlocking {
        val b = Peer("dev-b")
        b.record(1, 300, testTxn("t-b", "B记的", 300, device = "dev-b"))
        val journal = SyncEngine.journalText(b.store.ops.values.toList())
        server.raw("POST", HomeServerProtocol.PATH_OPS, body = journal)
        server.raw("POST", HomeServerProtocol.PATH_OPS, body = journal)
        assertEquals(2, server.lineCount("dev-b"))

        val a = Peer("dev-a")
        val result = a.engine.sync()

        assertEquals("重复行只应合并生效一次", 1, result.pulled)
        assertEquals(1, a.store.upserts)
        assertEquals("B记的", a.txn("t-b")?.note)
    }

    @Test
    fun `服务器不可达时返回中文原因且不写库`() = runBlocking {
        val dead = HomeServerTransport("http://127.0.0.1:1", FAMILY, "", maxAttempts = 1)
        val store = MemoryStore()
        val engine = HomeServerEngine(dead, SyncEngine(store), "dev-a", MemoryState())

        val result = engine.sync()

        assertFalse(result.serverOk)
        assertFalse(result.message.isBlank())
        assertTrue("不应写任何数据", store.ops.isEmpty())
        assertTrue(store.entities.isEmpty())
    }

    @Test
    fun `同步成功后记录状态供界面与下次选路使用`() = runBlocking {
        val a = Peer("dev-a")
        a.record(1, 100, testTxn("t-1", "第一笔", 100, device = "dev-a"))

        a.engine.sync()

        assertEquals(server.baseUrl, a.state.map[ServerEndpoint.STATE_LAST_GOOD_URL])
        assertTrue((a.state.map[ServerEndpoint.STATE_LAST_SYNC_AT]?.toLongOrNull() ?: 0L) > 0L)
        assertEquals("1", a.state.map[HomeServerEngine.uploadKey(server.baseUrl)])
    }

    private companion object {
        const val FAMILY = "FAMILY01"
    }
}
