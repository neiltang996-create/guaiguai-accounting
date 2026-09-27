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
 * 零输入配对握手（[HomeFamilyHandshake]）单测。
 *
 * 覆盖 lead 要求的三条分支：
 *   - **2a** 服务器还没登记 → 本机登记自己的家庭码
 *   - **2b** 服务器上就是我的家庭码 → 什么都不做
 *   - **2c** 服务器上是别人的家庭码 → 本机没有真实流水就自动加入；已有流水绝不自动改（给中文提示）
 * 另外覆盖「自动加入失败」与「两台手机同时登记」的竞争。
 */
class HomeFamilyHandshakeTest {

    private lateinit var server: FakeHomeServer

    @Before
    fun setUp() {
        server = FakeHomeServer(familyId = "ANYFAM", token = "").start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private class FakeBridge(private val txns: Int = 0, private val adoptOk: Boolean = true) : HomeFamilyBridge {
        var adopted: String? = null

        override suspend fun localTxnCount(): Int = txns

        override suspend fun adoptFamily(familyId: String): Boolean {
            if (!adoptOk) return false
            adopted = familyId
            return true
        }
    }

    private fun transport(family: String) =
        HomeServerTransport(server.baseUrl, family, "", maxAttempts = 1, retryDelayMs = 10)

    @Test
    fun `2a 服务器还没登记时第一台手机登记自己的家庭码`() = runBlocking {
        val bridge = FakeBridge()

        val result = HomeFamilyHandshake(bridge).run(transport(MINE), MINE)

        assertEquals(MINE, result.familyId)
        assertFalse(result.adopted)
        assertFalse(result.blocked)
        assertNull("自己的家庭码不需要 adopt", bridge.adopted)
        assertEquals("已登记到服务器", MINE, transport(MINE).getFamily())
    }

    @Test
    fun `2b 服务器上就是我的家庭码时什么都不做`() = runBlocking {
        server.registeredFamily = MINE
        val bridge = FakeBridge(txns = 42)

        val result = HomeFamilyHandshake(bridge).run(transport(MINE), MINE)

        assertEquals(MINE, result.familyId)
        assertFalse(result.adopted)
        assertFalse(result.blocked)
        assertNull(bridge.adopted)
    }

    @Test
    fun `2b 大小写不同的家庭码视为同一个`() = runBlocking {
        server.registeredFamily = MINE.lowercase()
        val bridge = FakeBridge(txns = 0)

        val result = HomeFamilyHandshake(bridge).run(transport(MINE), MINE)

        assertEquals(MINE, result.familyId)
        assertFalse(result.adopted)
        assertNull(bridge.adopted)
    }

    @Test
    fun `2c 服务器上是别人的家庭码且本机没有流水时自动加入`() = runBlocking {
        server.registeredFamily = WIFE
        val bridge = FakeBridge(txns = 0)

        val result = HomeFamilyHandshake(bridge).run(transport(MINE), MINE)

        assertEquals(WIFE, result.familyId)
        assertTrue(result.adopted)
        assertFalse(result.blocked)
        assertEquals("应加入对方的家庭码", WIFE, bridge.adopted)
        assertTrue("要有中文提示：${result.message}", result.message.contains("已自动加入"))
    }

    @Test
    fun `2c 本机已有流水时绝不自动加入并给中文提示`() = runBlocking {
        server.registeredFamily = WIFE
        val bridge = FakeBridge(txns = 600)

        val result = HomeFamilyHandshake(bridge).run(transport(MINE), MINE)

        assertNull("不能自动改本机家庭码", result.familyId)
        assertTrue(result.blocked)
        assertFalse(result.adopted)
        assertNull("绝不能调用 adopt", bridge.adopted)
        assertTrue("提示要带笔数：${result.message}", result.message.contains("600"))
        // 家庭码 UI 已删除：提示不能再指向那个入口，必须说明数据没丢 + 可执行的下一步
        assertTrue("提示要说明数据没丢：${result.message}", result.message.contains("没有丢"))
        assertTrue("提示要指向「设置 → 数据」导出：${result.message}", result.message.contains("导出"))
        assertTrue("提示不应再提配对码：${result.message}", !result.message.contains("配对码"))
    }

    @Test
    fun `自动加入失败时给出可执行的提示`() = runBlocking {
        server.registeredFamily = WIFE
        val bridge = FakeBridge(txns = 0, adoptOk = false)

        val result = HomeFamilyHandshake(bridge).run(transport(MINE), MINE)

        assertTrue(result.blocked)
        assertNull(result.familyId)
        // 家庭码 UI 已删除，提示不能再引导用户「去输入配对码」；
        // 必须说清「数据没丢」并给自动重试/排查方向
        assertTrue("提示要说明数据没丢：${result.message}", result.message.contains("没有丢"))
        assertTrue("提示不应再提配对码：${result.message}", !result.message.contains("配对码"))
    }

    @Test
    fun `两台手机同时登记时后到的一台自动加入先到的`() = runBlocking {
        // GET /family 说没人登记，POST 立刻 409：模拟配偶手机抢先一步
        server.raceFamilyId = WIFE
        val bridge = FakeBridge(txns = 0)

        val result = HomeFamilyHandshake(bridge).run(transport(MINE), MINE)

        assertEquals(WIFE, result.familyId)
        assertTrue(result.adopted)
        assertEquals(WIFE, bridge.adopted)
    }

    private companion object {
        const val MINE = "MYFAM001"
        const val WIFE = "WIFEFAM1"
    }
}
