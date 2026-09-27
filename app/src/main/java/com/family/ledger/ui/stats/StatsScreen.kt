package com.family.ledger.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.family.ledger.AppContainer
import com.family.ledger.core.Money
import com.family.ledger.ui.components.categorySymbol
import com.family.ledger.ui.components.SymbolBadge
import com.family.ledger.core.TimeFmt
import com.family.ledger.data.db.entity.TxnEntity
import com.family.ledger.data.db.entity.TxnType
import com.family.ledger.data.repo.LedgerRepository
import java.time.LocalDate
import java.time.YearMonth
import com.family.ledger.ui.theme.LedgerTheme

/** 图表配色，只用固定色板，不引第三方图表库。 */
private val ChartColors = listOf(
    Color(0xFFEF6C00), Color(0xFF00897B), Color(0xFF3949AB), Color(0xFFC2185B),
    Color(0xFF7CB342), Color(0xFF6D4C41), Color(0xFF00ACC1), Color(0xFF8E24AA),
    Color(0xFF546E7A), Color(0xFFD81B60),
)

/**
 * 统计页：可切换月份 / 年份 / 自选日期的收支合计 + 一级分类占比（环形图 + 条形）+ 按资产分布。
 * 口径与 LedgerRepository.MonthTotals 一致：排除「不计收支」、排除转账/还款/校准。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(container: AppContainer, onBack: () -> Unit) {
    val today = remember { LocalDate.now(TimeFmt.ZONE) }
    var period by rememberSaveable { mutableIntStateOf(0) }
    var analysis by rememberSaveable { mutableIntStateOf(0) }
    val flow = StatsFlow.entries[analysis]
    var monthDay by rememberSaveable { mutableLongStateOf(today.withDayOfMonth(1).toEpochDay()) }
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    var rangeStart by rememberSaveable { mutableLongStateOf(today.withDayOfMonth(1).toEpochDay()) }
    var rangeEnd by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var showPeriodDialog by remember { mutableStateOf(false) }
    var showRangeDialog by remember { mutableStateOf(false) }
    val month = YearMonth.from(LocalDate.ofEpochDay(monthDay))
    val customRange = StatsDateRange(LocalDate.ofEpochDay(rangeStart), LocalDate.ofEpochDay(rangeEnd))
    val range = when (period) {
        0 -> StatsDateRange.month(month)
        1 -> StatsDateRange.year(year)
        else -> customRange
    }
    val from = range.from
    val to = range.to
    val accents = LedgerTheme.accents

    if (showPeriodDialog) {
        StatsPeriodDialog(if (period == 0) month.year else year, if (period == 0) month.monthValue else null,
            onDismiss = { showPeriodDialog = false }, onConfirm = { chosenYear, chosenMonth ->
                if (chosenMonth == null) year = chosenYear
                else monthDay = LocalDate.of(chosenYear, chosenMonth, 1).toEpochDay()
                showPeriodDialog = false
            })
    }
    if (showRangeDialog) {
        StatsRangeDialog(customRange, onDismiss = { showRangeDialog = false }, onConfirm = {
            rangeStart = it.start.toEpochDay()
            rangeEnd = it.endInclusive.toEpochDay()
            period = 2
            showRangeDialog = false
        })
    }

    val txns by remember(from, to) { container.ledger.observeStatsBetween(from, to) }
        .collectAsState(initial = emptyList())
    val totals by remember(from, to) { container.ledger.observeMonthTotal(from, to) }
        .collectAsState(initial = LedgerRepository.MonthTotals(0L, 0L, 0L, 0L, 0))

    val assets by remember { container.assets.observeAllWithBalance() }.collectAsState(initial = emptyList())
    val categories by remember { container.categories.observeAll() }.collectAsState(initial = emptyList())
    val assetNames = remember(assets) { assets.associate { it.asset.id to it.asset.name } }
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }

    val selectedEntries = remember(txns, flow) { flow.entries(txns) }
    val byCategory = remember(selectedEntries, categoryNames, flow) {
        selectedEntries
            .groupBy { it.categoryId }
            .map { (id, list) -> (id?.let { categoryNames[it] } ?: "未分类") to list.sumOf { flow.amount(it) } }
            .filter { it.second != 0L }
            .sortedByDescending { it.second }
    }
    val byAsset = remember(selectedEntries, assetNames, flow) {
        selectedEntries
            .groupBy { it.assetId }
            .map { (id, list) -> (id?.let { assetNames[it] } ?: "未指定资产") to list.sumOf { flow.amount(it) } }
            .filter { it.second != 0L }
            .sortedByDescending { it.second }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("统计") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            TabRow(selectedTabIndex = period) {
                Tab(selected = period == 0, onClick = { period = 0 }, text = { Text("按月") })
                Tab(selected = period == 1, onClick = { period = 1 }, text = { Text("按年") })
                Tab(selected = period == 2, onClick = { showRangeDialog = true }, text = { Text("自选日期") })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (period != 2) {
                    IconButton(enabled = if (period == 0) month > YearMonth.of(1900, 1) else year > 1900,
                        onClick = { if (period == 0) monthDay = month.minusMonths(1).atDay(1).toEpochDay() else year-- }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = if (period == 0) "上个月" else "上一年")
                    }
                }
                Column(Modifier.weight(1f).clickable { if (period == 2) showRangeDialog = true else showPeriodDialog = true }
                    .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when (period) { 0 -> "${month.year}年${month.monthValue}月"; 1 -> "${year}年"; else -> "${range.start} 至 ${range.endInclusive}" },
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (period == 2) "点击修改起止日期" else if (period == 0) "点击选择月份" else "点击选择年份",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (period != 2) {
                    IconButton(enabled = if (period == 0) month < YearMonth.of(2100, 12) else year < 2100,
                        onClick = { if (period == 0) monthDay = month.plusMonths(1).atDay(1).toEpochDay() else year++ }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = if (period == 0) "下个月" else "下一年")
                    }
                } else {
                    IconButton(onClick = { showRangeDialog = true }) { Icon(Icons.Default.DateRange, contentDescription = "修改日期范围") }
                }
            }
            if ((period == 0 && month != YearMonth.from(today)) || (period == 1 && year != today.year)) {
                TextButton(onClick = { if (period == 0) monthDay = today.withDayOfMonth(1).toEpochDay() else year = today.year },
                    modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (period == 0) "回到本月" else "回到本年") }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TotalTile("支出", Money.format(totals.expense), accents.expense, Modifier.weight(1f))
                TotalTile("收入", Money.format(totals.income), accents.income, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TotalTile("退款 / 报销", Money.format(totals.refund), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                TotalTile(
                    "结余",
                    Money.format(totals.net),
                    if (totals.net >= 0) accents.income else accents.expense,
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "退款、报销扣减原支出日期的支出，不计入收入",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))

            TabRow(selectedTabIndex = analysis, modifier = Modifier.padding(horizontal = 16.dp)) {
                StatsFlow.entries.forEachIndexed { index, item ->
                    Tab(selected = analysis == index, onClick = { analysis = index }, text = { Text("${item.label}分析") })
                }
            }
            Spacer(Modifier.height(12.dp))

            if (totals.count == 0) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        text = "这段时间还没有收支记录。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (flow == StatsFlow.EXPENSE) {
                        Text("人物消费", style = MaterialTheme.typography.titleMedium)
                        (com.family.ledger.core.FixedPeople.names + ("FAMILY" to "家庭共同消费")).forEach { (id, name) ->
                            val amount = selectedEntries.filter { it.consumerMemberId == id }.sumOf { flow.amount(it) }
                            Row { Text(name, Modifier.weight(1f)); Text(Money.format(amount)) }
                        }
                    }
                    Text(if (flow == StatsFlow.INCOME) "按收入归属" else "按付款人", style = MaterialTheme.typography.titleMedium)
                    com.family.ledger.core.FixedPeople.names.forEach { (id, name) ->
                        val amount = selectedEntries.filter { (it.payerMemberId ?: it.recorderMemberId) == id }.sumOf { flow.amount(it) }
                        Row { Text(name, Modifier.weight(1f)); Text(Money.format(amount)) }
                    }
                    val unassigned = selectedEntries.filter { (it.payerMemberId ?: it.recorderMemberId) !in com.family.ledger.core.FixedPeople.names }
                    if (unassigned.isNotEmpty()) Row {
                        Text("未指定人物", Modifier.weight(1f)); Text(Money.format(unassigned.sumOf { flow.amount(it) }))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 一级分类占比：环形图 + 图例
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("${flow.label}分类占比", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    if (byCategory.any { it.second < 0 }) {
                        Text("含历史未关联退款，净支出见下方排行；关联原支出后可回溯到原月份。")
                    } else if (byCategory.isEmpty()) {
                        Text(
                            text = "没有${flow.label}记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val totalAmount = byCategory.sumOf { it.second }
                        val chartSlices = StatsFlow.chartSlices(byCategory)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(120.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                DonutChart(slices = chartSlices, modifier = Modifier.fillMaxSize())
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "合计",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = Money.format(totalAmount),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            Spacer(Modifier.size(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                chartSlices.forEachIndexed { index, (name, value) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(ChartColors[index % ChartColors.size]),
                                        )
                                        Spacer(Modifier.size(6.dp))
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = percentText(value, totalAmount),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 分类排行（横向条形）
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("分类${flow.label}排行", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    BarList(items = byCategory, total = byCategory.sumOf { it.second }, showCategoryIcons = true)
                }
            }

            Spacer(Modifier.height(12.dp))

            // 按资产分布
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(if (flow == StatsFlow.INCOME) "按收款账户看收入" else "按付款账户看支出", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    BarList(items = byAsset, total = byAsset.sumOf { it.second })
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TotalTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = color,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DonutChart(slices: List<Pair<String, Long>>, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.second }.coerceAtLeast(1L)
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.22f
        var startAngle = -90f
        slices.forEachIndexed { index, slice ->
            // 先用 Long 算千分之一度，再转 Float 交给 Canvas —— 金额比例不经过 Double
            val sweep = (slice.second * 36000L / total).toFloat() / 100f
            drawArc(
                color = ChartColors[index % ChartColors.size],
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
            )
            startAngle += sweep
        }
    }
}

@Composable
private fun BarList(items: List<Pair<String, Long>>, total: Long, showCategoryIcons: Boolean = false) {
    if (items.isEmpty()) {
        Text(
            text = "暂无数据",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val max = items.maxOf { kotlin.math.abs(it.second) }
    items.forEachIndexed { index, (name, value) ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showCategoryIcons) {
                    SymbolBadge(categorySymbol(name), size = 26.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${Money.format(value)}  ${if (items.any { it.second < 0 }) (if (value < 0) "退回" else "") else percentText(value, total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                // 条形宽度用整数千分比，避免浮点参与金额比例
                val fraction = if (max > 0L) (kotlin.math.abs(value) * 1000L / max).toFloat().coerceIn(0f, 1000f) / 1000f else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(ChartColors[index % ChartColors.size]),
                )
            }
        }
    }
}

/** 整数运算算百分比，避免 Double 参与金额口径。 */
private fun percentText(value: Long, total: Long): String {
    if (total <= 0L) return "0.0%"
    val perMille = value * 1000L / total
    return "${perMille / 10}.${perMille % 10}%"
}
