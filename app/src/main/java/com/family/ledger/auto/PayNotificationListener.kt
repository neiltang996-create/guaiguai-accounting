package com.family.ledger.auto

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 自动记账 · 通知监听。
 *
 * 微信/支付宝的付款结果通常以通知形式出现，比无障碍更稳定、更省电。
 *
 * 要点：
 *  - 把 `EXTRA_TITLE / EXTRA_TEXT / EXTRA_BIG_TEXT / EXTRA_SUB_TEXT` 拼成一段文本再解析，
 *    这样「微信支付」独立会话（标题=微信支付，正文=向XX付款…）能被正确识别；
 *  - 只处理支付类包名；未授权时系统不会回调，这里再兜一层；
 *  - 解析同步（纯字符串），落库交给 [AutoBillPipeline] 的后台协程。
 */
class PayNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching { AutoBillActionReceiver.ensureRegistered(this) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            handlePosted(sbn)
        } catch (t: Throwable) {
            // 通知监听服务异常会被系统重启，绝不能抛出
        }
    }

    private fun handlePosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (!PayPackages.isWatched(pkg)) return
        if (!AutoBillPermission.notificationAccessEnabled(this)) return

        val pipeline = AutoBillPipeline.getOrNull(this) ?: return
        if (!pipeline.enabled) return

        val extras = sbn.notification?.extras ?: return
        val parts = ArrayList<String>(4)
        for (key in TEXT_KEYS) {
            val value = runCatching { extras.getCharSequence(key)?.toString()?.trim() }.getOrNull()
            if (!value.isNullOrEmpty() && !parts.contains(value)) parts += value
        }
        if (parts.isEmpty()) return

        val text = parts.joinToString(" ")
        val at = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
        val signal = PaymentTextParser.parse(text, pkg, at, Origin.NOTIFICATION)?.copy(rawEventId = sbn.key + ":" + sbn.postTime)
        scope.launch {
            pipeline.recordDebug(pkg, null, "NOTIFICATION_POSTED", text, null, signal)
            if (signal != null) pipeline.handle(signal)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        /** 拼接顺序即语义顺序：标题 → 正文 → 大文本 → 子文本 → 摘要。 */
        val TEXT_KEYS = listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_SUMMARY_TEXT,
        )
    }
}
