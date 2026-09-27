package com.family.ledger.auto

import androidx.room.withTransaction
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.family.ledger.AppContainer
import com.family.ledger.R
import com.family.ledger.core.Money
import com.family.ledger.core.TimeFmt
import com.family.ledger.data.db.entity.PendingBillEntity
import com.family.ledger.data.repo.AssetWithBalance
import com.family.ledger.data.repo.CategoryGroup
import com.family.ledger.ui.components.DateTimePickerDialog
import com.family.ledger.ui.components.assetTypeLabel
import com.family.ledger.ui.components.SymbolBadge
import com.family.ledger.ui.components.categorySymbol
import com.family.ledger.ui.components.assetSymbol
import kotlinx.coroutines.launch
import kotlin.math.abs

// ---------- 面板配色：深色面板（钱迹式），主色沿用 App 品牌绿 ----------

private val PanelBg = Color(0xFF1B1F22)
private val PanelSurface = Color(0xFF272C31)
private val PanelText = Color(0xFFDEE5E1)
private val PanelMuted = Color(0xFF98A2A9)
private val PanelAccent = Color(0xFF318D79)
private val PanelExpense = Color(0xFFE29A8F)
private val PanelIncome = Color(0xFF87BFAA)
private val PanelMerchant = Color(0xFF93B6BC)
private val PanelWarn = Color(0xFFE8B45C)

private val PanelScheme = darkColorScheme(
    primary = PanelAccent,
    onPrimary = PanelText,
    surface = PanelBg,
    onSurface = PanelText,
    surfaceVariant = PanelSurface,
    onSurfaceVariant = PanelMuted,
    background = PanelBg,
    onBackground = PanelText,
    outlineVariant = Color(0xFF3A4147),
    error = PanelExpense,
)

/** 面板里可选的三个方向（钱迹的三段控件；退款仍由自动识别决定，不占分段位）。 */
private fun defaultTab(direction: Direction): Direction = when (direction) {
    Direction.INCOME -> Direction.INCOME
    Direction.TRANSFER -> Direction.TRANSFER
    Direction.REFUND -> Direction.PAYMENT
    Direction.PAYMENT -> Direction.PAYMENT
}

