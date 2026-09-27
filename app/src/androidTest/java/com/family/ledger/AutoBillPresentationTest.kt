package com.family.ledger

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.family.ledger.auto.AutoBillConfirmActivity
import com.family.ledger.auto.*
import com.family.ledger.core.FixedPeople
import com.family.ledger.attachments.ReceiptFiles
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoBillPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun alreadyRecordedOpensNoticeWithoutSaveActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".dev"))
        ActivityScenario.launch<AutoBillConfirmActivity>(AutoBillConfirmActivity.recordedIntent(context)).use {
            compose.onNodeWithText("这条账单已经记录过了，已忽略本次识别。").assertIsDisplayed()
            compose.onNodeWithText("保存").assertDoesNotExist()
            compose.onNodeWithText("记下").assertDoesNotExist()
            capture("ui-recorded-notice")
            compose.onNodeWithText("知道了").performClick()
        }
    }

    @Test fun newReceiptEditorShowsFullFormWithoutSmallCardChoice() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".dev"))
        val c = (context.applicationContext as FamilyLedgerApp).container
        c.settings.syncEnabled = false
        c.settings.lanSyncEnabled = false
        c.settings.autoBillPopupEnabled = false
        c.family.selectPerson(FixedPeople.A)
        val pipeline = AutoBillPipeline(context, c)
        val order = "UI-" + java.util.UUID.randomUUID()
        val bitmap = android.graphics.Bitmap.createBitmap(240, 360, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.DKGRAY)
        val receipt = try { ReceiptFiles(context).save(bitmap) } finally { bitmap.recycle() }
        pipeline.handle(PaySignal(PayPackages.ALIPAY, 80000L, "示例礼服店", "支付成功800元", System.currentTimeMillis(),
            Channel.ALIPAY, Direction.PAYMENT, "余额宝", orderId = order, origin = Origin.ACCESSIBILITY, receiptImage = receipt))
        val pending = c.db.pendingBillDao().allPending().single { PendingBillCodec.extrasOf(it.rawText)["order"] == order }
        try {
            ActivityScenario.launch<AutoBillConfirmActivity>(AutoBillConfirmActivity.intent(context, pending.id)).use {
                compose.waitUntil(5_000L) { runCatching { compose.onNodeWithText("保存").assertIsDisplayed() }.isSuccess }
                compose.onNodeWithText("示例礼服店").assertIsDisplayed()
                compose.onNodeWithText("保存").assertIsDisplayed()
                compose.onNodeWithText("忽略这笔，不记账").assertIsDisplayed()
                compose.onNodeWithText("改一下").assertDoesNotExist()
                compose.onNodeWithText("记下").assertDoesNotExist()
                capture("ui-auto-editor")
                compose.onNodeWithText("查看账单截图 · 1 张").assertIsDisplayed().performClick()
                compose.onNodeWithText("账单截图").assertIsDisplayed()
                compose.onNodeWithText("关闭").performClick()
                compose.onNodeWithText("保存").assertIsDisplayed()
            }
        } finally { pipeline.ignore(pending.id) }
    }
}
