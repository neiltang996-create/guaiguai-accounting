package com.family.ledger.ui.add

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.family.ledger.AppContainer
import com.family.ledger.core.Money
import com.family.ledger.core.TimeFmt
import com.family.ledger.data.db.entity.TxnType
import com.family.ledger.data.repo.AssetWithBalance
import com.family.ledger.data.repo.TxnDraft
import com.family.ledger.ui.components.AmountInput
import com.family.ledger.ui.components.AssetPickerSheet
import com.family.ledger.ui.components.CategoryPicker
import com.family.ledger.ui.components.DateTimePickerDialog
import com.family.ledger.ui.components.FamilyBadge
import com.family.ledger.ui.components.MoneyKeypad
import com.family.ledger.ui.theme.AmountHuge
import com.family.ledger.ui.theme.LedgerTheme
import kotlinx.coroutines.launch

/** 类型顺序与钱迹一致：支出 / 收入 / 转账 / 退款 / 还款。 */
private val TYPE_ORDER = listOf(
    TxnType.EXPENSE, TxnType.INCOME, TxnType.TRANSFER, TxnType.REPAYMENT,
)

/**
 * 记账页 —— 钱迹风格：自定义数字键盘 + 分类网格 + 分组的资产选择。
 * 所有写库都走 `container.ledger.save(TxnDraft)`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBillScreen(
    container: AppContainer,
    onDone: () -> Unit,
    onCancel: () -> Unit = onDone,
) {
    val scope = rememberCoroutineScope()

    val familyFlow = remember { container.assets.observeFamilyAssets() }
    val personalFlow = remember { container.assets.observePersonalAssets() }
    val memberFlow = remember { container.family.observeMembers() }
    val tagFlow = remember { container.categories.observeTags() }
    val expenseFlow = remember { container.categories.observeGroups("EXPENSE") }
    val incomeFlow = remember { container.categories.observeGroups("INCOME") }

    val familyAssets by familyFlow.collectAsState(initial = emptyList())
    val personalAssets by personalFlow.collectAsState(initial = emptyList())
    val members by memberFlow.collectAsState(initial = emptyList())
    val tags by tagFlow.collectAsState(initial = emptyList())
    val expenseGroups by expenseFlow.collectAsState(initial = emptyList())
    val incomeGroups by incomeFlow.collectAsState(initial = emptyList())

    // ---------- 表单状态 ----------
    var typeName by rememberSaveable { mutableStateOf(TxnType.EXPENSE.name) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var assetId by rememberSaveable { mutableStateOf<String?>(null) }
    var toAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var subCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var occurredAt by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    var note by rememberSaveable { mutableStateOf("") }
    var excludeFromStats by rememberSaveable { mutableStateOf(false) }
    var reimbursable by rememberSaveable { mutableStateOf(false) }
    var recorderId by rememberSaveable { mutableStateOf<String?>(null) }
    var payerId by rememberSaveable { mutableStateOf<String?>(null) }
    var consumerId by rememberSaveable { mutableStateOf<String?>(null) }
    var tagIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var bookId by remember { mutableStateOf<String?>(null) }

    // ---------- 弹层状态 ----------
    var pickerFor by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showNoteEditor by remember { mutableStateOf(false) }
    var showTagEditor by remember { mutableStateOf(false) }
    var memberPickerFor by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val type = runCatching { TxnType.valueOf(typeName) }.getOrDefault(TxnType.EXPENSE)
    val isTransferLike = type == TxnType.TRANSFER || type == TxnType.REPAYMENT
    val needCategory = !isTransferLike
    val kind = if (type == TxnType.INCOME) "INCOME" else "EXPENSE"
    val groups = if (kind == "INCOME") incomeGroups else expenseGroups

    // ---------- 默认值（幂等，只在缺失时补） ----------
    LaunchedEffect(Unit) { bookId = container.family.defaultBook().id }

    LaunchedEffect(familyAssets, personalAssets) {
        val all = familyAssets + personalAssets
        if (assetId == null || all.none { it.asset.id == assetId }) {
            assetId = AddBillDefaults.pickDefaultAsset(familyAssets, personalAssets)
        }
        if (toAssetId == null || all.none { it.asset.id == toAssetId } || toAssetId == assetId) {
            toAssetId = AddBillDefaults.pickDefaultToAsset(familyAssets, personalAssets, assetId)
        }
    }

    LaunchedEffect(members) {
        val me = members.firstOrNull { it.isMe } ?: members.firstOrNull()
        if (recorderId == null) recorderId = me?.id
        if (payerId == null) payerId = me?.id
        if (consumerId == null) consumerId = me?.id
    }

    LaunchedEffect(groups, kind) {
        if (categoryId == null || groups.none { it.top.id == categoryId }) {
            categoryId = groups.firstOrNull()?.top?.id
            subCategoryId = null
        }
    }

    val fromItem = (familyAssets + personalAssets).firstOrNull { it.asset.id == assetId }
    val toItem = (familyAssets + personalAssets).firstOrNull { it.asset.id == toAssetId }
    val selectedTop = groups.firstOrNull { it.top.id == categoryId }?.top
    val selectedSub = selectedTop?.let { top -> groups.firstOrNull { it.top.id == top.id }?.children?.firstOrNull { it.id == subCategoryId } }
    val categoryLabel = selectedSub?.name ?: selectedTop?.name

    fun showError(msg: String) {
        errorMessage = msg
    }

    fun attemptSave() {
        if (saving) return
        val cents = Money.parseToCents(amountText)
        when {
            cents <= 0L -> showError("请输入金额")
            assetId == null -> showError(if (type == TxnType.INCOME) "请选择收款资产" else "请选择付款资产")
            isTransferLike && toAssetId == null -> showError("请选择转入资产")
            isTransferLike && toAssetId == assetId -> showError("转入与转出资产不能相同")
            else -> {
                saving = true
                scope.launch {
                    val result = runCatching {
                        val book = bookId ?: container.family.defaultBook().id
                        container.ledger.save(
                            TxnDraft(
                                type = type,
                                amountCents = cents,
                                occurredAt = occurredAt,
                                bookId = book,
                                assetId = assetId,
                                toAssetId = if (isTransferLike) toAssetId else null,
                                categoryId = if (needCategory) categoryId else null,
                                subCategoryId = if (needCategory) subCategoryId else null,
                                recorderMemberId = recorderId,
                                payerMemberId = payerId,
                                consumerMemberId = consumerId,
                                tagIds = tagIds,
                                note = note.trim().takeIf { it.isNotEmpty() },
                                reimbursable = reimbursable,
                                excludeFromStats = excludeFromStats,
                            )
                        )
                    }
                    saving = false
                    result
                        .onSuccess {
                            AddBillDefaults.lastAssetId = assetId
                            if (isTransferLike) AddBillDefaults.lastToAssetId = toAssetId
                            onDone()
                        }
                        .onFailure { showError("保存失败：${it.message ?: it.javaClass.simpleName}") }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记一笔", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(enabled = !saving, onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "取消")
                    }
                },
                actions = {
                    TextButton(onClick = { attemptSave() }, enabled = !saving) {
                        Text("保存", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TypeTabs(
                current = type,
                onSelect = { picked ->
                    typeName = picked.name
                    val newKind = if (picked == TxnType.INCOME) "INCOME" else "EXPENSE"
                    if (newKind != kind) {
                        categoryId = null
                        subCategoryId = null
                    }
                },
            )

            // 金额 + 资产/分类
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Money.format(Money.parseToCents(amountText)),
                        style = AmountHuge,
                        color = if (Money.parseToCents(amountText) > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectChip(
                        label = if (type == TxnType.INCOME) "收款资产" else if (isTransferLike) "转出资产" else "付款资产",
                        value = fromItem?.asset?.name ?: "请选择",
                        isFamily = fromItem?.isFamily == true,
                        accent = if (fromItem?.isFamily == true) LedgerTheme.accents.family else null,
                        onClick = { pickerFor = "from" },
                    )
                    if (isTransferLike) {
                        IconButton(onClick = {
                            val tmp = assetId
                            assetId = toAssetId
                            toAssetId = tmp
                        }) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = "对调转出转入")
                        }
                        SelectChip(
                            label = "转入资产",
                            value = toItem?.asset?.name ?: "请选择",
                            isFamily = toItem?.isFamily == true,
                            accent = if (toItem?.isFamily == true) LedgerTheme.accents.family else null,
                            onClick = { pickerFor = "to" },
                        )
                    } else {
                        // 分类就在下面的网格里选，这里只做当前选择的回显
                        SelectChip(
                            label = if (kind == "INCOME") "收入分类" else "支出分类",
                            value = categoryLabel ?: "请选择",
                            isFamily = false,
                            accent = null,
                            onClick = null,
                        )
                    }
                }
            }

            // 日期 / 备注 / 标签
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectChip(
                    label = "时间",
                    value = TimeFmt.toCsv(occurredAt).take(16),
                    isFamily = false,
                    accent = null,
                    onClick = { showDatePicker = true },
                )
                SelectChip(
                    label = "备注",
                    value = note.ifBlank { "无" },
                    isFamily = false,
                    accent = null,
                    onClick = { showNoteEditor = true },
                )
                SelectChip(
                    label = "标签",
                    value = if (tagIds.isEmpty()) "无" else "${tagIds.size} 个",
                    isFamily = false,
                    accent = null,
                    onClick = { showTagEditor = true },
                )
            }

            // 成员 + 开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectChip(
                    label = "记账人",
                    value = members.firstOrNull { it.id == recorderId }?.displayName ?: "我",
                    isFamily = false,
                    accent = null,
                    onClick = { memberPickerFor = "recorder" },
                )
                SelectChip(
                    label = "付款人",
                    value = members.firstOrNull { it.id == payerId }?.displayName ?: "我",
                    isFamily = false,
                    accent = null,
                    onClick = { memberPickerFor = "payer" },
                )
                SelectChip(
                    label = "消费人",
                    value = if (consumerId == com.family.ledger.core.FixedPeople.FAMILY) "家庭共同消费" else members.firstOrNull { it.id == consumerId }?.displayName ?: "我",
                    isFamily = false,
                    accent = null,
                    onClick = { memberPickerFor = "consumer" },
                )
                ToggleChip("不计收支", excludeFromStats) { excludeFromStats = !excludeFromStats }
                ToggleChip("报销", reimbursable) { reimbursable = !reimbursable }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (needCategory) {
                    CategoryPicker(
                        groups = groups,
                        selectedTopId = categoryId,
                        selectedSubId = subCategoryId,
                        onPick = { top, sub ->
                            categoryId = top
                            subCategoryId = sub
                        },
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                } else {
                    TransferHint()
                }
            }

            MoneyKeypad(
                onDigit = { d -> amountText = AmountInput.appendDigit(amountText, d) },
                onDot = { amountText = AmountInput.appendDot(amountText) },
                onBackspace = { amountText = AmountInput.backspace(amountText) },
                onDone = { attemptSave() },
                doneEnabled = !saving,
            )
        }
    }

    // ---------- 资产选择（分「个人资产 / 家庭共享资产」两段） ----------
    if (pickerFor != null) {
        val isTo = pickerFor == "to"
        AssetPickerSheet(
            title = when {
                isTo -> "选择转入资产"
                type == TxnType.INCOME -> "选择收款资产"
                isTransferLike -> "选择转出资产"
                else -> "选择付款资产"
            },
            personal = personalAssets,
            family = familyAssets,
            selectedId = if (isTo) toAssetId else assetId,
            excludedId = if (isTo) assetId else toAssetId,
            onPick = { picked ->
                if (isTo) toAssetId = picked.asset.id else assetId = picked.asset.id
                pickerFor = null
            },
            onDismiss = { pickerFor = null },
        )
    }

    if (showDatePicker) {
        DateTimePickerDialog(
            initialMillis = occurredAt,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                occurredAt = it
                showDatePicker = false
            },
        )
    }

    if (showNoteEditor) {
        NoteEditorDialog(
            initial = note,
            onDismiss = { showNoteEditor = false },
            onConfirm = {
                note = it
                showNoteEditor = false
            },
        )
    }

    if (showTagEditor) {
        TagEditorDialog(
            container = container,
            availableTags = tags,
            selected = tagIds,
            onDismiss = { showTagEditor = false },
            onConfirm = {
                tagIds = it
                showTagEditor = false
            },
        )
    }

    if (memberPickerFor != null) {
        MemberPickerDialog(
            title = when (memberPickerFor) {
                "recorder" -> "记账人"
                "payer" -> "付款人"
                else -> "消费人"
            },
            members = members.map { it.id to it.displayName } + if (memberPickerFor == "consumer") listOf(com.family.ledger.core.FixedPeople.FAMILY to "家庭共同消费") else emptyList(),
            selectedId = when (memberPickerFor) {
                "recorder" -> recorderId
                "payer" -> payerId
                else -> consumerId
            },
            onDismiss = { memberPickerFor = null },
            onPick = { id ->
                when (memberPickerFor) {
                    "recorder" -> recorderId = id
                    "payer" -> payerId = id
                    else -> consumerId = id
                }
                memberPickerFor = null
            },
        )
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("知道了") } },
            title = { Text("提示") },
            text = { Text(errorMessage.orEmpty()) },
        )
    }
}

/** 转账/还款没有分类，只提示口径。 */
@Composable
private fun TransferHint() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("转账 / 还款不计入收支统计", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "只影响两个账户的余额，两边都会立刻同步",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TypeTabs(current: TxnType, onSelect: (TxnType) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TYPE_ORDER.forEach { t ->
            val selected = t == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(t) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.cn,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 一行「标签 + 值」的可点选择项；家庭共享资产带共享标记。onClick 为 null 时只读。 */
@Composable
private fun SelectChip(
    label: String,
    value: String,
    isFamily: Boolean,
    accent: Color?,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFamily) LedgerTheme.accents.familyContainer else MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$label ",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (isFamily) {
            Spacer(Modifier.width(4.dp))
            FamilyBadge()
        }
    }
}

@Composable
private fun ToggleChip(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (checked) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (checked) "✓ $label" else label,
            fontSize = 12.sp,
            color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
