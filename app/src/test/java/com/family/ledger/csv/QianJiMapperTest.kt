package com.family.ledger.csv

import com.family.ledger.core.TimeFmt
import com.family.ledger.data.csv.QianJiCsvCodec
import com.family.ledger.data.csv.QianJiMapper
import com.family.ledger.data.csv.QianJiRow
import com.family.ledger.data.db.entity.AssetType
import com.family.ledger.data.db.entity.OwnerType
import com.family.ledger.data.db.entity.TxnEntity
import com.family.ledger.data.db.entity.TxnSource
import com.family.ledger.data.db.entity.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 映射逻辑测试（不需要 Android / Room）。
 *
 * 覆盖任务里点名的映射规则：类型（含「债务-还款」）、不计收支、金额、时间、
 * externalId 去重键、标签 / 图片 / 关联账单，以及导出方向与稳定 ID 生成。
 */
class QianJiMapperTest {

    private fun row(
        id: String = "qj1",
        time: String = "2026-01-29 21:41:17",
        type: String = "支出",
        amount: String = "12.30",
        note: String = "备注",
    ) = QianJiRow(
        id = id,
        time = time,
        category = "吃",
        subCategory = "外卖",
        type = type,
        amount = amount,
        currency = "CNY",
        account1 = "微信零钱（用户 A）",
        note = note,
    )

    // ---------- 类型映射 ----------

    @Test
    fun `类型映射含 债务-还款`() {
        assertEquals(TxnType.EXPENSE, QianJiRow.typeOf("支出"))
        assertEquals(TxnType.INCOME, QianJiRow.typeOf("收入"))
        assertEquals(TxnType.REFUND, QianJiRow.typeOf("退款"))
        assertEquals(TxnType.TRANSFER, QianJiRow.typeOf("转账"))
        assertEquals(TxnType.REPAYMENT, QianJiRow.typeOf("还款"))
        assertEquals(TxnType.REPAYMENT, QianJiRow.typeOf("债务-还款"))
        assertEquals(TxnType.EXPENSE, QianJiRow.typeOf(" 支出 "))
        assertNull(QianJiRow.typeOf("转账支出"))
        assertNull(QianJiRow.typeOf(""))
        assertNull(QianJiRow.typeOf(null))
    }

    @Test
    fun `类型反向映射到钱迹列`() {
        assertEquals("支出", QianJiRow.typeToCn(TxnType.EXPENSE))
        assertEquals("收入", QianJiRow.typeToCn(TxnType.INCOME))
        assertEquals("退款", QianJiRow.typeToCn(TxnType.REFUND))
        assertEquals("转账", QianJiRow.typeToCn(TxnType.TRANSFER))
        assertEquals("还款", QianJiRow.typeToCn(TxnType.REPAYMENT))
        // 余额校准在钱迹里是「平账」= 支出 + 不计收支
        assertEquals("支出", QianJiRow.typeToCn(TxnType.BALANCE_ADJUST))
    }

    @Test
    fun `分类 kind 按交易类型推断`() {
        assertEquals(QianJiMapper.KIND_INCOME, QianJiMapper.kindFor(row(type = "收入")))
        assertEquals(QianJiMapper.KIND_EXPENSE, QianJiMapper.kindFor(row(type = "支出")))
        assertEquals(QianJiMapper.KIND_EXPENSE, QianJiMapper.kindFor(row(type = "退款")))
        assertEquals(QianJiMapper.KIND_EXPENSE, QianJiMapper.kindFor(row(type = "转账")))
        assertEquals(QianJiMapper.KIND_EXPENSE, QianJiMapper.kindFor(row(type = "债务-还款")))
        assertEquals(QianJiMapper.KIND_INCOME, QianJiMapper.otherKind(QianJiMapper.KIND_EXPENSE))
        assertEquals(QianJiMapper.KIND_EXPENSE, QianJiMapper.otherKind(QianJiMapper.KIND_INCOME))
    }

    // ---------- 行 → 流水 ----------

