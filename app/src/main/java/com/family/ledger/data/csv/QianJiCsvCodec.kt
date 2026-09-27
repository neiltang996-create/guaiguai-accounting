package com.family.ledger.data.csv

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import org.apache.commons.csv.QuoteMode
import java.io.StringReader
import java.io.StringWriter

/**
 * 钱迹 CSV 编解码。
 *
 * 纯 Kotlin，不依赖任何 Android API，因此可以直接跑 JVM 单测（合成 CSV 数据）。
 *
 * 兼容性要点：
 *   - 文件以 UTF-8 BOM 开头（写入时也补上，钱迹才能正确识别中文表头）；
 *   - 字段内可能含逗号、双引号、换行（钱迹用标准 CSV 引号包裹）；
 *   - 行尾 CRLF，但解析同时容忍 LF；
 *   - 空行 / 只有逗号的行会被忽略。
 */
object QianJiCsvCodec {

    /** UTF-8 BOM。 */
    const val BOM = "\uFEFF"

    /** 钱迹导出的行尾。 */
    private const val CRLF = "\r\n"

    /** 读取：不 trim、保留空行判定交给调用方、引号与逗号按 RFC4180。 */
    private val READ_FORMAT: CSVFormat = CSVFormat.DEFAULT.builder()
        .setDelimiter(',')
        .setQuote('"')
        .setTrim(false)
        .setIgnoreEmptyLines(true)
        .setIgnoreSurroundingSpaces(false)
        .build()

    /** 写出：最小引号策略（与钱迹一致，普通字段不加引号）。 */
    private val WRITE_FORMAT: CSVFormat = CSVFormat.DEFAULT.builder()
        .setDelimiter(',')
        .setQuote('"')
        .setQuoteMode(QuoteMode.MINIMAL)
        .setRecordSeparator(CRLF)
        .build()

    /**
     * 解析钱迹 CSV 文本（自动跳过 BOM 与表头）。
     * 返回的 [QianJiRow] 保留全部 18 列原文，可无损 [write] 回去。
     */
    fun parse(text: String): List<QianJiRow> {
        val body = stripBom(text)
        if (body.isBlank()) return emptyList()
        val rows = ArrayList<QianJiRow>()
        CSVParser.parse(StringReader(body), READ_FORMAT).use { parser ->
            var headerSkipped = false
            for (record in parser) {
                val fields = ArrayList<String>(record.size())
                for (value in record) fields += value
                if (!headerSkipped && QianJiRow.isHeader(fields)) {
                    headerSkipped = true
                    continue
                }
                if (fields.all { it.isBlank() }) continue
                rows += QianJiRow.fromList(fields)
            }
        }
        return rows
    }

    /**
     * 写出钱迹 CSV 文本：UTF-8 BOM + 一字不差的表头 + CRLF 行尾。
     * `parse(write(rows)) == rows` 恒成立。
     */
    fun write(rows: List<QianJiRow>): String {
        val out = StringWriter()
        CSVPrinter(out, WRITE_FORMAT).use { printer ->
            printer.printRecord(QianJiRow.HEADER)
            for (row in rows) printer.printRecord(row.toFields())
        }
        return BOM + out.toString()
    }

    /** 去掉可能存在的 UTF-8 BOM。 */
    fun stripBom(text: String): String =
        if (text.startsWith(BOM)) text.substring(1) else text
}
