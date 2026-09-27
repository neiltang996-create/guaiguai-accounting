package com.family.ledger.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.family.ledger.FamilyLedgerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 开机 / 应用更新后恢复自动记账。
 *
 * Android 15 起不允许从后台启动前台服务，所以这里**只做轻量恢复**：
 *  - 重新注册通知动作接收器（进程内动态注册的 receiver 不跨进程存活）；
 *  - 把当前授权状态与开关写一条 auto_bill_log，方便排查「为什么没自动记账」；
 *  - 绝不启动任何服务、绝不弹 Activity，任何异常都吞掉。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val appContext = context.applicationContext
        val action = intent?.action.orEmpty()
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                AutoBillActionReceiver.ensureRegistered(appContext)
                val app = appContext as? FamilyLedgerApp ?: return@launch
                val container = runCatching { app.container }.getOrNull() ?: return@launch
                val status = AutoBillPermission.status(appContext)
                container.ledger.logAutoBill(
                    action = "BOOT",
                    matchingMode = action,
                    entryJson = buildJsonObject {
                        put("action", action)
                        put("autoBillEnabled", container.settings.autoBillEnabled)
                        put("autoCommit", container.settings.autoBillAutoCommit)
                        put("accessibility", status.accessibilityEnabled)
                        put("notificationAccess", status.notificationAccessEnabled)
                        put("notifications", status.notificationsGranted)
                    }.toString(),
                )
            } catch (t: Throwable) {
                // 开机恢复失败不能让系统崩溃
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }
}