/**
 * 自动记账 · 快速确认**底部大面板**（钱迹式）。
 *
 * 形态：`ModalBottomSheet` 从底部滑出，占屏高约 74%，顶部 24dp 圆角，
 * 宿主 [AutoBillConfirmActivity] 是透明主题，所以**背后能直接看到支付宝付款页**。
 *
 * 内容：类型分段（支出/收入/转账）→ 商户/金额 → 分类图标网格（5 列，一级↔二级）→
 * 属性 chips（资产/账本/时间/报销/不计收支）→ 取消/保存。
 *
 * 写入路径：用户改过的方向/金额/商户先回写到待确认单（`pending_bill` 是本地暂存表，不参与同步），
 * 再统一走 [AutoBillPipeline.confirm]（与通知动作同一条路径、同一份去重与商户学习）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoBillConfirmScreen(
    pendingId: String,
    container: AppContainer,
    pipeline: AutoBillPipeline?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var model by remember { mutableStateOf<AutoBillPipeline.ConfirmUiModel?>(null) }
    var pending by remember { mutableStateOf<PendingBillEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }

    // 表单
    var payerId by remember { mutableStateOf(container.settings.myMemberId) }
    var consumerId by remember { mutableStateOf(container.settings.myMemberId) }
    var rolePicker by remember { mutableStateOf<String?>(null) }
    var selectedBookId by remember { mutableStateOf<String?>(null) }
    var showBookPicker by remember { mutableStateOf(false) }
    val books by container.db.bookDao().observeAll().collectAsState(initial = emptyList())
    var typeChoice by remember { mutableStateOf<Direction?>(null) }   // null = 沿用自动识别
    var assetId by remember { mutableStateOf<String?>(null) }
    var toAssetId by remember { mutableStateOf<String?>(null) }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var subCategoryId by remember { mutableStateOf<String?>(null) }
    var openedTopId by remember { mutableStateOf<String?>(null) }     // 分类网格当前进入的一级分类
    var occurredAt by remember { mutableStateOf<Long?>(null) }
    var amountCents by remember { mutableStateOf<Long?>(null) }
    var merchant by remember { mutableStateOf<String?>(null) }
    var merchantEdited by remember { mutableStateOf(false) }
    var reimburse by remember { mutableStateOf(false) }
    var excludeFromStats by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    // 弹层
    var pickerFor by remember { mutableStateOf<String?>(null) }        // asset / toAsset
    var showTimePicker by remember { mutableStateOf(false) }
    var showAmountEditor by remember { mutableStateOf(false) }
    var showMerchantEditor by remember { mutableStateOf(false) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingId) {
        val m = pipeline?.loadConfirmModel(pendingId)
        model = m
        pending = m?.pending
        assetId = m?.pending?.guessedAssetId
        categoryId = m?.pending?.guessedCategoryId
        subCategoryId = m?.pending?.guessedSubCategoryId
        openedTopId = null
        occurredAt = m?.pending?.occurredAt
        amountCents = m?.pending?.amount
        merchant = m?.pending?.merchant
        val learned = container.ledger.guessCategory(m?.pending?.merchant)
        consumerId = learned?.consumerId ?: container.settings.myMemberId
        selectedBookId = learned?.bookId ?: container.family.defaultBook().id
        loaded = true
    }

    val assets by container.assets.observeActiveWithBalance().collectAsState(initial = emptyList())
    val familyAssets by container.assets.observeFamilyAssets().collectAsState(initial = emptyList())
    val personalAssets by container.assets.observePersonalAssets().collectAsState(initial = emptyList())

    val detected = model?.direction
    val type = typeChoice ?: detected?.let { defaultTab(it) } ?: Direction.PAYMENT
    val isTransfer = type == Direction.TRANSFER
    val categoryKind = if (type == Direction.INCOME) "INCOME" else "EXPENSE"
    val groups by remember(categoryKind) {
        container.categories.observeGroups(categoryKind)
    }.collectAsState(initial = emptyList())

    var bookName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        bookName = runCatching { container.family.defaultBook().name }.getOrDefault("")
    }

    // 资产默认值：猜中的优先，其次家庭共享资产（这个家的默认口径）
    LaunchedEffect(assets, model?.pending?.id) {
        val m = model ?: return@LaunchedEffect
        val all = assets
        if (assetId == null || all.none { it.asset.id == assetId }) {
            val needsExactAccount = m.pending.sourcePackage == PayPackages.ICBC
            assetId = m.pending.guessedAssetId?.takeIf { id -> all.any { it.asset.id == id } }
                ?: if (needsExactAccount) null else (all.firstOrNull { it.isFamily }?.asset?.id ?: all.firstOrNull()?.asset?.id)
        }
    }

    // 转账：把页面的「转入账户」（如 中国工商银行(8804)）匹配成账本里的资产并默认选中
    LaunchedEffect(assets, model?.pending?.id) {
        val m = model ?: return@LaunchedEffect
        if (!m.isTransfer || toAssetId != null) return@LaunchedEffect
        val hint = m.toAssetHint
        val best = assets.maxByOrNull { AssetHintMatcher.score(it.asset.name, hint) }
            ?.takeIf { AssetHintMatcher.score(it.asset.name, hint) > 0 }
        toAssetId = best?.asset?.id
    }

    // 分类默认值：切换类型（支出↔收入）后原来的一级分类不适用，自动落到新类型的第一项
    LaunchedEffect(groups, type) {
        if (type == Direction.TRANSFER) return@LaunchedEffect
        if (categoryId == null || groups.none { it.top.id == categoryId }) {
            categoryId = groups.firstOrNull()?.top?.id
            subCategoryId = null
            openedTopId = null
        }
    }

    val fromAsset = assets.firstOrNull { it.asset.id == assetId }
    val toAsset = assets.firstOrNull { it.asset.id == toAssetId }
    val transferReady = !isTransfer || (toAssetId != null && toAssetId != assetId)

    val failedText = stringResource(R.string.auto_confirm_failed)
    val noAssetText = stringResource(R.string.auto_confirm_no_asset)
    val noTargetText = stringResource(R.string.auto_confirm_transfer_no_target)

    fun doIgnore() {
        if (working) return
        working = true
        scope.launch {
            val ignored = runCatching { pipeline?.ignore(pendingId) == true }.getOrDefault(false)
            working = false
            if (ignored) onDismiss() else errorMessage = "暂时无法忽略，请重试"

        }
    }

    fun doSave() {
        val p = pending
        val m = model
        if (working) return
        if (assetId == null) {
            errorMessage = noAssetText
            return
        }
        if (isTransfer && !transferReady) {
            errorMessage = noTargetText
            return
        }
        if (p == null || m == null) return
        working = true
        scope.launch {
            val result = runCatching {
                // 1) 用户改过的方向 / 金额 / 商户 → 先回写待确认单（pipeline 读的就是这一行）
                applyPendingEdits(
                    container = container,
                    original = p,
                    direction = typeChoice,
                    amountCents = amountCents,
                    merchant = merchant,
                    merchantEdited = merchantEdited,
                )
                // 2) 统一走 pipeline 确认：去重、猜分类学习、oplog、通知收尾都在里面
                val txnId = pipeline?.confirm(
                    pendingId,
                    AutoBillPipeline.ConfirmOverride(
                        payerId = payerId, consumerId = consumerId, bookId = selectedBookId,
                        assetId = assetId,
                        toAssetId = if (isTransfer) toAssetId else null,
                        categoryId = if (isTransfer) null else categoryId,
                        subCategoryId = if (isTransfer) null else subCategoryId,
                        occurredAt = occurredAt?.takeIf { it != p.occurredAt },
                    ),
                )
                // 3) 报销 / 不计收支：pipeline 的草稿不带这两个开关，确认后按 id 补一次（仍走 repository）
                if (txnId != null && (reimburse || excludeFromStats)) {
                    container.ledger.byId(txnId)?.let { txn ->
                        container.ledger.update(
                            txn.copy(
                                reimbursable = txn.reimbursable || reimburse,
                                excludeFromStats = txn.excludeFromStats || excludeFromStats,
                            )
                        )
                    }
                }
                txnId
            }
            working = false
            result
                .onSuccess { txnId -> if (txnId != null) onDismiss() else errorMessage = failedText }
                .onFailure { errorMessage = failedText }
        }
    }

    if (loaded && detected == Direction.REFUND && model?.resolved == false && pending != null && pipeline != null) {
        var origin by remember(pendingId) { mutableStateOf<com.family.ledger.data.db.entity.TxnEntity?>(null) }
        val p = pending!!
        if (origin == null) {
            com.family.ledger.ui.list.ExpensePickerSheet(container, "识别到退款 · 选择原支出", p.merchant,
                onDismiss = onDismiss, onSelect = { origin = it })
        } else {
            com.family.ledger.ui.list.RecoveryFormSheet(container, origin!!, com.family.ledger.data.repo.RecoveryKind.REFUND,
                initialAmount = p.amount, initialAssetId = p.guessedAssetId, initialOccurredAt = p.occurredAt,
                imagePaths = PendingBillCodec.extrasOf(p.rawText)["image"],
                onDismiss = { origin = null }, onSaved = onDismiss,
                onSave = { amount, account, time, note ->
                    pipeline.confirmRecovery(p.id, AutoBillPipeline.ConfirmOverride(assetId = account, occurredAt = time,
                        relatedTxnId = origin!!.id, recoveryKind = "REFUND", amountCents = amount, note = note))
                    Unit
                })
        }
        return
    }

    MaterialTheme(colorScheme = PanelScheme) {
        ModalBottomSheet(
            onDismissRequest = { if (!working) onDismiss() },
            sheetState = sheetState,
            containerColor = PanelBg,
            contentColor = PanelText,
            // 宿主 Activity 自带半透明遮罩，这里不再叠一层，保证背后支付宝页面看得清
            scrimColor = Color.Transparent,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(PanelMuted.copy(alpha = 0.45f))
                    )
                }
            },
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val panelHeight = (maxHeight * 0.74f).takeIf { it.value.isFinite() } ?: 620.dp
                when {
                    !loaded -> PanelMessage(
                        height = panelHeight,
                        title = stringResource(R.string.auto_confirm_loading),
                    )

                    model == null -> PanelMessage(
                        height = panelHeight,
                        title = stringResource(R.string.auto_confirm_missing),
                        action = stringResource(R.string.auto_confirm_close),
                        onAction = onDismiss,
                    )

                    model!!.resolved -> ResolvedPanel(
                        height = panelHeight,
                        pending = model!!.pending,
                        onDismiss = onDismiss,
                    )

                    else -> ConfirmPanel(
                        height = panelHeight,
                        model = model!!,
                        type = type,
                        detected = detected,
                        typeChoice = typeChoice,
                        onTypeChange = { picked ->
                            if (picked != type) {
                                typeChoice = picked
                                if (picked == Direction.TRANSFER) {
                                    // 转出/转入不能是同一个账户
                                    if (toAssetId == null || toAssetId == assetId) {
                                        toAssetId = assets.firstOrNull { it.asset.id != assetId }?.asset?.id
                                    }
                                }
                            }
                        },
                        fromAsset = fromAsset,
                        toAsset = toAsset,
                        groups = groups,
                        categoryId = categoryId,
                        subCategoryId = subCategoryId,
                        openedTopId = openedTopId,
                        onOpenTop = { topId ->
                            openedTopId = topId
                            categoryId = topId
                            subCategoryId = null
                        },
                        onPickSub = { topId, subId ->
                            categoryId = topId
                            subCategoryId = subId
                        },
                        onBackToTops = { openedTopId = null },
                        amountCents = amountCents ?: (pending?.amount ?: 0L),
                        merchant = merchant,
                        couponCents = pending?.let { PendingBillCodec.couponOf(it.rawText) } ?: 0L,
                        channelLabel = model!!.channelLabel?.takeIf { it.isNotBlank() }
                            ?: channelName(model!!.channel),
                        orderId = model!!.orderId,
                        receiptImage = pending?.let { PendingBillCodec.extrasOf(it.rawText)["image"] },
                        occurredAt = occurredAt ?: (pending?.occurredAt ?: System.currentTimeMillis()),
                        bookName = books.firstOrNull { it.id == selectedBookId }?.name ?: bookName,
                        payerName = com.family.ledger.core.FixedPeople.name(payerId),
                        consumerName = com.family.ledger.core.FixedPeople.name(consumerId),
                        onPickPayer = { rolePicker = "payer" },
                        onPickConsumer = { rolePicker = "consumer" },
                        onPickBook = { showBookPicker = true },
                        reimburse = reimburse,
                        excludeFromStats = excludeFromStats,
                        working = working,
                        transferReady = transferReady,
                        onPickAsset = { pickerFor = "asset" },
                        onPickToAsset = { pickerFor = "toAsset" },
                        onEditAmount = { showAmountEditor = true },
                        onEditMerchant = { showMerchantEditor = true },
                        onEditTime = { showTimePicker = true },
                        onToggleReimburse = { reimburse = !reimburse },
                        onToggleExclude = { excludeFromStats = !excludeFromStats },
                        onInfo = { infoMessage = it },
                        onCancel = onDismiss,
                        onIgnore = { doIgnore() },
                        onSave = { doSave() },
                    )
                }
            }
        }
    }

    rolePicker?.let { role ->
        com.family.ledger.ui.add.MemberPickerDialog(
            title = if (role == "payer") "付款人" else "消费人",
            members = com.family.ledger.core.FixedPeople.names.toList() + if (role == "consumer") listOf("FAMILY" to "家庭共同消费") else emptyList(),
            selectedId = if (role == "payer") payerId else consumerId,
            onDismiss = { rolePicker = null }, onPick = { if (role == "payer") payerId = it else consumerId = it; rolePicker = null })
    }
    if (showBookPicker) AlertDialog(onDismissRequest = { showBookPicker = false }, title = { Text("账本") },
        text = { Column { books.forEach { b -> TextButton(onClick = { selectedBookId = b.id; showBookPicker = false }) { Text(b.name) } } } },
        confirmButton = { TextButton(onClick = { showBookPicker = false }) { Text("取消") } })

    // ---------- 资产选择（家庭共享资产在前，个人资产在后） ----------
    if (pickerFor != null) {
        val isTo = pickerFor == "toAsset"
        AssetPickDialog(
            title = stringResource(
                when {
                    isTo -> R.string.auto_confirm_pick_asset_to
                    type == Direction.INCOME -> R.string.auto_confirm_pick_asset_income
                    isTransfer -> R.string.auto_confirm_pick_asset_from
                    else -> R.string.auto_confirm_pick_asset_pay
                }
            ),
            family = familyAssets,
            personal = personalAssets,
            selectedId = if (isTo) toAssetId else assetId,
            excludedId = if (isTo) assetId else toAssetId,
            onPick = { picked ->
                if (isTo) toAssetId = picked.asset.id else assetId = picked.asset.id
                pickerFor = null
            },
            onDismiss = { pickerFor = null },
        )
    }

    if (showTimePicker) {
        DateTimePickerDialog(
            initialMillis = occurredAt ?: System.currentTimeMillis(),
            onDismiss = { showTimePicker = false },
            onConfirm = {
                occurredAt = it
                showTimePicker = false
            },
        )
    }

    if (showAmountEditor) {
        AmountEditorDialog(
            initialCents = amountCents ?: 0L,
            onDismiss = { showAmountEditor = false },
            onConfirm = {
                amountCents = it
                showAmountEditor = false
            },
        )
    }

    if (showMerchantEditor) {
        MerchantEditorDialog(
            initial = merchant.orEmpty(),
            onDismiss = { showMerchantEditor = false },
            onConfirm = {
                merchant = it
                merchantEdited = true
                showMerchantEditor = false
            },
        )
    }

    infoMessage?.let { msg ->
        InfoDialog(text = msg) { infoMessage = null }
    }

    errorMessage?.let { msg ->
        InfoDialog(text = msg) { errorMessage = null }
    }
}

/**
 * 把用户在面板上改过的方向 / 金额 / 商户回写到待确认单。
 *
 * 为什么必须回写：`AutoBillPipeline.confirm` 的 `ConfirmOverride` 只覆盖
 * 资产 / 转入资产 / 分类 / 二级分类 / 时间 —— 方向、金额、商户是**直接从这一行读的**。
 *
 * 安全性：`pending_bill` 是本机暂存表（不在 `EntityCodec` 的同步表清单里、不参与 oplog），
 * 提交与忽略都只影响这一条待确认记录；方向以机器可读标记写进 rawText 末尾，
 * [PendingBillCodec.cleanRawText] 会把它剥掉，二次解析仍然幂等。
 */
