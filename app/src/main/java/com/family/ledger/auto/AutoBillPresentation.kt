package com.family.ledger.auto

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/** 直接打开完整记账面板；页面访问去重由 ReceiptVisitGate 负责，此处仅防止窗口叠加。 */
object AutoBillPresentation {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var visibleKey: String? = null
    @Volatile private var launchingKey: String? = null
    @Volatile var isMainVisible: Boolean = false
    val isShowing: Boolean get() = visibleKey != null || launchingKey != null

    fun show(context: Context, pendingId: String?, recordedKey: String? = null,
             allowed: () -> Boolean = { true }) {
        val key = recordedKey?.let { "recorded:$it" } ?: "pending:$pendingId"
        main.post {
            if (!allowed() || isShowing) {
                Log.i("AutoBillWindow", "suppressed key=$key visible=$visibleKey")
                return@post
            }
            val intent = if (recordedKey != null) AutoBillConfirmActivity.recordedIntent(context)
                else AutoBillConfirmActivity.intent(context, requireNotNull(pendingId))
            runCatching {
                launchingKey = key
                context.startActivity(intent.putExtra(AutoBillConfirmActivity.EXTRA_PRESENTATION_KEY, key)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Log.i("AutoBillWindow", "requested key=$key")
                main.postDelayed({
                    if (launchingKey == key) {
                        launchingKey = null
                        Log.w("AutoBillWindow", "not visible after request key=$key; check system background-window permission")
                    }
                }, 1500L)
            }.onFailure { launchingKey = null; Log.w("AutoBillWindow", "launch failed", it) }
        }
    }

    fun opened(key: String) { launchingKey = null; visibleKey = key; Log.i("AutoBillWindow", "opened key=$key") }
    fun closed(key: String) {
        if (visibleKey == key) visibleKey = null
        Log.i("AutoBillWindow", "closed key=$key")
    }
}
