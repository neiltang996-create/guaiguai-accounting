package com.family.ledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.family.ledger.core.Money
import com.family.ledger.data.db.entity.AssetType
import com.family.ledger.data.db.entity.TxnType
import com.family.ledger.data.repo.AssetWithBalance
import com.family.ledger.ui.theme.AmountMedium
import com.family.ledger.ui.theme.LedgerTheme

// ---------- 基础容器 ----------

@Composable
fun LedgerCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), content = content)
    }
}

/**
 * 分组标题。左侧竖条颜色用于区分「家庭共享资产」（橙）/「个人资产」（蓝）。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/** 「共享」标记，家庭共享资产专用，全 App 视觉统一。 */
@Composable
fun FamilyBadge(text: String = "共享", modifier: Modifier = Modifier) {
    val accents = LedgerTheme.accents
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accents.familyContainer)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Groups,
            contentDescription = null,
            tint = accents.family,
            modifier = Modifier.size(11.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(text, fontSize = 10.sp, color = accents.family, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- 金额 ----------

/** 带正负号的金额文本，符号与颜色由交易类型决定。 */
@Composable
fun AmountText(
    cents: Long,
    modifier: Modifier = Modifier,
    sign: String = "",
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = AmountMedium,
) {
    Text(
        text = sign + Money.format(cents),
        modifier = modifier,
        color = color,
        style = style,
        maxLines = 1,
    )
}

fun txnSign(type: TxnType): String = when (type) {
    TxnType.EXPENSE -> "-"
    TxnType.INCOME, TxnType.REFUND -> "+"
    else -> ""
}

@Composable
fun txnColor(type: TxnType): Color {
    val accents = LedgerTheme.accents
    return when (type) {
        TxnType.EXPENSE -> accents.expense
        TxnType.INCOME, TxnType.REFUND -> accents.income
        else -> accents.transfer
    }
}

// ---------- 资产 ----------

fun assetTypeLabel(type: AssetType): String = when (type) {
    AssetType.CASH -> "现金"
    AssetType.SAVINGS -> "储蓄卡"
    AssetType.CREDIT -> "信用卡"
    AssetType.PREPAID -> "储值卡"
    AssetType.VIRTUAL -> "虚拟账户"
    AssetType.INVEST -> "投资"
    AssetType.RECEIVABLE -> "应收"
    AssetType.PAYABLE -> "应付"
    AssetType.OTHER -> "其他"
}

fun assetTypeIcon(type: AssetType): ImageVector = when (type) {
    AssetType.CASH -> Icons.Filled.Payments
    AssetType.SAVINGS -> Icons.Filled.AccountBalance
    AssetType.CREDIT -> Icons.Filled.CreditCard
    AssetType.PREPAID -> Icons.Filled.CardGiftcard
    AssetType.VIRTUAL -> Icons.Filled.Smartphone
    AssetType.INVEST -> Icons.AutoMirrored.Filled.TrendingUp
    AssetType.RECEIVABLE -> Icons.AutoMirrored.Filled.CallReceived
    AssetType.PAYABLE -> Icons.AutoMirrored.Filled.CallMade
    AssetType.OTHER -> Icons.Filled.AccountBalanceWallet
}

/**
 * 资产行：名称 + 类型 +（家庭资产的）共享标记 + 余额。
 * 家庭共享资产整行用暖色底 + 共享标记，个人资产用蓝色图标。
 */
@Composable
fun AssetRowItem(
    item: AssetWithBalance,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    showType: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val accents = LedgerTheme.accents
    val isFamily = item.isFamily
    val accent = if (isFamily) accents.family else accents.personal
    val base = if (isFamily) accents.familyContainer else MaterialTheme.colorScheme.surface
    val container = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> base
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymbolBadge(assetSymbol(item.asset), size = 34.dp)
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.asset.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isFamily) {
                    Spacer(Modifier.width(5.dp))
                    FamilyBadge()
                }
            }
            if (showType) {
                Text(
                    text = buildString {
                        append(assetTypeLabel(item.asset.type))
                        if (item.asset.assetKindHint()) append(" · 负债")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        if (trailing != null) {
            trailing()
        } else {
            AmountText(cents = item.displayBalance, style = AmountMedium)
        }
    }
}

private fun com.family.ledger.data.db.entity.AssetEntity.assetKindHint(): Boolean = type.isLiability

/** 资产组小计（展示口径，信用卡欠款显示为正）。 */
fun List<AssetWithBalance>.displayTotal(): Long =
    filter { it.asset.includeInNetWorth }.sumOf { it.displayBalance }

/** 成员头像缩写。 */
@Composable
fun MemberAvatar(name: String, modifier: Modifier = Modifier, isMe: Boolean = false) {
    val accent = if (isMe) MaterialTheme.colorScheme.primary else LedgerTheme.accents.personal
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        if (name.isBlank()) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
        } else {
            Text(name.take(1), fontSize = 11.sp, color = accent, fontWeight = FontWeight.Medium)
        }
    }
}

/** 分类名占位。 */
fun categoryDisplayName(name: String?): String = name?.takeIf { it.isNotBlank() } ?: "未分类"
