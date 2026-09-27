package com.family.ledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 钱迹风格的自定义数字键盘 —— 记金额时不弹系统键盘。
 * 布局：左侧 3 列数字（1-9 / . 0 ⌫），右侧「完成」通栏。
 */
@Composable
fun MoneyKeypad(
    onDigit: (String) -> Unit,
    onDot: () -> Unit,
    onBackspace: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    doneLabel: String = "完成",
    doneEnabled: Boolean = true,
) {
    val surface = MaterialTheme.colorScheme.surface
    val keyColor = MaterialTheme.colorScheme.surfaceVariant
    val doneColor = if (doneEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(
            modifier = Modifier.weight(3f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { label ->
                        KeypadKey(
                            label = label,
                            modifier = Modifier.weight(1f),
                            container = keyColor,
                            onClick = { onDigit(label) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KeypadKey(".") { onDot() }
                KeypadKey("0") { onDigit("0") }
                KeypadKey("⌫") { onBackspace() }
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(50.dp * 4 + 6.dp * 3)
                .clip(RoundedCornerShape(12.dp))
                .background(doneColor)
                .clickable(enabled = doneEnabled) { onDone() },
            contentAlignment = Alignment.Center,
        ) {
            Text(doneLabel, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KeypadKey(
    label: String,
    modifier: Modifier = Modifier.weight(1f),
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 21.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * 金额输入状态机：控制小数位、长度与前导零，保证只产出合法金额字符串。
 */
object AmountInput {

    private const val MAX_INT_DIGITS = 7

    fun appendDigit(current: String, digit: String): String {
        val dot = current.indexOf('.')
        if (dot >= 0) {
            val frac = current.length - dot - 1
            if (frac >= 2) return current
            return current + digit
        }
        if (current == "0") return digit
        if (current.filter { it.isDigit() }.length >= MAX_INT_DIGITS) return current
        return current + digit
    }

    fun appendDot(current: String): String = when {
        current.isEmpty() -> "0."
        current.contains('.') -> current
        else -> "$current."
    }

    fun backspace(current: String): String = if (current.isEmpty()) "" else current.dropLast(1)
}
