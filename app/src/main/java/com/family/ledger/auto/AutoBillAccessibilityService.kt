package com.family.ledger.auto

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.family.ledger.MainActivity
import com.family.ledger.auto.rules.BillPageParser
import com.family.ledger.auto.rules.NodeSnapshot
import com.family.ledger.auto.rules.PageRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Read only supported payment apps; page rules decide whether this is a single receipt. */
class AutoBillAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val visits = ReceiptVisitGate()
    private val settler = ReceiptSettler()
    private val dumpGate = DedupEngine.TextHashCache(windowMs = 600_000L, maxSize = 32)
    private var dumpCount = 0
    private var lastScanAt = -1_000L
    private var lastDebugHash: String? = null
    private var nonReceiptSince: Long? = null
    private var lastRootPackage: String? = null
    @Volatile private var paymentAppActive = false
    private var foregroundStarted = false
    private val scanning = AtomicBoolean(false)
    private var lastHealthLog = 0L
    private val receiptOcr by lazy { LocalReceiptOcr(this) }
    @Volatile private var foregroundActivity: String? = null
    private var lastOcrAt = -2_000L
    private var lastPaymentWindowId: Int? = null

    // Some ROM/app combinations stop delivering content events while the service remains bound.
    // Check only the root package while idle; traverse nodes only in the payment-app allowlist.
    private val watchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastHealthLog > 5_000L && com.family.ledger.FamilyLedgerApp.get().container.settings.debugCaptureEnabled) {
                lastHealthLog = now
                Log.i(DIAG, "health scanning=${scanning.get()} showing=${AutoBillPresentation.isShowing}")
            }
            scanSafely("watchdog", null)
            main.postDelayed(this, if (paymentAppActive) 700L else 1_500L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            packageNames = PayPackages.WATCHED.toTypedArray()
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            notificationTimeout = 50L
        }
        Log.i(DIAG, "connected; events plus foreground payment-page watchdog")
        runCatching { AutoBillActionReceiver.ensureRegistered(this) }
        main.removeCallbacks(watchdog)
        main.post(watchdog)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !PayPackages.isWatched(event.packageName?.toString())) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val name = event.className?.toString()
            if (name != null && !name.startsWith("android.") && !name.startsWith("androidx.")) foregroundActivity = name
        }
        scanSafely(AccessibilityEvent.eventTypeToString(event.eventType), event.className?.toString())
    }

    private fun scanSafely(trigger: String, activity: String?) {
        if (!scanning.compareAndSet(false, true)) return
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            try { scan(trigger, activity) }
            catch (t: Exception) { Log.w(DIAG, "scan failed trigger=$trigger", t) }
            finally {
                val took = SystemClock.elapsedRealtime() - started
                if (took > 250L) Log.w(DIAG, "slow scan took=${took}ms trigger=$trigger")
                scanning.set(false)
            }
        }
    }

    private suspend fun scan(trigger: String, activity: String?) {
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - lastScanAt < 100L) return
        lastScanAt = elapsed
        val pipeline = AutoBillPipeline.getOrNull(this) ?: return
        if (!pipeline.enabled) {
            visits.leave(); paymentAppActive = false
            if (foregroundStarted) { stopForeground(STOP_FOREGROUND_REMOVE); foregroundStarted = false }
            return
        }
        ensureForeground()
        if (!getSystemService(PowerManager::class.java).isInteractive) {
            visits.leave(); paymentAppActive = false; return
        }
        // Never parse the receipt behind our own window, or treat closing that window as a new visit.
        if (AutoBillPresentation.isShowing) return
        if (trigger == "watchdog" && Build.VERSION.SDK_INT >= 33) clearCache()
        val root = rootInActiveWindow ?: return // A missing root during navigation is not a page exit.
        val pkg = root.packageName?.toString() ?: return
        if (pkg != lastRootPackage) {
            Log.i(DIAG, "foreground=$pkg trigger=$trigger")
            lastRootPackage = pkg
        }
        paymentAppActive = PayPackages.isWatched(pkg)
        if (!paymentAppActive) {
            // Our translucent Activity can remain the active root for a moment during dismissal.
            if (pkg != packageName || AutoBillPresentation.isMainVisible) visits.leave()
            settler.ready(null, elapsed)
            nonReceiptSince = null
            return
        }
        if (lastPaymentWindowId != null && lastPaymentWindowId != root.windowId) {
            visits.leave(); settler.ready(null, elapsed)
        }
        lastPaymentWindowId = root.windowId
        // Empty protected roots still convey their Activity/window transition. Returning from a
        // native wallet/chat to the same receipt must count as a new visit.
        if (pkg == PayPackages.WECHAT && foregroundActivity?.startsWith("com.tencent.mm.") == true &&
            !ReceiptCapturePolicy.mayOcr(pkg, foregroundActivity)) {
            visits.leave(); settler.ready(null, elapsed)
        }
        root.refresh()
        var snapshot = AutoBillRules.snapshotOf(root) ?: return
        var text = snapshot.textDump(3_000)
        var receiptBitmap: android.graphics.Bitmap? = null
        val ocrEnabled = com.family.ledger.FamilyLedgerApp.get().container.settings.receiptOcrEnabled
        // Native WeChat chats and password pages are outside this fallback. Only its payment webview
        // is eligible, and an image is retained only after strict single-receipt rules succeed.
        if (ocrEnabled && BillPageParser.parseSignal(pkg, snapshot, System.currentTimeMillis()) == null &&
            ReceiptCapturePolicy.mayOcr(pkg, foregroundActivity) &&
            android.os.Build.VERSION.SDK_INT >= 30) {
            if (elapsed - lastOcrAt < 1_200L) return
            lastOcrAt = elapsed
            receiptBitmap = receiptOcr.capture(root.windowId)
            if (receiptBitmap != null) {
                try {
                    snapshot = receiptOcr.read(receiptBitmap, pkg)
                    text = snapshot.textDump(3_000)
                    val current = rootInActiveWindow
                    if (!com.family.ledger.FamilyLedgerApp.get().container.settings.receiptOcrEnabled || AutoBillPresentation.isShowing || current?.packageName?.toString() != pkg ||
                        current.windowId != root.windowId || !getSystemService(PowerManager::class.java).isInteractive) {
                        receiptBitmap.recycle(); return
                    }
                } catch (t: Exception) {
                    receiptBitmap.recycle(); receiptBitmap = null
                    Log.w(DIAG, "local OCR unavailable", t)
                    return
                }
            }
        }
        try {
        val signal = if (text.length < 6) null else BillPageParser.parseSignal(pkg, snapshot, System.currentTimeMillis())
        val hash = DedupEngine.textHash("$pkg|${snapshot.nodeCount()}|$text")
        if (hash != lastDebugHash) {
            lastDebugHash = hash
            Log.i(DIAG, "page=${signal?.pageType ?: "not-receipt"} nodes=${snapshot.nodeCount()} textChars=${text.length} trigger=$trigger ocr=${receiptBitmap != null} activity=$foregroundActivity")
            val nodes = if (com.family.ledger.FamilyLedgerApp.get().container.settings.debugCaptureEnabled)
                AutoBillRules.dumpJson(snapshot) else null
            scope.launch { pipeline.recordDebug(pkg, activity, trigger, text, nodes, signal) }
        }
        // 空窗口也留诊断证据，但加载或受保护的空节点不能当成离开账单。
        if (text.length < 6) return
        if (signal == null) {
            settler.ready(null, elapsed)
            // A short blank/loading state must not cause a dismissed receipt to reopen in a loop.
            val since = nonReceiptSince
            if (since == null) nonReceiptSince = elapsed
            else if (elapsed - since >= 250L) visits.leave()
            maybeDumpUnknownPage(pkg, text, snapshot)
            return
        }
        nonReceiptSince = null
        val key = ReceiptVisitGate.key(signal)
        if (!settler.ready("$key|${signal.suggestedAccountHint}|${signal.couponCents}", elapsed)) return
        val token = visits.enter(key) ?: return
        Log.i(DIAG, "receipt visit=$token page=${signal.pageType} trigger=$trigger")
        if (receiptBitmap == null && ocrEnabled && Build.VERSION.SDK_INT >= 30) {
            receiptBitmap = receiptOcr.capture(root.windowId)
            // Navigation can race the callback. Never attach a different page to this bill.
            if (receiptBitmap != null) {
                // Readable native fields are more reliable than recognizing them again from pixels.
                // Re-read the window after capture and require the same receipt before attaching.
                if (Build.VERSION.SDK_INT >= 33) clearCache()
                val current = rootInActiveWindow
                current?.refresh()
                val afterCapture = current?.let(AutoBillRules::snapshotOf)?.let {
                    BillPageParser.parseSignal(pkg, it, System.currentTimeMillis())
                }
                if (current?.packageName?.toString() != pkg || current.windowId != root.windowId ||
                    afterCapture == null || !ReceiptCapturePolicy.sameReceipt(signal, afterCapture) ||
                    !getSystemService(PowerManager::class.java).isInteractive) {
                    receiptBitmap.recycle(); receiptBitmap = null
                }
            }
        }
        val attached = if (receiptBitmap != null) signal.copy(receiptImage =
            com.family.ledger.attachments.ReceiptFiles(this).save(receiptBitmap)) else signal
        scope.launch { pipeline.handle(attached, presentationAllowed = { visits.isCurrent(token) }) }
        } finally { receiptBitmap?.recycle() }
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("auto_bill_running", "自动记账运行状态",
            NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 4101, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "auto_bill_running")
            .setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle("自动记账正在运行")
            .setContentText("仅识别支持的支付和银行应用单笔账单")
            .setOngoing(true).setSilent(true).setContentIntent(open).build()
        runCatching {
            ServiceCompat.startForeground(this, 4101, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            foregroundStarted = true
            Log.i(DIAG, "foreground service started")
        }.onFailure { Log.w(DIAG, "foreground service unavailable", it) }
    }

    private fun maybeDumpUnknownPage(pkg: String, text: String, snapshot: NodeSnapshot) {
        if (!com.family.ledger.FamilyLedgerApp.get().container.settings.debugCaptureEnabled) return
        if (dumpCount >= 20 || !BillPageParser.isPaymentLike(text)) return
        if (dumpGate.markAndCheck(DedupEngine.textHash(text))) return
        val pipeline = AutoBillPipeline.getOrNull(this) ?: return
        val pageType = PageRecognizer.recognizePageType(pkg, snapshot)
        val reason = if (pageType != null) "PAGE_NO_AMOUNT:$pageType" else "UNRECOGNIZED"
        val json = AutoBillRules.dumpJson(snapshot)
        dumpCount++
        scope.launch { pipeline.logUnknownPage(pkg, text, json, reason = reason) }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        main.removeCallbacks(watchdog)
        Log.i(DIAG, "unbound")
        return super.onUnbind(intent)
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        main.removeCallbacks(watchdog)
        scope.cancel()
        super.onDestroy()
    }
    private companion object { const val DIAG = "AutoBillDiag" }
}
