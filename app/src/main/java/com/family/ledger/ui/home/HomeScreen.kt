package com.family.ledger.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import com.family.ledger.ui.components.LedgerEntryRow
import com.family.ledger.ui.components.LedgerDayHeader
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.family.ledger.AppContainer
import com.family.ledger.core.Money
import com.family.ledger.core.TimeFmt
import com.family.ledger.data.db.entity.TxnEntity
import com.family.ledger.data.repo.AssetWithBalance
import com.family.ledger.data.repo.LedgerRepository
import com.family.ledger.ui.components.AssetRowItem
import com.family.ledger.ui.components.AmountText
import com.family.ledger.ui.components.EmptyHint
import com.family.ledger.ui.components.LedgerCard
import com.family.ledger.ui.components.SectionHeader
import com.family.ledger.ui.components.displayTotal
import com.family.ledger.ui.components.txnColor
import com.family.ledger.ui.components.txnSign
import com.family.ledger.ui.theme.AmountHuge
import com.family.ledger.ui.theme.AmountLarge
import com.family.ledger.ui.theme.LedgerTheme
import java.time.Instant

/**
 * 首页 —— 本 App 的门面。
 *
 * 三块信息自上而下：
 *   1. 本月收支 + 净资产
 *   2. **资产分组**：家庭共享资产 / 个人资产（核心卖点，必须严格分组）
 *   3. 最近账单
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onAddBill: () -> Unit,
    onOpenList: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenAssets: () -> Unit = {},
    onOpenFamily: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val familyFlow = remember { container.assets.observeFamilyAssets() }
    val personalFlow = remember { container.assets.observePersonalAssets() }
    val netWorthFlow = remember { container.assets.observeNetWorth() }
    val categoryFlow = remember { container.categories.observeAll() }

    var monthStart by rememberSaveable { mutableStateOf(TimeFmt.startOfMonth(System.currentTimeMillis())) }
    val monthEnd = remember(monthStart) { nextMonthStart(monthStart) }
    val recentFlow = remember { container.ledger.observeRecent() }
    val monthFlow = remember(monthStart) { container.ledger.observeMonthTotal(monthStart, monthEnd) }

    val familyAssets by familyFlow.collectAsState(initial = emptyList())
    val personalAssets by personalFlow.collectAsState(initial = emptyList())
    val netWorth by netWorthFlow.collectAsState(initial = 0L)
    val allRecent by recentFlow.collectAsState(initial = emptyList())
    val recent = remember(allRecent, monthStart, monthEnd) { com.family.ledger.data.repo.ExpenseRecoveries.bills(allRecent).filter { it.occurredAt >= monthStart && it.occurredAt < monthEnd } }
    val statistics = remember(allRecent) { com.family.ledger.data.repo.ExpenseRecoveries.forStatistics(allRecent) }
    var selectedTxnId by remember { mutableStateOf<String?>(null) }
    selectedTxnId?.let { id -> com.family.ledger.ui.list.BillDetailsDialog(container, id, onDismiss = { selectedTxnId = null }) }
    val categories by categoryFlow.collectAsState(initial = emptyList())
    val totals by monthFlow.collectAsState(initial = LedgerRepository.MonthTotals(0L, 0L, 0L, 0L, 0))

    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }
    val assetNames = remember(familyAssets, personalAssets) {
        (familyAssets + personalAssets).associate { it.asset.id to it.asset.name }
    }
    val accents = LedgerTheme.accents

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("乖乖记账", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = {
            HomeBottomBar(
                onOpenList = onOpenList,
                onOpenStats = onOpenStats,
                onOpenAssets = onOpenAssets,
                onOpenFamily = onOpenFamily,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddBill,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("记一笔") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "month") {
                MonthSummaryCard(totals = totals, netWorth = netWorth, monthStart = monthStart, onMonthChange = { offset ->
                    monthStart = Instant.ofEpochMilli(monthStart).atZone(TimeFmt.ZONE).plusMonths(offset).toInstant().toEpochMilli()
                })
            }

            item(key = "asset-summary") {
                LedgerCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("家庭共享" to familyAssets, "个人资产" to personalAssets).forEach { (title, assets) ->
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                AmountText(assets.displayTotal(), style = AmountLarge)
                                TextButton(onClick = onOpenAssets) { Text("${assets.size} 个账户  ›") }
                            }
                        }
                    }
                }
            }

            item(key = "recent-header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(
                        title = "本月明细",
                        subtitle = if (recent.isEmpty()) null else "共 ${recent.size} 笔",
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenList) { Text("全部") }
                }
            }

            if (recent.isEmpty()) {
                item(key = "recent-empty") {
                    LedgerCard { EmptyHint("还没有账单，点右下角「记一笔」开始") }
                }
            } else {
                recent.take(30).groupBy { TimeFmt.toDay(it.occurredAt) }.forEach { (day, bills) ->
                    item(key = "day-$day") { LedgerDayHeader(day, statistics.filter { t -> bills.any { it.id == t.id || it.id == t.relatedTxnId } }) }
                    items(bills, key = { it.id }) { txn ->
                        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                            LedgerEntryRow(txn, categoryNames[txn.subCategoryId ?: txn.categoryId], assetNames[txn.assetId], { selectedTxnId = txn.id }, parentCategory = categoryNames[txn.categoryId],
                                recovered = if (txn.type == com.family.ledger.data.db.entity.TxnType.EXPENSE) com.family.ledger.data.repo.ExpenseRecoveries.recovered(txn, allRecent) else 0)
                        }
                    }
                }
            }
        }
    }
}

private fun nextMonthStart(monthStart: Long): Long =
    Instant.ofEpochMilli(monthStart).atZone(TimeFmt.ZONE).plusMonths(1).toInstant().toEpochMilli()

/** 顶部收支卡：本月支出 / 本月收入 / 结余 + 净资产。 */
@Composable
private fun MonthSummaryCard(totals: LedgerRepository.MonthTotals, netWorth: Long, monthStart: Long, onMonthChange: (Long) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMonthChange(-1) }) { Icon(Icons.Default.ChevronLeft, "上一月", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Text(TimeFmt.toDay(monthStart).take(7).replace("-", " / "), Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { onMonthChange(1) }) { Icon(Icons.Default.ChevronRight, "下一月", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            Text("月支出", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f), fontSize = 12.sp)
            Spacer(Modifier.height(2.dp))
            AmountText(
                cents = totals.expense,
                sign = "",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = AmountHuge,
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryCell("月收入", Money.format(totals.income), Modifier.weight(1f))
                SummaryCell("月结余", Money.format(totals.net), Modifier.weight(1f))
                SummaryCell("记账笔数", "${totals.count} 笔", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.06f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "家庭净资产",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    AmountText(cents = netWorth, color = MaterialTheme.colorScheme.onPrimaryContainer, style = AmountLarge)
                }
            }
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f), fontSize = 11.sp)
        Spacer(Modifier.height(1.dp))
        Text(value, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** 首页底部导航；「我的」类入口统一收在设置里。 */
@Composable
private fun HomeBottomBar(
    onOpenList: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenAssets: () -> Unit,
    onOpenFamily: () -> Unit,
) {
    val items: List<BottomTab> = listOf(
        BottomTab("首页", Icons.Filled.Home, {}),
        BottomTab("账单", Icons.AutoMirrored.Filled.ReceiptLong, onOpenList),
        BottomTab("统计", Icons.Filled.PieChart, onOpenStats),
        BottomTab("资产", Icons.Filled.AccountBalanceWallet, onOpenAssets),
        BottomTab("家庭", Icons.Filled.Groups, onOpenFamily),
    )
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        items.forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = index == 0,
                onClick = { if (index != 0) tab.action() },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, fontSize = 11.sp) },
            )
        }
    }
}

private class BottomTab(val label: String, val icon: ImageVector, val action: () -> Unit)
