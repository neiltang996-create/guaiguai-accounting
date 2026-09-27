package com.family.ledger.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.family.ledger.AppContainer
import com.family.ledger.core.Money
import com.family.ledger.core.TimeFmt
import com.family.ledger.data.db.entity.*
import com.family.ledger.data.repo.*
import com.family.ledger.ui.components.*
import kotlinx.coroutines.launch

private val RecoveryScheme = darkColorScheme(primary = Color(0xFF87BFAA), onPrimary = Color(0xFF123A2F),
    surfaceContainerLow = Color(0xFF1B1F22), surfaceContainerHigh = Color(0xFF272C31),
    surface = Color(0xFF1B1F22), surfaceVariant = Color(0xFF272C31),
    onSurface = Color(0xFFDEE5E1), onSurfaceVariant = Color(0xFFADB8BA), error = Color(0xFFE29A8F))

/** 原支出选择：只作建议排序，必须由使用者点选，不凭金额自动关联。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensePickerSheet(container: AppContainer, title: String, suggestedMerchant: String? = null,
    onDismiss: () -> Unit, onSelect: (TxnEntity) -> Unit) {
    val all by remember { container.ledger.observeRecent() }.collectAsState(emptyList())
    val assets by remember { container.assets.observeAllWithBalance() }.collectAsState(emptyList())
    val categories by remember { container.categories.observeAll() }.collectAsState(emptyList())
    val names = remember(assets) { assets.associate { it.asset.id to it.asset.name } }
    val catNames = remember(categories) { categories.associate { it.id to it.name } }
    var query by remember { mutableStateOf("") }
    val candidates = remember(all, query, names, catNames, suggestedMerchant) {
        all.filter { it.type == TxnType.EXPENSE && !it.deleted }.filter {
            listOfNotNull(it.merchant, it.note, names[it.assetId], catNames[it.categoryId],
                catNames[it.subCategoryId], TimeFmt.toDay(it.occurredAt), Money.toPlainString(it.amount))
                .joinToString(" ").contains(query.trim(), ignoreCase = true)
        }.sortedWith(compareByDescending<TxnEntity> { !suggestedMerchant.isNullOrBlank() && it.merchant == suggestedMerchant }
            .thenByDescending { it.occurredAt })
    }
    MaterialTheme(colorScheme = RecoveryScheme) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text("选择原支出后填写到账金额和账户；按原支出日期扣减统计。", Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(query, { query = it }, label = { Text("搜索商户、备注、日期、金额或账户") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f)) {
                    if (candidates.isEmpty()) item { Text("没有找到原支出，请调整搜索条件或先补记原支出。", Modifier.padding(vertical = 24.dp)) }
                    items(candidates, key = { it.id }) { txn ->
                        Column(Modifier.fillMaxWidth().clickable { onSelect(txn) }.padding(vertical = 14.dp)) {
                            Text(txn.merchant ?: txn.note?.takeIf { it.isNotBlank() } ?: catNames[txn.categoryId] ?: "支出")
                            Text("${TimeFmt.toDay(txn.occurredAt)} · ${names[txn.assetId] ?: "未指定账户"}", style = MaterialTheme.typography.bodySmall)
                            Text("实付 ${Money.format(ExpenseRecoveries.paid(txn))} · 剩余可退/报销 ${Money.format(ExpenseRecoveries.remaining(txn, all))}",
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        }
    }
}

/** 手动与自动识别共用的到账表单；成功才关闭，失败保留所有输入。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryFormSheet(container: AppContainer, origin: TxnEntity, kind: RecoveryKind,
    initialAmount: Long? = null, initialAssetId: String? = null,
    initialOccurredAt: Long? = null, imagePaths: String? = null,
    onDismiss: () -> Unit, onSaved: () -> Unit,
    onSave: suspend (Long, String, Long, String?) -> Unit) {
    val all by remember { container.ledger.observeRecent() }.collectAsState(emptyList())
    val assets by remember { container.assets.observeActiveWithBalance() }.collectAsState(emptyList())
    val currentOrigin = all.firstOrNull { it.id == origin.id } ?: origin
    val remaining = ExpenseRecoveries.remaining(currentOrigin, all)
    var amountText by remember(origin.id, kind) { mutableStateOf(initialAmount?.let { Money.toPlainString(it) }.orEmpty()) }
    var assetId by remember(origin.id, kind) { mutableStateOf(initialAssetId ?: origin.assetId) }
    var occurredAt by remember(origin.id, kind) { mutableStateOf(initialOccurredAt ?: System.currentTimeMillis()) }
    var note by remember(origin.id, kind) { mutableStateOf("") }
    var showAsset by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val cents = FormAmount.parse(amountText)
    val selectedAsset = assets.firstOrNull { it.asset.id == assetId && it.asset.currency == origin.currency }?.asset
    MaterialTheme(colorScheme = RecoveryScheme) {
        ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
                confirmValueChange = { !busy || it != SheetValue.Hidden })) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.86f).padding(horizontal = 20.dp).imePadding()) {
                Text("支出账单 · ${kind.label}", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.height(4.dp))
                    Text(currentOrigin.merchant ?: currentOrigin.note ?: "原支出", style = MaterialTheme.typography.titleMedium)
                    Text("${TimeFmt.toDay(currentOrigin.occurredAt)} · 实付 ${Money.format(ExpenseRecoveries.paid(currentOrigin))}")
                    Text("已退/报销 ${Money.format(ExpenseRecoveries.recovered(currentOrigin, all))} · 剩余 ${Money.format(remaining)}",
                        color = MaterialTheme.colorScheme.primary)
                    Text("到账后扣减 ${TimeFmt.toDay(currentOrigin.occurredAt)} 的支出，不计入收入。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(amountText, { amountText = it; error = null }, enabled = !busy,
                        label = { Text("${kind.label}金额（元）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        isError = amountText.isNotBlank() && (cents == null || cents <= 0 || cents > remaining),
                        supportingText = { if (amountText.isNotBlank() && (cents == null || cents <= 0 || cents > remaining)) Text("请输入大于零且不超过剩余金额的数值") })
                    TextButton(enabled = !busy && remaining > 0, onClick = { amountText = Money.toPlainString(remaining) }) { Text("填入全部剩余金额") }
                    OutlinedButton(enabled = !busy, onClick = { showAsset = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("到账账户：${selectedAsset?.name ?: "请选择"}")
                    }
                    OutlinedButton(enabled = !busy, onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("到账时间：${TimeFmt.toCsv(occurredAt)}")
                    }
                    OutlinedTextField(note, { note = it }, enabled = !busy, label = { Text("备注（选填）") }, modifier = Modifier.fillMaxWidth())
                    ReceiptAttachment(imagePaths)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("返回") }
                    Button(enabled = !busy && cents != null && cents > 0 && cents <= remaining && selectedAsset != null,
                        onClick = {
                            busy = true; error = null
                            scope.launch {
                                val result = runCatching { onSave(cents!!, selectedAsset!!.id, occurredAt, note.trim().ifEmpty { null }) }
                                busy = false
                                if (result.isSuccess) onSaved() else error = result.exceptionOrNull()?.message ?: "保存失败，请重试"
                            }
                        }, modifier = Modifier.weight(1f)) { Text(if (busy) "保存中…" else "确认${kind.label}") }
                }
            }
        }
        if (showAsset) AlertDialog(onDismissRequest = { showAsset = false }, title = { Text("选择到账账户") }, text = {
            val eligible = assets.filter { it.asset.currency == origin.currency }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (eligible.isEmpty()) item { Text("没有可用的同币种账户，请先在资产管理中添加。") }
                items(eligible, key = { it.asset.id }) { item ->
                    TextButton(onClick = { assetId = item.asset.id; showAsset = false }, modifier = Modifier.fillMaxWidth()) { Text(item.asset.name) }
                }
            }
        }, confirmButton = { TextButton(onClick = { showAsset = false }) { Text("取消") } })
        if (showDate) DateTimePickerDialog(occurredAt, onDismiss = { showDate = false }, onConfirm = { occurredAt = it; showDate = false })
    }
}
