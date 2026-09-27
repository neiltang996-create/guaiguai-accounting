package com.family.ledger.sync

import com.family.ledger.data.db.entity.TxnEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.file.Files

/**
 * 与仓库中的实际 Python 服务端 `server/familyledger_sync_server.py` 本机联测：
 * 在 JVM 里用 `ProcessBuilder` 把它起在临时端口上，验证客户端协议与服务端**逐条对齐**
 * （health / count / GET ops / POST ops / devices / 403 / 401），以及
 * **两个客户端模拟两台手机通过真服务端收敛**（其中一条走本机非回环 IPv4，尽量贴近真实链路）。
 *
 * 找不到 python3 或脚本时用 [Assume] 跳过（不会把基线搞成 failure）。
 */
class HomeServerRealServerTest {

    private var process: Process? = null

    @After
    fun tearDown() {
        process?.let { p ->
            runCatching { p.destroy() }
            runCatching { p.waitFor() }
        }
        process = null
    }

    @Test
    fun `与真Python服务端协议一致`() {
        val port = startRealServer()
        Assume.assumeTrue("找不到脚本或没有 python3，跳过真服务端联测", port != null)
        val base = "http://127.0.0.1:$port"
        val transport = HomeServerTransport(base, FAMILY, TOKEN, maxAttempts = 1)
        val up = runBlocking { waitUntilUp(transport, 15_000) }
        Assume.assumeTrue("Python 服务端没能起来（端口被占用？），跳过真服务端联测", up)

        runBlocking {
            assertEquals("familyledger", transport.health().app)

            val ops = listOf(
                testOp("dev-jvm", 1, 100, testTxn("t-1", "真服务端", 100, device = "dev-jvm")),
                testOp("dev-jvm", 2, 200, testTxn("t-2", "第二笔", 200, device = "dev-jvm")),
            )
            val posted = transport.postOps(SyncEngine.journalText(ops))
            assertEquals(2, posted.applied)
            assertEquals(0, posted.bad)
            assertEquals(2, transport.opsCount("dev-jvm"))
            assertTrue(transport.devices().contains("dev-jvm"))
            assertEquals(
                ops.toSet(),
                SyncEngine(MemoryStore()).parseJournal(transport.getOps("dev-jvm")).toSet(),
            )
            assertEquals("没同步过的设备应返回空体", "", transport.getOps("dev-nobody"))

            // /health 只校验 token：客户端此刻可能还不知道对方的家庭码（零输入配对要用）
            val noFamily = HomeServerTransport(base, "", TOKEN, maxAttempts = 1)
            assertTrue("health 不要求 X-Family-Id", noFamily.ping())

            // 但数据端点缺家庭码 → 403
            val forbidden = runCatching { noFamily.getOps("dev-jvm") }.exceptionOrNull()
            assertTrue(
                "ops 应 403：${forbidden?.message}",
                forbidden is SyncException && forbidden.message.orEmpty().contains("403"),
            )

            // token 不对 → 401
            val badToken = HomeServerTransport(base, FAMILY, "wrong-token", maxAttempts = 1)
            assertFalse(badToken.ping())
            assertTrue("lastError=${badToken.lastError}", badToken.lastError.orEmpty().contains("401"))

            // 零输入配对：读 → 登记 → 冲突（服务端绝不覆盖已有家庭）
            assertNull("一开始服务器上没人登记", transport.getFamily())
            assertTrue(transport.claimFamily(FAMILY).created)
            assertEquals(FAMILY, transport.getFamily())
            val conflict = transport.claimFamily("OTHERFAM")
            assertTrue(conflict.conflict)
            assertEquals(FAMILY, conflict.familyId)
        }
    }

