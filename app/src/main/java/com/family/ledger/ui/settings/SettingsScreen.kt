package com.family.ledger.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.family.ledger.AppContainer
import com.family.ledger.auto.AutoBillOverlay
import com.family.ledger.auto.AutoBillPermission
import com.family.ledger.core.TimeFmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 设置页：同步（零配置，默认可用）、自动记账开关与系统授权入口、钱迹 CSV 导入导出、关于。
 *
 * 同步的默认视图刻意做成「什么都不用填」：App 自动连接家里 NAS 上的同步服务
 * （在家走局域网地址、在外面走公网地址，地址由 App 自己探测），两台手机共用同一份账。
 * 用户明确反对手填服务器/账号/密码，所以 WebDAV 那些字段全部收进默认收起的「高级选项」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onDebug: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current

    var receiptOcrEnabled by remember { mutableStateOf(container.settings.receiptOcrEnabled) }
    var autoBillEnabled by remember { mutableStateOf(container.settings.autoBillEnabled) }
    var autoBillAutoCommit by remember { mutableStateOf(container.settings.autoBillAutoCommit) }
    var autoBillPopupEnabled by remember { mutableStateOf(container.settings.autoBillPopupEnabled) }

    // 自动记账三项授权的实时状态（无障碍 / 通知使用权 / 通知权限）+ 可选的浮窗权限
    var permissionStatus by remember { mutableStateOf<AutoBillPermission.Status?>(null) }
    var overlayGranted by remember { mutableStateOf(AutoBillPermission.canDrawOverlay(context)) }
    var permissionTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(permissionTick) {
        permissionStatus = runCatching { AutoBillPermission.status(context) }.getOrNull()
        overlayGranted = AutoBillPermission.canDrawOverlay(context)
    }
    // 从系统设置页返回时自动刷新授权状态
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionTick++
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    // ---------- 同步（局域网优先，零配置） ----------
    var lanSyncEnabled by remember { mutableStateOf(container.settings.lanSyncEnabled) }
    // WebDAV 是可选的高级选项，默认收起
    var webDavExpanded by remember { mutableStateOf(false) }
    var syncUrl by remember { mutableStateOf(container.settings.syncUrl) }
    var syncUser by remember { mutableStateOf(container.settings.syncUser) }
    var syncPassword by remember { mutableStateOf(container.settings.syncPassword) }
    var syncEnabled by remember { mutableStateOf(container.settings.syncEnabled) }
    var lastSyncAt by remember { mutableStateOf(container.settings.lastSyncAt) }
    var lastSyncMessage by remember { mutableStateOf(container.settings.lastSyncMessage) }
    var syncing by remember { mutableStateOf(false) }
    var webDavSyncing by remember { mutableStateOf(false) }
    var syncUi by remember { mutableStateOf(SyncUiState()) }

    // 对方手机上显示的本机名字；零配置：没设过就按昵称自动生成
    var deviceName by remember {
        mutableStateOf(
            container.settings.myDeviceName.ifBlank { autoDeviceName(container.settings.myDisplayName) }
        )
    }
    var showNameDialog by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }

    // 「刚刚同步 / N 分钟前同步」需要随时间刷新
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowTick = System.currentTimeMillis()
        }
    }
    // 零配置：首次进入就把本机名字定下来，对方手机上看到的是「XX 的手机」而不是一串设备 ID
    LaunchedEffect(Unit) {
        if (container.settings.myDeviceName.isBlank()) {
            val auto = autoDeviceName(container.settings.myDisplayName)
            container.settings.myDeviceName = auto
            deviceName = auto
        }
    }

    var csvBusy by remember { mutableStateOf(false) }
    var csvMessage by remember { mutableStateOf("") }

    var txnCount by remember { mutableStateOf(-1) }
    LaunchedEffect(Unit) {
        txnCount = runCatching { container.ledger.count() }.getOrDefault(-1)
    }

    // 同步状态（运行中/最近一次结果）——只读展示，写入仍由 SyncRepository 负责
    LaunchedEffect(Unit) {
        runCatching {
            container.sync.observeStatus().collect { status ->
                syncUi = status.toUiState()
                if (status.lastSyncAt > 0L) lastSyncAt = status.lastSyncAt
                if (status.message.isNotBlank()) lastSyncMessage = status.message
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            csvBusy = true
            val result = runCatching { container.csv.exportTo(uri) }
            csvBusy = false
            csvMessage = result.fold(
                onSuccess = { "已导出 ${it.count} 条账单到所选文件" },
                onFailure = { "导出失败：${it.message}" },
            )
            snackbarHost.showSnackbar(csvMessage)
        }
    }

    val aiCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { runCatching { com.family.ledger.data.csv.AiExport(context, container.db).export(uri, false) }
            .onSuccess { snackbarHost.showSnackbar("已导出 $it 条完整账单") }.onFailure { snackbarHost.showSnackbar("导出失败：${it.message}") } }
    }
    val aiXlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) scope.launch { runCatching { com.family.ledger.data.csv.AiExport(context, container.db).export(uri, true) }
            .onSuccess { snackbarHost.showSnackbar("已导出 $it 条完整账单") }.onFailure { snackbarHost.showSnackbar("导出失败：${it.message}") } }
    }

    var importPreview by remember { mutableStateOf<com.family.ledger.data.csv.ImportPreview?>(null) }
    var assetMapping by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    var importAssets by remember { mutableStateOf<List<com.family.ledger.data.db.entity.AssetEntity>>(emptyList()) }
    var mappingName by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            csvBusy = true
            runCatching { container.csv.preview(uri) }.onSuccess {
                importAssets = container.assets.all().filter { !it.archived }
                assetMapping = emptyMap(); importPreview = it
            }.onFailure { snackbarHost.showSnackbar("预览失败：${it.message}") }
            csvBusy = false
        }
    }
    importPreview?.let { preview ->
        AlertDialog(onDismissRequest = { if (!csvBusy) importPreview = null }, title = { Text("导入前预览") },
            text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("识别 ${preview.total} 条\n准备导入 ${preview.ready} 条\n重复 ${preview.duplicates} 条\n异常 ${preview.errors.size} 条")
                Text("资产映射", style = MaterialTheme.typography.titleMedium)
                Text("可将两个人导出文件中的同一个小荷包映射到同一家庭资产。", style = MaterialTheme.typography.bodySmall)
                preview.assetNames.forEach { name ->
                    val mapped = assetMapping[name]?.let { id -> importAssets.firstOrNull { it.id == id }?.name }
                    TextButton(onClick = { mappingName = name }) { Text("$name → ${mapped ?: "同名资产／新建"}") }
                }
                preview.errors.take(10).forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !csvBusy && preview.ready > 0, onClick = {
                csvBusy = true
                scope.launch {
                    runCatching { container.csv.importPreview(preview, assetMapping) }.onSuccess { result ->
                        importPreview = null; csvMessage = "已导入 ${result.imported} 条，重复 ${result.skipped} 条，异常 ${result.errors.size} 条"
                        snackbarHost.showSnackbar(csvMessage)
                    }.onFailure { snackbarHost.showSnackbar("导入失败：${it.message}") }
                    csvBusy = false
                }
            }) { Text("确认导入 ${preview.ready} 条") } },
            dismissButton = { TextButton(enabled = !csvBusy, onClick = { importPreview = null }) { Text("取消") } })
    }
    mappingName?.let { name ->
        AlertDialog(onDismissRequest = { mappingName = null }, title = { Text("映射「$name」") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                importAssets.forEach { asset -> TextButton(onClick = { assetMapping = assetMapping + (name to asset.id); mappingName = null }) { Text(asset.name) } }
            } }, confirmButton = { TextButton(onClick = { assetMapping = assetMapping - name; mappingName = null }) { Text("按原名导入") } })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SectionCard("AI 分析导出") {
                Text("包含付款人、消费人、记账人、资产归属和自动记账来源。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { aiCsv.launch("家庭账单_AI.csv") }) { Text("CSV") }
                    OutlinedButton(onClick = { aiXlsx.launch("家庭账单_AI.xlsx") }) { Text("XLSX") }
                }
            }
            Spacer(Modifier.height(16.dp))
            com.family.ledger.ui.onboarding.CurrentPersonCard(container)
            Spacer(Modifier.height(16.dp))
            // ---------- 同步 ----------
            // 放在最前面：第一眼就是「自动同步已开启」，而不是一堆要填的输入框
            SectionCard("同步") {
                SwitchRow(
                    title = if (lanSyncEnabled) "自动同步已开启" else "自动同步已关闭",
                    // 零输入：服务器地址不用填；另一台手机会自动加入这本账（本机已有真实流水时才会
                    // 拒绝自动改家庭，那种情况仓库层会在 message 里提示去「家庭」页手动配对）
                    subtitle = "什么都不用填：App 会自动连接家里的同步服务，另一台手机会自动加入这本账。",
                    checked = lanSyncEnabled,
                ) { checked ->
                    lanSyncEnabled = checked
                    container.settings.lanSyncEnabled = checked
                }

                Spacer(Modifier.height(10.dp))
                SyncStatusLine(syncPresentation(lanSyncEnabled, syncUi, nowTick))

                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = !syncing,
                    onClick = {
                        scope.launch {
                            syncing = true
                            // 自动模式：优先连家里的同步服务（地址自动探测，用户不用填）；
                            // 只有连不上、且填过 WebDAV 时才回落到 WebDAV
                            val result = runCatching { container.sync.syncNowAuto() }
                            syncing = false
                            result.onSuccess { r ->
                                snackbarHost.showSnackbar(
                                    when {
                                        !r.success -> r.message.ifBlank { SYNC_UNREACHABLE_PRIMARY }
                                        r.pulled + r.pushed > 0 ->
                                            "同步完成：拉取 ${r.pulled} 条 / 推送 ${r.pushed} 条"
                                        else -> "已经是最新的，没有新数据"
                                    }
                                )
                            }.onFailure {
                                snackbarHost.showSnackbar("同步异常：${it.message}")
                            }
                        }
                    },
                ) { Text(if (syncing || syncUi.running) "同步中…" else "立即同步") }

                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "对方手机上显示本机为「$deviceName」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            nameDraft = deviceName
                            showNameDialog = true
                        },
                    ) { Text("改名") }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "账本默认保存在本机。自托管同步需配置自己的 HTTPS 服务地址与口令，再打开同步开关。" +
                        "演示版本不启用网络同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()

                // 可选的高级选项：默认收起，避免用户以为同步需要自己填东西
                WebDavAdvancedSection(
                    expanded = webDavExpanded,
                    onToggle = { webDavExpanded = !webDavExpanded },
                ) {
                    Text(
                        text = "平时完全不用填。只有家里的同步服务暂时连不上、而你又想改用坚果云等 WebDAV 网盘时才需要；" +
                            "两台手机填同一份地址和账号即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = syncUrl,
                        onValueChange = {
                            syncUrl = it
                            container.settings.syncUrl = it
                        },
                        label = { Text("WebDAV 目录地址") },
                        placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = syncUser,
                        onValueChange = {
                            syncUser = it
                            container.settings.syncUser = it
                        },
                        label = { Text("账号") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = syncPassword,
                        onValueChange = {
                            syncPassword = it
                            container.settings.syncPassword = it
                        },
                        label = { Text("密码 / 应用密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        title = "启用 WebDAV 同步",
                        subtitle = "只在勾选后才会访问上面的地址（平时不用开）",
                        checked = syncEnabled,
                    ) { checked ->
                        syncEnabled = checked
                        container.settings.syncEnabled = checked
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = !webDavSyncing && !syncUi.running,
                            onClick = {
                                scope.launch {
                                    webDavSyncing = true
                                    val result = runCatching { container.sync.syncNow() }
                                    webDavSyncing = false
                                    result.onSuccess { r ->
                                        snackbarHost.showSnackbar(
                                            if (r.success) "WebDAV 同步完成：推送 ${r.pushed} 条，拉取 ${r.pulled} 条"
                                            else "WebDAV 同步失败：${r.message}"
                                        )
                                    }.onFailure {
                                        snackbarHost.showSnackbar("WebDAV 同步异常：${it.message}")
                                    }
                                }
                            },
                        ) { Text(if (webDavSyncing || syncUi.running) "同步中…" else "立即同步（WebDAV）") }
                        Spacer(Modifier.width(12.dp))
                        // 这里刻意用 WebDAV 自己的字段判断，而不是 sync.isConfigured()：
                        // 局域网模式下 isConfigured() 恒为 true，用它会让这段说明误导用户。
                        Text(
                            text = if (syncUrl.isNotBlank() && syncUser.isNotBlank()) "已填写" else "未填写（可选）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = buildString {
                            append("上次同步：")
                            append(if (lastSyncAt > 0L) TimeFmt.toCsv(lastSyncAt) else "从未同步")
                            if (lastSyncMessage.isNotBlank()) append("\n").append(lastSyncMessage)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---------- 自动记账 ----------
            SectionCard("自动记账") {
                SwitchRow(
                    title = "启用自动记账",
                    subtitle = "付款完成后从通知/无障碍读取金额与商户，生成待确认账单",
                    checked = autoBillEnabled,
                ) { checked ->
                    autoBillEnabled = checked
                    container.settings.autoBillEnabled = checked
                }
                SwitchRow(title = "账单截图与本机识别", subtitle = "支付宝、微信、云闪付及工商银行的单笔账单保存截图；文字读取受限时本机识别。截图作为附件随账本同步到家庭 NAS。",
                    checked = receiptOcrEnabled) { checked ->
                    receiptOcrEnabled = checked
                    container.settings.receiptOcrEnabled = checked
                }
                Text("识别后由你确认，确认后立即存入本机。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onDebug) { Text("自动记账调试中心") }
                SwitchRow(
                    title = "付款后自动打开记账窗口",
                    subtitle = "识别后直接打开完整记账窗口，可调整分类、资产和人物；" +
                        "通知仍然保留，可以从通知栏继续处理",
                    checked = autoBillPopupEnabled,
                ) { checked ->
                    autoBillPopupEnabled = checked
                    container.settings.autoBillPopupEnabled = checked
                    // 关掉开关时，正浮着的卡片也立刻收起（不然用户会以为开关没生效）
                    if (!checked) AutoBillOverlay.dismissAll()
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (overlayGranted) {
                            "已获得「显示在其他应用上层」权限"
                        } else {
                            "未获得，点此开启：显示在其他应用上层"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (overlayGranted) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !overlayGranted) {
                                runCatching {
                                    context.startActivity(AutoBillPermission.overlaySettingsIntent(context))
                                }.onFailure {
                                    scope.launch { snackbarHost.showSnackbar("无法打开悬浮窗权限页，请到系统设置里手动开启") }
                                }
                            },
                    )
                    if (!overlayGranted) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(AutoBillPermission.overlaySettingsIntent(context))
                                }.onFailure {
                                    scope.launch { snackbarHost.showSnackbar("无法打开悬浮窗权限页，请到系统设置里手动开启") }
                                }
                            }
                        ) { Text("去开启") }
                    }
                }
                if (!overlayGranted && autoBillPopupEnabled) {
                    Text(
                        text = "没有这个权限时，付款后只会发通知，不会自动打开记账窗口 —— 记账本身不受影响，" +
                            "点通知可打开完整记账窗口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val status = permissionStatus
                PermissionLine("无障碍", status?.accessibilityEnabled)
                PermissionLine("通知使用权", status?.notificationAccessEnabled)
                PermissionLine("通知权限", status?.notificationsGranted)
                PermissionLine("电池优化豁免", status?.batteryExempt)
                Text("小米手机还需将省电策略设为「无限制」，并允许「后台弹出界面」。否则服务可能显示已开启，实际却被暂停。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(AutoBillPermission.backgroundSettingsIntent(context)) }
                        .onFailure { context.startActivity(AutoBillPermission.batteryOptimizationSettingsIntent()) }
                }) { Text("后台运行设置") }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        status == null -> "正在检查授权状态…"
                        !status.batteryExempt -> "后台运行仍受电池优化限制，请先完成上面的后台设置。"
                        status.allReady && overlayGranted && autoBillPopupEnabled ->
                            "已就绪：识别后直接打开完整记账窗口，已入账的同一笔仅显示提示。"
                        status.allReady -> "三项授权都已就绪，付款后会自动生成待确认账单。"
                        status.anyChannelReady -> "已有一条识别通道可用；补齐另一条会更稳。"
                        else -> "还没有可用的识别通道，自动记账不会生效。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val opened = runCatching { AutoBillPermission.openFirstMissingSettings(context) }
                                .getOrDefault(false)
                            if (!opened) {
                                scope.launch { snackbarHost.showSnackbar("三项授权都已就绪") }
                            }
                        }
                    ) { Text(if (status?.allReady == true) "授权已就绪" else "一键去授权") }
                    OutlinedButton(onClick = { permissionTick++ }) { Text("刷新状态") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (!openSystemSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS)) {
                                scope.launch { snackbarHost.showSnackbar("无法打开无障碍设置，请到系统设置里手动开启") }
                            }
                        }
                    ) { Text("无障碍授权") }
                    OutlinedButton(
                        onClick = {
                            if (!openSystemSettings(context, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) {
                                scope.launch { snackbarHost.showSnackbar("无法打开通知使用权设置，请手动开启") }
                            }
                        }
                    ) { Text("通知使用权") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "两个权限都是可选的：通知使用权更省电、更稳；无障碍能读到更完整的支付结果页。不开也能手动记账。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            // ---------- 数据 ----------
            SectionCard("数据（钱迹 CSV）") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !csvBusy,
                        onClick = {
                            exportLauncher.launch("乖乖记账_${TimeFmt.toDay(System.currentTimeMillis())}.csv")
                        },
                    ) { Text("导出 CSV") }
                    OutlinedButton(
                        enabled = !csvBusy,
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                    ) { Text("导入 CSV") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "列顺序与钱迹完全一致：ID,时间,分类,二级分类,类型,金额,币种,账户1,账户2,备注,已报销,手续费,优惠券,记账者,账单标记,标签,账单图片,关联账单。" +
                        "导入以钱迹 ID 去重，重复导入不会产生重复账单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (csvBusy || csvMessage.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = csvMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---------- 关于 ----------
            SectionCard("关于") {
                Text(
                    text = "乖乖记账 ${appVersion(context)}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append("本机已存 ")
                        append(if (txnCount >= 0) "$txnCount 条流水" else "流水（读取中）")
                        append("。账单、资产、家庭数据保存在本机 Room，并同步到你自己的 NAS；")
                        append("同步默认开启，App 会自动连接家里的同步服务（你自己的设备），")
                        append("不需要注册账号，也不用填服务器地址。")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "只有你额外展开「高级」并填了 WebDAV 目录时，数据才会额外同步到那里。" +
                        "资产余额永远由流水推导，任何时候都能对账、可审计。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("本机名称") },
            text = {
                Column {
                    Text(
                        text = "这是对方手机同步时看到的名字，例如「老婆的手机」。只是为了让两边分得清谁是谁，不影响记账。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text("本机名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = nameDraft.isNotBlank(),
                    onClick = {
                        val name = nameDraft.trim()
                        container.settings.myDeviceName = name
                        deviceName = name
                        showNameDialog = false
                        scope.launch { snackbarHost.showSnackbar("本机名称已改为「$name」") }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------- 同步区块组件

/** 状态行：一个彩色圆点 + 主文案 + 可选的说明行。 */
@Composable
private fun SyncStatusLine(presentation: SyncPresentation) {
    val dotColor = when (presentation.tone) {
        SyncTone.OK -> Color(0xFF2E7D32)
        SyncTone.RUNNING -> MaterialTheme.colorScheme.primary
        SyncTone.WARN -> Color(0xFFEF6C00)
        SyncTone.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("●", style = MaterialTheme.typography.bodySmall, color = dotColor)
            Spacer(Modifier.width(6.dp))
            Text(
                text = presentation.primary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        presentation.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp),
            )
        }
    }
}

/**
 * 「高级：改用 WebDAV 网盘同步（可选）」——默认收起。
 * 这是为了回应用户的原话「同步联网还需要我自己输入吗」：WebDAV 不该出现在首屏。
 */
@Composable
private fun WebDavAdvancedSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "高级：改用 WebDAV 网盘同步（可选）",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (expanded) "收起" else "默认不用填，家里的同步服务连不上时才考虑，点这里展开",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (expanded) "▴" else "▾",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (expanded) {
        Spacer(Modifier.height(4.dp))
        content()
    }
}

// ---------------------------------------------------------------- 通用组件

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 授权状态一行：绿=已开启，红=未开启。 */
@Composable
private fun PermissionLine(label: String, granted: Boolean?) {
    val (text, color) = when (granted) {
        true -> "已开启" to Color(0xFF2E7D32)
        false -> "未开启" to Color(0xFFD32F2F)
        null -> "检查中…" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** 打开系统设置页；失败返回 false（部分定制 ROM 会拦截）。 */
private fun openSystemSettings(context: Context, action: String): Boolean =
    runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

@Suppress("DEPRECATION")
private fun appVersion(context: Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0.1.0"
