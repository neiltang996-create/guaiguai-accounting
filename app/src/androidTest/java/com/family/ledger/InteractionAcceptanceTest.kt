package com.family.ledger

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.family.ledger.core.FixedPeople
import com.family.ledger.data.SettingsStore
import com.family.ledger.data.db.AppDatabase
import com.family.ledger.data.db.entity.*
import com.family.ledger.ui.assets.AssetManageScreen
import com.family.ledger.ui.list.BillListScreen
import com.family.ledger.ui.theme.FamilyLedgerTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class InteractionAcceptanceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var c: AppContainer
    private lateinit var db: AppDatabase
    private val name = "校准交互测试账户"
    private var assetsVisible by mutableStateOf(true)
    @Before fun prepare() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".dev"))
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = SettingsStore(context, "interaction-test-" + UUID.randomUUID())
        settings.syncEnabled = false; settings.lanSyncEnabled = false
        c = AppContainer(context, db, settings)
        c.family.selectPerson(FixedPeople.A)
        c.assets.upsert(AssetEntity(id="test-calibration", name=name, type=AssetType.CASH,
            ownerType=OwnerType.USER, ownerUserId=FixedPeople.A, createdAt=1, updatedAt=1))
        compose.setContent { FamilyLedgerTheme {
            if (assetsVisible) AssetManageScreen(c, {}) else BillListScreen(c, {})
        } }
    }
    @After fun close() { db.close() }
    private fun openCalibration() {
        compose.waitUntil(5000) { compose.onAllNodesWithText("资产管理").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performClick()
        compose.onNodeWithText("余额校准").performClick()
        compose.onNodeWithText("真实余额（元）").performTextReplacement("123.45")
    }
    @Test fun successfulCalibrationClosesWhileFeedbackIsStillVisibleAndStaysOutOfBills() {
        openCalibration()
        compose.onNodeWithText("校准", useUnmergedTree=true).performClick()
        compose.waitUntil(2500) { compose.onAllNodesWithText("余额已校准").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("余额校准").assertDoesNotExist()
        runBlocking { assertEquals(12345L, db.balanceAnchorDao().all().single { it.assetId=="test-calibration" }.realBalance) }
        compose.runOnIdle { assetsVisible = false }
        compose.waitForIdle()
        compose.onNodeWithText("余额校准").assertDoesNotExist()
    }
    @Test fun calibrationWriteFailurePreservesDialogAndTypedBalance() {
        openCalibration()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_calibration BEFORE INSERT ON sync_op BEGIN SELECT RAISE(ABORT, 'test storage failure'); END")
        compose.onNodeWithText("校准", useUnmergedTree=true).performClick()
        compose.waitUntil(2500) { compose.onAllNodes(hasText("校准失败", substring=true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("余额校准").assertIsDisplayed()
        compose.onNodeWithText("123.45").assertIsDisplayed()
        runBlocking { assertTrue(db.balanceAnchorDao().all().none { it.assetId=="test-calibration" }) }
    }
}