private suspend fun applyPendingEdits(
    container: AppContainer,
    original: PendingBillEntity,
    direction: Direction?,
    amountCents: Long?,
    merchant: String?,
    merchantEdited: Boolean,
): PendingBillEntity = container.db.withTransaction {
    val current = container.db.pendingBillDao().byId(original.id) ?: return@withTransaction original
    if (current.status == PendingBillEntity.STATUS_CONFIRMED || current.status == PendingBillEntity.STATUS_IGNORED)
        return@withTransaction current
    val original = current
    val extras = PendingBillCodec.extrasOf(original.rawText).toMutableMap()
    var raw = original.rawText
    if (direction != null && PendingBillCodec.directionOf(original) != direction) {
        extras[PendingBillCodec.KEY_DIRECTION] = direction.name
        // 从转账改成别的类型：转入账户线索要清掉，别把脏线索留在这条单子上
        if (direction != Direction.TRANSFER) extras.remove(PendingBillCodec.KEY_TO_ACCOUNT)
        raw = PendingBillCodec.encodeRawText(PendingBillCodec.cleanRawText(raw), extras)
    }
    val edited = original.copy(
        amount = amountCents?.takeIf { it > 0L } ?: original.amount,
        merchant = if (merchantEdited) merchant?.takeIf { it.isNotBlank() } else original.merchant,
        rawText = raw,
        updatedAt = System.currentTimeMillis(),
    )
    if (edited != original) {
        container.db.pendingBillDao().upsert(edited)
    }
    edited
}

