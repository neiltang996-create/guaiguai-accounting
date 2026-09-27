package com.family.ledger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.family.ledger.sync.SyncScheduler

class FamilyLedgerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        createNotificationChannels()
        // 回到前台时按需自动同步（距上次超过间隔才触发；未配置/未初始化时静默跳过）
        SyncScheduler.install(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AUTO_BILL,
                getString(R.string.notif_channel_auto_bill),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "自动记账识别到付款后的待确认账单" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                getString(R.string.notif_channel_sync),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notif_channel_sync_desc) }
        )
    }

    companion object {
        const val CHANNEL_AUTO_BILL = "auto_bill"
        const val CHANNEL_SYNC = "sync"

        @Volatile private var instance: FamilyLedgerApp? = null
        fun get(): FamilyLedgerApp = instance ?: error("Application not created yet")
        fun containerOrNull(): AppContainer? = instance?.container
    }
}
