package com.family.ledger.ui.list

import com.family.ledger.ui.components.LedgerEntryRow
import com.family.ledger.ui.components.SymbolBadge
import com.family.ledger.ui.components.categorySymbol
import com.family.ledger.ui.components.LedgerDayHeader
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.family.ledger.AppContainer
import com.family.ledger.core.Money
import com.family.ledger.core.TimeFmt
import com.family.ledger.data.db.entity.CategoryEntity
import com.family.ledger.data.db.entity.TxnEntity
import com.family.ledger.data.db.entity.TxnType
import com.family.ledger.data.repo.LedgerRepository
import com.family.ledger.data.repo.ExpenseRecoveries
import com.family.ledger.data.repo.RecoveryKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** 支出红 / 收入绿 / 转账灰，全 App 统一口径。 */
internal val ExpenseColor: Color
    @Composable get() = com.family.ledger.ui.theme.LedgerTheme.accents.expense
internal val IncomeColor: Color
    @Composable get() = com.family.ledger.ui.theme.LedgerTheme.accents.income
internal val NeutralColor = Color(0xFF616161)

/** 某个月的月初（本地时区），offsetMonths 为 0 表示本月。 */
internal fun monthStartOf(epochMillis: Long, offsetMonths: Long = 0L): Long =
    Instant.ofEpochMilli(epochMillis).atZone(TimeFmt.ZONE).toLocalDate()
        .withDayOfMonth(1)
        .plusMonths(offsetMonths)
        .atStartOfDay(TimeFmt.ZONE)
        .toInstant()
        .toEpochMilli()

/** "2026-09" → "2026年9月"。 */
internal fun monthLabel(key: String): String {
    val parts = key.split("-")
    if (parts.size < 2) return key
    val month = parts[1].trimStart('0').ifEmpty { "0" }
    return "${parts[0]}年${month}月"
}

/** 本地搜索用：把备注 / 商户 / 分类 / 二级分类 / 资产名拼成可匹配文本。 */
private fun searchHaystack(
    t: TxnEntity,
    categoryNames: Map<String, String>,
    assetNames: Map<String, String>,
): String = buildString {
    append(t.note.orEmpty()).append(' ')
    append(t.merchant.orEmpty()).append(' ')
    t.categoryId?.let { categoryNames[it] }?.let { append(it).append(' ') }
    t.subCategoryId?.let { categoryNames[it] }?.let { append(it).append(' ') }
    t.assetId?.let { assetNames[it] }?.let { append(it).append(' ') }
    t.toAssetId?.let { assetNames[it] }?.let { append(it).append(' ') }
}

