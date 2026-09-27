package com.family.ledger

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.family.ledger.auto.*
import com.family.ledger.core.FixedPeople
import com.family.ledger.data.SettingsStore
import com.family.ledger.data.db.AppDatabase
import com.family.ledger.data.db.entity.*
import com.family.ledger.data.repo.*
import com.family.ledger.ui.theme.FamilyLedgerTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class AutoRecoveryInteractionTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var c: AppContainer
    private lateinit var db: AppDatabase
    private lateinit var pipeline: AutoBillPipeline
    private lateinit var pending: PendingBillEntity
    private lateinit var origin: TxnEntity
    private var visible by mutableStateOf(true)
    @Before fun prepare() = runBlocking<Unit> {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext;check(ctx.packageName.endsWith(".dev"))
        db=Room.inMemoryDatabaseBuilder(ctx,AppDatabase::class.java).build()
        val prefs=SettingsStore(ctx,"auto-recovery-ui-"+UUID.randomUUID());prefs.syncEnabled=false;prefs.lanSyncEnabled=false;prefs.autoBillPopupEnabled=false
        c=AppContainer(ctx,db,prefs);c.family.selectPerson(FixedPeople.A)
        origin=c.ledger.save(TxnDraft(bookId=c.family.defaultBook().id,amountCents=10000,assetId=c.assets.all().first().id,
            occurredAt=System.currentTimeMillis()-86400000L,merchant="自动退款测试商户"))
        pipeline=AutoBillPipeline(ctx,c)
        pipeline.handle(PaySignal(PayPackages.ALIPAY,2000,"自动退款测试商户","退款成功20元",System.currentTimeMillis(),
            Channel.ALIPAY,Direction.REFUND,null,orderId="auto-refund-ui-test",origin=Origin.ACCESSIBILITY))
        pending=c.db.pendingBillDao().allPending().single()
    }
    @After fun close() { db.close() }
    private fun show() { compose.setContent { FamilyLedgerTheme { if(visible) AutoBillConfirmScreen(pending.id,c,pipeline,{visible=false}) else Text("退款确认已关闭") } } }
    @Test fun detectedRefundSelectsOriginalAndClosesOnlyAfterLinkedSave() {
        show()
        compose.waitUntil(5000) { compose.onAllNodesWithText("识别到退款 · 选择原支出").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { assertEquals(1,c.ledger.count()) }
        compose.onNodeWithText("自动退款测试商户").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("支出账单 · 退款").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("20.00").assertExists()
        compose.onNodeWithText("收入").assertDoesNotExist()
        compose.onNodeWithText("确认退款").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("退款确认已关闭").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { val r=c.ledger.all().single { it.type==TxnType.REFUND }; assertEquals(origin.id,r.relatedTxnId);assertEquals(2000L,r.amount) }
    }
    @Test fun alreadyConfirmedRefundDoesNotOfferAnotherLinkOrSave() {
        runBlocking { pipeline.confirmRecovery(pending.id,AutoBillPipeline.ConfirmOverride(relatedTxnId=origin.id,assetId=origin.assetId)) }
        show()
        compose.waitUntil(5000) { compose.onAllNodesWithText("这条账单已经处理过了").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("识别到退款 · 选择原支出").assertDoesNotExist()
        compose.onNodeWithText("确认退款").assertDoesNotExist()
        runBlocking { assertEquals(2,c.ledger.count()) }
    }
}
