package com.family.ledger.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「同步」区块文案的回归测试。
 *
 * 这一组测试是**真机测出来的 bug 的护栏**：在 Redmi K Pad 上真实同步时，
 * 设置页同时显示了「暂时连不上家里的同步服务。」和
 * 「已连接「家里的 NAS」：当前没有需要同步的新账目」—— 自相矛盾。
 *
 * 根因：`peersFound == 0` 被当成了「失败」，但服务器**可达、只是没有新账目**时它也是 0。
 */
class SyncUiTextTest {

    private val now = 1_790_401_500_000L

    private fun state(
        message: String = "",
        peersFound: Int = 0,
        lastSyncAt: Long = 0L,
        running: Boolean = false,
        pushed: Int = 0,
        pulled: Int = 0,
    ) = SyncUiState(
        running = running,
        lastSyncAt = lastSyncAt,
        message = message,
        pushed = pushed,
        pulled = pulled,
        peersFound = peersFound,
        peerName = "家里的 NAS",
    )

    @Test
    fun `服务器可达但没有新账目时不能说成连不上`() {
        // 真机上出现过的原始状态：peersFound=0 + 这句 message
        val p = syncPresentation(
            enabled = true,
            s = state(
                message = "已连接「家里的 NAS」：当前没有需要同步的新账目",
                peersFound = 0,
                lastSyncAt = now - 30_000L,
            ),
            now = now,
        )
        assertFalse(
            "主行不能是「连不上」：${p.primary}",
            p.primary.contains("连不上"),
        )
        assertTrue("应说明已连接：${p.primary}", p.primary.contains("已连接"))
        assertEquals(SyncTone.OK, p.tone)
        // 详情里要保留原始信息（用户能看懂发生了什么）
        assertTrue("详情应保留仓库层原话：${p.detail}", p.detail?.contains("没有需要同步的新账目") == true)
        assertTrue("详情应带上次同步时间：${p.detail}", p.detail?.contains("上次同步") == true)
    }

    @Test
    fun `真的连不上时仍然要说连不上`() {
        val p = syncPresentation(
            enabled = true,
            s = state(message = "网络错误：连接超时", peersFound = 0, lastSyncAt = 0L),
            now = now,
        )
        assertTrue("应提示连不上：${p.primary}", p.primary.contains("连不上"))
        assertEquals(SyncTone.WARN, p.tone)
        assertTrue("详情应给出排查建议：${p.detail}", p.detail?.contains("网络错误") == true)
    }

    @Test
    fun `主行与详情绝不能同时说连不上和已连接`() {
        // 穷举「已连接」类消息，保证任何一条都不会掉进「连不上」分支
        val reachableMessages = listOf(
            "已连接「家里的 NAS」：当前没有需要同步的新账目",
            "已连接「家里的 NAS」：接收 3 条，发送 2 条",
            "已连接「家里的 NAS」，但有部分数据没同步成功：家庭 冲突",
        )
        for (msg in reachableMessages) {
            val p = syncPresentation(true, state(message = msg, peersFound = 0, lastSyncAt = now), now)
            assertFalse("「$msg」被误判成连不上：${p.primary}", p.primary.contains("连不上"))
        }
    }

    @Test
    fun `同步成功且有对方时显示条数与相对时间`() {
        val p = syncPresentation(
            enabled = true,
            s = state(
                message = "已连接「家里的 NAS」：接收 5 条，发送 3 条",
                peersFound = 1,
                lastSyncAt = now - 45_000L,
                pulled = 5,
                pushed = 3,
            ),
            now = now,
        )
        assertTrue("主行应含拉取条数：${p.primary}", p.primary.contains("5"))
        assertTrue("主行应含推送条数：${p.primary}", p.primary.contains("3"))
        assertTrue("主行应是相对时间：${p.primary}", p.primary.contains("刚刚同步"))
        assertEquals(SyncTone.OK, p.tone)
    }

    @Test
    fun `关掉开关时明确说已关闭`() {
        val p = syncPresentation(enabled = false, s = state(), now = now)
        assertTrue("应说明自动同步已关闭：${p.primary}", p.primary.contains("已关闭"))
        assertEquals(SyncTone.IDLE, p.tone)
    }

    @Test
    fun `从没同步过时不谎报连不上`() {
        val p = syncPresentation(enabled = true, s = state(), now = now)
        assertFalse("首次打开不该说连不上：${p.primary}", p.primary.contains("连不上"))
        assertEquals(SyncTone.IDLE, p.tone)
    }

    @Test
    fun `相对时间的分档`() {
        assertEquals("刚刚同步", relativeSyncTime(now - 5_000L, now))
        assertEquals("5 分钟前同步", relativeSyncTime(now - 5 * 60_000L, now))
        assertEquals("2 小时前同步", relativeSyncTime(now - 2 * 3_600_000L, now))
        assertTrue(relativeSyncTime(now - 3 * 86_400_000L, now).endsWith("同步"))
    }

    @Test
    fun `设备名跟着昵称自动生成`() {
        assertEquals("用户 A 的手机", autoDeviceName("用户 A"))
        assertEquals(DEFAULT_DEVICE_NAME, autoDeviceName("   "))
        assertTrue(isAutoDeviceName(DEFAULT_DEVICE_NAME, "用户 A"))
        assertTrue(isAutoDeviceName("用户 A 的手机", "用户 A"))
        assertFalse("用户手动改过的名字不该被覆盖", isAutoDeviceName("老唐的平板", "用户 A"))
    }
}
