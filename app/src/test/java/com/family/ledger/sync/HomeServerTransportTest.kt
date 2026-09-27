package com.family.ledger.sync

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [HomeServerTransport] 单测：用**真实 socket** 在本机起一个假 home server
 * （[FakeHomeServer]，行为与 lead 的 Python 服务端一致），验证：
 * health / devices / GET ops / POST ops / count / 403 / 401 / 404 / 405 / 5xx 自动重试 /
 * 地址不通与地址非法时**不抛异常、只给中文原因**。
 */
class HomeServerTransportTest {

    private lateinit var server: FakeHomeServer

    @Before
    fun setUp() {
        server = FakeHomeServer(familyId = FAMILY, token = TOKEN).start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun transport(
        family: String = FAMILY,
        token: String = TOKEN,
        url: String = server.baseUrl,
    ) = HomeServerTransport(url, family, token, maxAttempts = 2, retryDelayMs = 20)

    @Test fun redirectsNeverForwardPrivateHeadersOrBodies() = runBlocking {
        val destination = okhttp3.mockwebserver.MockWebServer()
        val redirect = okhttp3.mockwebserver.MockWebServer()
        destination.start(); redirect.start()
        try {
            redirect.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(307)
                .addHeader("Location", destination.url("/health")))
            val t = HomeServerTransport(redirect.url("/").toString(), FAMILY, TOKEN, maxAttempts=1)
            assertFalse(t.ping())
            assertEquals(0, destination.requestCount)
        } finally { redirect.shutdown(); destination.shutdown() }
    }

    @Test
    fun `health可达并返回协议版本`() = runBlocking {
        val t = transport()

        assertTrue(t.ping())
        val health = t.health()
        assertEquals("familyledger", health.app)
        assertEquals(1, health.v)
        assertNull("成功时不应留下错误原因", t.lastError)
    }

    @Test
    fun `POST与GET往返并按设备计数`() = runBlocking {
        val t = transport()
        assertEquals("没有日志的设备应返回空体", "", t.getOps("dev-b"))
        assertEquals(0, t.opsCount("dev-b"))

        val ops = listOf(
            testOp("dev-a", 1, 100, testTxn("t-1", "早饭", 100)),
            testOp("dev-a", 2, 200, testTxn("t-2", "午饭", 200)),
        )
        val posted = t.postOps(SyncEngine.journalText(ops))

        assertEquals(2, posted.applied)
        assertEquals(0, posted.bad)
        assertEquals(listOf("dev-a"), t.devices())
        assertEquals(2, t.opsCount("dev-a"))

        val parsed = SyncEngine(MemoryStore()).parseJournal(t.getOps("dev-a"))
        assertEquals(2, parsed.size)
        assertEquals(ops.toSet(), parsed.toSet())
    }

    @Test
    fun `坏行被服务端忽略并计入bad`() = runBlocking {
        val t = transport()
        val good = SyncEngine.journalText(listOf(testOp("dev-a", 1, 100, testTxn("t-1", "好行", 100))))

        val posted = t.postOps("这不是 JSON\n$good")

        assertEquals(1, posted.applied)
        assertEquals(1, posted.bad)
    }

    @Test
    fun `空日志不上传`() = runBlocking {
        val t = transport()
        val posted = t.postOps("")
        assertEquals(0, posted.applied)
        assertEquals(0, server.requestCount())
    }

    @Test
    fun `health与family只需token但ops缺家庭码给403`() = runBlocking {
        val t = transport(family = "")

        // /health 与 /family 只校验 token：客户端此刻还不知道对方的家庭码（零输入配对要用）
        assertTrue(t.ping())
        assertNull(t.getFamily())

        val get = runCatching { t.getOps("dev-a") }.exceptionOrNull()
        assertTrue("读 ops 应 403：${get?.message}", get is SyncException && get.message.orEmpty().contains("403"))
        val post = runCatching {
            t.postOps(SyncEngine.journalText(listOf(testOp("dev-a", 1, 100, testTxn("t-1", "x", 100)))))
        }.exceptionOrNull()
        assertTrue("写 ops 应 403：${post?.message}", post is SyncException && post.message.orEmpty().contains("403"))
    }

    @Test
    fun `family端点支持登记 重复 与冲突`() = runBlocking {
        val t = transport()

        assertNull("服务器上还没人登记", t.getFamily())

        val first = t.claimFamily(FAMILY)
        assertEquals(FAMILY, first.familyId)
        assertTrue(first.created)
        assertFalse(first.conflict)
        assertEquals(FAMILY, t.getFamily())

        val again = t.claimFamily(FAMILY)
        assertFalse("同一个家庭码不算新建", again.created)
        assertFalse(again.conflict)

        val other = t.claimFamily("OTHERFAM")
        assertTrue("服务器上已有别的家庭码时绝不覆盖", other.conflict)
        assertEquals(FAMILY, other.familyId)
    }

    @Test
    fun `token不对给401中文原因`() = runBlocking {
        val t = transport(token = "wrong-token")

        assertFalse(t.ping())
        assertTrue("lastError=${t.lastError}", t.lastError.orEmpty().contains("401"))
    }

    @Test
    fun `5xx会自动重试`() = runBlocking {
        server.failNextRequests = 1
        val t = transport()

        assertTrue("第一次 503，重试后应成功", t.ping())
        assertTrue("应该发生了重试", server.requestCount() >= 2)
    }

    @Test
    fun `地址不通时不抛异常只给中文原因`() = runBlocking {
        val t = transport(url = "http://127.0.0.1:1")

        assertFalse(t.ping())
        assertFalse("要有中文原因", t.lastError.isNullOrBlank())
    }

    @Test
    fun `地址格式非法时不抛异常`() = runBlocking {
        val t = HomeServerTransport("这不是一个地址", FAMILY, TOKEN)

        assertFalse(t.ping())
        assertTrue("lastError=${t.lastError}", t.lastError.orEmpty().contains("地址不正确"))
    }

    @Test
    fun `未知路径404与非GETPOST405与ops缺家庭码403`() {
        assertTrue(server.raw("GET", "/nope").startsWith("HTTP/1.1 404"))
        assertTrue(server.raw("DELETE", "/ops").startsWith("HTTP/1.1 405"))
        assertTrue(server.raw("GET", "/ops/dev-a", family = null).startsWith("HTTP/1.1 403"))
        assertTrue(server.raw("GET", HomeServerProtocol.PATH_HEALTH, family = null).startsWith("HTTP/1.1 200"))
        assertTrue(server.raw("GET", HomeServerProtocol.PATH_FAMILY, family = null).startsWith("HTTP/1.1 200"))
    }

    private companion object {
        const val FAMILY = "FAMILY01"
        const val TOKEN = "tk-test"
    }
}
