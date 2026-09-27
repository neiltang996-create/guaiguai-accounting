package com.family.ledger.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.family.ledger.AppContainer
import com.family.ledger.core.FixedPeople
import kotlinx.coroutines.launch

@Composable
fun IdentityScreen(container: AppContainer, onChosen: () -> Unit) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("乖乖记账", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text("这是谁的设备？", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("选择本机身份。公开版本默认离线，同步需自行配置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(36.dp))
            FixedPeople.names.forEach { (id, name) ->
                Button(enabled = !saving, modifier = Modifier.fillMaxWidth().height(60.dp), onClick = {
                    saving = true
                    scope.launch {
                        runCatching { container.family.selectPerson(id) }
                            .onSuccess { onChosen() }.onFailure { error = it.message }
                        saving = false
                    }
                }) { Text(name, style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.height(16.dp))
            }
            if (saving) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun CurrentPersonCard(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(container.settings.myMemberId) }
    var confirmId by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("当前使用者：${FixedPeople.name(current)}", style = MaterialTheme.typography.titleMedium)
            Text("切换只影响本设备之后的记账，历史账单保持原人物归属。", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !saving, onClick = { confirmId = if (current == FixedPeople.A) FixedPeople.B else FixedPeople.A }) { Text("重新选择身份") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    confirmId?.let { target ->
        AlertDialog(onDismissRequest = { confirmId = null }, title = { Text("切换当前使用者") },
            text = { Text("确定将本设备使用者从${FixedPeople.name(current)}切换为${FixedPeople.name(target)}？") },
            confirmButton = { TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    runCatching { container.family.selectPerson(target) }.onSuccess {
                        current = target; confirmId = null
                    }.onFailure { error = it.message }
                    saving = false
                }
            }) { Text("确定切换") } }, dismissButton = { TextButton(onClick = { confirmId = null }) { Text("取消") } })
    }
}
