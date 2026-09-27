package com.family.ledger.auto

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.family.ledger.FamilyLedgerApp

/**
 * 自动记账 · 快速确认页（底部大面板的宿主）。
 *
 * 从单笔页面识别或通知进入，[AutoBillConfirmScreen] 会用 `ModalBottomSheet`
 * 从底部滑出钱迹式面板，确认后建流水 + 学商户，然后 `finish()`。
 *
 * 说明：
 * - manifest 已冻结（`Theme.FamilyLedger.Transparent`：透明底 + 半透明遮罩 + noHistory + taskAffinity），
 *   所以**面板背后能直接看到支付宝付款页**；这里只把基类从 `Activity` 换成 `ComponentActivity` 启用 Compose。
 * - 遮罩浓度调轻一点（0.45），既能看出「浮在支付页之上」，又不至于糊成一片黑。
 */
class AutoBillConfirmActivity : ComponentActivity() {
    private var presentationKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 通知动作走动态注册的广播，任何入口进来都顺手保证它已注册
        AutoBillActionReceiver.ensureRegistered(this)

        // 半透明遮罩：背后是支付宝/微信的付款页（面板里的编辑弹窗自带软键盘适配）
        runCatching { window.setDimAmount(DIM_AMOUNT) }

        presentationKey = intent?.getStringExtra(EXTRA_PRESENTATION_KEY)
        presentationKey?.let(AutoBillPresentation::opened)
        if (intent?.getBooleanExtra(EXTRA_ALREADY_RECORDED, false) == true) {
            setContent {
                com.family.ledger.ui.theme.FamilyLedgerTheme {
                    AlertDialog(onDismissRequest = { finish() },
                        title = { Text("已记录") },
                        text = { Text("这条账单已经记录过了，已忽略本次识别。") },
                        confirmButton = { TextButton(onClick = { finish() }) { Text("知道了") } })
                }
            }
            return
        }

        val pendingId = intent?.getStringExtra(EXTRA_PENDING_ID)
        val container = (application as? FamilyLedgerApp)?.let { runCatching { it.container }.getOrNull() }
        if (pendingId.isNullOrBlank() || container == null) {
            finish()
            return
        }
        val pipeline = AutoBillPipeline.getOrNull(this)

        setContent {
            com.family.ledger.ui.theme.FamilyLedgerTheme {
                AutoBillConfirmScreen(
                    pendingId = pendingId,
                    container = container,
                    pipeline = pipeline,
                    onDismiss = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        presentationKey?.let(AutoBillPresentation::closed)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PENDING_ID = "auto_bill_pending_id"
        const val EXTRA_PRESENTATION_KEY = "auto_bill_presentation_key"
        private const val EXTRA_ALREADY_RECORDED = "auto_bill_already_recorded"

        /** 遮罩浓度：能看到背后的付款页，又能聚焦面板。 */
        private const val DIM_AMOUNT = 0.45f

        fun intent(context: Context, pendingId: String): Intent =
            Intent(context, AutoBillConfirmActivity::class.java)
                .putExtra(EXTRA_PENDING_ID, pendingId)

        fun recordedIntent(context: Context): Intent =
            Intent(context, AutoBillConfirmActivity::class.java).putExtra(EXTRA_ALREADY_RECORDED, true)
    }
}
