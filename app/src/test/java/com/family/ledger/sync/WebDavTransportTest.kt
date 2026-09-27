package com.family.ledger.sync

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [WebDavTransport] 的真实 HTTP 单测。
 *
 * 用 OkHttp **MockWebServer** 起一个真的 HTTP 服务端，走真实的 socket 与 OkHttp 栈。
 *
 * 注意：**不要**用 JDK 的 `com.sun.net.httpserver`。
 * Android 单测的编译 classpath 是 `android.jar`，看不到 `jdk.httpserver` 模块，
 * 用了会让整个 `compileDebugUnitTestKotlin` 失败，把全队所有单测一起挡死。
 */
class WebDavTransportTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun transport(
        user: String = "user",
        password: String = "pass",
        maxAttempts: Int = 3,
    ) = WebDavTransport(
        baseUrl = server.url("/dav/").toString(),
        user = user,
        password = password,
        maxAttempts = maxAttempts,
    )

    private fun url(path: String) = server.url("/dav/$path").toString()

    private fun ok(code: Int = 201, body: String = "") =
        MockResponse().setResponseCode(code).setBody(body)

    // ---------------------------------------------------------------- 闭环

    @Test
    fun `建目录_上传_下载_闭环`() = runBlocking {
        // 本用例总共会发 4 个请求：2×MKCOL + 1×PUT + 1×GET。
        // MockWebServer 的 QueueDispatcher 在队列空时会阻塞等待，
        // 少 enqueue 一个会表现为客户端「读超时」（而不是立刻失败），很难查。
        server.enqueue(ok(201))                       // MKCOL familyledger
        server.enqueue(ok(201))                       // MKCOL familyledger/FAM
        server.enqueue(ok(201))                       // PUT
        server.enqueue(ok(200, """{"seq":1}"""))      // GET

        val t = transport()
        t.ensureCollections(listOf(url("familyledger"), url("familyledger/FAM")))
        t.putText(url("familyledger/FAM/dev-A.jsonl"), """{"seq":1}""")
        val got = t.getText(url("familyledger/FAM/dev-A.jsonl"))

        assertEquals("""{"seq":1}""", got)

        val mk1 = server.takeRequest()
        assertEquals("MKCOL", mk1.method)
        assertEquals("/dav/familyledger", mk1.path)
        val mk2 = server.takeRequest()
        assertEquals("MKCOL", mk2.method)
        assertEquals("/dav/familyledger/FAM", mk2.path)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/dav/familyledger/FAM/dev-A.jsonl", put.path)
        assertEquals("""{"seq":1}""", put.body.readUtf8())
    }

    @Test
    fun `ensureCollections 由浅到深依次建目录`() = runBlocking {
        repeat(3) { server.enqueue(ok(201)) }
        transport().ensureCollections(
            listOf(url("a"), url("a/b"), url("a/b/c")),
        )
        assertEquals("/dav/a", server.takeRequest().path)
        assertEquals("/dav/a/b", server.takeRequest().path)
        assertEquals("/dav/a/b/c", server.takeRequest().path)
    }

    @Test
    fun `PUT 的 Content-Type 是 ndjson`() = runBlocking {
        server.enqueue(ok(201))
        transport().putText(url("x.jsonl"), "line")
        val req = server.takeRequest()
        assertTrue(
            "Content-Type 应为 application/x-ndjson，实际: ${req.getHeader("Content-Type")}",
            req.getHeader("Content-Type")?.contains("ndjson") == true,
        )
    }

    // ---------------------------------------------------------------- 状态码语义

    @Test
    fun `404 表示对方还没同步过_返回null而不是抛错`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(transport().getText(url("nobody.jsonl")))
    }

    @Test
    fun `目录已存在时405不算失败`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405))
        transport().mkcol(url("familyledger"))   // 不应抛异常
        assertEquals("MKCOL", server.takeRequest().method)
    }

    @Test
    fun `301与302也视为目录已存在`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(301))
        transport().mkcol(url("familyledger"))
        assertEquals("MKCOL", server.takeRequest().method)
        server.enqueue(MockResponse().setResponseCode(302))
        transport().mkcol(url("familyledger"))
        assertEquals("MKCOL", server.takeRequest().method)
    }

    @Test
    fun `父目录不存在时给出中文错误`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409))
        val e = runCatching { transport().mkcol(url("a/b")) }.exceptionOrNull()
        assertNotNull("409 应当抛 SyncException", e)
        assertTrue("应当是 SyncException，实际 ${e!!::class.simpleName}", e is SyncException)
        assertTrue("不应直接崩溃", e.message!!.isNotBlank())
    }

    @Test
    fun `上传遇到403给出中文提示而不是崩溃`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val e = runCatching { transport().putText(url("x.jsonl"), "{}") }.exceptionOrNull()
        assertTrue(e is SyncException)
        assertTrue("403 提示应可读，实际: ${e!!.message}", e.message!!.contains("403"))
    }

    // ---------------------------------------------------------------- 认证

    @Test
    fun `带Basic认证头`() = runBlocking {
        server.enqueue(ok(201))
        transport(user = "me@example.com", password = "app-pass")
            .putText(url("x.jsonl"), "{}")
        val req = server.takeRequest()
        assertEquals(
            Credentials.basic("me@example.com", "app-pass"),
            req.getHeader("Authorization"),
        )
    }

    @Test
    fun `用户名密码都为空时不发认证头`() = runBlocking {
        server.enqueue(ok(201))
        transport(user = "", password = "").putText(url("x.jsonl"), "{}")
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `认证失败给出中文提示而不是崩溃`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val e = runCatching { transport().putText(url("x.jsonl"), "{}") }.exceptionOrNull()
        assertTrue("401 应当抛 SyncException", e is SyncException)
        val msg = e!!.message!!
        assertTrue("提示应说明账号密码问题，实际: $msg", msg.contains("401"))
    }

    // ---------------------------------------------------------------- 重试

    @Test
    fun `服务器5xx会自动重试`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(ok(201))

        transport(maxAttempts = 2).putText(url("x.jsonl"), "{}")

        assertEquals("应当发出 2 次请求（第一次 500，第二次成功）", 2, server.requestCount)
    }

    @Test
    fun `5xx重试用尽后抛出中文错误`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        val e = runCatching { transport(maxAttempts = 1).putText(url("x.jsonl"), "{}") }
            .exceptionOrNull()
        assertTrue(e is SyncException)
        assertTrue("实际: ${e!!.message}", e.message!!.contains("503"))
    }

    // ---------------------------------------------------------------- PROPFIND

    @Test
    fun `PROPFIND能列出对方的设备日志`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:href>/dav/familyledger/FAM/</d:href></d:response>
                  <d:response><d:href>/dav/familyledger/FAM/dev-A.jsonl</d:href></d:response>
                  <d:response><d:href>/dav/familyledger/FAM/dev-B.jsonl</d:href></d:response>
                </d:multistatus>
                """.trimIndent()
            )
        )
        val names = transport().listFileNames(url("familyledger/FAM"))
        assertTrue("应包含 dev-A.jsonl，实际: $names", names.contains("dev-A.jsonl"))
        assertTrue("应包含 dev-B.jsonl，实际: $names", names.contains("dev-B.jsonl"))
        assertEquals("目录本身不应作为文件返回", 2, names.size)

        val req = server.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("1", req.getHeader("Depth"))
    }

    @Test
    fun `PROPFIND被禁用时返回空表不影响主流程`() = runBlocking {
        // 用 403 / 405 这种**非 5xx** 的「不支持」状态码：
        // 若用 501，会被 send() 当成服务器抽风而重试，队列空了就阻塞到读超时，
        // 单测会白等 60 秒（虽然最终仍返回空表）。
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(emptyList<String>(), transport().listFileNames(url("familyledger/FAM")))

        server.enqueue(MockResponse().setResponseCode(405))
        assertEquals(emptyList<String>(), transport().listFileNames(url("familyledger/FAM")))
    }

    // ---------------------------------------------------------------- 地址与纯函数

    @Test
    fun `地址不合法给出中文提示`() {
        val e = runCatching {
            runBlocking { WebDavTransport("这不是一个网址", "u", "p").putText("also-bad", "{}") }
        }.exceptionOrNull()
        assertNotNull("非法地址应当抛异常", e)
        assertTrue("应当是 SyncException，实际 ${e!!::class.simpleName}", e is SyncException)
        assertTrue("提示应为中文可读，实际: ${e.message}", e.message!!.contains("地址"))
    }

    @Test
    fun `parseHrefs 能解析并去重且忽略目录`() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/r/F/</d:href></d:response>
              <d:response><d:href>/dav/r/F/dev-A.jsonl</d:href></d:response>
              <d:response><d:href>/dav/r/F/dev-A.jsonl</d:href></d:response>
              <d:response><d:href>/dav/r/F/%E5%AE%B6%E5%BA%AD.jsonl</d:href></d:response>
            </d:multistatus>
        """.trimIndent()
        val names = WebDavTransport.parseHrefs(xml)
        assertTrue(names.contains("dev-A.jsonl"))
        assertTrue("URL 编码的文件名应解码，实际: $names", names.contains("家庭.jsonl"))
        assertEquals("重复 href 应去重", 2, names.size)
    }
}
