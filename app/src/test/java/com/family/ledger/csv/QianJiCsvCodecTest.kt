package com.family.ledger.csv

import com.family.ledger.core.TimeFmt
import com.family.ledger.data.csv.QianJiCsvCodec
import com.family.ledger.data.csv.QianJiRow
import com.family.ledger.data.db.entity.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic format parsing, all transaction types and round trips; never loads a user's CSV. */
class QianJiCsvCodecTest {
    private fun sampleText() = com.family.ledger.fixtures.SampleLedger.text
    @Test fun `合成 CSV 解析六种类型`() {
        val rows = QianJiCsvCodec.parse(sampleText())
        assertEquals(6, rows.size)
        assertEquals("demo-1", rows.first().id)
        assertEquals("示例早餐", rows.first().note)
        assertEquals(3000L, rows.first().amountCents)
        assertEquals(setOf("支出", "收入", "退款", "转账", "还款", "债务-还款"), rows.map { it.type }.toSet())
        rows.forEach { assertNotNull(it.txnType); assertNotNull(it.occurredAt) }
        assertEquals(TxnType.REPAYMENT, rows.last().txnType)
        assertEquals(listOf("demo-1"), rows[2].relatedIds)
    }
    @Test fun `表头 BOM LF 和往返一致`() {
        val rows = QianJiCsvCodec.parse(sampleText())
        assertEquals(18, QianJiRow.HEADER.size)
        assertEquals(QianJiRow.HEADER_LINE, sampleText().removePrefix(QianJiCsvCodec.BOM).substringBefore("\r\n"))
        val written = QianJiCsvCodec.write(rows)
        assertTrue(written.startsWith(QianJiCsvCodec.BOM))
        assertEquals(rows, QianJiCsvCodec.parse(written))
        assertEquals(written, QianJiCsvCodec.write(QianJiCsvCodec.parse(written)))
        assertEquals(rows, QianJiCsvCodec.parse(sampleText().replace("\r\n", "\n")))
        assertEquals(rows.first().time, TimeFmt.toCsv(rows.first().occurredAt!!))
    }

    // ---------- 边界 ----------

    @Test
    fun `空文件解析为空列表`() {
        assertEquals(emptyList<QianJiRow>(), QianJiCsvCodec.parse(""))
        assertEquals(emptyList<QianJiRow>(), QianJiCsvCodec.parse(QianJiCsvCodec.BOM))
        assertEquals(emptyList<QianJiRow>(), QianJiCsvCodec.parse("\n\r\n   \n"))
        assertEquals(emptyList<QianJiRow>(), QianJiCsvCodec.parse(QianJiCsvCodec.write(emptyList())))
    }

    @Test
    fun `只有表头解析为空列表且能原样写出`() {
        val headerOnly = QianJiCsvCodec.BOM + QianJiRow.HEADER_LINE + "\r\n"
        assertEquals(0, QianJiCsvCodec.parse(headerOnly).size)

        val noBom = QianJiRow.HEADER_LINE + "\n"
        assertEquals(0, QianJiCsvCodec.parse(noBom).size)
    }

    @Test
    fun `字段含逗号引号与换行时往返一致`() {
        val tricky = QianJiRow(
            id = "qj1",
            time = "2026-01-29 21:41:17",
            category = "其它",
            subCategory = "",
            type = "支出",
            amount = "12.30",
            currency = "CNY",
            account1 = "微信零钱（用户 A）",
            account2 = "",
            note = "含,逗号 \"引号\" 和\n换行",
            reimbursed = "",
            fee = "0.50",
            coupon = "1.00",
            recorder = "用户 A",
            flag = QianJiRow.FLAG_EXCLUDE,
            tags = "报销,旅行",
            images = "http://a/b.jpg,http://a/c.jpg",
            relatedBill = "qj2",
        )
        val text = QianJiCsvCodec.write(listOf(tricky))
        val back = QianJiCsvCodec.parse(text)
        assertEquals(listOf(tricky), back)

        val row = back.single()
        assertEquals("含,逗号 \"引号\" 和\n换行", row.note)
        assertEquals(1230L, row.amountCents)
        assertEquals(50L, row.feeCents)
        assertEquals(100L, row.couponCents)
        assertEquals(listOf("报销", "旅行"), row.tagList)
        assertEquals(listOf("http://a/b.jpg", "http://a/c.jpg"), row.imageList)
        assertEquals(listOf("qj2"), row.relatedIds)
        assertTrue(row.excludedFromStats)
        assertNotNull(row.occurredAt)
    }

    @Test
    fun `缺列的行补空串 多列的行按前 18 列截断`() {
        val short = QianJiCsvCodec.parse("qj9,2026-01-01 00:00:00,吃\n")
        assertEquals(1, short.size)
        assertEquals("qj9", short[0].id)
        assertEquals("吃", short[0].category)
        assertEquals("", short[0].relatedBill)
        assertEquals(18, short[0].toFields().size)

        val long = QianJiCsvCodec.parse((1..20).joinToString(",") + "\r\n").single()
        assertEquals(18, long.toFields().size)
        assertEquals("18", long.relatedBill)
        assertEquals("1", long.id)
    }

    @Test
    fun `无表头的数据文件也能解析`() {
        val text = "qj7,2026-01-01 08:00:00,吃,早餐,支出,8.0,CNY,现金,,,,,,用户 A,,,,\r\n"
        val rows = QianJiCsvCodec.parse(text)
        assertEquals(1, rows.size)
        assertEquals("qj7", rows[0].id)
        assertEquals(TxnType.EXPENSE, rows[0].txnType)
        assertFalse(rows[0].excludedFromStats)
    }

}
