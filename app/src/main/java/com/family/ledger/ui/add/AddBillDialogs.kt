package com.family.ledger.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.family.ledger.AppContainer
import com.family.ledger.data.db.entity.TagEntity
import kotlinx.coroutines.launch

/** 备注编辑。 */
@Composable
fun NoteEditorDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("备注") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("例如：周末超市采购") },
                singleLine = false,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 标签选择 + 新建。
 * 新建走 `categories.findOrCreateTag`，保证写库与 oplog 成对发生。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditorDialog(
    container: AppContainer,
    availableTags: List<TagEntity>,
    selected: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(selected) }
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标签") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (availableTags.isEmpty()) {
                    Text(
                        "还没有标签，先在下面新建一个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        availableTags.forEach { tag ->
                            val on = picked.contains(tag.id)
                            FilterChip(
                                selected = on,
                                onClick = {
                                    picked = if (on) picked - tag.id else picked + tag.id
                                },
                                label = { Text(tag.name) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("新标签") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        enabled = newName.isNotBlank() && !creating,
                        onClick = {
                            val name = newName.trim()
                            if (name.isEmpty()) return@TextButton
                            creating = true
                            scope.launch {
                                val tag = container.categories.findOrCreateTag(name)
                                picked = (picked + tag.id).distinct()
                                newName = ""
                                creating = false
                            }
                        },
                    ) { Text("新建") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(picked) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 记账人 / 付款人 / 消费人选择。 */
@Composable
fun MemberPickerDialog(
    title: String,
    members: List<Pair<String, String>>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (members.isEmpty()) {
                    Text(
                        "还没有家庭成员，请先到「家庭」页添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    members.forEach { (id, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(id) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .width(26.dp)
                                    .height(26.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(name.take(1), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(name, modifier = Modifier.weight(1f), fontSize = 15.sp)
                            RadioButton(selected = id == selectedId, onClick = { onPick(id) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(selectedId) }) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("确定")
            }
        },
    )
}