    @Test
    fun `支出行映射为完整流水`() {
        val r = QianJiRow(
            id = "qj9000000000000000015",
            time = "2024-04-12 10:00:00",
            category = "会员",
            subCategory = "美团",
            type = "支出",
            amount = "36.0",
            currency = "CNY",
            account1 = "支付宝小荷包(示例日常)",
            account2 = "",
            note = "示例公园门票",
            reimbursed = "",
            fee = "0.50",
            coupon = "1.00",
            recorder = "用户 A",
            flag = QianJiRow.FLAG_EXCLUDE,
            tags = "报销,旅行",
            images = "http://a/1.jpg,http://a/2.jpg",
            relatedBill = "qj9000000000000000013",
        )
        val txn = QianJiMapper.buildTxn(
            row = r,
            bookId = "b-1",
            deviceId = "dev-1",
            assetId = "a-1",
            toAssetId = null,
            categoryId = "c-1",
            subCategoryId = "c-2",
            recorderMemberId = "m-1",
            tagIds = listOf("tg-1", "tg-2"),
            relatedTxnId = "t-origin",
            now = 1_700_000_000_000L,
        )!!

        assertEquals("b-1", txn.bookId)
        assertEquals(TxnType.EXPENSE, txn.type)
        assertEquals(3600L, txn.amount)
        assertEquals(1_700_000_000_000L, txn.createdAt)
        assertEquals(1_700_000_000_000L, txn.updatedAt)
        assertEquals("dev-1", txn.createdByDeviceId)
        assertEquals("a-1", txn.assetId)
        assertNull(txn.toAssetId)
        assertEquals("c-1", txn.categoryId)
        assertEquals("c-2", txn.subCategoryId)
        assertEquals("m-1", txn.recorderMemberId)
        assertEquals("m-1", txn.payerMemberId)
        assertEquals("m-1", txn.consumerMemberId)
        assertEquals("tg-1,tg-2", txn.tagIds)
        assertEquals("示例公园门票", txn.note)
        assertEquals(50L, txn.fee)
        assertEquals(100L, txn.coupon)
        assertTrue(txn.excludeFromStats)
        assertEquals("t-origin", txn.relatedTxnId)
        assertEquals("http://a/1.jpg,http://a/2.jpg", txn.imagePaths)
        assertEquals(TxnSource.IMPORT_QIANJI, txn.source)
        assertEquals("qj9000000000000000015", txn.externalId)
        assertFalse(txn.deleted)

        // 时间用 TimeFmt 解析：能原样回到 CSV 字符串
        assertEquals("2024-04-12 10:00:00", TimeFmt.toCsv(txn.occurredAt))
    }

    @Test
    fun `债务-还款与转账映射到转入转出账户`() {
        val debt = QianJiMapper.buildTxn(
            row = QianJiRow(
                id = "qj9000000000000000016",
                time = "2026-05-11 18:02:54",
                category = "其它",
                type = "债务-还款",
                amount = "20000.0",
                currency = "CNY",
                account1 = "支付宝小荷包(示例储蓄)",
                account2 = "示例往来账户",
                recorder = "用户 A",
            ),
            bookId = "b-1",
            deviceId = "dev-1",
            assetId = "a-from",
            toAssetId = "a-to",
            categoryId = "c-1",
            subCategoryId = null,
            recorderMemberId = "m-1",
            tagIds = emptyList(),
            relatedTxnId = null,
        )!!
        assertEquals(TxnType.REPAYMENT, debt.type)
        assertEquals(2_000_000L, debt.amount)
        assertEquals("a-from", debt.assetId)
        assertEquals("a-to", debt.toAssetId)
        assertNull(debt.tagIds)
        assertNull(debt.imagePaths)
        assertNull(debt.subCategoryId)
        assertNull(debt.relatedTxnId)
        assertFalse(debt.excludeFromStats)
    }

    @Test
    fun `金额恒为正且容错千分位`() {
        val neg = QianJiMapper.buildTxn(
            row = row(amount = "-5.00"),
            bookId = "b", deviceId = "d", assetId = null, toAssetId = null,
            categoryId = null, subCategoryId = null, recorderMemberId = null,
            tagIds = emptyList(), relatedTxnId = null,
        )!!
        assertEquals(500L, neg.amount)

        val grouped = QianJiMapper.buildTxn(
            row = row(amount = "1,234.50"),
            bookId = "b", deviceId = "d", assetId = null, toAssetId = null,
            categoryId = null, subCategoryId = null, recorderMemberId = null,
            tagIds = emptyList(), relatedTxnId = null,
        )!!
        assertEquals(123450L, grouped.amount)
    }