// ---------- 主面板 ----------

@Composable
private fun ConfirmPanel(
    height: Dp,
    model: AutoBillPipeline.ConfirmUiModel,
    type: Direction,
    detected: Direction?,
    typeChoice: Direction?,
    onTypeChange: (Direction) -> Unit,
    fromAsset: AssetWithBalance?,
    toAsset: AssetWithBalance?,
    groups: List<CategoryGroup>,
    categoryId: String?,
    subCategoryId: String?,
    openedTopId: String?,
    onOpenTop: (String) -> Unit,
    onPickSub: (String, String) -> Unit,
    onBackToTops: () -> Unit,
    amountCents: Long,
    merchant: String?,
    couponCents: Long,
    channelLabel: String,
    orderId: String?,
    receiptImage: String?,
    occurredAt: Long,
    bookName: String,
    payerName: String,
    consumerName: String,
    onPickPayer: () -> Unit,
    onPickConsumer: () -> Unit,
    onPickBook: () -> Unit,
    reimburse: Boolean,
    excludeFromStats: Boolean,
    working: Boolean,
    transferReady: Boolean,
    onPickAsset: () -> Unit,
    onPickToAsset: () -> Unit,
    onEditAmount: () -> Unit,
    onEditMerchant: () -> Unit,
    onEditTime: () -> Unit,
    onToggleReimburse: () -> Unit,
    onToggleExclude: () -> Unit,
    onInfo: (String) -> Unit,
    onCancel: () -> Unit,
    onIgnore: () -> Unit,
    onSave: () -> Unit,
) {
    val openedGroup = groups.firstOrNull { it.top.id == openedTopId }
    val showSubLevel = openedGroup != null && openedGroup.children.isNotEmpty()
    // 下面这些文案会被 onClick lambda 使用，必须在 composable 作用域里先取出来
    val bookHint = stringResource(R.string.auto_confirm_book_hint, bookName)
    val tagSoon = stringResource(R.string.auto_confirm_coming_soon, stringResource(R.string.auto_confirm_tag))
    val imageSoon = stringResource(R.string.auto_confirm_coming_soon, stringResource(R.string.auto_confirm_image))
    var showReceipt by remember { mutableStateOf(false) }
    if (showReceipt && receiptImage != null) com.family.ledger.ui.components.ReceiptAttachmentDialog(listOf(receiptImage)) { showReceipt = false }
    val timeText = TimeFmt.toCsv(occurredAt).take(16)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        // 1) 类型分段 + 渠道
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(PanelSurface)
                    .padding(3.dp),
            ) {
                TypeTab(
                    text = stringResource(R.string.auto_confirm_type_expense),
                    selected = type == Direction.PAYMENT,
                    onClick = { onTypeChange(Direction.PAYMENT) },
                )
                TypeTab(
                    text = stringResource(R.string.auto_confirm_type_income),
                    selected = type == Direction.INCOME,
                    onClick = { onTypeChange(Direction.INCOME) },
                )
                TypeTab(
                    text = stringResource(R.string.auto_confirm_type_transfer),
                    selected = type == Direction.TRANSFER,
                    onClick = { onTypeChange(Direction.TRANSFER) },
                )
            }
            Spacer(Modifier.width(8.dp))
            if (detected == Direction.REFUND && typeChoice == null) {
                Pill(text = stringResource(R.string.auto_confirm_refund_auto), color = PanelWarn)
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = channelLabel,
                color = PanelMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(8.dp))

        // 4) 商户 + 金额（都可点改）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onEditMerchant)
                    .padding(vertical = 4.dp),
            ) {
                val title = merchant?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.auto_confirm_merchant_placeholder)
                Text(
                    text = title,
                    color = if (merchant.isNullOrBlank()) PanelMuted else PanelMerchant,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val orderText = orderId?.let { stringResource(R.string.auto_confirm_order_id, it) }
                val sub = buildString {
                    append(TimeFmt.toCsv(occurredAt).take(16))
                    if (!orderText.isNullOrBlank()) append(" · ").append(orderText)
                }
                Text(sub, color = PanelMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (model.duplicate) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.auto_notif_duplicate),
                        color = PanelWarn,
                        fontSize = 11.sp,
                    )
                }
                if (couponCents > 0L) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.auto_confirm_coupon, Money.format(couponCents)),
                        color = PanelIncome,
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = Money.format(amountCents),
                color = if (type == Direction.INCOME) PanelIncome else PanelExpense,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onEditAmount)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }


        // 2) 面包屑
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    type == Direction.TRANSFER -> stringResource(R.string.auto_confirm_to_asset)
                    showSubLevel -> stringResource(R.string.auto_confirm_second_level)
                    else -> stringResource(R.string.auto_confirm_select_category)
                },
                color = PanelMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            if (showSubLevel) {
                TextButton(onClick = onBackToTops) {
                    Text(
                        text = stringResource(R.string.auto_confirm_back),
                        color = PanelAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // 3) 主体：分类网格 / 转账面板
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (type == Direction.TRANSFER) {
                TransferPanel(
                    fromAsset = fromAsset,
                    toAsset = toAsset,
                    onPickFrom = onPickAsset,
                    onPickTo = onPickToAsset,
                    ready = transferReady,
                )
            } else {
                CategoryGrid(
                    groups = groups,
                    openedGroup = if (showSubLevel) openedGroup else null,
                    categoryId = categoryId,
                    subCategoryId = subCategoryId,
                    onOpenTop = onOpenTop,
                    onPickSub = onPickSub,
                )
            }
        }


        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelChip(label = "付款人", value = payerName, onClick = onPickPayer)
            PanelChip(label = "消费人", value = consumerName, onClick = onPickConsumer)
        }
        Spacer(Modifier.height(6.dp))
        // 5) 属性 chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val assetChipText = when {
                type == Direction.INCOME -> stringResource(R.string.auto_confirm_asset_income)
                type == Direction.TRANSFER -> stringResource(R.string.auto_confirm_asset_from)
                else -> stringResource(R.string.auto_confirm_asset)
            }
            PanelChip(
                label = assetChipText,
                value = fromAsset?.asset?.name,
                family = fromAsset?.isFamily == true,
                onClick = onPickAsset,
            )
            if (type == Direction.TRANSFER) {
                PanelChip(
                    label = stringResource(R.string.auto_confirm_to_asset),
                    value = toAsset?.asset?.name,
                    family = toAsset?.isFamily == true,
                    warn = toAsset == null,
                    onClick = onPickToAsset,
                )
            }
            PanelChip(
                label = stringResource(R.string.auto_confirm_book),
                value = bookName,
                onClick = onPickBook,
            )
            PanelChip(
                label = stringResource(R.string.auto_confirm_time),
                value = timeText,
                onClick = onEditTime,
            )
            if (receiptImage != null) PanelChip(label = "账单截图", value = "1 张", onClick = { showReceipt = true })
            PanelChip(
                label = stringResource(R.string.auto_confirm_reimburse),
                selected = reimburse,
                onClick = onToggleReimburse,
            )
            PanelChip(
                label = stringResource(R.string.auto_confirm_exclude),
                selected = excludeFromStats,
                onClick = onToggleExclude,
            )
        }

        Spacer(Modifier.height(6.dp))

        Spacer(Modifier.height(10.dp))

        if (receiptImage != null) TextButton(onClick = { showReceipt = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("查看账单截图 · 1 张", color = PanelAccent)
        }
        // 6) 取消 / 保存
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                enabled = !working,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, PanelMuted.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PanelText),
            ) {
                Text(stringResource(R.string.auto_confirm_cancel), fontSize = 15.sp)
            }
            Button(
                onClick = onSave,
                enabled = !working && transferReady,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PanelAccent,
                    contentColor = Color.White,
                    disabledContainerColor = PanelSurface,
                    disabledContentColor = PanelMuted,
                ),
            ) {
                Text(stringResource(R.string.auto_confirm_save_short), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (!transferReady) {
            Text(
                text = stringResource(R.string.auto_confirm_transfer_no_target),
                color = PanelWarn,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                textAlign = TextAlign.Center,
            )
        }

        TextButton(
            onClick = onIgnore,
            enabled = !working,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.auto_confirm_ignore_long),
                color = PanelMuted,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun TypeTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) PanelAccent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else PanelMuted,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp)
    }
}

