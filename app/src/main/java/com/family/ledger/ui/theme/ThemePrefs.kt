package com.family.ledger.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 界面明暗模式。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("日间"),
    DARK("夜间"),
}

/**
 * 主题偏好：单独一个 prefs key，不动 [com.family.ledger.data.SettingsStore]（那是 lead 的 scope）。
 * 用 Compose State 保存，切换后整棵树自动重组。
 */
object ThemePrefs {

    private const val PREFS = "family_ledger_prefs"
    private const val KEY = "ui_theme_mode"

    var mode: ThemeMode by mutableStateOf(ThemeMode.DARK)
        private set

    /** 启动时读一次。 */
    fun load(context: Context) {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        mode = runCatching { ThemeMode.valueOf(raw ?: ThemeMode.DARK.name) }.getOrDefault(ThemeMode.DARK)
    }

    fun set(context: Context, value: ThemeMode) {
        mode = value
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value.name).apply()
    }

    @Composable
    fun resolveDark(): Boolean = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

/** 供设置页复用的明暗切换控件。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeModeSelector(
    modifier: Modifier = Modifier,
    onSelect: (ThemeMode) -> Unit = {},
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val current = ThemePrefs.mode
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("外观", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, m ->
                SegmentedButton(
                    selected = current == m,
                    onClick = {
                        ThemePrefs.set(ctx, m)
                        onSelect(m)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                    modifier = Modifier.padding(end = 2.dp),
                ) { Text(m.label) }
            }
        }
    }
}
