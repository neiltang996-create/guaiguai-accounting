package com.family.ledger.ui.assets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.family.ledger.AppContainer
import com.family.ledger.core.Money
import com.family.ledger.data.db.entity.AssetEntity
import com.family.ledger.data.db.entity.AssetType
import com.family.ledger.data.db.entity.OwnerType
import com.family.ledger.data.repo.AssetWithBalance
import com.family.ledger.data.repo.BalanceCalculator
import com.family.ledger.ui.theme.LedgerTheme
import com.family.ledger.ui.components.SymbolBadge
import com.family.ledger.ui.components.assetSymbol
import com.family.ledger.ui.components.assetGroupSymbol
import kotlinx.coroutines.launch


internal fun AssetType.label(): String = when (this) {
    AssetType.CASH -> "现金"
    AssetType.SAVINGS -> "储蓄卡"
    AssetType.CREDIT -> "信用卡"
    AssetType.PREPAID -> "储值卡"
    AssetType.VIRTUAL -> "虚拟账户"
    AssetType.INVEST -> "投资"
    AssetType.RECEIVABLE -> "应收（借出）"
    AssetType.PAYABLE -> "应付（借入）"
    AssetType.OTHER -> "其他"
}

internal fun OwnerType.label(): String = if (this == OwnerType.FAMILY) "家庭共享" else "个人"