/**
 * 账单列表：按月分组 + 搜索 + 编辑（分类/备注/金额）+ 软删除。
 *
 * 只读用 Flow（observeBetween / search），写入一律走 LedgerRepository。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillListScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    val assets by remember { container.assets.observeAllWithBalance() }.collectAsState(initial = emptyList())
    val categories by remember { container.categories.observeAll() }.collectAsState(initial = emptyList())
    val assetNames = remember(assets) { assets.associate { it.asset.id to it.asset.name } }
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }

    // 展示最近 12 个月（含本月）
    val now = remember { System.currentTimeMillis() }
    val from = remember(now) { monthStartOf(now, -11) }
    val to = remember(now) { monthStartOf(now, 1) }
    val allTxns by remember { container.ledger.observeRecent() }.collectAsState(initial = emptyList())
    val visibleBills = remember(allTxns) { ExpenseRecoveries.bills(allTxns) }
    val txns = remember(visibleBills, from, to) { visibleBills.filter { it.occurredAt >= from && it.occurredAt < to } }
    val statistics = remember(allTxns) { ExpenseRecoveries.forStatistics(allTxns) }

    val monthFrom = remember(now) { monthStartOf(now, 0) }
    val monthTo = remember(now) { monthStartOf(now, 1) }
    val monthTotals by remember(monthFrom, monthTo) { container.ledger.observeMonthTotal(monthFrom, monthTo) }
        .collectAsState(initial = LedgerRepository.MonthTotals(0L, 0L, 0L, 0L, 0))

    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<TxnEntity>?>(null) }
    var searching by remember { mutableStateOf(false) }
    // 编辑/删除后要重跑一次搜索，否则搜索结果会停在旧快照上
    var searchTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(query, searchTick, allTxns) {
        val q = query.trim()
        if (q.isEmpty()) {
            searchResults = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(250) // 输入防抖
        searchResults = runCatching { container.ledger.search(q) }.getOrDefault(emptyList())
        searching = false
    }

    // 搜索口径 = DAO 查备注/商户 + 本地匹配分类/二级分类/资产名（只覆盖当前 12 个月窗口）
    val results = searchResults
    val shown = remember(results, txns, query, categoryNames, assetNames, visibleBills) {
        if (results == null) {
            txns
        } else {
            val q = query.trim()
            val local = txns.filter { t -> searchHaystack(t, categoryNames, assetNames).contains(q, ignoreCase = true) }
            (results + local).filter { t -> visibleBills.any { it.id == t.id } }.distinctBy { it.id }.sortedByDescending { it.occurredAt }
        }
    }

    var typeFilter by remember { mutableStateOf("全部") }
    val filtered = shown.filter { it.type != TxnType.BALANCE_ADJUST }.filter { when(typeFilter) {
        "支出" -> it.type == TxnType.EXPENSE
        "收入" -> it.type == TxnType.INCOME
        "退款/报销" -> it.type == TxnType.REFUND || (it.type == TxnType.EXPENSE && ExpenseRecoveries.recovered(it, allTxns) > 0)
        "转账" -> it.type in listOf(TxnType.TRANSFER, TxnType.REPAYMENT)
        else -> true
    } }

    var editTarget by remember { mutableStateOf<TxnEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账单") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索备注 / 商户 / 分类 / 资产") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )
            MonthSummaryCard(monthTotals)
            Spacer(Modifier.height(8.dp))
            if (searching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "支出", "收入", "退款/报销", "转账").forEach { label ->
                    FilterChip(selected = typeFilter == label, onClick = { typeFilter = label }, label = { Text(label) })
                }
            }
            val grouped = remember(filtered) {
                filtered.groupBy { TimeFmt.toDay(it.occurredAt) }
                    .entries
                    .sortedByDescending { it.key }
                    .map { it.key to it.value }
            }

            if (grouped.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (searchResults != null) "没有找到匹配的账单" else "还没有账单，去「记一笔」开始吧",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    grouped.forEach { (month, list) ->
                        item(key = "month-$month") { LedgerDayHeader(month, statistics.filter { t -> list.any { it.id == t.id || it.id == t.relatedTxnId } }) }
                        items(list, key = { it.id }) { txn ->
                            TxnRow(
                                txn = txn,
                                categoryNames = categoryNames,
                                assetNames = assetNames,
                                onClick = { editTarget = txn },
                                recovered = if (txn.type == TxnType.EXPENSE) ExpenseRecoveries.recovered(txn, allTxns) else 0,
                            )
                        }
                    }
                }
            }
        }
    }

    editTarget?.let { target ->
        BillDetailsDialog(container, target.id, onDismiss = { editTarget = null }, onChanged = { searchTick++ })
    }
}

/** 首页与账单列表共用详情；所有退回操作都从对应的原支出进入。 */
@Composable
fun BillDetailsDialog(container: AppContainer, txnId: String, onDismiss: () -> Unit, onChanged: () -> Unit = {}) {
    val all by remember { container.ledger.observeRecent() }.collectAsState(emptyList())
    val categories by remember { container.categories.observeAll() }.collectAsState(emptyList())
    val assets by remember { container.assets.observeAllWithBalance() }.collectAsState(emptyList())
    val categoryNames = categories.associate { it.id to it.name }
    val assetNames = assets.associate { it.asset.id to it.asset.name }
    val target = all.firstOrNull { it.id == txnId } ?: return
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var recovery by remember { mutableStateOf<RecoveryKind?>(null) }
    var revoke by remember { mutableStateOf<TxnEntity?>(null) }
    var linking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun write(close: Boolean, message: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            val result = runCatching { action() }
            busy = false
            if (result.isSuccess) { feedback = message; onChanged(); if (close) onDismiss() }
            else error = result.exceptionOrNull()?.message ?: "操作失败，请重试"
        }
    }
    if (recovery != null) {
        val kind = recovery!!
        RecoveryFormSheet(container, target, kind, onDismiss = { recovery = null },
            onSaved = { recovery = null; feedback = "${kind.label}已保存，已扣减原支出"; onChanged() },
            onSave = { amount, asset, time, note -> container.ledger.createRecoveryFor(target.id, kind, amount, asset, time, note); Unit })
        return
    }
    if (linking) {
        ExpensePickerSheet(container, "关联原支出", suggestedMerchant = target.merchant,
            onDismiss = { if (!busy) linking = false }, onSelect = { origin ->
                write(true, "已关联原支出") { container.ledger.linkRecovery(target.id, origin.id, ExpenseRecoveries.kind(target)) }
            })
        error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("无法关联") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
        return
    }
    TxnEditDialog(target, busy, error, categories, categoryNames, assetNames, onDismiss,
        onSave = { amount, note, category, subCategory ->
            write(true, "已保存") { container.ledger.update(target.copy(amount = amount, note = note, categoryId = category, subCategoryId = subCategory)) }
        }, onDelete = { write(true, "已删除") { container.ledger.softDelete(target.id) } },
        details = {
            if (target.type == TxnType.EXPENSE) {
                val refunds = ExpenseRecoveries.linked(target, all)
                Text("实付 ${Money.format(ExpenseRecoveries.paid(target))} · 净支出 ${Money.format(ExpenseRecoveries.remaining(target, all))}",
                    style = MaterialTheme.typography.titleSmall)
                Text("累计退款 ${Money.format(refunds.filter { ExpenseRecoveries.kind(it) == RecoveryKind.REFUND }.sumOf { it.amount })} · 报销 ${Money.format(refunds.filter { ExpenseRecoveries.kind(it) == RecoveryKind.REIMBURSEMENT }.sumOf { it.amount } + ExpenseRecoveries.historicalReimbursement(target, all))}",
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecoveryKind.entries.forEach { kind ->
                        OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { recovery = kind; error = null }) { Text(kind.label) }
                    }
                }
                if (ExpenseRecoveries.remaining(target, all) < 0) Text("累计退回已超过实付金额，请核对并撤销重复记录。", color = MaterialTheme.colorScheme.error)
                else if (ExpenseRecoveries.remaining(target, all) == 0L) Text("已全部收回，无剩余可退款或报销金额", style = MaterialTheme.typography.bodySmall)
                if (ExpenseRecoveries.historicalReimbursement(target, all) > 0) Text("含历史导入的已报销汇总；不重复增加账户余额。", style = MaterialTheme.typography.bodySmall)
                refunds.forEach { r ->
                    Text("${ExpenseRecoveries.kind(r).label} ${Money.format(r.amount)} · ${assetNames[r.assetId] ?: "未指定账户"}")
                    Text("到账 ${TimeFmt.toCsv(r.occurredAt)}", style = MaterialTheme.typography.bodySmall)
                    r.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    com.family.ledger.ui.components.ReceiptAttachment(r.imagePaths)
                    TextButton(enabled = !busy, onClick = { revoke = r; error = null }) { Text("撤销这笔${ExpenseRecoveries.kind(r).label}") }
                }
            } else if (target.type == TxnType.REFUND) {
                Text("${ExpenseRecoveries.kind(target).label}冲减支出，不计收入；关联后回溯原支出日期。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = { linking = true; error = null }) { Text("关联原支出") }
            }
            feedback?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(12.dp))
        })
    revoke?.let { row ->
        AlertDialog(onDismissRequest = { if (!busy) revoke = null }, title = { Text("撤销这笔${ExpenseRecoveries.kind(row).label}？") },
            text = { Column { Text("将移除 ${Money.format(row.amount)} 的到账记录，恢复原支出和账户余额。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                write(false, "已撤销") { container.ledger.softDelete(row.id); revoke = null }
            }) { Text("确认撤销") } }, dismissButton = { TextButton(enabled = !busy, onClick = { revoke = null; error = null }) { Text("取消") } })
    }
}

