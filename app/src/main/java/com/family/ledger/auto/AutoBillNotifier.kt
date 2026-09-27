package com.family.ledger.auto

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.family.ledger.FamilyLedgerApp
import com.family.ledger.R
import com.family.ledger.core.Money
import com.family.ledger.data.db.entity.PendingBillEntity

/**
 * 自动记账 · 待确认账单通知。
 *
 * 通知正文形如「¥45.00 · 美团 · 支付宝小荷包」，三个动作：
 *  - 记入家庭账本：[AutoBillActionReceiver] 直接确认（不打断当前操作）；
 *  - 改一下：打开 [AutoBillConfirmActivity] 快速确认页；
 *  - 忽略：标记 IGNORED。
 *
 * 通知渠道由 [FamilyLedgerApp] 在应用启动时创建。未授权通知权限时静默跳过，绝不崩溃。
 */
class AutoBillNotifier(private val context: Context) {

    private val nm: NotificationManagerCompat get() = NotificationManagerCompat.from(context)

    /** 待确认账单提醒。 */
    fun notifyPending(pending: PendingBillEntity, assetName: String?) {
        if (!AutoBillPermission.notificationsGranted(context)) return
        val isRefund = PendingBillCodec.directionOf(pending) == Direction.REFUND
        val duplicate = pending.status == PendingBillEntity.STATUS_DUPLICATE
        val line = summaryLine(pending.amount, pending.merchant, assetName, duplicate)

        // 动态注册的 receiver 只能在进程存活时收到广播，这里保证「发通知时一定已注册」
        AutoBillActionReceiver.ensureRegistered(context)

        val openIntent = PendingIntent.getActivity(
            context,
            AutoBillActionReceiver.requestCode(pending.id, 2),
            AutoBillConfirmActivity.intent(context, pending.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, FamilyLedgerApp.CHANNEL_AUTO_BILL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.auto_notif_title))
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(
                0,
                if (isRefund) "关联原支出" else context.getString(R.string.auto_notif_confirm),
                if (isRefund) openIntent else AutoBillActionReceiver.pendingIntent(context, AutoBillActionReceiver.ACTION_CONFIRM, pending.id),
            )
            .addAction(0, context.getString(R.string.auto_notif_change), openIntent)
            .addAction(
                0,
                context.getString(R.string.auto_notif_ignore),
                AutoBillActionReceiver.pendingIntent(context, AutoBillActionReceiver.ACTION_IGNORE, pending.id),
            )

        try { nm.notify(notificationId(pending.id), builder.build()) } catch (_: SecurityException) { /* Permission may change between check and notify. */ }
    }

    /** 自动入账成功后，把「记一笔？」替换成结果通知，让用户知道这笔已经记上了。 */
    fun notifySaved(pendingId: String, amountCents: Long, merchant: String?, assetName: String?) {
        if (!AutoBillPermission.notificationsGranted(context)) return
        val line = summaryLine(amountCents, merchant, assetName, duplicate = false)
        val openIntent = PendingIntent.getActivity(
            context,
            AutoBillActionReceiver.requestCode(pendingId, 3),
            AutoBillConfirmActivity.intent(context, pendingId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, FamilyLedgerApp.CHANNEL_AUTO_BILL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.auto_notif_saved))
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        try { nm.notify(notificationId(pendingId), n) } catch (_: SecurityException) { /* Permission was revoked. */ }
    }

    fun cancel(pendingId: String) {
        runCatching { nm.cancel(notificationId(pendingId)) }
    }

    /**
     * 一次性提示：识别到付款了，但没有「显示在其他应用上层」权限，所以弹不出记账卡片。
     *
     * 只在「用户确实开了弹卡片开关 + 没权限 + 从没提示过」时由 [AutoBillPipeline] 调一次
     * （`settings.autoBillPopupHintShown` 负责记「已经提示过」）。点通知或「去开启」直接跳到授权页。
     *
     * @return true = 通知真的发出去了（此时调用方才把 hintShown 置 true；发不出去就下次再试）
     */
    fun notifyOverlayPermissionHint(): Boolean {
        if (!AutoBillPermission.notificationsGranted(context)) return false
        val text = context.getString(R.string.auto_perm_overlay_hint_text)
        val open = PendingIntent.getActivity(
            context,
            OVERLAY_HINT_REQUEST_CODE,
            AutoBillPermission.overlaySettingsIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, FamilyLedgerApp.CHANNEL_AUTO_BILL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.auto_perm_overlay_hint_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.auto_perm_overlay_hint_action), open)
            .build()
        return try { nm.notify(OVERLAY_HINT_NOTIFICATION_ID, n); true } catch (_: SecurityException) { false }
    }

    fun cancelAll() {
        runCatching { nm.cancelAll() }
    }

    private fun summaryLine(amountCents: Long, merchant: String?, assetName: String?, duplicate: Boolean): String {
        val sb = StringBuilder(Money.format(amountCents))
        merchant?.takeIf { it.isNotBlank() }?.let { sb.append(" · ").append(it) }
        assetName?.takeIf { it.isNotBlank() }?.let { sb.append(" · ").append(it) }
        if (duplicate) sb.append(" · ").append(context.getString(R.string.auto_notif_duplicate))
        return sb.toString()
    }

    companion object {
        fun notificationId(pendingId: String): Int = pendingId.hashCode() and 0x7FFFFFFF

        /** 「去开启悬浮窗权限」提示通知的固定 id（与待确认账单通知互不覆盖）。 */
        private const val OVERLAY_HINT_NOTIFICATION_ID = 0x7A11
        private const val OVERLAY_HINT_REQUEST_CODE = 0x7A12
    }
}