/**
 * 资产管理：按账户种类分组，支持家庭共享 / 个人筛选、新建与编辑（含**归属切换**）、余额校准、归档。
 *
 * 余额永远由流水推导，这里只提供 `reconcile` 这条校准入口，不提供「直接改余额」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetManageScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    val all by remember { container.assets.observeAllWithBalance() }.collectAsState(initial = emptyList())
    val netWorth by remember { container.assets.observeNetWorth() }.collectAsState(initial = 0L)
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var ownerFilter by rememberSaveable { mutableStateOf("全部") }

    val active = all.filter { !it.asset.archived }
    val family = active.filter { it.isFamily }
    val personal = active.filter { !it.isFamily }
    val visible = active.filter { ownerFilter == "全部" || it.isFamily == (ownerFilter == "家庭共享") }
    val grouped = visible.groupBy { AssetGroups.of(it.asset) }.toList()
        .sortedWith(compareBy({ AssetGroups.order(it.first) }, { it.first }))
    val archived = all.filter { it.asset.archived && (ownerFilter == "全部" || it.isFamily == (ownerFilter == "家庭共享")) }

    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AssetEntity?>(null) }
    var actionTarget by remember { mutableStateOf<AssetWithBalance?>(null) }
    var reconciling by remember { mutableStateOf<AssetWithBalance?>(null) }
    var saving by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(creating, editing?.id, reconciling?.asset?.id) { operationError = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("资产管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建资产")
            }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item(key = "overview") {
                Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "净资产（只统计计入净值的资产）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = Money.format(netWorth),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            SubTotal("家庭共享", family, LedgerTheme.accents.family, Modifier.weight(1f))
                            SubTotal("个人", personal, LedgerTheme.accents.personal, Modifier.weight(1f))
                        }
                    }
                }
            }

            item(key = "owner-filter") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("全部" to active.size, "家庭共享" to family.size, "个人" to personal.size).forEach { (label, count) ->
                        FilterChip(selected = ownerFilter == label, onClick = { ownerFilter = label },
                            label = { Text("$label $count") })
                    }
                }
            }
            if (visible.isEmpty()) {
                item(key = "empty") { EmptyHint("还没有${if (ownerFilter == "全部") "" else ownerFilter}资产，点击右下角添加。") }
            }
            grouped.forEach { (group, accounts) ->
                item(key = "group-$group") {
                    AssetGroupCard(group, accounts, onClick = { actionTarget = it })
                }
            }

            item(key = "archived-toggle") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { showArchived = !showArchived }) {
                        Text(if (showArchived) "隐藏已归档" else "显示已归档（${archived.size}）")
                    }
                }
            }
            if (showArchived && archived.isNotEmpty()) {
                item(key = "h-archived") { SectionHeader("已归档 · ${archived.size}") }
                items(archived, key = { "arch-" + it.asset.id }) { item ->
                    AssetRow(item, dimmed = true) { actionTarget = item }
                }
            }

            item(key = "bottom-space") { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (creating || editing != null) {
        AssetEditDialog(
            asset = editing,
            busy = saving,
            error = operationError,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = save@{ form ->
                if (saving) return@save
                saving = true
                operationError = null
                val isNew = editing == null
                scope.launch {
                    val result = runCatching {
                        val base = editing
                        if (base == null) {
                            container.assets.create(
                                name = form.name,
                                type = form.type,
                                ownerType = form.ownerType,
                                familyId = container.settings.familyId,
                                openingBalanceCents = form.openingBalanceCents,
                                groupName = form.groupName,
                                iconKey = null,
                                note = form.note,
                                sharedForFamily = form.sharedForFamily,
                            )
                        } else {
                            container.assets.update(
                                base.copy(
                                    name = form.name,
                                    type = form.type,
                                    ownerType = form.ownerType,
                                    ownerUserId = if (form.ownerType == OwnerType.USER) {
                                        if (base.ownerType == OwnerType.USER) base.ownerUserId else container.settings.myMemberId
                                    } else null,
                                    ownerFamilyId = if (form.ownerType == OwnerType.FAMILY) container.settings.familyId else null,
                                    groupName = form.groupName,
                                    openingBalance = form.openingBalanceCents,
                                    includeInNetWorth = form.includeInNetWorth,
                                    sharedForFamily = form.sharedForFamily,
                                    note = form.note,
                                )
                            )
                        }
                    }
                    saving = false
                    if (result.isSuccess) {
                        creating = false
                        editing = null
                        snackbarHost.showSnackbar(if (isNew) "已新建资产" else "已保存（归属：${form.ownerType.label()}）")
                    } else {
                        operationError = "保存失败：${result.exceptionOrNull()?.message}"
                    }
                }
            },
        )
    }

    val target = actionTarget
    if (target != null) {
        val isArchived = target.asset.archived
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(target.asset.name) },
            text = {
                Column {
                    Text(
                        text = "当前余额 ${Money.format(target.displayBalance)} · ${target.asset.type.label()} · ${target.asset.ownerType.label()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    ActionRow("编辑资料 / 切换归属") {
                        actionTarget = null
                        editing = target.asset
                    }
                    ActionRow("余额校准") {
                        actionTarget = null
                        reconciling = target
                    }
                    ActionRow(if (isArchived) "恢复资产" else "归档资产") {
                        actionTarget = null
                        scope.launch {
                            val result = runCatching { container.assets.archive(target.asset.id, !isArchived) }
                            snackbarHost.showSnackbar(if (result.isSuccess) { if (isArchived) "已恢复" else "已归档" } else "操作失败：${result.exceptionOrNull()?.message}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { actionTarget = null }) { Text("关闭") } },
        )
    }

    val reco = reconciling
    if (reco != null) {
        ReconcileDialog(
            item = reco,
            busy = saving,
            error = operationError,
            onDismiss = { reconciling = null },
            onConfirm = confirm@{ realBalanceCents, note ->
                if (saving) return@confirm
                saving = true
                operationError = null
                scope.launch {
                    val result = runCatching { container.assets.reconcile(reco.asset.id, realBalanceCents, note) }
                    saving = false
                    if (result.isSuccess) {
                        reconciling = null
                        snackbarHost.showSnackbar("余额已校准")
                    } else {
                        operationError = "校准失败：${result.exceptionOrNull()?.message}"
                    }
                }
            },
        )
    }
}

@Composable
private fun AssetGroupCard(group: String, accounts: List<AssetWithBalance>, onClick: (AssetWithBalance) -> Unit) {
    var expanded by rememberSaveable(group) { mutableStateOf(true) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SymbolBadge(assetGroupSymbol(group), size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Text(group, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("${accounts.size} 个账户", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起$group" else "展开$group",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) accounts.forEach { account -> AssetRow(account) { onClick(account) } }
        if (expanded) Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun SubTotal(label: String, list: List<AssetWithBalance>, color: Color, modifier: Modifier = Modifier) {
    // 与净资产口径一致：负债类资产按原始符号（负数）计入
    val sum = list.sumOf { BalanceCalculator.netWorthContribution(it.asset, it.balance) }
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = Money.format(sum),
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun AssetRow(item: AssetWithBalance, dimmed: Boolean = false, onClick: () -> Unit) {
    val asset = item.asset
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymbolBadge(assetSymbol(asset), size = 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = asset.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    append(asset.type.label())
                    append(" · ").append(asset.ownerType.label())
                    if (!asset.includeInNetWorth) append(" · 不计入净值")
                    if (asset.archived) append(" · 已归档")
                    asset.note?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = Money.format(item.displayBalance),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (asset.type.isLiability) LedgerTheme.accents.expense else MaterialTheme.colorScheme.onSurface,
            )
            if (asset.type.isLiability) {
                Text(
                    text = "欠款",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

private data class AssetFormData(
    val name: String,
    val type: AssetType,
    val ownerType: OwnerType,
    val groupName: String?,
    val openingBalanceCents: Long,
    val includeInNetWorth: Boolean,
    val sharedForFamily: Boolean,
    val note: String?,
)

@Composable
private fun AssetEditDialog(
    asset: AssetEntity?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (AssetFormData) -> Unit,
) {
    var name by remember(asset?.id) { mutableStateOf(asset?.name.orEmpty()) }
    var type by remember(asset?.id) { mutableStateOf(asset?.type ?: AssetType.VIRTUAL) }
    var owner by remember(asset?.id) { mutableStateOf(asset?.ownerType ?: OwnerType.USER) }
    var groupName by remember(asset?.id) { mutableStateOf(AssetGroups.explicitGroup(asset?.groupName).orEmpty()) }
    var openingText by remember(asset?.id) { mutableStateOf(Money.toPlainString(asset?.openingBalance ?: 0L)) }
    var includeInNetWorth by remember(asset?.id) { mutableStateOf(asset?.includeInNetWorth ?: true) }
    var sharedForFamily by remember(asset?.id) { mutableStateOf(asset?.sharedForFamily ?: false) }
    var note by remember(asset?.id) { mutableStateOf(asset?.note.orEmpty()) }
    var typeMenuOpen by remember { mutableStateOf(false) }
    var groupMenuOpen by remember { mutableStateOf(false) }

    val nameError = name.isBlank()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (asset == null) "新建资产" else "编辑资产") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    isError = nameError,
                    supportingText = { if (nameError) Text("名称不能为空") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                Text("类型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Box {
                    OutlinedButton(onClick = { typeMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(type.label())
                    }
                    DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                        AssetType.entries.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.label()) },
                                onClick = {
                                    type = candidate
                                    typeMenuOpen = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                Text("归属", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = owner == OwnerType.USER,
                        onClick = {
                            if (owner != OwnerType.USER) {
                                owner = OwnerType.USER
                            }
                        },
                        label = { Text("个人") },
                    )
                    FilterChip(
                        selected = owner == OwnerType.FAMILY,
                        onClick = {
                            if (owner != OwnerType.FAMILY) {
                                sharedForFamily = true
                                owner = OwnerType.FAMILY
                            }
                        },
                        label = { Text("家庭共享") },
                    )
                }
                Text(
                    text = if (owner == OwnerType.FAMILY) "家庭共享资产会出现在首页「家庭共享资产」分组，两人同步后都可见。"
                    else "个人资产只属于你，不计入家庭共享分组。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                Text("资产分组", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { groupMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(groupName.ifBlank { "自动识别 · ${AssetGroups.suggested(name, type)}" })
                    }
                    DropdownMenu(expanded = groupMenuOpen, onDismissRequest = { groupMenuOpen = false }) {
                        (listOf("") + AssetGroups.names + listOfNotNull(groupName.takeIf { it.isNotBlank() && it !in AssetGroups.names })).forEach { candidate ->
                            DropdownMenuItem(text = { Text(candidate.ifBlank { "自动识别" }) }, onClick = {
                                groupName = candidate
                                groupMenuOpen = false
                            })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = openingText,
                    onValueChange = { openingText = it },
                    label = { Text("期初余额（元）") },
                    supportingText = { Text("仅作为推导起点；之后余额只由流水和校准改变") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("计入净值", modifier = Modifier.weight(1f))
                    Switch(checked = includeInNetWorth, onCheckedChange = { includeInNetWorth = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("家庭共用（记账时默认选中）", modifier = Modifier.weight(1f))
                    Switch(checked = sharedForFamily, onCheckedChange = { sharedForFamily = it })
                }
                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !nameError && com.family.ledger.ui.components.FormAmount.parse(openingText) != null,
                onClick = {
                    onSave(
                        AssetFormData(
                            name = name.trim(),
                            type = type,
                            ownerType = owner,
                            groupName = groupName.trim().ifEmpty { null },
                            openingBalanceCents = Money.parseToCents(openingText),
                            includeInNetWorth = includeInNetWorth,
                            sharedForFamily = sharedForFamily,
                            note = note.trim().ifEmpty { null },
                        )
                    )
                },
            ) { Text(if (busy) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ReconcileDialog(
    item: AssetWithBalance,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (realBalanceCents: Long, note: String?) -> Unit,
) {
    var realText by remember(item.asset.id) { mutableStateOf(Money.toPlainString(item.balance)) }
    var note by remember(item.asset.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("余额校准") },
        text = {
            Column {
                Text(
                    text = item.asset.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "当前余额：${Money.format(item.balance)}" +
                        if (item.asset.type.isLiability) "（信用卡/应付为负数表示欠款）" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = realText,
                    onValueChange = { realText = it },
                    label = { Text("真实余额（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = com.family.ledger.ui.components.FormAmount.parse(realText) == null,
                    supportingText = { if (com.family.ledger.ui.components.FormAmount.parse(realText) == null) Text("请输入有效余额，最多两位小数") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    placeholder = { Text("例如：和支付宝账单核对") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "按真实余额更新此账户，并同步到家人手机。校准不计入收支，也不出现在日常账单中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && com.family.ledger.ui.components.FormAmount.parse(realText) != null,
                onClick = { com.family.ledger.ui.components.FormAmount.parse(realText)?.let { onConfirm(it, note.trim().ifEmpty { null }) } }) {
                Text(if (busy) "校准中…" else "校准")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