/** 一级分类网格；进入一级后显示其二级分类。 */
@Composable
private fun CategoryGrid(
    groups: List<CategoryGroup>,
    openedGroup: CategoryGroup?,
    categoryId: String?,
    subCategoryId: String?,
    onOpenTop: (String) -> Unit,
    onPickSub: (String, String) -> Unit,
) {
    val cells: List<CategoryCellModel> = if (openedGroup != null) {
        openedGroup.children.map { child ->
            CategoryCellModel(
                id = child.id,
                name = child.name,
                parentName = openedGroup.top.name,
                selected = child.id == subCategoryId,
                onPick = { onPickSub(openedGroup.top.id, child.id) },
            )
        }
    } else {
        groups.map { group ->
            CategoryCellModel(
                id = group.top.id,
                name = group.top.name,
                selected = group.top.id == categoryId && subCategoryId == null,
                highlight = group.top.id == categoryId,
                onPick = { onOpenTop(group.top.id) },
            )
        }
    }

    if (cells.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.auto_confirm_select_category),
                color = PanelMuted,
                fontSize = 13.sp,
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp),
        contentPadding = PaddingValues(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(cells, key = { it.id }) { cell ->
            CategoryCell(cell)
        }
    }
}

private class CategoryCellModel(
    val id: String,
    val name: String,
    val parentName: String? = null,
    val selected: Boolean,
    val highlight: Boolean = false,
    val onPick: () -> Unit,
)

