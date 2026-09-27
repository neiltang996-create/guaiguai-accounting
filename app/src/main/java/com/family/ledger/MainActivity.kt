package com.family.ledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.family.ledger.demo.DemoData
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.family.ledger.ui.AppNav
import com.family.ledger.ui.theme.FamilyLedgerTheme
import com.family.ledger.ui.theme.ThemePrefs
import kotlinx.coroutines.launch

/**
 * 唯一的 Activity。所有界面走 Compose 导航（见 [AppNav]）。
 * 启动时做一次幂等的初始化（家庭 / 我 / 默认账本 / 示例分类与账户）。
 */
class MainActivity : ComponentActivity() {

    override fun onStart() {
        super.onStart()
        com.family.ledger.auto.AutoBillPresentation.isMainVisible = true
    }

    override fun onStop() {
        com.family.ledger.auto.AutoBillPresentation.isMainVisible = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as FamilyLedgerApp).container
        ThemePrefs.load(this)

        setContent {
            FamilyLedgerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    var demoReady by remember { mutableStateOf(!BuildConfig.DEMO_MODE) }
                    var demoError by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) {
                        if (BuildConfig.DEMO_MODE) runCatching { DemoData.seed(container) }
                            .onSuccess { demoReady = true }.onFailure { demoError = it.message }
                    }
                    if (!demoReady) Text(demoError ?: "正在准备虚构演示账本…")
                    else Column(if (BuildConfig.DEMO_MODE) Modifier.fillMaxSize().statusBarsPadding().consumeWindowInsets(WindowInsets.statusBars) else Modifier.fillMaxSize()) {
                        if (BuildConfig.DEMO_MODE) Text("演示模式 · 全部为虚构数据 · 同步已禁用", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
                        Box(Modifier.weight(1f)) { AppNav(container) }
                    }
                }
            }
        }
    }
}
