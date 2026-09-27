package com.family.ledger.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.family.ledger.AppContainer
import com.family.ledger.auto.AutoBillConfirmActivity
import com.family.ledger.core.TimeFmt
import com.family.ledger.data.db.entity.AutoBillLogEntity
import com.family.ledger.data.sync.EntityCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoDebugScreen(container: AppContainer, onBack: () -> Unit) {
    val logs by container.db.autoBillLogDao().observeRecent(200).collectAsState(initial = emptyList())
    var enabled by remember { mutableStateOf(container.settings.debugCaptureEnabled) }
    var selected by remember { mutableStateOf<AutoBillLogEntity?>(null) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val data = container.db.autoBillLogDao().recent(1000).reversed().joinToString("\n") {
                    EntityCodec.json.encodeToString(AutoBillLogEntity.serializer(), it)
                }
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(data.toByteArray()) }
            } }.onSuccess { message = "已导出调试记录" }.onFailure { message = "导出失败：${it.message}" }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("自动记账调试中心") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row { Text("记录支付页面节点与通知", Modifier.weight(1f)); Switch(enabled, { enabled = it; container.settings.debugCaptureEnabled = it }) }
                Text("仅采集已关注支付应用，保留最近 1000 条。记录可能包含商户与订单信息；保存在本机，由你选择导出。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { export.launch("乖乖记账_自动记账调试.jsonl") }) { Text("导出调试记录") }
                if (message.isNotEmpty()) Text(message)
                Text("最近事件", style = MaterialTheme.typography.titleMedium)
            }
            items(logs, key = { it.id }) { log ->
                Card(Modifier.fillMaxWidth().clickable { selected = log }) { Column(Modifier.padding(12.dp)) {
                    Text("${log.action} · ${log.matchingMode.orEmpty()}")
                    Text(TimeFmt.toCsv(log.timeMs), style = MaterialTheme.typography.bodySmall)
                    Text(log.entryJson.take(180), maxLines = 3, style = MaterialTheme.typography.bodySmall)
                } }
            }
        }
    }
    selected?.let { log -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(log.action) },
        text = { androidx.compose.foundation.text.selection.SelectionContainer { LazyColumn { item { Text(log.entryJson) } } } },
        dismissButton = { log.pendingBillId?.let { id -> TextButton(onClick = {
            selected = null
            context.startActivity(AutoBillConfirmActivity.intent(context, id))
        }) { Text("查看对应账单") } } },
        confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }) }
}