    @Test
    fun `两个客户端经非回环地址通过真服务端收敛`() {
        val lanIp = localLanIpv4()
        Assume.assumeTrue("本机没有非回环 IPv4，跳过局域网地址联测", lanIp != null)
        val port = startRealServer()
        Assume.assumeTrue("找不到脚本或没有 python3，跳过局域网地址联测", port != null)
        val base = "http://$lanIp:$port"
        val probe = HomeServerTransport(base, FAMILY, TOKEN, maxAttempts = 1)
        val up = runBlocking { waitUntilUp(probe, 15_000) }
        Assume.assumeTrue("真服务端没能在非回环地址上起来，跳过", up)

        runBlocking {
            val a = Client("dev-lan-a", base)
            val b = Client("dev-lan-b", base)
            a.record(1, 100, testTxn("t-a", "A记的", 100, device = "dev-lan-a"))
            b.record(1, 100, testTxn("t-b", "B记的", 100, device = "dev-lan-b"))

            assertTrue(a.engine.sync().serverOk)
            assertTrue(b.engine.sync().serverOk)
            assertTrue(a.engine.sync().serverOk)

            assertEquals("两台手机的数据应完全一致", a.store.entities, b.store.entities)
            assertEquals(2, a.store.entities.size)
            assertEquals("A记的", (a.store.entities["txn/t-a"] as TxnEntity).note)
            assertEquals("B记的", (a.store.entities["txn/t-b"] as TxnEntity).note)

            val repeat = a.engine.sync()
            assertEquals("重复同步应幂等", 0, repeat.pulled)
            assertEquals(0, repeat.pushed)
            assertEquals("应用远端 op 不应产生本机 op", 1, a.store.ops.values.count { it.deviceId == "dev-lan-a" })
        }
    }

    // ---------------------------------------------------------------- 一台「手机」

    private inner class Client(private val deviceId: String, base: String) {
        val store = MemoryStore()
        val state = MemoryState()
        val engine = HomeServerEngine(
            transport = HomeServerTransport(base, FAMILY, TOKEN, maxAttempts = 2, retryDelayMs = 100),
            merge = SyncEngine(store),
            myDeviceId = deviceId,
            state = state,
            localOps = { store.ops.values.filter { it.deviceId == deviceId }.sortedBy { it.seq } },
            knownPeers = { emptyList() },
        )

        suspend fun record(seq: Long, hlc: Long, txn: TxnEntity) {
            store.putOp(testOp(deviceId, seq, hlc, txn))
            store.upsertEntity(txn)
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 起真服务端，返回端口；脚本或 python3 缺失时返回 null。 */
    private fun startRealServer(): Int? {
        val script = findServerScript() ?: return null
        if (!python3Available()) return null
        val port = freePort()
        val dataDir = Files.createTempDirectory("fl-sync-real").toFile()
        process = ProcessBuilder(
            "python3", script.absolutePath,
            "--port", port.toString(),
            "--data", dataDir.absolutePath,
            "--token", TOKEN,
            "--quiet",
        ).redirectErrorStream(true).start()
        return port
    }

    private suspend fun waitUntilUp(transport: HomeServerTransport, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (transport.ping()) return true
            delay(200)
        }
        return false
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun python3Available(): Boolean = try {
        ProcessBuilder("python3", "-V").redirectErrorStream(true).start().waitFor() == 0
    } catch (t: Throwable) {
        false
    }

    /** 本机第一个非回环 IPv4（优先 `en*` 网卡的私有地址），模拟「手机走的那个网段」。 */
    private fun localLanIpv4(): String? {
        val candidates = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("en")) 0 else 1 }
                .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>() }
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        }.getOrNull().orEmpty()
        return (candidates.firstOrNull { it.isSiteLocalAddress } ?: candidates.firstOrNull())?.hostAddress
    }

    /** Gradle 单测的工作目录可能是 `app/` 或工程根，两个都试一下。 */
    private fun findServerScript(): File? {
        listOf("../server/familyledger_sync_server.py", "server/familyledger_sync_server.py").forEach { path ->
            val f = File(path)
            if (f.isFile) return f.absoluteFile
        }
        var dir: File? = File("").absoluteFile
        repeat(4) {
            val candidate = dir?.let { File(it, "server/familyledger_sync_server.py") }
            if (candidate != null && candidate.isFile) return candidate.absoluteFile
            dir = dir?.parentFile
        }
        return null
    }

    private companion object {
        const val FAMILY = "FAMILYIT1"

        /** 测试生成的假令牌，与真实部署完全无关。 */
        const val TOKEN: String = "synthetic-integration-test-token"
    }
}
