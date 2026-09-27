package com.family.ledger.auto

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 自动记账 · 授权状态检查与设置页跳转。
 *
 * 自动记账依赖三项授权，缺一不可但都可以独立降级：
 *  1. 无障碍（读支付结果页）—— 主力通道；
 *  2. 通知使用权（读微信/支付宝付款通知）—— 更稳更省电的通道；
 *  3. 通知权限（Android 13+）—— 没有它就无法弹「记一笔？」的确认提醒。
 *
 * 第四项是**可选**的：4. 「显示在其他应用上层」（`SYSTEM_ALERT_WINDOW`）—— 有了才能像钱迹那样
 * 付款后直接浮出记账卡片；没有就降级为通知，**绝不因为没有浮窗权限就不记账**。
 */
object AutoBillPermission {

    data class Status(
        val accessibilityEnabled: Boolean,
        val notificationAccessEnabled: Boolean,
        val notificationsGranted: Boolean,
        val overlayGranted: Boolean = false,
        val batteryExempt: Boolean = false,
    ) {
        /** 至少有一条识别通道可用。 */
        val anyChannelReady: Boolean get() = accessibilityEnabled || notificationAccessEnabled

        val allReady: Boolean get() = accessibilityEnabled && notificationAccessEnabled && notificationsGranted
    }

    fun status(context: Context): Status = Status(
        accessibilityEnabled = accessibilityEnabled(context),
        notificationAccessEnabled = notificationAccessEnabled(context),
        notificationsGranted = notificationsGranted(context),
        overlayGranted = canDrawOverlay(context),
        batteryExempt = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName),
    )

    /** 无障碍服务是否已开启。 */
    fun accessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context.packageName, AutoBillAccessibilityService::class.java.name)
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am != null) {
            val enabled = runCatching {
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            }.getOrNull().orEmpty()
            val hit = enabled.any { info ->
                val si = info.resolveInfo?.serviceInfo
                si != null && si.packageName == expected.packageName &&
                    (si.name == expected.className || si.name.endsWith("AutoBillAccessibilityService"))
            }
            if (hit) return true
        }
        // 兜底：直接读系统设置里的已启用列表
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        if (raw.isEmpty()) return false
        return raw.split(':').any { flat ->
            val cn = runCatching { ComponentName.unflattenFromString(flat) }.getOrNull()
            cn != null && cn.packageName == expected.packageName &&
                (cn.className == expected.className || cn.className.endsWith("AutoBillAccessibilityService"))
        }
    }

    /** 通知使用权（NotificationListenerService）是否已开启。 */
    fun notificationAccessEnabled(context: Context): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    /** Android 13+ 的 POST_NOTIFICATIONS 与用户级通知开关。 */
    fun notificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false)
    }

    // ---------- 浮窗（显示在其他应用上层） ----------

    /**
     * 是否已获得「显示在其他应用上层」权限 —— 有它才能像钱迹那样在微信/支付宝上方浮出记账卡片。
     *
     * 注意：这是**可选**权限。返回 false 时调用方必须降级为通知，不能因此不记账。
     * 读取过程绝不抛异常（部分 ROM 的 `Settings.canDrawOverlays` 会抛 SecurityException）。
     */
    fun canDrawOverlay(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    /** 跳「显示在其他应用上层」授权页（直接定位到本应用）。 */
    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---------- 跳转系统设置 ----------

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun backgroundSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 按「通知权限 → 无障碍 → 通知使用权」的顺序打开第一个缺失的授权页；全部就绪返回 false。 */
    fun openFirstMissingSettings(context: Context): Boolean {
        val s = status(context)
        val intent = when {
            !s.notificationsGranted -> appNotificationSettingsIntent(context)
            !s.accessibilityEnabled -> accessibilitySettingsIntent()
            !s.notificationAccessEnabled -> notificationAccessSettingsIntent()
            else -> return false
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