    @Test
    fun `无法识别的类型或时间返回 null`() {
        val badType = QianJiMapper.buildTxn(
            row = row(type = "转账支出"),
            bookId = "b", deviceId = "d", assetId = null, toAssetId = null,
            categoryId = null, subCategoryId = null, recorderMemberId = null,
            tagIds = emptyList(), relatedTxnId = null,
        )
        assertNull(badType)

        val badTime = QianJiMapper.buildTxn(
            row = row(time = "不是时间"),
            bookId = "b", deviceId = "d", assetId = null, toAssetId = null,
            categoryId = null, subCategoryId = null, recorderMemberId = null,
            tagIds = emptyList(), relatedTxnId = null,
        )
        assertNull(badTime)
    }

    @Test
    fun `空备注与空 ID 不会写成空串`() {
        val txn = QianJiMapper.buildTxn(
            row = row(id = "", note = "   "),
            bookId = "b", deviceId = "d", assetId = null, toAssetId = null,
            categoryId = null, subCategoryId = null, recorderMemberId = null,
            tagIds = emptyList(), relatedTxnId = null,
        )!!
        assertNull(txn.note)
        assertNull(txn.externalId)
    }

    // ---------- 资产 / 成员 猜测 ----------

    @Test
    fun `小荷包算家庭资产 其余算个人`() {
        assertEquals(OwnerType.FAMILY, QianJiMapper.guessOwnerType("支付宝小荷包(示例日常)"))
        assertEquals(OwnerType.USER, QianJiMapper.guessOwnerType("微信零钱（用户 A）"))
        assertEquals(OwnerType.USER, QianJiMapper.guessOwnerType("招商银行信用卡(8806)"))
    }

    @Test
    fun `账户名猜资产类型`() {
        assertEquals(AssetType.CREDIT, QianJiMapper.guessAssetType("中信银行信用卡(8803)"))
        assertEquals(AssetType.SAVINGS, QianJiMapper.guessAssetType("工商银行储蓄卡（8804）"))
        assertEquals(AssetType.SAVINGS, QianJiMapper.guessAssetType("建设银行（8808）"))
        assertEquals(AssetType.INVEST, QianJiMapper.guessAssetType("余额宝"))
        assertEquals(AssetType.PREPAID, QianJiMapper.guessAssetType("品诺充值卡"))
        assertEquals(AssetType.VIRTUAL, QianJiMapper.guessAssetType("微信零钱（用户 B）"))
        assertEquals(AssetType.VIRTUAL, QianJiMapper.guessAssetType("支付宝小荷包(示例储蓄)"))
        assertEquals(AssetType.OTHER, QianJiMapper.guessAssetType("示例往来账户"))
    }

    // ---------- 导出 ----------

    @Test
    fun `流水反向映射为钱迹行`() {
        val txn = TxnEntity(
            id = "t-1",
            bookId = "b-1",
            type = TxnType.REPAYMENT,
            amount = 2_000_000L,
            occurredAt = TimeFmt.parseCsv("2026-05-11 18:02:54")!!,
            assetId = "a-from",
            toAssetId = "a-to",
            categoryId = "c-1",
            subCategoryId = null,
            recorderMemberId = "m-1",
            note = "还示例往来账户",
            fee = 100L,
            coupon = 0L,
            tagIds = "tg-1,tg-2",
            imagePaths = "http://a/1.jpg",
            excludeFromStats = true,
            externalId = "qj9",
            createdByDeviceId = "dev-1",
            createdAt = 1L,
            updatedAt = 1L,
        )
        val r = QianJiMapper.toRow(
            txn = txn,
            externalId = "qj9",
            assetName = { id -> if (id == "a-from") "支付宝小荷包(示例储蓄)" else "示例往来账户" },
            categoryName = { id -> if (id == "c-1") "其它" else "" },
            memberName = { "用户 A" },
            tagNames = { ids -> ids.joinToString(",") { if (it == "tg-1") "报销" else "旅行" } },
            relatedExternalId = "qj8",
        )!!
        assertEquals("qj9", r.id)
        assertEquals("还款", r.type)
        assertEquals("20000.00", r.amount)
        assertEquals("2026-05-11 18:02:54", r.time)
        assertEquals("支付宝小荷包(示例储蓄)", r.account1)
        assertEquals("示例往来账户", r.account2)
        assertEquals("其它", r.category)
        assertEquals("", r.subCategory)
        assertEquals("用户 A", r.recorder)
        assertEquals("1.00", r.fee)
        assertEquals("", r.coupon)
        assertEquals("报销,旅行", r.tags)
        assertEquals("http://a/1.jpg", r.images)
        assertEquals("qj8", r.relatedBill)
        assertEquals(QianJiRow.FLAG_EXCLUDE, r.flag)
        assertEquals("CNY", r.currency)
        assertEquals(TxnType.REPAYMENT, r.txnType)
        assertEquals(2_000_000L, r.amountCents)
    }

