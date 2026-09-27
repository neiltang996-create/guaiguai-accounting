package com.family.ledger

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.family.ledger.auto.*
import com.family.ledger.core.FixedPeople
import com.family.ledger.data.SettingsStore
import com.family.ledger.data.db.AppDatabase
import com.family.ledger.data.db.entity.*
import com.family.ledger.data.repo.*
import com.family.ledger.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class RecoveryAcceptanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databases = mutableListOf<AppDatabase>()
    private suspend fun client(): AppContainer {
        check(context.packageName.endsWith(".dev"))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        databases += db
        val prefs = SettingsStore(context, "recovery-test-" + UUID.randomUUID())
        prefs.syncEnabled = false; prefs.lanSyncEnabled = false; prefs.autoBillPopupEnabled = false
        return AppContainer(context, db, prefs).also { it.family.selectPerson(FixedPeople.A) }
    }
    @After fun close() { databases.forEach { it.close() } }
    private suspend fun origin(c: AppContainer): TxnEntity {
        val a = c.assets.all().first()
        return c.ledger.save(TxnDraft(bookId=c.family.defaultBook().id, amountCents=10000, feeCents=200,
            couponCents=1200, occurredAt=100, assetId=a.id, merchant="隔离测试原支出"))
    }
    @Test fun partialReturnsCashBalancesStatisticsSyncAndUndoAgree() = runBlocking<Unit> {
        val a=client(); val b=client(); val o=origin(a)
        val receiving=a.assets.all().first { it.id != o.assetId && it.currency==o.currency }
        val r=a.ledger.createRecoveryFor(o.id,RecoveryKind.REFUND,2000,receiving.id,300)
        val reimbursement=a.ledger.createRecoveryFor(o.id,RecoveryKind.REIMBURSEMENT,3000,receiving.id,400)
        repeat(2) { SyncEngine(RoomSyncEntityStore(b.db)).applyOps(a.db.syncDao().allOps()) }
        for (c in listOf(a,b)) {
            assertEquals(4000L,c.ledger.observeMonthTotal(0,200).first().expense)
            assertEquals(0L,c.ledger.observeMonthTotal(200,500).first().income)
            assertEquals(0L,c.ledger.observeMonthTotal(200,500).first().expense)
            assertEquals(5000L,BalanceCalculator.currentBalance(receiving,emptyList(),c.db.txnDao().balanceRows()) - receiving.openingBalance)
            assertEquals("REIMBURSEMENT",c.ledger.byId(reimbursement.id)!!.recoveryKind)
            assertEquals(o.id,c.ledger.byId(r.id)!!.relatedTxnId)
        }
        a.ledger.softDelete(r.id)
        SyncEngine(RoomSyncEntityStore(b.db)).applyOps(a.db.syncDao().allOps())
        assertEquals(6000L,b.ledger.observeMonthTotal(0,200).first().expense)
        assertEquals(3000L,BalanceCalculator.currentBalance(receiving,emptyList(),b.db.txnDao().balanceRows()) - receiving.openingBalance)
    }
    @Test fun concurrentLocalReturnsCannotOverRecoverAndInvalidWritesDoNotChangeOutbox() = runBlocking<Unit> {
        val c=client(); val o=origin(c); val account=o.assetId!!
        val results=coroutineScope { (1..2).map { async { runCatching { c.ledger.createRecoveryFor(o.id,RecoveryKind.REFUND,6000,account,300) } } }.awaitAll() }
        assertEquals(1,results.count { it.isSuccess }); assertEquals(2,c.ledger.count())
        val ops=c.db.syncDao().allOps().size
        assertTrue(runCatching { c.ledger.createRecoveryFor(o.id,RecoveryKind.REIMBURSEMENT,4000,account,300) }.isFailure)
        assertTrue(runCatching { c.ledger.createRecoveryFor(o.id,RecoveryKind.REFUND,100,"missing-account",300) }.isFailure)
        assertTrue(runCatching { c.ledger.createRecoveryFor(o.id,RecoveryKind.REFUND,100,account,99) }.isFailure)
        assertTrue(runCatching { c.ledger.update(o.copy(amount=5000)) }.isFailure)
        assertTrue(runCatching { c.ledger.softDelete(o.id) }.isFailure)
        assertEquals(ops,c.db.syncDao().allOps().size)
    }
    @Test fun failedJournalRollsBackRecoveryCompletely() = runBlocking<Unit> {
        val c=client();val o=origin(c)
        c.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_recovery BEFORE INSERT ON sync_op BEGIN SELECT RAISE(ABORT, 'test outbox failure'); END")
        assertTrue(runCatching { c.ledger.createRecoveryFor(o.id,RecoveryKind.REFUND,100,o.assetId!!,300) }.isFailure)
        assertEquals(1,c.ledger.count());assertEquals(9000L,c.ledger.observeMonthTotal(0,200).first().expense)
    }
    @Test fun detectedRefundRequiresOriginPreservesReceiptAndConfirmsOnlyOnce() = runBlocking<Unit> {
        val c=client();val o=origin(c); val pipeline=AutoBillPipeline(context,c)
        val signal=PaySignal(PayPackages.ALIPAY,2000,"隔离测试原支出","退款成功20元",300,
            Channel.ALIPAY,Direction.REFUND,null,orderId="test-refund-order",origin=Origin.ACCESSIBILITY,receiptImage="receipts/" + "a".repeat(64) + ".jpg")
        pipeline.handle(signal)
        val pending=c.db.pendingBillDao().allPending().single()
        assertNull(pipeline.confirm(pending.id)); assertEquals(1,c.ledger.count())
        val override=AutoBillPipeline.ConfirmOverride(assetId=o.assetId,relatedTxnId=o.id,amountCents=1500,occurredAt=400)
        val id=pipeline.confirmRecovery(pending.id,override)
        assertEquals(id,pipeline.confirmRecovery(pending.id,override))
        assertEquals(2,c.ledger.count())
        val r=c.ledger.byId(id)!!; assertEquals(o.id,r.relatedTxnId);assertEquals(1500L,r.amount);assertEquals(0L,r.coupon)
        assertEquals(signal.receiptImage,r.imagePaths)
        assertEquals(7500L,c.ledger.observeMonthTotal(0,200).first().expense)
    }
    @Test fun linkingLegacyRefundDoesNotCreditTwiceAndMovesOnlyStatistics() = runBlocking<Unit> {
        val c=client();val o=origin(c)
        val legacy=o.copy(id="legacy-refund",type=TxnType.REFUND,amount=1000,fee=0,coupon=0,occurredAt=300,relatedTxnId=null)
        c.ledger.upsert(legacy)
        val before=BalanceCalculator.currentBalance(c.assets.byId(o.assetId!!)!!,emptyList(),c.db.txnDao().balanceRows())
        c.ledger.linkRecovery(legacy.id,o.id,RecoveryKind.REFUND)
        assertEquals(2,c.ledger.count())
        assertEquals(before,BalanceCalculator.currentBalance(c.assets.byId(o.assetId!!)!!,emptyList(),c.db.txnDao().balanceRows()))
        assertEquals(8000L,c.ledger.observeMonthTotal(0,200).first().expense)
        assertEquals(0L,c.ledger.observeMonthTotal(200,500).first().expense)
    }
    @Test fun versionTwoMigrationPreservesRealFieldsAndAddsNullableKind() = runBlocking<Unit> {
        val name="recovery-migration-"+UUID.randomUUID()+".db"
        val schema=org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open(
            "com.family.ledger.data.db.AppDatabase/2.json").bufferedReader().readText()).getJSONObject("database")
        val old=context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null)
        val entities=schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e=entities.getJSONObject(i);val table=e.getString("tableName")
            old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
            val indices=e.getJSONArray("indices")
            for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
        }
        val setup=schema.getJSONArray("setupQueries");for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
        old.execSQL("INSERT INTO txn (id,bookId,type,amount,currency,occurredAt,reimbursable,reimbursedAmount,fee,coupon,excludeFromStats,relatedTxnId,imagePaths,source,createdByDeviceId,createdAt,updatedAt,deleted) VALUES ('legacy','book','REFUND',1234,'CNY',300,0,0,0,0,0,'purchase','receipts/test.jpg','IMPORT_QIANJI','test',1,1,0)")
        old.version=2;old.close()
        val upgraded=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_1_2,AppDatabase.MIGRATION_2_3).build()
        try {
            val r=upgraded.txnDao().byId("legacy")!!
            assertEquals(1234L,r.amount);assertEquals("purchase",r.relatedTxnId);assertEquals("receipts/test.jpg",r.imagePaths)
            assertNull(r.recoveryKind);assertEquals(3,upgraded.openHelper.writableDatabase.version)
        } finally { upgraded.close();context.deleteDatabase(name) }
    }
}
