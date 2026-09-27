package com.family.ledger.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.family.ledger.FamilyLedgerApp
import com.family.ledger.MainActivity
import com.family.ledger.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 同步前台服务：把一次同步（家里的服务器优先，WebDAV 兜底）跑在显式的前台服务里，
 * 这样用户切走 App 也不会被系统掐断（Android 14+ 必须 startForeground 后再干活）。
 *
 * 用法：
 *   - UI / 定时器统一调 [start]（内部发 ACTION_SYNC_NOW）
 *   - 服务只负责「跑一次然后自杀」；真正逻辑在 [SyncRepository.syncNowAuto]
 *   - 若自动同步开关是关的，直接跳过不联网
 *
 * 任何一步失败都只写日志 + 更新 lastSyncMessage，绝不崩溃：
 * 通知渠道缺失、通知权限被拒、后台启动前台服务被系统拒绝都做了兜底。
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_SYNC_NOW
        var foreground = false
        try {
            // Android 14+ 要求先进入前台再工作；API 29 起显式声明 dataSync 类型
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("正在同步…"), type)
            foreground = true
        } catch (t: Throwable) {
            // 通知被禁用 / 系统限制后台启动前台服务：退化成普通后台任务，不能崩
            Log.w(TAG, "进入前台失败，退化为后台同步", t)
        }

        if (action != ACTION_SYNC_NOW) {
            finish(foreground, startId)
            return START_NOT_STICKY
        }

        scope.launch {
            val result = try {
                val container = FamilyLedgerApp.containerOrNull()
                when {
                    container == null -> SyncResult(success = false, message = "应用尚未初始化，无法同步")
                    // 跳过也要留可见原因：写 lastSyncMessage + 经 observeStatus 发给设置页
                    !container.sync.isEnabled() -> container.sync.reportSkipped(
                        "自动同步未打开：请到「设置 → 同步」打开「自动同步」"
                    )
                    else -> container.sync.syncNowAuto()
                }
            } catch (t: Throwable) {
                SyncResult(success = false, message = SyncErrors.translate(t))
            }
            Log.i(TAG, "同步结束：${result.message}")
            finish(foreground, startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun finish(foreground: Boolean, startId: Int) {
        try {
            if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf(startId)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, FamilyLedgerApp.CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.notif_channel_sync))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(FamilyLedgerApp.CHANNEL_SYNC) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                FamilyLedgerApp.CHANNEL_SYNC,
                getString(R.string.notif_channel_sync),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notif_channel_sync_desc) }
        )
    }

    companion object {
        private const val TAG = "FamilyLedgerSync"
        private const val NOTIFICATION_ID = 0x5A11

        const val ACTION_SYNC_NOW = "com.family.ledger.sync.action.SYNC_NOW"

        /** UI 侧调用入口：跑一次同步（前台服务承载）。 */
        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java).setAction(ACTION_SYNC_NOW)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // Android 12+ 后台启动前台服务受限时会抛异常，静默跳过（下次回到前台再同步）
                Log.w(TAG, "启动同步服务失败", t)
            }
        }
    }
}
