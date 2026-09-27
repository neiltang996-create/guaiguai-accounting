package com.family.ledger.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.family.ledger.attachments.ReceiptFiles
import com.family.ledger.attachments.ReceiptReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReceiptAttachment(paths: String?, modifier: Modifier = Modifier) {
    val references = remember(paths) { ReceiptReference.fromPaths(paths) }
    if (references.isEmpty()) return
    var shown by remember(paths) { mutableStateOf(false) }
    TextButton(onClick = { shown = true }, modifier = modifier) { Text("查看账单截图（${references.size}）") }
    if (shown) ReceiptAttachmentDialog(references, onDismiss = { shown = false })
}

@Composable
fun ReceiptAttachmentDialog(references: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("账单截图", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    references.forEach { reference ->
                        val bitmap by produceState<android.graphics.Bitmap?>(null, reference) {
                            value = withContext(Dispatchers.IO) {
                                ReceiptFiles(context).file(reference)?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                            }
                        }
                        if (bitmap == null) Text("截图尚未下载，请连接网络后同步账本。")
                        else Image(bitmap!!.asImageBitmap(), "账单截图", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    }
                }
            }
        }
    }
}
