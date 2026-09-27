package com.family.ledger.demo

import androidx.room.withTransaction
import com.family.ledger.AppContainer
import com.family.ledger.core.FixedPeople
import com.family.ledger.data.db.entity.*
import com.family.ledger.data.repo.TxnDraft
import com.family.ledger.data.repo.RecoveryKind
import java.time.LocalDate
import java.time.ZoneId

/** Entirely invented sample ledger. Call only from the separate demo application or an in-memory test. */
object DemoData {
    suspend fun seed(c: AppContainer) {
        c.settings.syncEnabled = false
        c.settings.lanSyncEnabled = false
        c.settings.autoBillEnabled = false
        c.settings.receiptOcrEnabled = false
        c.family.selectPerson(FixedPeople.A)
        c.db.withTransaction {
            if (c.db.syncDao().getState("demo.seed.v1") == "done") return@withTransaction
            check(c.ledger.count() == 0) { "演示数据只允许写入空账本" }
            val now = System.currentTimeMillis()
            val month = LocalDate.now().withDayOfMonth(1)
            fun at(day: Int) = month.withDayOfMonth(day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            suspend fun asset(id: String, name: String, type: AssetType, amount: Long) {
                c.assets.upsert(AssetEntity(id=id, name=name, type=type, openingBalance=amount,
                    ownerType=OwnerType.FAMILY, ownerFamilyId=c.settings.familyId,
                    sharedForFamily=true, createdAt=now, updatedAt=now))
            }
            // Replace only empty bootstrap accounts, never a user's nonempty ledger.
            c.db.openHelper.writableDatabase.execSQL("DELETE FROM asset")
            asset("demo-pocket", "示例共同零钱", AssetType.VIRTUAL, 200000)
            asset("demo-bank", "示例家庭储蓄卡", AssetType.SAVINGS, 500000)
            asset("demo-credit", "示例信用卡", AssetType.CREDIT, -30000)
            asset("demo-prepaid", "示例超市充值卡", AssetType.PREPAID, 20000)
            val book = c.family.defaultBook().id
            val cats = c.db.categoryDao().all()
            fun category(name: String) = cats.first { it.name == name && it.parentId == null }.id
            suspend fun expense(amount: Long, categoryName: String, merchant: String, day: Int, person: String) =
                c.ledger.save(TxnDraft(bookId=book, amountCents=amount, assetId="demo-pocket",
                    categoryId=category(categoryName), merchant=merchant, occurredAt=at(day),
                    recorderMemberId=person, payerMemberId=person, consumerMemberId=FixedPeople.FAMILY))
            expense(3000, "吃", "示例早餐店", 2, FixedPeople.A)
            expense(5000, "日用品", "示例生活超市", 3, FixedPeople.B)
            expense(1200, "电瓶车", "示例充电服务有限公司", 4, FixedPeople.A)
            expense(8600, "交通", "示例周末出行", 6, FixedPeople.B)
            c.ledger.save(TxnDraft(type=TxnType.INCOME, bookId=book, amountCents=600000,
                assetId="demo-bank", categoryId=category("收入"), merchant="示例工资",
                occurredAt=at(1), recorderMemberId=FixedPeople.A, payerMemberId=FixedPeople.A))
            val origin = c.ledger.save(TxnDraft(bookId=book, amountCents=10000, assetId="demo-pocket",
                categoryId=category("吃"), merchant="示例餐饮支出", consumerMemberId=FixedPeople.FAMILY,
                occurredAt=month.minusDays(3).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
            c.ledger.createRecoveryFor(origin.id, RecoveryKind.REFUND, 2000, "demo-pocket", at(5), "示例部分退款")
            c.ledger.createRecoveryFor(origin.id, RecoveryKind.REIMBURSEMENT, 3000, "demo-bank", at(7), "示例部分报销")
            c.db.syncDao().putState(com.family.ledger.data.db.entity.SyncStateEntity("demo.seed.v1", "done", now))
        }
    }
}