    /** 构造一条导出用的本地流水。 */
    private fun exportTxn(
        id: String,
        type: TxnType = TxnType.EXPENSE,
        note: String? = null,
        amount: Long = 1000L,
        excludeFromStats: Boolean = false,
        occurredAt: Long = TimeFmt.parseCsv("2026-03-01 09:00:00")!!,
    ): TxnEntity = TxnEntity(
        id = id,
        bookId = "b-1",
        type = type,
        amount = amount,
        occurredAt = occurredAt,
        assetId = "a-1",
        categoryId = "c-1",
        note = note,
        excludeFromStats = excludeFromStats,
        createdByDeviceId = "dev-1",
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `余额校准不导出`() {
        val adjust = exportTxn(
            id = "t-adjust",
            type = TxnType.BALANCE_ADJUST,
            note = "余额校准",
            amount = 1234L,
            excludeFromStats = true,
        )
        // 钱迹 CSV 没有「余额校准」类型：单条映射直接拒绝
        assertNull(
            QianJiMapper.toRow(
                txn = adjust,
                externalId = QianJiMapper.generatedExternalId(adjust.id),
                assetName = { "" },
                categoryName = { "" },
                memberName = { "" },
                tagNames = { "" },
                relatedExternalId = null,
            )
        )
    }

    @Test
    fun `导出跳过余额校准 行数只等于普通流水`() {
        val txns = listOf(
            exportTxn(id = "t-1", type = TxnType.EXPENSE, note = "午饭", amount = 2550L),
            exportTxn(id = "t-2", type = TxnType.INCOME, note = "工资", amount = 100_000L),
            exportTxn(
                id = "t-adjust",
                type = TxnType.BALANCE_ADJUST,
                note = "余额校准",
                amount = 1234L,
                excludeFromStats = true,
            ),
            exportTxn(id = "t-3", type = TxnType.TRANSFER, note = "转存", amount = 500L),
        )
        val rows = QianJiMapper.exportRows(
            txns = txns,
            externalIdOf = { QianJiMapper.generatedExternalId(it.id) },
            assetName = { "现金" },
            categoryName = { "其它" },
            memberName = { "用户 A" },
            tagNames = { "" },
            relatedExternalIdOf = { null },
        )

        // 校准行被丢掉：只剩 3 条普通流水
        assertEquals("校准行不应进入导出", 3, rows.size)
        assertTrue("导出不应包含余额校准", rows.none { it.note == "余额校准" })
        assertTrue("不应出现「支出 + 不计收支」的校准行", rows.none { it.type == "支出" && it.flag == QianJiRow.FLAG_EXCLUDE })
        assertEquals(3, rows.map { it.id }.toSet().size)

        // 走完整 codec：导出的 CSV 数据行数 == 普通流水数，表头不变，且可再解析
        val text = QianJiCsvCodec.write(rows)
        val back = QianJiCsvCodec.parse(text)
        assertEquals(3, back.size)
        assertEquals(rows, back)
        assertEquals(
            "ID,时间,分类,二级分类,类型,金额,币种,账户1,账户2,备注,已报销,手续费,优惠券,记账者,账单标记,标签,账单图片,关联账单",
            text.removePrefix(QianJiCsvCodec.BOM).substringBefore("\r\n"),
        )
        assertTrue(back.none { it.note == "余额校准" })
        // 金额汇总里也不含校准的 12.34
        assertEquals(2550L + 100_000L + 500L, back.sumOf { it.amountCents })
    }

    @Test
    fun `生成的externalId稳定且不重复`() {
        val a1 = QianJiMapper.generatedExternalId("t-abc123")
        val a2 = QianJiMapper.generatedExternalId("t-abc123")
        assertEquals(a1, a2)
        assertTrue(a1.startsWith("qj"))
        assertEquals(21, a1.length)
        assertTrue(a1.removePrefix("qj").all { it.isDigit() })

        val ids = (1..5000).map { QianJiMapper.generatedExternalId("t-$it-x") }
        assertEquals("生成的 ID 不能重复", ids.size, ids.toSet().size)
        assertNotEquals(QianJiMapper.generatedExternalId("t-1"), QianJiMapper.generatedExternalId("t-2"))
    }
}