/** 分类格子：语义图标与全应用一致，颜色适配深色面板。 */
@Composable
private fun CategoryCell(cell: CategoryCellModel) {
    val visual = categorySymbol(cell.name, cell.parentName)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = cell.onPick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (cell.selected) PanelAccent else visual.tone.dark.copy(alpha = 0.12f))
                .then(
                    if (cell.selected || cell.highlight) {
                        Modifier.border(
                            width = 2.dp,
                            color = if (cell.selected) PanelAccent else PanelAccent.copy(alpha = 0.6f),
                            shape = CircleShape,
                        )
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(visual.icon, contentDescription = null,
                tint = if (cell.selected) PanelBg else visual.tone.dark, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = cell.name,
            color = if (cell.selected || cell.highlight) PanelText else PanelMuted,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 转账：转出 / 转入两个账户选择（转入账户能力保持）。 */
@Composable
private fun TransferPanel(
    fromAsset: AssetWithBalance?,
    toAsset: AssetWithBalance?,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit,
    ready: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TransferRow(
            label = stringResource(R.string.auto_confirm_asset_from),
            asset = fromAsset,
            warn = fromAsset == null,
            onClick = onPickFrom,
        )
        TransferRow(
            label = stringResource(R.string.auto_confirm_to_asset),
            asset = toAsset,
            warn = !ready,
            onClick = onPickTo,
        )
        Text(
            text = stringResource(R.string.auto_confirm_transfer_tip),
            color = PanelMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TransferRow(
    label: String,
    asset: AssetWithBalance?,
    warn: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = PanelMuted, fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = asset?.asset?.name ?: stringResource(R.string.auto_confirm_select_category),
            color = if (warn) PanelWarn else PanelText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (asset?.isFamily == true) {
            Pill(text = stringResource(R.string.auto_confirm_family_group), color = PanelMerchant)
        }
        Spacer(Modifier.width(6.dp))
        Text(Money.format(asset?.displayBalance ?: 0L), color = PanelMuted, fontSize = 12.sp)
    }
}

/** 面板属性 chip：`[标签 值]`，值缺失时显示占位。 */
@Composable
private fun PanelChip(
    label: String,
    value: String? = null,
    family: Boolean = false,
    selected: Boolean = false,
    warn: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        selected -> PanelAccent.copy(alpha = 0.22f)
        else -> PanelSurface
    }
    val fg = when {
        warn -> PanelWarn
        selected -> PanelAccent
        else -> PanelText
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (value.isNullOrBlank()) label else "$label · $value",
            color = fg,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (family) {
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(PanelMerchant)
            )
        }
    }
}

// ---------- 资产选择弹窗（家庭共享资产在前） ----------

@Composable
private fun AssetPickDialog(
    title: String,
    family: List<AssetWithBalance>,
    personal: List<AssetWithBalance>,
    selectedId: String?,
    excludedId: String?,
    onPick: (AssetWithBalance) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (family.isEmpty() && personal.isEmpty()) {
                Text(stringResource(R.string.auto_confirm_no_asset), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item(key = "family-head") {
                        GroupLabel(stringResource(R.string.auto_confirm_family_group), family.size)
                    }
                    items(family, key = { "f-" + it.asset.id }) { awb ->
                        AssetPickRow(awb, awb.asset.id == selectedId, awb.asset.id == excludedId, onPick)
                    }
                    item(key = "personal-head") {
                        GroupLabel(stringResource(R.string.auto_confirm_personal_group), personal.size)
                    }
                    items(personal, key = { "p-" + it.asset.id }) { awb ->
                        AssetPickRow(awb, awb.asset.id == selectedId, awb.asset.id == excludedId, onPick)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.auto_confirm_cancel)) }
        },
    )
}

