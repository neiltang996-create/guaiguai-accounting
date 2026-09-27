package com.family.ledger

import android.os.ParcelFileDescriptor
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.family.ledger.data.repo.BalanceCalculator
import com.family.ledger.data.db.entity.AssetType
import com.family.ledger.data.db.entity.OwnerType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated Room acceptance using hand-authored synthetic CSV, never a connected phone's ledger. */
@RunWith(AndroidJUnit4::class)
class QianJiImportAcceptanceTest {

    private lateinit var container: AppContainer

    /** 与 Python 独立复算一致的期望值。 */
    private val expectedTxnCount = 6
    private val expectedSeedCategories = 42
    private val expectedSeedAssets = 6

    private val sharedAssetName = "支付宝小荷包(示例日常)"
    private val expectedSharedBalanceCents = 5000L // -30 +100 +10 -20 -5 -5
    private val expectedSharedTxnCount = 6



    // 注意：JUnit 要求 @Before 方法返回 void。写成 `= runBlocking { ... }` 时，
    // 块的最后一句若返回值（这里是 ensureBootstrap 的 BootstrapResult），
    // 整个方法就变成有返回值，JUnit 会报 “Method setUp() should be void”。
    @Before
    fun setUp() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName.endsWith(".dev"))
            container = AppContainer(context, androidx.room.Room.inMemoryDatabaseBuilder(context, com.family.ledger.data.db.AppDatabase::class.java).build(),
                com.family.ledger.data.SettingsStore(context, "csv-test-" + java.util.UUID.randomUUID()))
            container.settings.lanSyncEnabled = false
            container.settings.syncEnabled = false
            container.family.selectPerson(com.family.ledger.core.FixedPeople.A)
            // 保证从干净状态开始，结果可复现
            container.db.clearAllTables()
            container.family.ensureBootstrap("用户 A")
        }
    }

    private fun csvTextOrSkip(): String = com.family.ledger.fixtures.SampleLedger.text
    @org.junit.After fun closeDatabase() { container.db.close() }

    @Test
    fun bootstrapSeedsSyntheticCategoriesAndAccounts() = runBlocking {
        val categories = container.db.categoryDao().all()
        val assets = container.db.assetDao().all()

        assertEquals("种子分类数量不对", expectedSeedCategories, categories.size)
        assertEquals("种子资产数量不对", expectedSeedAssets, assets.size)

        // 一级分类应当有 28 个（其余是二级）
        val topLevel = categories.count { it.parentId == null }
        assertEquals("一级分类数量不对", 12, topLevel)

        // 「支付宝小荷包」必须是家庭共享资产 —— 这是本项目的核心
        val xhb = assets.first { it.name == sharedAssetName }
        assertEquals(OwnerType.FAMILY, xhb.ownerType)
        assertTrue(xhb.sharedForFamily)

        // 个人零钱应当是个人资产
        val wx = assets.firstOrNull { it.name.contains("微信零钱") }
        assertNotNull("应当有微信零钱账户", wx)
        assertEquals(OwnerType.USER, wx!!.ownerType)

        // 信用卡类型识别正确
        val credit = assets.first { it.name.contains("信用卡") }
        assertEquals(AssetType.CREDIT, credit.type)
    }

    @Test
    fun importsSyntheticQianJiCsvIdempotentlyAndDerivesCorrectSharedBalance() = runBlocking {
        val csv = csvTextOrSkip()

        // ---- 第一次导入 ----
        val first = container.csv.importFromText(csv)
        assertEquals("解析出的总行数不对", expectedTxnCount, first.total)
        assertEquals("首次导入应当全部写入", expectedTxnCount, first.imported)
        assertEquals("首次导入不该有跳过", 0, first.skipped)
        assertTrue("导入报错: ${first.errors}", first.errors.isEmpty())

        assertEquals("库里的流水数不对", expectedTxnCount, container.db.txnDao().count())

        // ---- 幂等：再导一次不应产生重复 ----
        val second = container.csv.importFromText(csv)
        assertEquals("第二次导入不应新增", 0, second.imported)
        assertEquals("第二次导入应当全部按 externalId 跳过", expectedTxnCount, second.skipped)
        assertEquals("重复导入后流水数变了", expectedTxnCount, container.db.txnDao().count())

        // ---- 类型映射 ----
        val txns = container.db.txnDao().all()
        val byType = txns.groupingBy { it.type }.eachCount()
        assertEquals("支出条数不对", 1, byType[com.family.ledger.data.db.entity.TxnType.EXPENSE])
        assertEquals("收入条数不对", 1, byType[com.family.ledger.data.db.entity.TxnType.INCOME])
        assertEquals("退款条数不对", 1, byType[com.family.ledger.data.db.entity.TxnType.REFUND])
        assertEquals("转账条数不对", 1, byType[com.family.ledger.data.db.entity.TxnType.TRANSFER])
        assertEquals("还款条数不对（含「债务-还款」）", 2, byType[com.family.ledger.data.db.entity.TxnType.REPAYMENT])

        // ---- 家庭共享资产余额：由流水推导，与独立 Python 复算一致 ----
        val assets = container.db.assetDao().all()
        val xhb = assets.first { it.name == sharedAssetName }
        assertEquals(OwnerType.FAMILY, xhb.ownerType)

        val balances = BalanceCalculator.balancesFor(
            assets,
            container.db.balanceAnchorDao().all(),
            container.db.txnDao().balanceRows(),
        )
        val sharedBalance = balances[xhb.id] ?: 0L
        assertEquals(
            "「$sharedAssetName」推导余额与独立复算不一致",
            expectedSharedBalanceCents,
            sharedBalance,
        )

        // ---- 家庭共享资产确实被两个人共用（六笔虚构流水涉及它）----
        val touching = txns.count { it.assetId == xhb.id || it.toAssetId == xhb.id }
        assertEquals("涉及小荷包的流水数不对", expectedSharedTxnCount, touching)

        // ---- 导入过程应当留下可同步的 oplog ----
        assertTrue("导入后 oplog 为空，同步会丢数据", container.db.syncDao().opCount() > expectedTxnCount)

        // ---- 记账人解析 ----
        val members = container.db.familyMemberDao().all()
        assertTrue(
            "应当解析出「用户 A」这个记账人，实际: ${members.map { it.displayName }}",
            members.any { it.displayName == "用户 A" },
        )
    }

    @Test
    fun exportRoundTripsBackToQianJiFormat() = runBlocking {
        val csv = csvTextOrSkip()
        container.csv.importFromText(csv)

        val exported = container.csv.exportToString()
        val header = "ID,时间,分类,二级分类,类型,金额,币种,账户1,账户2,备注,已报销,手续费,优惠券,记账者,账单标记,标签,账单图片,关联账单"
        assertTrue(
            "导出的表头必须与钱迹完全一致，实际开头: ${exported.take(80)}",
            exported.removePrefix("\uFEFF").startsWith(header),
        )

        // 用真实解析器把导出读回来 —— 不能拿 lines() 数行：
        // 真实数据里有 1 条备注自带换行（合法 CSV，字段被正确引号包裹），
        // 按 \n 硬切会多算一行。
        val back = container.csv.importFromText(exported)
        assertEquals("自有导出的逻辑行数不对", expectedTxnCount, back.total)
        assertEquals("自有导出再导入不应新增", 0, back.imported)
        assertEquals("自有导出再导入应当全部按 externalId 跳过", expectedTxnCount, back.skipped)
    }

    @Test
    fun reconcileCreatesAnchorAndDoesNotDirectlyOverwriteBalance() = runBlocking {
        val assets = container.db.assetDao().all()
        val xhb = assets.first { it.name == sharedAssetName }

        val before = BalanceCalculator.balancesFor(
            assets, container.db.balanceAnchorDao().all(), container.db.txnDao().balanceRows(),
        )[xhb.id] ?: 0L

        // 假设真实余额是 5000.00
        val anchor = container.assets.reconcile(xhb.id, 500_000L, "验收测试校准")
        assertNotNull("校准应当生成锚点", anchor)

        val afterAssets = container.db.assetDao().all()
        val after = BalanceCalculator.balancesFor(
            afterAssets, container.db.balanceAnchorDao().all(), container.db.txnDao().balanceRows(),
        )[xhb.id] ?: 0L

        assertEquals("校准后余额应当等于真实余额", 500_000L, after)
        assertEquals("锚点真实余额记录不对", 500_000L, anchor!!.realBalance)

        // 校准必须留下可审计的「余额校准」流水，而不是悄悄改余额
        val adjust = container.db.txnDao().all().filter {
            it.type == com.family.ledger.data.db.entity.TxnType.BALANCE_ADJUST
        }
        assertTrue("校准应当留下可审计的校准流水", adjust.isNotEmpty())
        assertTrue("校准流水不应计入收支统计", adjust.all { it.excludeFromStats })

        // 资产表本身没有被写入余额字段（openingBalance 保持 0）
        assertEquals("期初余额不应被校准改动", 0L, afterAssets.first { it.id == xhb.id }.openingBalance)
        assertTrue("before/after 至少有一个有意义", before >= 0L)
    }

    @Test
    fun importsFromFileUriLikeTheSettingsScreenDoes() = runBlocking {
        // 覆盖真实 UI 路径：把文件复制到 app 私有目录，用 file:// Uri 走 importFrom(uri)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dest = File(ctx.cacheDir, "qianji_from_ui.csv")
        dest.writeText(csvTextOrSkip())

        val result = container.csv.importFrom(android.net.Uri.fromFile(dest))
        assertEquals(expectedTxnCount, result.total)
        assertEquals(expectedTxnCount, result.imported)
        assertTrue("URI 导入报错: ${result.errors}", result.errors.isEmpty())
    }

    @Test
    fun databaseHasNoDuplicateExternalIds() = runBlocking {
        val csv = csvTextOrSkip()
        container.csv.importFromText(csv)
        container.csv.importFromText(csv)

        val ids = container.db.txnDao().all().mapNotNull { it.externalId }
        assertEquals("externalId 有重复，去重逻辑失效", ids.size, ids.toSet().size)
        assertEquals(expectedTxnCount, ids.size)
    }

    /**
     * 两台手机各自首启都会播种同一批分类/账户。
     *
     * 如果种子 id 是随机的，配对同步后 LWW 无法按 id 合并 —— 用户会看到
     * **双份分类、双份账户**，而且两个人各记的账会挂在不同的账户 id 上，
     * 「夫妻共享同一份资产」就散了。
     *
     * 这条用例把「种子 id 必须由内容确定性推导」钉死。
     */
    @Test
    fun seedIdsAreDeterministicAcrossFreshInstalls() = runBlocking {
        suspend fun snapshot(): Triple<Map<String, String>, Map<String, String>, Map<String, String>> {
            val assets = container.db.assetDao().all().associate { it.name to it.id }
            val cats = container.db.categoryDao().all().associate { it.name to it.id }
            val books = container.db.bookDao().all().associate { it.name to it.id }
            return Triple(assets, cats, books)
        }

        val (assetsA, catsA, booksA) = snapshot()
        assertTrue("应当播下种子资产", assetsA.isNotEmpty())
        assertTrue("应当播下种子分类", catsA.isNotEmpty())
        assertTrue("应当建出默认账本", booksA.isNotEmpty())

        // 模拟「另一台手机首启」：清库后重新初始化
        container.db.clearAllTables()
        container.family.selectPerson(com.family.ledger.core.FixedPeople.B)

        val (assetsB, catsB, booksB) = snapshot()

        assertEquals(
            "默认账本 id 必须可复现，否则两台设备同步后会出现两本「日常账本」（真机实测踩到过）",
            booksA,
            booksB,
        )

        assertEquals(
            "资产种子 id 必须可复现，否则配对后会出现双份账户",
            assetsA,
            assetsB,
        )
        assertEquals(
            "分类种子 id 必须可复现，否则配对后会出现双份分类",
            catsA,
            catsB,
        )
    }

    /**
     * 首次启动的「你是哪位？」页面与 `MainActivity` 里**异步**跑的 `ensureBootstrap()` 几乎同时发生。
     * 如果用户点得快，改名时成员记录还没建出来 —— 早先 `setMyName` 会静默返回，
     * 结果这个成员永远叫占位名「我」，同步到配偶手机上也是「我」（真机实测踩到过）。
     */
    @Test
    fun 选身份时成员还没建出来也要能改对名字() = runBlocking {
        container.db.clearAllTables()
        container.settings.identityChosen = false
        container.settings.myDisplayName = ""
        container.settings.myMemberId = ""

        // 模拟「用户点得比 bootstrap 快」：先改名，再让 bootstrap 跑
        container.family.setMyName("用户 B")
        container.family.ensureBootstrap()

        val me = container.family.me()
        assertNotNull("bootstrap 后必须存在本机成员", me)
        assertEquals("成员名必须是用户选的名字，不能是占位名「我」", "用户 B", me?.displayName)
        assertEquals("昵称要一致", "用户 B", container.settings.myDisplayName)

        // 再跑一次 bootstrap 不应该把名字改回去，也不该多建成员
        container.family.ensureBootstrap()
        val again = container.family.me()
        assertEquals("重复 bootstrap 不能让成员改名", "用户 B", again?.displayName)
        assertEquals("固定两个成员", 2, container.db.familyMemberDao().all().size)
    }
}
