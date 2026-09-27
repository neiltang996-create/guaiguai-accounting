package com.family.ledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.family.ledger.data.repo.AssetWithBalance
import com.family.ledger.ui.theme.LedgerTheme

/**
 * 资产选择半屏弹窗 —— **严格分两段**：
 *   1. 家庭共享资产（暖色 + 共享标记，默认置顶）
 *   2. 个人资产
 * 这是本 App 的核心卖点，任何资产选择入口都必须保持这个分组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetPickerSheet(
    title: String,
    personal: List<AssetWithBalance>,
    family: List<AssetWithBalance>,
    selectedId: String?,
    onPick: (AssetWithBalance) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    excludedId: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accents = LedgerTheme.accents
    val surface = MaterialTheme.colorScheme.surface
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(4.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                assetSection(
                    title = "家庭共享资产",
                    subtitle = "夫妻两人共同使用",
                    icon = Icons.Filled.Groups,
                    accent = accents.family,
                    container = accents.familyContainer,
                    list = family,
                    selectedId = selectedId,
                    excludedId = excludedId,
                    onPick = onPick,
                )
                assetSection(
                    title = "个人资产",
                    subtitle = "仅自己可见与使用",
                    icon = Icons.Filled.Person,
                    accent = accents.personal,
                    container = surface,
                    list = personal,
                    selectedId = selectedId,
                    excludedId = excludedId,
                    onPick = onPick,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.assetSection(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    container: androidx.compose.ui.graphics.Color,
    list: List<AssetWithBalance>,
    selectedId: String?,
    excludedId: String?,
    onPick: (AssetWithBalance) -> Unit,
) {
    item(key = "head-$title") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(12.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = accent)
            Spacer(Modifier.width(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (list.isEmpty()) {
        item(key = "empty-$title") {
            Text(
                "暂无$title",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
    items(list, key = { it.asset.id }) { item ->
        val disabled = item.asset.id == excludedId
        Column {
            AssetRowItem(
                item = item,
                selected = item.asset.id == selectedId,
                showType = true,
                trailing = {
                    if (item.asset.id == selectedId) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "已选",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                onClick = { if (!disabled) onPick(item) },
            )
            if (disabled) {
                Text(
                    "（已作为另一个账户选中）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}
