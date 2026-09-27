package com.family.ledger

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.family.ledger.auto.*
import com.family.ledger.core.FixedPeople
import com.family.ledger.data.SettingsStore
import com.family.ledger.data.db.AppDatabase
import com.family.ledger.demo.DemoData
import com.family.ledger.ui.home.HomeScreen
import com.family.ledger.ui.assets.AssetManageScreen
import com.family.ledger.ui.add.AddBillScreen
import com.family.ledger.ui.stats.StatsScreen
import com.family.ledger.ui.list.BillDetailsDialog
import com.family.ledger.ui.theme.FamilyLedgerTheme
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*

/** Public screenshots use production composables, synthetic inputs and in-memory storage. */
class PublicScreenshotsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun captureSyntheticScreensAndVerifyDemoIsIdempotent() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".dev"))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val c = AppContainer(context, db, SettingsStore(context, "public-demo-" + System.nanoTime()))
        try {
            DemoData.seed(c)
            c.settings.autoBillPopupEnabled = false
            val count = c.ledger.count()
            DemoData.seed(c)
            assertEquals(count, c.ledger.count())
            assertEquals(8, count)
            assertFalse(c.settings.syncEnabled); assertFalse(c.settings.lanSyncEnabled)
            val origin = c.ledger.all().single { it.merchant == "示例餐饮支出" && it.type == com.family.ledger.data.db.entity.TxnType.EXPENSE }
            val now = System.currentTimeMillis()
            c.settings.autoBillEnabled = true // Synthetic signal only, never a device notification.
            val pipeline = AutoBillPipeline(context, c)
            pipeline.handle(PaySignal(PayPackages.ALIPAY, 3050L, "示例社区便利店", "支付成功30.50元", now,
                Channel.ALIPAY, Direction.PAYMENT, "示例共同零钱", orderId="SYNTHETIC-SCREENSHOT", origin=Origin.ACCESSIBILITY))
            val pending = db.pendingBillDao().allPending().single().id
            var screen by mutableStateOf(0)
            compose.setContent {
                FamilyLedgerTheme(darkTheme=true) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().statusBarsPadding().consumeWindowInsets(WindowInsets.statusBars)) {
                            Text("演示数据 · 用户 A / 用户 B · 仅本机", Modifier.padding(12.dp), style=MaterialTheme.typography.labelMedium)
                            Box(Modifier.weight(1f)) {
                                when(screen) {
                                    0 -> HomeScreen(c, onAddBill={})
                                    1 -> AssetManageScreen(c, onBack={})
                                    2 -> AddBillScreen(c, onDone={}, onCancel={})
                                    3 -> StatsScreen(c, onBack={})
                                    4 -> { HomeScreen(c, onAddBill={}); BillDetailsDialog(c, origin.id, onDismiss={}) }
                                    5 -> AutoBillConfirmScreen(pending, c, pipeline, onDismiss={})
                                }
                            }
                        }
                    }
                }
            }
            fun capture(name: String) {
                compose.waitForIdle(); android.os.SystemClock.sleep(700)
                val file=java.io.File(context.getExternalFilesDir(null), "public-$name.png")
                val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
                try { file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
            }
            fun show(n: Int) { compose.runOnIdle { screen=n }; compose.waitForIdle(); android.os.SystemClock.sleep(600) }
            compose.waitUntil(10000) { compose.onAllNodesWithText("乖乖记账").fetchSemanticsNodes().isNotEmpty() }
            capture("home")
            show(1); compose.waitUntil(10000) { compose.onAllNodesWithText("示例家庭储蓄卡").fetchSemanticsNodes().isNotEmpty() }; capture("assets")
            show(2)
            for ((label, name) in listOf("记账人" to "用户 A", "付款人" to "用户 A", "消费人" to "用户 B")) {
                compose.onNode(hasText(label, substring=true) and hasClickAction()).performClick()
                compose.onAllNodesWithText(name).onLast().performClick()
            }
            capture("roles")
            show(3); compose.onNodeWithText("收入分析").performClick(); capture("stats-income")
            show(4); capture("recoveries")
            show(5); capture("auto-confirm")
        } finally { db.close() }
    }
}
