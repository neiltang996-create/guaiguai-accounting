package com.family.ledger.ui.components

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.DialogInterface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.family.ledger.core.TimeFmt
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 日期 + 时间选择：先选日期，再选时间，最后回调 epoch millis。
 * 用系统 DatePickerDialog/TimePickerDialog，稳、轻、无额外依赖。
 */
@Composable
fun DateTimePickerDialog(
    initialMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    dateOnly: Boolean = false,
) {
    val context = LocalContext.current
    val zone = TimeFmt.ZONE
    val initial = remember(initialMillis) {
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(initialMillis), zone)
    }
    var pickedDate by remember { mutableStateOf(initial.toLocalDate()) }
    var stage by remember { mutableStateOf(0) }

    if (stage == 0) {
        DisposableEffect(Unit) {
            val dialog = DatePickerDialog(
                context,
                { _, year, month, day ->
                    pickedDate = LocalDate.of(year, month + 1, day)
                    if (dateOnly) {
                        onConfirm(pickedDate.atStartOfDay(zone).toInstant().toEpochMilli())
                    } else {
                        stage = 1
                    }
                },
                initial.year,
                initial.monthValue - 1,
                initial.dayOfMonth,
            )
            dialog.setButton(DialogInterface.BUTTON_NEGATIVE, "取消") { _, _ -> onDismiss() }
            dialog.setOnCancelListener { onDismiss() }
            dialog.show()
            onDispose { dialog.dismiss() }
        }
    } else {
        DisposableEffect(Unit) {
            val dialog = TimePickerDialog(
                context,
                { _, hour, minute ->
                    val millis = pickedDate.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
                    onConfirm(millis)
                },
                initial.hour,
                initial.minute,
                true,
            )
            dialog.setButton(DialogInterface.BUTTON_NEGATIVE, "取消") { _, _ -> onDismiss() }
            dialog.setOnCancelListener { onDismiss() }
            dialog.show()
            onDispose { dialog.dismiss() }
        }
    }
}