@Composable
private fun MonthSummaryCard(totals: LedgerRepository.MonthTotals) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "本月 · ${totals.count} 笔",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryItem("支出", Money.format(totals.expense), ExpenseColor, Modifier.weight(1f))
                SummaryItem("收入", Money.format(totals.income), IncomeColor, Modifier.weight(1f))
                SummaryItem(
                    "结余",
                    Money.format(totals.net),
                    if (totals.net >= 0) IncomeColor else ExpenseColor,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MonthHeader(month: String, list: List<TxnEntity>) {
    var expense = 0L
    var income = 0L
    for (t in list) {
        if (t.deleted || t.excludeFromStats) continue
        when (t.type) {
            TxnType.EXPENSE -> expense += t.amount + t.fee - t.coupon
            TxnType.INCOME -> income += t.amount - t.fee
            TxnType.REFUND -> expense -= t.amount
            else -> Unit
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = monthLabel(month),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "支出 ${Money.format(expense)} · 收入 ${Money.format(income)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TxnRow(
    txn: TxnEntity,
    categoryNames: Map<String, String>,
    assetNames: Map<String, String>,
    onClick: () -> Unit,
    recovered: Long = 0,
) {
    val asset = listOfNotNull(assetNames[txn.assetId], txn.toAssetId?.let { assetNames[it] }).joinToString(" → ")
    LedgerEntryRow(txn, categoryNames[txn.subCategoryId ?: txn.categoryId], asset, onClick, parentCategory = categoryNames[txn.categoryId], recovered = recovered)
}

private enum class TxnDialogMode { EDIT, CATEGORY, DELETE }

@Composable
private fun TxnEditDialog(
    txn: TxnEntity,
    busy: Boolean,
    error: String?,
    categories: List<CategoryEntity>,
    categoryNames: Map<String, String>,
    assetNames: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (amountCents: Long, note: String?, categoryId: String?, subCategoryId: String?) -> Unit,
    onDelete: () -> Unit,
    details: @Composable () -> Unit = {},
) {
    var mode by remember(txn.id) { mutableStateOf(TxnDialogMode.EDIT) }
    var amountText by remember(txn.id) { mutableStateOf(Money.toPlainString(txn.amount)) }
    var note by remember(txn.id) { mutableStateOf(txn.note.orEmpty()) }
    var categoryId by remember(txn.id) { mutableStateOf(txn.categoryId) }
    var subCategoryId by remember(txn.id) { mutableStateOf(txn.subCategoryId) }

    val kind = when (txn.type) {
        TxnType.EXPENSE -> "EXPENSE"
        TxnType.INCOME -> "INCOME"
        else -> null
    }
    val categoryText = listOfNotNull(
        categoryId?.let { categoryNames[it] },
        subCategoryId?.let { categoryNames[it] },
    ).joinToString(" · ").ifEmpty { "未分类" }

    when (mode) {
        TxnDialogMode.CATEGORY -> AlertDialog(
            onDismissRequest = { if (!busy) mode = TxnDialogMode.EDIT },
            title = { Text("选择分类") },
            text = {
                CategoryPickerList(categories = categories, kind = kind) { top, child ->
                    categoryId = top
                    subCategoryId = child
                    mode = TxnDialogMode.EDIT
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    categoryId = null
                    subCategoryId = null
                    mode = TxnDialogMode.EDIT
                }) { Text("清除分类") }
            },
            dismissButton = {
                TextButton(onClick = { mode = TxnDialogMode.EDIT }) { Text("返回") }
            },
        )

        TxnDialogMode.DELETE -> AlertDialog(
            onDismissRequest = { if (!busy) mode = TxnDialogMode.EDIT },
            title = { Text("删除这笔账？") },
            text = { Column { Text("删除后，家庭账本中将不再显示这笔账，账户余额也会相应更新。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = {
                TextButton(enabled = !busy, onClick = onDelete) { Text("删除", color = ExpenseColor) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { mode = TxnDialogMode.EDIT }) { Text("取消") }
            },
        )

        TxnDialogMode.EDIT -> AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text("账单详情") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    details()
                    com.family.ledger.ui.components.ReceiptAttachment(txn.imagePaths)
                    Text(
                        text = "${txn.type.cn} · ${txn.assetId?.let { assetNames[it] } ?: "未指定资产"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("金额（元）") },
                        isError = (com.family.ledger.ui.components.FormAmount.parse(amountText) ?: 0L) <= 0L,
                        supportingText = { if ((com.family.ledger.ui.components.FormAmount.parse(amountText) ?: 0L) <= 0L) Text("请输入大于零的金额，最多两位小数") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("备注") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { mode = TxnDialogMode.CATEGORY },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("分类：$categoryText")
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && (com.family.ledger.ui.components.FormAmount.parse(amountText) ?: 0L) > 0L,
                    onClick = {
                        onSave(
                            Money.parseToCents(amountText),
                            note.trim().ifEmpty { null },
                            categoryId,
                            subCategoryId,
                        )
                    }
                ) { Text(if (busy) "保存中…" else "保存") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = { mode = TxnDialogMode.DELETE }) {
                        Text("删除", color = ExpenseColor)
                    }
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
                }
            },
        )
    }
}

/** 分类选择器：一级分类 + 其二级分类，点一级只填一级，点二级同时填两级。 */
@Composable
private fun CategoryPickerList(
    categories: List<CategoryEntity>,
    kind: String?,
    onPick: (topId: String, childId: String?) -> Unit,
) {
    val tops = remember(categories, kind) {
        categories
            .filter { it.parentId == null && !it.archived && (kind == null || it.kind == kind) }
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
    }
    val childrenByParent = remember(categories) {
        categories.filter { it.parentId != null && !it.archived }.groupBy { it.parentId }
    }
    LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
        items(tops, key = { it.id }) { top ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().clickable { onPick(top.id, null) }
                    .padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SymbolBadge(categorySymbol(top.name), size = 30.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(top.name, style = MaterialTheme.typography.bodyLarge)
                }
                (childrenByParent[top.id] ?: emptyList())
                    .sortedWith(compareBy({ it.sortOrder }, { it.name }))
                    .forEach { child ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(top.id, child.id) }
                            .padding(start = 20.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            SymbolBadge(categorySymbol(child.name, top.name), size = 26.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(child.name, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
            }
        }
    }
}
