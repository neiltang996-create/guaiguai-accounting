package com.family.ledger.data.csv

import android.content.Context
import androidx.room.withTransaction
import com.family.ledger.core.FixedPeople
import android.net.Uri
import com.family.ledger.data.db.AppDatabase
import com.family.ledger.data.db.entity.TxnEntity
import com.family.ledger.data.repo.AssetRepository
import com.family.ledger.data.repo.CategoryRepository
import com.family.ledger.data.repo.FamilyRepository
import com.family.ledger.data.repo.LedgerRepository
import com.family.ledger.data.sync.SyncJournal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ImportResult(
    val total: Int = 0,
    val imported: Int = 0,
    val skipped: Int = 0,
    val createdAssets: Int = 0,
    val createdCategories: Int = 0,
    val errors: List<String> = emptyList(),
)

data class ImportPreview(val text: String, val total: Int, val ready: Int, val duplicates: Int, val errors: List<String>, val assetNames: List<String>)

data class ExportResult(val count: Int, val uri: Uri)

/**
 * 钱迹 CSV 互操作入口（导入 / 导出）。
 *
 * 注意：本文件的**构造签名与公开方法签名已冻结**，由 csv-import 负责实现体。
 * 首启时用户会用「导入钱迹 CSV」导入自己的历史账单。
 *
 * 导入策略（覆盖合成测试和个人使用验证）：
 *   - 解析纯内存完成，写库走 `upsertAll` 单批；
 *   - oplog 用 [SyncJournal.recordAll] 一次性写入，不逐条 upsert；
 *   - 去重键是钱迹 ID（[TxnEntity.externalId]），含已软删除的墓碑；
 *   - 「关联账单」第二遍解析（被关联的单可能出现在后面）。
 */
class QianJiCsvRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val ledger: LedgerRepository,
    private val assets: AssetRepository,
    private val categories: CategoryRepository,
    private val family: FamilyRepository,
    private val journal: SyncJournal,
) {

    // ---------- 导入 ----------
    suspend fun preview(uri: Uri): ImportPreview = withContext(Dispatchers.IO) { previewText(readText(uri)) }
    suspend fun previewText(text: String): ImportPreview {
        val rows = QianJiCsvCodec.parse(text)
        val seen = db.txnDao().allIncludingDeleted().mapNotNull { it.externalId }.toMutableSet()
        var ready = 0; var duplicates = 0
        val errors = mutableListOf<String>()
        rows.forEachIndexed { index, row ->
            val error = validationError(row)
            when {
                error != null -> errors += "第 ${index + 2} 行：$error"
                !seen.add(row.id.trim()) -> duplicates++
                else -> ready++
            }
        }
        return ImportPreview(text, rows.size, ready, duplicates, errors,
            rows.flatMap { listOf(it.account1, it.account2) }.filter { it.isNotBlank() }.distinct())
    }
    private fun validationError(row: QianJiRow): String? = when {
        row.id.isBlank() -> "缺少钱迹 ID，无法安全去重"
        row.txnType == null -> "无法识别类型 ${row.type}"
        row.occurredAt == null -> "无法识别时间 ${row.time}"
        row.amountCents == 0L -> "金额无效或为零"
        row.currency.isNotBlank() && row.currency != "CNY" -> "当前版本只支持人民币"
        row.recorder.isNotBlank() && FixedPeople.idForName(row.recorder) == null -> "记账者 ${row.recorder} 需要映射到固定人物"
        else -> null
    }
    suspend fun importPreview(preview: ImportPreview, assetMapping: Map<String,String>): ImportResult = withContext(Dispatchers.IO) {
        // 使用预览时读取的文件内容；确认前后文件变化不会悄悄改变导入对象。
        doImport(preview.text, assetMapping)
    }


    suspend fun importFromText(text: String): ImportResult = withContext(Dispatchers.IO) {
        try {
            doImport(text)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Exception) {
            ImportResult(errors = listOf("导入失败：${messageOf(t)}"))
        }
    }

    suspend fun importFrom(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            doImport(readText(uri))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Exception) {
            ImportResult(errors = listOf("读取或导入失败：${messageOf(t)}"))
        }
    }

    private suspend fun doImport(text: String, mapping: Map<String,String> = emptyMap()): ImportResult = db.withTransaction {
        val rows = QianJiCsvCodec.parse(text)
        if (rows.isEmpty()) {
            return@withTransaction ImportResult(errors = listOf("CSV 中没有可导入的账单行，请确认是钱迹导出的账单文件"))
        }

        val book = family.defaultBook()
        val deviceId = resolveDeviceId()
        val mapper = QianJiMapper(assets, categories, family)
        mapper.load()
        mapper.setAssetMappings(mapping)

        // 去重：钱迹 ID → 本地流水 id。含软删除的墓碑，避免把用户删掉的账再灌回来。
        val localIdByExternal = HashMap<String, String>()
        for (t in db.txnDao().allIncludingDeleted()) {
            val ext = t.externalId?.trim().orEmpty()
            if (ext.isNotEmpty()) localIdByExternal.putIfAbsent(ext, t.id)
        }

        val errors = ArrayList<String>()
        val imported = ArrayList<TxnEntity>(rows.size)
        val pendingRelated = ArrayList<Pair<Int, List<String>>>()
        var skipped = 0

        rows.forEachIndexed { index, row ->
            val invalid = validationError(row)
            if (invalid != null) { errors += "第 ${index + 2} 行：$invalid"; return@forEachIndexed }
            val ext = row.id.trim()
            if (ext.isNotEmpty() && localIdByExternal.containsKey(ext)) {
                skipped++
                return@forEachIndexed
            }
            val txn = mapper.toTxn(row, book.id, deviceId)
            if (txn == null) {
                errors += "第 ${index + 2} 行：无法识别（类型「${row.type}」时间「${row.time}」）"
                return@forEachIndexed
            }
            imported += txn
            if (ext.isNotEmpty()) localIdByExternal[ext] = txn.id
            if (row.relatedIds.isNotEmpty()) pendingRelated += (imported.size - 1) to row.relatedIds
        }

        // 第二遍：把「关联账单」的钱迹 ID 换成本地流水 id
        for ((i, relatedIds) in pendingRelated) {
            val localId = relatedIds.firstNotNullOfOrNull { localIdByExternal[it] } ?: continue
            imported[i] = imported[i].copy(relatedTxnId = localId)
        }

        if (imported.isNotEmpty()) {
            db.txnDao().upsertAll(imported)
            // 批量写 oplog：整批导入只占一段 seq（逐条 journal 会产生 600 次单独写入）
            journal.recordAll(imported)
        }

        ImportResult(
            total = rows.size,
            imported = imported.size,
            skipped = skipped,
            createdAssets = mapper.createdAssets,
            createdCategories = mapper.createdCategories,
            errors = errors,
        )
    }

    // ---------- 导出 ----------

    suspend fun exportToString(): String = withContext(Dispatchers.IO) { doExport().first }

    suspend fun exportTo(uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        val (text, count) = doExport()
        writeText(uri, text)
        ExportResult(count, uri)
    }

    private suspend fun doExport(): Pair<String, Int> {
        val txns = ledger.all().sortedWith(compareBy({ it.occurredAt }, { it.id }))

        // 反查表：id → 名字
        val assetNames = HashMap<String, String>()
        assets.all().forEach { assetNames[it.id] = it.name }

        val categoryNames = HashMap<String, String>()
        for (kind in listOf(QianJiMapper.KIND_EXPENSE, QianJiMapper.KIND_INCOME)) {
            for (group in categories.groups(kind)) {
                categoryNames[group.top.id] = group.top.name
                for (child in group.children) categoryNames[child.id] = child.name
            }
        }

        val memberNames = HashMap<String, String>()
        family.members().forEach { memberNames[it.id] = it.displayName }

        val tagNameById = HashMap<String, String>()
        categories.tags().forEach { tagNameById[it.id] = it.name }

        // 对外 ID：有钱迹 ID 就沿用，手工记的账生成稳定的钱迹格式 ID
        val externalById = HashMap<String, String>(txns.size * 2)
        for (t in txns) {
            val ext = t.externalId?.trim().orEmpty()
            externalById[t.id] = ext.ifEmpty { QianJiMapper.generatedExternalId(t.id) }
        }

        // 映射时会跳过「余额校准」（钱迹 CSV 没有该类型，见 QianJiMapper.exportRows 注释）
        val rows = QianJiMapper.exportRows(
            txns = txns,
            externalIdOf = { externalById.getValue(it.id) },
            assetName = { id -> id?.let { assetNames[it] }.orEmpty() },
            categoryName = { id -> id?.let { categoryNames[it] }.orEmpty() },
            memberName = { id -> id?.let { memberNames[it] }.orEmpty() },
            tagNames = { ids -> ids.mapNotNull { tagNameById[it] }.joinToString(",") },
            relatedExternalIdOf = { txn -> txn.relatedTxnId?.let { externalById[it] } },
        )
        return QianJiCsvCodec.write(rows) to rows.size
    }

    // ---------- 文件 IO ----------

    private fun readText(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: File(uri.path ?: error("无法打开文件：$uri")).takeIf { it.exists() }?.readBytes()
            ?: error("无法打开文件：$uri")
        // 按 UTF-8 解码；BOM 由 codec 去掉
        return bytes.decodeToString()
    }

    private fun writeText(uri: Uri, text: String) {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: File(uri.path ?: error("无法写入文件：$uri")).outputStream()
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /**
     * 本机 deviceId。
     * 与 [com.family.ledger.data.SettingsStore] 共用同一个 prefs 文件与 key
     * （AppContainer 构造 SyncJournal 时已经读取并落盘），取不到再退回「我」这个成员。
     */
    private suspend fun resolveDeviceId(): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEVICE_ID, null)
            ?: family.me()?.deviceId
            ?: FALLBACK_DEVICE_ID

    private fun messageOf(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    private companion object {
        const val PREFS_NAME = "family_ledger_prefs"
        const val KEY_DEVICE_ID = "device_id"
        const val FALLBACK_DEVICE_ID = "qj-import"
    }
}
