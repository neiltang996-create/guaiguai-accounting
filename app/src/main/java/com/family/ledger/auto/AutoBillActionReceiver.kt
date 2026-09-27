package com.family.ledger.auto

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.family.ledger.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动记账 · 通知动作接收器（记入 / 忽略）。
 *
 * **注意**：manifest 已冻结，本 receiver 不在 manifest 里声明，而是由
 * [AutoBillPipeline] / 两个服务 / 确认页在进程内**动态注册**（`RECEIVER_NOT_EXPORTED`）。
 * 因此广播 Intent 只用 action（不设 component）——显式 Intent 不会投递给动态注册的接收器。
 *
 * 「改一下」走 [AutoBillConfirmActivity]，那条路径不受进程重启影响。
 */
class AutoBillActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        if (action != ACTION_CONFIRM && action != ACTION_IGNORE) return
        val pendingId = intent.getStringExtra(EXTRA_PENDING_ID) ?: return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val pipeline = AutoBillPipeline.getOrNull(appContext) ?: return@launch
                when (action) {
                    ACTION_CONFIRM -> {
                        val model = pipeline.loadConfirmModel(pendingId)
                        if (model?.direction == Direction.REFUND) {
                            appContext.startActivity(AutoBillConfirmActivity.intent(appContext, pendingId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            return@launch
                        }
                        val txnId = pipeline.confirm(pendingId)
                        if (txnId != null) toast(appContext, appContext.getString(R.string.auto_notif_saved))
                    }
                    ACTION_IGNORE -> pipeline.ignore(pendingId)
                }
            } catch (t: Throwable) {
                // 广播里绝不抛异常：静默失败，通知还在，用户可以从确认页继续
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    private fun toast(context: Context, text: String) {
        runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val ACTION_CONFIRM = "com.family.ledger.auto.action.CONFIRM"
        const val ACTION_IGNORE = "com.family.ledger.auto.action.IGNORE"
        const val EXTRA_PENDING_ID = "auto_bill_pending_id"

        private val registered = AtomicBoolean(false)
        private var receiver: AutoBillActionReceiver? = null

        /** 幂等注册（进程内只注册一次）。 */
        fun ensureRegistered(context: Context) {
            if (!registered.compareAndSet(false, true)) return
            try {
                val r = AutoBillActionReceiver()
                val filter = IntentFilter().apply {
                    addAction(ACTION_CONFIRM)
                    addAction(ACTION_IGNORE)
                }
                ContextCompat.registerReceiver(
                    context.applicationContext,
                    r,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                receiver = r
            } catch (t: Throwable) {
                registered.set(false)
            }
        }

        fun isRegistered(): Boolean = registered.get()

        fun pendingIntent(context: Context, action: String, pendingId: String): PendingIntent {
            ensureRegistered(context)
            // 必须 setPackage：Android 14+ 起「隐式 Intent 不再投递给内部组件」，
            // 只带 action 的广播匹配不到 RECEIVER_NOT_EXPORTED 的动态 receiver
            // （实测：不加包名 → AMS 记录 terminalCount=0、零接收者；加包名 → 正常投递）。
            // 仍然只在本应用内可见，NOT_EXPORTED 的安全属性不变。
            val intent = Intent(action)
                .setPackage(context.packageName)
                .putExtra(EXTRA_PENDING_ID, pendingId)
            return PendingIntent.getBroadcast(
                context,
                requestCode(pendingId, action.hashCode()),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun requestCode(pendingId: String, salt: Int): Int = (pendingId.hashCode() * 31 + salt) and 0x7FFFFFFF
    }
}
