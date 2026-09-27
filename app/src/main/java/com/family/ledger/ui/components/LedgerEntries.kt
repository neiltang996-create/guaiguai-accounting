package com.family.ledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.family.ledger.core.FixedPeople
import com.family.ledger.core.Money
import com.family.ledger.data.db.entity.TxnEntity
import com.family.ledger.data.db.entity.TxnType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun LedgerEntryRow(txn: TxnEntity, category: String?, asset: String?, onClick: (() -> Unit)? = null, parentCategory: String? = null, recovered: Long = 0) {
    val color = txnColor(txn.type)
    val amount = when (txn.type) {
        TxnType.EXPENSE -> txn.amount + txn.fee - txn.coupon
        TxnType.INCOME -> txn.amount - txn.fee
        else -> txn.amount
    }
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        SymbolBadge(categorySymbol(category ?: txn.type.cn, parentCategory))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(txn.merchant?.takeIf(String::isNotBlank) ?: category ?: txn.type.cn,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(listOfNotNull(category, asset, txn.payerMemberId?.let { FixedPeople.name(it) },
                "不计收支".takeIf { txn.excludeFromStats }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(txnSign(txn.type) + Money.format(amount - recovered), color = color,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (recovered > 0) Text("已退/报销 ${Money.toPlainString(recovered)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (txn.type == TxnType.REFUND) Text(com.family.ledger.data.repo.ExpenseRecoveries.kind(txn).label + " · 冲减支出",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LedgerDayHeader(day: String, list: List<TxnEntity>) {
    val totals = com.family.ledger.data.repo.LedgerRepository.MonthTotals.from(list)
    val expense = totals.expense
    val income = totals.income
    val label = runCatching { LocalDate.parse(day).format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)) }.getOrDefault(day)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("支 ${Money.toPlainString(expense)}  收 ${Money.toPlainString(income)}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
