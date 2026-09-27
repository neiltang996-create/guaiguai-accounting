package com.family.ledger

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.family.ledger.core.FixedPeople
import com.family.ledger.data.SettingsStore
import com.family.ledger.data.db.AppDatabase
import com.family.ledger.data.db.entity.*
import com.family.ledger.data.repo.*
import com.family.ledger.ui.list.BillDetailsDialog
import com.family.ledger.ui.theme.FamilyLedgerTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class RecoveryInteractionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var c: AppContainer
    private lateinit var db: AppDatabase
    private lateinit var origin: TxnEntity
    private var visible by mutableStateOf(true)
    @Before fun prepare() = runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".dev"))
        db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val prefs=SettingsStore(context,"recovery-ui-"+UUID.randomUUID())
        prefs.syncEnabled=false;prefs.lanSyncEnabled=false;prefs.autoBillPopupEnabled=false
        c=AppContainer(context,db,prefs);c.family.selectPerson(FixedPeople.A)
        c.assets.upsert(AssetEntity(id="recovery-ui-wallet",name="测试到账钱包",type=AssetType.CASH,ownerType=OwnerType.USER,ownerUserId=FixedPeople.A,createdAt=1,updatedAt=1))
        origin=c.ledger.save(TxnDraft(bookId=c.family.defaultBook().id,amountCents=10000,assetId=c.assets.all().first { it.id != "recovery-ui-wallet" }.id,
            occurredAt=System.currentTimeMillis()-86400000L,merchant="测试餐饮支出"))
        compose.setContent { FamilyLedgerTheme { if (visible) BillDetailsDialog(c,origin.id,onDismiss={visible=false}) else Text("详情已关闭") } }
    }
    @After fun close() { db.close() }
    private fun open(kind: String) {
        compose.waitUntil(5000) { compose.onAllNodesWithText("账单详情").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("退款").assertIsDisplayed();compose.onNodeWithText("报销").assertIsDisplayed()
        compose.onNodeWithText(kind).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("支出账单 · $kind").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("${kind}金额（元）").performTextInput("30.00")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(500) // 等待系统窗口淡出，Compose idle 不覆盖 WindowManager 动画。
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name + ".png")
        instrumentation.uiAutomation.takeScreenshot().useBitmap { bitmap -> file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
    }
    private inline fun android.graphics.Bitmap.useBitmap(action: (android.graphics.Bitmap) -> Unit) { try { action(this) } finally { recycle() } }
    @Test fun reimbursementChoosesAccountSavesThenReturnsToUpdatedOriginal() {
        open("报销")
        compose.onNode(hasText("到账账户：",substring=true) and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("测试到账钱包").performScrollTo().performClick()
        capture("recovery-reimbursement-form")
        compose.onNodeWithText("确认报销").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("账单详情").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("支出账单 · 报销").assertDoesNotExist()
        compose.onNode(hasText("净支出",substring=true)).assertTextContains("净支出 ¥70",substring=true)
        capture("recovery-original-after-return")
        runBlocking {
            val r=c.ledger.all().single { it.type==TxnType.REFUND }
            assertEquals(3000L,r.amount);assertEquals("recovery-ui-wallet",r.assetId);assertEquals("REIMBURSEMENT",r.recoveryKind)
        }
    }
    @Test fun refundFailureKeepsAmountAndAllowsRetryThenUndo() {
        open("退款")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_recovery_ui BEFORE INSERT ON sync_op BEGIN SELECT RAISE(ABORT,'test recovery save failure'); END")
        compose.onNodeWithText("确认退款").performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasText("test recovery save failure",substring=true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("支出账单 · 退款").assertIsDisplayed()
        compose.onNodeWithText("30.00").assertExists()
        runBlocking { assertEquals(1,c.ledger.count()) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_recovery_ui")
        compose.onNodeWithText("确认退款").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("撤销这笔退款").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("撤销这笔退款").performScrollTo().performClick()
        compose.onNodeWithText("确认撤销").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("撤销这笔退款").fetchSemanticsNodes().isEmpty() }
        runBlocking { assertEquals(1,c.ledger.count()) }
        compose.onNode(hasText("净支出",substring=true)).assertTextContains("净支出 ¥100",substring=true)
    }
    @Test fun excessiveAmountCannotSaveAndBackCreatesNoReturn() {
        open("退款")
        compose.onNodeWithText("退款金额（元）").performTextReplacement("100.01")
        compose.onNodeWithText("确认退款").assertIsNotEnabled()
        compose.onNodeWithText("返回").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("账单详情").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { assertEquals(1,c.ledger.count()) }
    }
}
