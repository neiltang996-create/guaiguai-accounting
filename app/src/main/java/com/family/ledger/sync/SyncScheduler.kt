package com.family.ledger.sync

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import com.family.ledger.FamilyLedgerApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.debounce
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 持久任务由 WorkManager 调度：联网约束、指数退避、重启恢复，不依赖后台启动前台服务。 */
object SyncScheduler {
    const val FOREGROUND_INTERVAL_MS = SyncRepository.FOREGROUND_INTERVAL_MS
    const val DEFAULT_INTERVAL_MS = SyncRepository.DEFAULT_INTERVAL_MS
    private const val IMMEDIATE = "family-sync-now"
    private const val PERIODIC = "family-sync-periodic"
    private val installed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    @OptIn(FlowPreview::class)
    fun install(context: Context, foregroundIntervalMs: Long = FOREGROUND_INTERVAL_MS, backgroundIntervalMs: Long = DEFAULT_INTERVAL_MS) {
        if (!installed.compareAndSet(false, true)) return
        schedulePeriodic(context, backgroundIntervalMs)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var polling: Job? = null
            override fun onStart(owner: LifecycleOwner) {
                polling = scope.launch {
                    while (isActive) {
                        FamilyLedgerApp.containerOrNull()?.sync?.let { runCatching { it.syncIfDue(foregroundIntervalMs) } }
                        delay(foregroundIntervalMs)
                    }
                }
            }
            override fun onStop(owner: LifecycleOwner) { polling?.cancel(); polling = null }
        })
        scope.launch {
            val c = FamilyLedgerApp.containerOrNull() ?: return@launch
            c.db.syncDao().observeOwnMaxSeq(c.settings.deviceId).debounce(700).collect {
                if (it != null && c.settings.identityChosen && c.sync.isEnabled()) syncNow(context)
            }
        }
    }
    fun syncNow(context: Context) {
        val c = FamilyLedgerApp.containerOrNull() ?: return
        if (!c.settings.identityChosen || !c.sync.isEnabled()) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
    fun schedulePeriodic(context: Context, intervalMs: Long = DEFAULT_INTERVAL_MS) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMs.coerceAtLeast(900_000), TimeUnit.MILLISECONDS)
            .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
    fun uninstall(context: Context) = cancel(context)
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as FamilyLedgerApp).container
        if (!c.settings.identityChosen || !c.sync.isEnabled()) return Result.success()
        return try {
            c.family.ensureBootstrap()
            if (c.sync.syncNowAuto().success) Result.success() else Result.retry()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.retry() }
    }
}