@Composable
private fun GroupLabel(text: String, count: Int) {
    Text(
        text = "$text · $count",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun AssetPickRow(
    awb: AssetWithBalance,
    selected: Boolean,
    disabled: Boolean,
    onPick: (AssetWithBalance) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !disabled) { onPick(awb) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymbolBadge(assetSymbol(awb.asset), size = 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = awb.asset.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = assetTypeLabel(awb.asset.type) + if (disabled) " · 已作为另一个账户选中" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = Money.format(awb.displayBalance),
            style = MaterialTheme.typography.bodyMedium,
            color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---------- 金额 / 商户编辑 ----------

@Composable
private fun AmountEditorDialog(
    initialCents: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(Money.toPlainString(initialCents)) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_confirm_edit_amount)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = false
                    },
                    label = { Text(stringResource(R.string.auto_confirm_amount_hint)) },
                    singleLine = true,
                    isError = error,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.auto_confirm_amount_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = Money.parseToCents(text)
                if (cents > 0L) onConfirm(cents) else error = true
            }) { Text(stringResource(R.string.auto_confirm_save_short)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.auto_confirm_cancel)) }
        },
    )
}

@Composable
private fun MerchantEditorDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_confirm_edit_merchant)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.auto_confirm_merchant_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) { Text(stringResource(R.string.auto_confirm_save_short)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.auto_confirm_cancel)) }
        },
    )
}

// ---------- 状态页 ----------

@Composable
private fun PanelMessage(
    height: Dp,
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = PanelText, fontSize = 15.sp)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = PanelAccent, contentColor = Color.White),
            ) { Text(action) }
        }
    }
}

@Composable
private fun ResolvedPanel(
    height: Dp,
    pending: PendingBillEntity,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.auto_confirm_resolved), color = PanelText, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = Money.format(pending.amount) + " · " + TimeFmt.toCsv(pending.occurredAt),
            color = PanelMuted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(containerColor = PanelAccent, contentColor = Color.White),
        ) { Text(stringResource(R.string.auto_confirm_close)) }
    }
}

@Composable
private fun InfoDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.auto_confirm_ok)) }
        },
        text = { Text(text) },
    )
}

@Composable
private fun channelName(channel: Channel): String = stringResource(
    when (channel) {
        Channel.ALIPAY -> R.string.auto_channel_alipay
        Channel.WECHAT -> R.string.auto_channel_wechat
        Channel.UNIONPAY -> R.string.auto_channel_unionpay
        Channel.UNKNOWN -> R.string.auto_channel_unknown
    }
)
