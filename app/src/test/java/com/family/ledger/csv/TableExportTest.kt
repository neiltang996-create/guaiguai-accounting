package com.family.ledger.csv
import com.family.ledger.data.csv.TableExport
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
class TableExportTest {
    @Test fun workbookIsValidZipWithEscapedInlineStrings() {
        val out=ByteArrayOutputStream()
        TableExport.xlsx(out,listOf("商户","备注"),listOf(listOf("用户 A & 用户 B","=1+1<测试>")))
        val entries=mutableMapOf<String,String>()
        ZipInputStream(out.toByteArray().inputStream()).use { z ->
            var entry=z.nextEntry
            while(entry!=null) { entries[entry.name]=z.readBytes().decodeToString(); entry=z.nextEntry }
        }
        assertTrue(entries.containsKey("[Content_Types].xml"))
        val sheet=entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("用户 A &amp; 用户 B")); assertTrue(sheet.contains("=1+1&lt;测试&gt;")); assertFalse(sheet.contains("<f>"))
    }
    @Test fun csvEscapesCommaQuotesAndNewlines() {
        val csv=TableExport.csv(listOf("note"),listOf(listOf("他说\"你好\",\n家")))
        assertTrue(csv.startsWith("\uFEFF")); assertTrue(csv.contains("他说\"\"你好\"\",\n家"))
    }
}
