package com.family.ledger.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AutoBillOverlay` 的**纯逻辑**单测：卡片文案、弹卡片策略、位置计算、超时收起、队列。
 *
 * 这些对象刻意不碰任何 Android 类型（WindowManager / View 在真机上才跑），
 * 所以能在这里把「12 秒后该收起」「第 2 笔要等第 1 笔结束才显示」这类行为钉死。
 */
class AutoBillOverlayTest {

    // ---------- 文案：`¥45 · 美团 · 支付宝小荷包(示例日常)` ----------

    @Test
    fun `卡片一行显示金额商户资产`() {
        val line = OverlayText.summary(
            amountCents = 4500L,
            merchant = "美团",
            assetName = "支付宝小荷包(示例日常)",
        )
        // 金额走 core 的 Money.format（fen == 0 时不显示 .00）
        assertEquals("¥45 · 美团 · 支付宝小荷包(示例日常)", line)
    }

    @Test
    fun `金额带角分与千分位`() {
        assertEquals("¥12,345.67 · 星巴克", OverlayText.summary(1_234_567L, "星巴克", null))
        assertEquals("¥45.50 · 美团", OverlayText.summary(4550L, "美团", null))
    }

    @Test
    fun `拿不到商户或资产时自动省略分隔符`() {
        assertEquals("¥45", OverlayText.summary(4500L, null, null))
        assertEquals("¥45 · 美团", OverlayText.summary(4500L, "美团", null))
        assertEquals("¥45 · 美团", OverlayText.summary(4500L, "美团", "  "))
    }

    @Test
    fun `重复账单在文案里追加提示`() {
        val line = OverlayText.summary(4500L, "美团", "支付宝小荷包", suffix = "这笔可能已经记过了")
        assertEquals("¥45 · 美团 · 支付宝小荷包 · 这笔可能已经记过了", line)
    }

    // ---------- 策略：什么时候弹、什么时候提示去开权限 ----------

    @Test
    fun `有权限且开关打开才弹卡片`() {
        assertTrue(OverlayPolicy.shouldShow(popupEnabled = true, canDrawOverlay = true, pendingResolved = false))
        assertFalse(OverlayPolicy.shouldShow(popupEnabled = false, canDrawOverlay = true, pendingResolved = false))
        assertFalse(OverlayPolicy.shouldShow(popupEnabled = true, canDrawOverlay = false, pendingResolved = false))
        assertFalse(OverlayPolicy.shouldShow(popupEnabled = true, canDrawOverlay = true, pendingResolved = true))
    }

    @Test
    fun `没权限且开关打开时提示一次`() {
        assertTrue(
            OverlayPolicy.shouldHintMissingPermission(
                popupEnabled = true, canDrawOverlay = false, hintShown = false,
            )
        )
        // 已经提示过 → 不再烦用户
        assertFalse(
            OverlayPolicy.shouldHintMissingPermission(
                popupEnabled = true, canDrawOverlay = false, hintShown = true,
            )
        )
        // 用户自己关掉了弹卡片开关 → 不提示
        assertFalse(
            OverlayPolicy.shouldHintMissingPermission(
                popupEnabled = false, canDrawOverlay = false, hintShown = false,
            )
        )
        // 已经有权限 → 不提示
        assertFalse(
            OverlayPolicy.shouldHintMissingPermission(
                popupEnabled = true, canDrawOverlay = true, hintShown = false,
            )
        )
    }

    // ---------- 位置：避开支付结果的金额区与底部按钮区 ----------

    @Test
    fun `卡片纵向位置不越界`() {
        val screen = 2400
        val card = 300
        val inset = 84
        val y = OverlayGeometry.verticalOffset(screen, card, inset)
        assertTrue("y 不能为负：$y", y >= 0)
        assertTrue("卡片底部不能压到导航栏：y=$y", y + card <= screen - inset)
        // 落在屏幕中下部（金额区在顶部，支付按钮在底部）
        assertTrue("应落在中下部：$y", y > screen / 4 && y < screen * 3 / 4)
    }

    @Test
    fun `小屏幕上卡片也放在顶部而不是负坐标`() {
        assertEquals(0, OverlayGeometry.verticalOffset(screenHeight = 200, cardHeight = 300, bottomInset = 50))
        assertEquals(0, OverlayGeometry.verticalOffset(screenHeight = 0, cardHeight = 100, bottomInset = 0))
    }

    @Test
    fun `拖动位置被夹在屏幕内`() {
        // 往上拖过头 → 贴顶
        assertEquals(0, OverlayGeometry.clampVertical(-500, 2400, 300, 84))
        // 往下拖过头 → 贴底（不含导航栏）
        assertEquals(2016, OverlayGeometry.clampVertical(9_999, 2400, 300, 84))
        // 正常范围原样返回
        assertEquals(1200, OverlayGeometry.clampVertical(1200, 2400, 300, 84))
    }

    // ---------- 超时：12 秒无操作收起 ----------

    @Test
    fun `12 秒无操作才收起`() {
        val t = OverlayPolicy.DEFAULT_TIMEOUT_MS
        assertEquals(12_000L, t)
        assertFalse(OverlayTimer.shouldAutoDismiss(lastInteractionAt = 1_000L, now = 1_000L + t - 1, timeoutMs = t))
        assertTrue(OverlayTimer.shouldAutoDismiss(lastInteractionAt = 1_000L, now = 1_000L + t, timeoutMs = t))
        assertTrue(OverlayTimer.shouldAutoDismiss(lastInteractionAt = 1_000L, now = 1_000L + t + 5_000, timeoutMs = t))
    }

    @Test
    fun `有操作就重新计时`() {
        val t = OverlayPolicy.DEFAULT_TIMEOUT_MS
        // 第 11 秒用户点了卡片 → 重新从 0 开始算
        assertEquals(1_000L, OverlayTimer.remainingMs(lastInteractionAt = 11_000L, now = 22_000L, timeoutMs = t))
        // 早已超时 → 剩余 0，不会出现负数
        assertEquals(0L, OverlayTimer.remainingMs(lastInteractionAt = 0L, now = 99_999L, timeoutMs = t))
    }

    // ---------- 队列：同时只显示 1 张，其余排队 ----------

    private fun card(id: String, amount: Long = 4500L) = OverlayCard(
        pendingId = id,
        amountCents = amount,
        merchant = "美团",
        assetName = "支付宝小荷包",
    )

    @Test
    fun `第 2 笔要等第 1 笔结束才显示`() {
        val q = OverlayQueue()
        assertTrue(q.enqueue(card("pb-1")))
        assertTrue(q.enqueue(card("pb-2")))
        assertEquals(listOf("pb-1", "pb-2"), q.ids())
        // 显示第 1 笔：从队列里取走；剩下的是「还在等」的
        assertEquals("pb-1", q.poll()?.pendingId)
        assertEquals(listOf("pb-2"), q.ids())
        assertEquals(1, q.waitingCount())
        // 第 1 笔收起 → 第 2 笔接上
        assertEquals("pb-2", q.poll()?.pendingId)
        assertEquals(0, q.waitingCount())
        assertEquals(0, q.size())
        // 队列空了就没什么可显示
        assertNull(q.peek())
        assertNull(q.poll())
    }

    @Test
    fun `同一笔账单重复上报不重复排队`() {
        val q = OverlayQueue()
        q.enqueue(card("pb-1", amount = 4500L))
        val added = q.enqueue(card("pb-1", amount = 9900L))
        assertFalse("同一 pendingId 只更新内容，不新增", added)
        assertEquals(1, q.size())
        // 内容被新的覆盖（金额纠正成 99 元），仍然只占 1 个位子
        assertEquals(9900L, q.peek()?.amountCents)
        assertEquals(1, q.waitingCount())
    }

    @Test
    fun `已经在别处处理掉的账单从队列里移除`() {
        val q = OverlayQueue()
        q.enqueue(card("pb-1"))
        q.enqueue(card("pb-2"))
        assertTrue(q.remove("pb-2"))
        assertFalse(q.remove("pb-2"))
        assertEquals(listOf("pb-1"), q.ids())
        assertEquals(1, q.waitingCount())

        q.clear()
        assertEquals(0, q.size())
        assertEquals(0, q.waitingCount())
        assertNull(q.poll())
    }
}
