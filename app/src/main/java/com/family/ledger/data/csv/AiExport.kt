package com.family.ledger.data.csv

import android.content.Context
import android.net.Uri
import com.family.ledger.core.FixedPeople
import com.family.ledger.core.Money
import com.family.ledger.core.TimeFmt
import com.family.ledger.data.db.AppDatabase
import com.family.ledger.data.db.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 面向分析的完整人物/资产维度导出；独立于钱迹兼容 CSV。 */
class AiExport(private val context: Context, private val db: AppDatabase) {
    suspend fun export(uri: Uri, xlsx: Boolean): Int = withContext(Dispatchers.IO) {
        val assets = db.assetDao().allIncludingDeleted().associateBy { it.id }
        val categories = db.categoryDao().allIncludingDeleted().associateBy { it.id }
        val books = db.bookDao().allIncludingDeleted().associateBy { it.id }
        val tags = db.tagDao().allIncludingDeleted().associateBy { it.id }
        val txns = db.txnDao().all().sortedBy { it.occurredAt }
        val rows = txns.map { t ->
            val a = assets[t.assetId]
            listOf(t.id, TimeFmt.toCsv(t.occurredAt).take(10), TimeFmt.toCsv(t.occurredAt).takeLast(8), t.type.name,
                Money.toPlainString(t.amount), t.currency, categories[t.categoryId]?.name.orEmpty(), categories[t.subCategoryId]?.name.orEmpty(),
                t.merchant.orEmpty(), a?.name.orEmpty(), if (a?.ownerType == OwnerType.USER) "PERSONAL" else a?.ownerType?.name.orEmpty(),
                FixedPeople.name(t.payerMemberId), FixedPeople.name(t.consumerMemberId), FixedPeople.name(t.recorderMemberId),
                books[t.bookId]?.name.orEmpty(), t.tagIds.orEmpty().split(',').mapNotNull { tags[it]?.name }.joinToString("|"), t.note.orEmpty(),
                when(t.source) { TxnSource.AUTO_ALIPAY -> "ALIPAY"; TxnSource.AUTO_WECHAT -> "WECHAT"; else -> "" },
                t.source.name, t.source.isAuto.toString(), t.manuallyConfirmed?.toString().orEmpty(),
                t.createdByDeviceId, t.createdAt.toString(), t.updatedAt.toString(),
                Money.toPlainString(t.fee), Money.toPlainString(t.coupon), t.excludeFromStats.toString(),
                assets[t.toAssetId]?.name.orEmpty())
        }
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { out ->
            if (xlsx) TableExport.xlsx(out, HEADER, rows) else out.write(TableExport.csv(HEADER, rows).toByteArray(Charsets.UTF_8))
        }
        txns.size
    }
    companion object {
        val HEADER = listOf("transaction_id","date","time","type","amount","currency","category","subcategory","merchant","asset","asset_owner_type",
            "payer","consumer","recorder","ledger","tags","note","payment_platform","source","auto_detected","manually_confirmed","device_id","created_at","updated_at","fee","coupon","exclude_from_stats","to_asset")
    }
}

/** OOXML 最小工作簿：inlineStr 避免公式执行，金额保持精确十进制文本。 */
object TableExport {
    fun csv(header: List<String>, rows: List<List<String>>): String = "\uFEFF" + (listOf(header) + rows).joinToString("\r\n") { row ->
        row.joinToString(",") { value -> "\"" + value.replace("\"", "\"\"") + "\"" }
    } + "\r\n"
    private fun xml(s: String): String = s.filter { it >= ' ' || it == '\n' || it == '\r' || it == '\t' }
        .replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    fun xlsx(output: OutputStream, header: List<String>, rows: List<List<String>>) {
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            entry("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            entry("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="家庭账单" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            entry("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
            val sheet = buildString {
                append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" state=\"frozen\"/></sheetView></sheetViews><sheetData>")
                (listOf(header) + rows).forEachIndexed { i, row ->
                    append("<row r=\"${i+1}\">")
                    row.forEach { append("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(it)}</t></is></c>") }
                    append("</row>")
                }
                append("</sheetData></worksheet>")
            }
            entry("xl/worksheets/sheet1.xml", sheet)
        }
    }
}
