package com.family.ledger.ui.family

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.family.ledger.AppContainer
import com.family.ledger.core.FixedPeople
import com.family.ledger.ui.onboarding.CurrentPersonCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyScreen(container: AppContainer, onBack: () -> Unit) {
    val devices by container.family.observeDevices().collectAsState(initial = emptyList())
    val books by container.db.bookDao().observeAll().collectAsState(initial = emptyList())
    Scaffold(topBar = { TopAppBar(title = { Text("我们家") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CurrentPersonCard(container)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("用户 A · 用户 B", style = MaterialTheme.typography.titleLarge)
                    Text("两个人，同一个家。共同管理账本与资产。")
                    FixedPeople.names.forEach { (id, name) ->
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        val owned = devices.filter { it.personId == id }
                        Text(if (owned.isEmpty()) "尚未连接设备" else owned.joinToString("\n") {
                            it.name + if (it.id == container.settings.deviceId) " · 本设备" else ""
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("账本", style = MaterialTheme.typography.titleMedium)
                books.forEach { Text(it.name, Modifier.padding(vertical = 8.dp)) }
            } }
        }
    }
}
