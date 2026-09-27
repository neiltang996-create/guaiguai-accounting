package com.family.ledger

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.family.ledger.auto.*
import com.family.ledger.core.FixedPeople
import com.family.ledger.data.SettingsStore
import com.family.ledger.data.db.AppDatabase
import com.family.ledger.data.repo.*
import com.family.ledger.sync.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** 每个测试使用独立 Room 数据库和偏好设置；只允许在 .dev 测试包运行。 */
@RunWith(AndroidJUnit4::class)
class HouseholdAcceptanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databases = mutableListOf<AppDatabase>()
    private fun client(): AppContainer {
        check(context.packageName.endsWith(".dev")) { "禁止在正式包执行测试" }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        databases += db
        val prefs = SettingsStore(context, "test-" + UUID.randomUUID())
        prefs.lanSyncEnabled = false
        prefs.syncEnabled = false
        prefs.autoBillPopupEnabled = false
        return AppContainer(context, db, prefs)
    }
    @After fun close() { databases.forEach { it.close() } }
    private suspend fun merge(a: AppContainer, b: AppContainer) {
        SyncEngine(RoomSyncEntityStore(b.db)).applyOps(a.db.syncDao().allOps())
    }
    @Test fun offlineAutoPaymentsConvergeToMinusEightyExactlyOnce() = runBlocking {
        val a = client(); val b = client()
        a.family.selectPerson(FixedPeople.A); b.family.selectPerson(FixedPeople.B)
        val pocketA = a.assets.all().single { it.name == "支付宝小荷包(示例日常)" }
        val pocketB = b.assets.all().single { it.name == "支付宝小荷包(示例日常)" }
        assertEquals(pocketA.id, pocketB.id)
        val now = System.currentTimeMillis()
        suspend fun pay(c: AppContainer, cents: Long, order: String) {
            val pipeline = AutoBillPipeline(context, c)
            val signal = PaySignal(PayPackages.ALIPAY, cents, "盒马", "支付成功 ¥${cents / 100} 盒马 支付宝小荷包", now,
                Channel.ALIPAY, Direction.PAYMENT, "支付宝小荷包(示例日常)", orderId = order, origin = Origin.ACCESSIBILITY)
            coroutineScope {
                listOf(async { pipeline.handle(signal) }, async { pipeline.handle(signal.copy(origin = Origin.NOTIFICATION)) }).awaitAll()
            }
            val pending = c.db.pendingBillDao().allPending().single()
            val first = pipeline.confirm(pending.id)
            assertNotNull(first)
            assertEquals(first, pipeline.confirm(pending.id))
            assertEquals(1, c.ledger.count())
        }
        pay(a, 3000L, "A-ORDER-1"); pay(b, 5000L, "B-ORDER-1")
        repeat(3) { merge(a,b); merge(b,a) }
        for (c in listOf(a,b)) {
            assertEquals(2, c.ledger.count())
            assertEquals(2, c.family.members().size)
            val balance = BalanceCalculator.currentBalance(c.assets.byId(pocketA.id)!!, c.db.balanceAnchorDao().all(), c.db.txnDao().balanceRows())
            assertEquals(-8000L, balance)
            val byPayer = c.ledger.all().associate { it.payerMemberId to it.amount }
            assertEquals(3000L, byPayer[FixedPeople.A]); assertEquals(5000L, byPayer[FixedPeople.B])
        }
    }
    @Test fun twoDevicesForSamePersonAndIdentitySwitchDoNotRewriteHistory() = runBlocking {
        val a = client(); val b = client()
        a.family.selectPerson(FixedPeople.A); b.family.selectPerson(FixedPeople.A)
        val txn = a.ledger.save(TxnDraft(bookId = a.family.defaultBook().id, amountCents = 800L, consumerMemberId = FixedPeople.FAMILY))
        a.family.selectPerson(FixedPeople.B)
        merge(a,b); merge(b,a)
        assertEquals(2, a.family.members().size)
        assertEquals(2, a.db.deviceDao().all().size)
        assertEquals(FixedPeople.A, a.ledger.byId(txn.id)!!.payerMemberId)
        assertEquals(FixedPeople.FAMILY, a.ledger.byId(txn.id)!!.consumerMemberId)
        assertEquals(FixedPeople.B, a.settings.myMemberId)
        assertEquals(FixedPeople.A, b.settings.myMemberId)
    }
    @Test fun failureWritingOutboxRollsBackBill() = runBlocking {
        val a = client(); a.family.selectPerson(FixedPeople.A)
        a.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_op BEFORE INSERT ON sync_op BEGIN SELECT RAISE(ABORT, 'simulated disk write failure'); END")
        val result = runCatching { a.ledger.save(TxnDraft(bookId = a.family.defaultBook().id, amountCents = 3000L)) }
        assertTrue(result.isFailure)
        assertEquals(0, a.ledger.count())
    }
    @Test fun concurrentSavesKeepEveryBillAndUniqueSequence() = runBlocking {
        val a = client(); a.family.selectPerson(FixedPeople.A)
        val book = a.family.defaultBook().id
        coroutineScope { (1..60).map { async(Dispatchers.Default) { a.ledger.save(TxnDraft(bookId = book, amountCents = it.toLong())) } }.awaitAll() }
        assertEquals(60, a.ledger.count())
        val ops = a.db.syncDao().allOps()
        assertEquals(ops.size, ops.map { it.opId }.toSet().size)
        assertEquals(60, ops.count { it.entityTable == "txn" })
    }
    @Test fun thirdPersonRejected() = runBlocking {
        val a = client()
        assertTrue(runCatching { a.family.selectPerson("PERSON_OTHER") }.isFailure)
        a.family.selectPerson(FixedPeople.A)
        assertTrue(runCatching { a.family.addMember("第三人") }.isFailure)
        assertEquals(setOf(FixedPeople.A, FixedPeople.B), a.family.members().map { it.id }.toSet())
    }
    @Test fun versionOneUpgradePreservesOldBillsAndMapsLegacyPerson() = runBlocking {
        check(context.packageName.endsWith(".dev"))
        val name = "migration-" + UUID.randomUUID() + ".db"
        val schema = org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open(
            "com.family.ledger.data.db.AppDatabase/1.json").bufferedReader().readText()).getJSONObject("database")
        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
            old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.getJSONArray("indices")
            for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
        old.execSQL("INSERT INTO family VALUES ('DEMOHOME','我们家',1,1,0)")
        old.execSQL("INSERT INTO family_member VALUES ('m-old','DEMOHOME','用户 A','dev-old',1,1,1,0)")
        val txn = android.content.ContentValues().apply {
            put("id","t-legacy"); put("bookId","b-old"); put("type","EXPENSE"); put("amount",3000L)
            put("currency","CNY"); put("occurredAt",1L); put("recorderMemberId","m-old"); put("payerMemberId","m-old"); put("consumerMemberId","m-old")
            put("reimbursable",0); put("reimbursedAmount",0L); put("fee",0L); put("coupon",0L); put("excludeFromStats",0)
            put("source","MANUAL"); put("createdByDeviceId","dev-old"); put("createdAt",1L); put("updatedAt",1L); put("deleted",0)
        }
        assertTrue(old.insertOrThrow("txn", null, txn) > 0)
        old.version = 1; old.close()
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
        databases += upgraded
        assertEquals(3000L, upgraded.txnDao().byId("t-legacy")!!.amount)
        val prefs = SettingsStore(context, "migration-prefs-"+UUID.randomUUID())
        prefs.myDisplayName = "用户 A"; prefs.identityChosen = true
        val c = AppContainer(context, upgraded, prefs)
        c.family.ensureBootstrap()
        assertEquals(1, c.ledger.count())
        assertEquals(FixedPeople.A, c.ledger.byId("t-legacy")!!.payerMemberId)
        assertNotNull(upgraded.deviceDao().byId("dev-old"))
    }
    @Test fun malformedRemoteBatchDoesNotAdvanceCursorOrPartiallyWrite() = runBlocking {
        val a=client(); val b=client(); a.family.selectPerson(FixedPeople.A); b.family.selectPerson(FixedPeople.B)
        a.ledger.save(TxnDraft(bookId=a.family.defaultBook().id, amountCents=3000L))
        val ops=a.db.syncDao().allOps()
        val broken=ops.last().copy(seq=9999, opId=a.settings.deviceId+":9999", payloadJson="broken")
        assertTrue(runCatching { SyncEngine(RoomSyncEntityStore(b.db)).applyOps(ops+broken) }.isFailure)
        assertEquals(0,b.ledger.count())
        assertNull(b.db.syncDao().getState(SyncEngine.cursorKey(a.settings.deviceId)))
    }

    @Test fun paidAmountWithCouponIsNotDiscountedTwice() = runBlocking {
        val c=client(); c.family.selectPerson(FixedPeople.A)
        val pocket=c.assets.all().single { it.name == "支付宝小荷包(示例日常)" }
        val pipeline=AutoBillPipeline(context,c)
        pipeline.handle(PaySignal(PayPackages.ALIPAY, 3000L, "超市", "实付30元，优惠5元", System.currentTimeMillis(),
            Channel.ALIPAY, Direction.PAYMENT, pocket.name, orderId="COUPON-ORDER", origin=Origin.ACCESSIBILITY, couponCents=500L))
        val id=pipeline.confirm(c.db.pendingBillDao().allPending().single().id)!!
        assertEquals(3500L,c.ledger.byId(id)!!.amount)
        assertEquals(500L,c.ledger.byId(id)!!.coupon)
        assertEquals(-3000L,BalanceCalculator.currentBalance(pocket,c.db.balanceAnchorDao().all(),c.db.txnDao().balanceRows()))
    }
    @Test fun separateNotificationsWithIdenticalTextAreKeptForConfirmation() = runBlocking {
        val c=client(); c.family.selectPerson(FixedPeople.A)
        val pipeline=AutoBillPipeline(context,c)
        val signal=PaySignal(PayPackages.WECHAT, 1000L, "便利店", "微信支付10元 便利店", System.currentTimeMillis(),
            Channel.WECHAT, Direction.PAYMENT, suggestedAccountHint=null, origin=Origin.NOTIFICATION, rawEventId="notification-1")
        pipeline.handle(signal)
        pipeline.handle(signal)
        assertEquals(1,c.db.pendingBillDao().allPending().size)
        pipeline.handle(signal.copy(rawEventId="notification-2",occurredAt=signal.occurredAt+1000))
        assertEquals(2,c.db.pendingBillDao().allPending().size)
        assertEquals(0,c.ledger.count())
    }

    @Test fun reopeningExactReceiptReusesPendingAcrossPipelineInstances() = runBlocking {
        val c = client(); c.family.selectPerson(FixedPeople.A)
        val at = System.currentTimeMillis()
        val signal = PaySignal(PayPackages.ALIPAY, 80000L, "示例礼服店", "示例礼服店 支出800元 交易成功 支付时间:$at 余额宝",
            at, Channel.ALIPAY, Direction.PAYMENT, "余额宝", origin = Origin.ACCESSIBILITY,
            pageType = "AlipayBillDetail", receiptTimeMillis = at)
        AutoBillPipeline(context, c).handle(signal)
        val original = c.db.pendingBillDao().allPending().single()
        AutoBillPipeline(context, c).handle(signal)
        assertEquals(original.id, c.db.pendingBillDao().allPending().single().id)
        val pipeline = AutoBillPipeline(context, c)
        val txnId = pipeline.confirm(original.id)
        assertNotNull(txnId)
        AutoBillPipeline(context, c).handle(signal)
        assertEquals(1, c.ledger.count())
        assertTrue(c.db.pendingBillDao().allPending().isEmpty())
        assertEquals(1, c.db.autoBillLogDao().recent(100).count { it.action == "ALREADY_RECORDED" })
        // 规则升级修正商户字段后，同一份原始回执仍然是已经入账的同一笔。
        AutoBillPipeline(context, c).handle(signal.copy(merchant = "修正后的店名"))
        assertEquals(1, c.ledger.count())
        assertTrue(c.db.pendingBillDao().allPending().isEmpty())
        assertEquals(2, c.db.autoBillLogDao().recent(100).count { it.action == "ALREADY_RECORDED" })

        // 详情页的推荐服务 / 积分提示更新不代表另一笔支付。
        AutoBillPipeline(context, c).handle(signal.copy(rawText = signal.rawText + "\n立即领取18积分"))
        assertEquals(1, c.ledger.count())
        assertTrue(c.db.pendingBillDao().allPending().isEmpty())
        assertEquals(3, c.db.autoBillLogDao().recent(100).count { it.action == "ALREADY_RECORDED" })

        // 同店同金额但支付时间不同，必须保留为另一笔待确认。
        val next = at + 1000L
        AutoBillPipeline(context, c).handle(signal.copy(occurredAt = next, receiptTimeMillis = next,
            rawText = signal.rawText.replace(at.toString(), next.toString())))
        val second = c.db.pendingBillDao().allPending().single()
        assertNotEquals(original.id, second.id)
        assertEquals(1, c.ledger.count())
        assertTrue(pipeline.ignore(second.id))
        AutoBillPipeline(context, c).handle(signal.copy(occurredAt = next, receiptTimeMillis = next,
            rawText = signal.rawText.replace(at.toString(), next.toString())))
        assertTrue(c.db.pendingBillDao().allPending().isEmpty())
        assertEquals(3, c.db.autoBillLogDao().recent(100).count { it.action == "ALREADY_RECORDED" })
    }

}
