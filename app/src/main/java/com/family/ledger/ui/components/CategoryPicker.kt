package com.family.ledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.family.ledger.data.db.entity.CategoryEntity
import com.family.ledger.data.repo.CategoryGroup

/** 一级、二级共用可滚动网格，较多子分类不会挤占一级网格或被键盘遮住。 */
@Composable
fun CategoryPicker(
    groups: List<CategoryGroup>,
    selectedTopId: String?,
    selectedSubId: String?,
    onPick: (topId: String?, subId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedId by remember { mutableStateOf<String?>(null) }
    val expandedGroup = groups.firstOrNull { it.top.id == expandedId && it.children.isNotEmpty() }

    Column(modifier = modifier.fillMaxWidth()) {
        if (groups.isEmpty()) {
            EmptyHint("暂无分类，可到「设置 → 分类管理」添加")
        } else {
            if (expandedGroup != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { expandedId = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("全部分类")
                    }
                    Text(expandedGroup.top.name, Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { onPick(expandedGroup.top.id, null) }) { Text("不细分") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            key(expandedGroup?.top?.id) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (expandedGroup == null) {
                        items(groups, key = { it.top.id }) { group ->
                            CategoryCell(
                                name = group.top.name,
                                selected = group.top.id == selectedTopId && selectedSubId == null,
                                highlighted = group.top.id == selectedTopId,
                                hasChildren = group.children.isNotEmpty(),
                                onClick = {
                                    expandedId = group.top.id
                                    onPick(group.top.id, null)
                                },
                            )
                        }
                    } else {
                        items(expandedGroup.children, key = { it.id }) { child ->
                            CategoryCell(
                                name = child.name,
                                parentName = expandedGroup.top.name,
                                selected = child.id == selectedSubId,
                                highlighted = child.id == selectedSubId,
                                hasChildren = false,
                                onClick = { onPick(expandedGroup.top.id, child.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryCell(
    name: String,
    parentName: String? = null,
    selected: Boolean,
    highlighted: Boolean,
    hasChildren: Boolean,
    onClick: () -> Unit,
) {
    val visual = categorySymbol(name, parentName)
    val tint = visual.tone.color()
    val bg = when {
        selected -> MaterialTheme.colorScheme.primary
        highlighted -> MaterialTheme.colorScheme.primaryContainer
        else -> tint.copy(alpha = 0.12f)
    }
    val fg = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        else -> tint
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Icon(visual.icon, null, Modifier.size(22.dp), tint = fg)
        }
        Spacer(Modifier.height(5.dp))
        Text(name + if (hasChildren) " ▾" else "", textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** 一级分类 flatMap 出全部（含二级），用于名称查找。 */
fun List<CategoryGroup>.nameMap(): Map<String, CategoryEntity> {
    val map = HashMap<String, CategoryEntity>()
    forEach { g ->
        map[g.top.id] = g.top
        g.children.forEach { map[it.id] = it }
    }
    return map
}
