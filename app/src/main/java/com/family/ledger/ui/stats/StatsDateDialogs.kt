package com.family.ledger.ui.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
internal fun StatsPeriodDialog(
    year: Int, month: Int?, onDismiss: () -> Unit, onConfirm: (Int, Int?) -> Unit,
) {
    var yearText by remember { mutableStateOf(year.toString()) }
    var selectedMonth by remember { mutableIntStateOf(month ?: 1) }
    val chosenYear = yearText.toIntOrNull()?.takeIf { it in 1900..2100 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (month == null) "选择年份" else "选择月份") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = yearText, onValueChange = { yearText = it }, label = { Text("年份") },
                    supportingText = { if (chosenYear == null) Text("请输入 1900 至 2100 年") },
                    isError = chosenYear == null, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (month != null) {
                    (1..12).chunked(3).forEach { months ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            months.forEach { candidate ->
                                FilterChip(selected = selectedMonth == candidate, onClick = { selectedMonth = candidate },
                                    label = { Text("${candidate}月") }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = chosenYear != null, onClick = {
            chosenYear?.let { onConfirm(it, if (month == null) null else selectedMonth) }
        }) { Text("查看统计") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatsRangeDialog(
    initial: StatsDateRange, onDismiss: () -> Unit, onConfirm: (StatsDateRange) -> Unit,
) {
    var start by remember { mutableStateOf(initial.start) }
    var end by remember { mutableStateOf(initial.endInclusive) }
    var choosingStart by remember { mutableStateOf<Boolean?>(null) }
    val valid = !end.isBefore(start)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自选日期范围") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { choosingStart = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("开始日期  $start")
                }
                OutlinedButton(onClick = { choosingStart = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("结束日期  $end")
                }
                Text(if (valid) "包含开始和结束日期当天的全部账单。" else "结束日期不能早于开始日期。",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(StatsDateRange(start, end)) }) { Text("查看统计") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    choosingStart?.let { isStart ->
        key(isStart) {
            // Material DatePicker 的日期使用 UTC 零点；统计边界另按账本时区换算。
            val initialDate = if (isStart) start else end
            val picker = rememberDatePickerState(initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { choosingStart = null },
                confirmButton = { TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                    picker.selectedDateMillis?.let { millis ->
                        val selected = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        if (isStart) start = selected else end = selected
                    }
                    choosingStart = null
                }) { Text("确定") } },
                dismissButton = { TextButton(onClick = { choosingStart = null }) { Text("取消") } },
            ) {
                DatePicker(state = picker, title = { Text(if (isStart) "开始日期" else "结束日期", Modifier.padding(24.dp)) })
            }
        }
    }
}
