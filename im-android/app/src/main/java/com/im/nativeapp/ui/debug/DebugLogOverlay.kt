package com.im.nativeapp.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.im.nativeapp.core.log.AppLog

/**
 * 右上角调试日志浮层：随「我的 → 开发调试」开关显隐。
 * 只显示最后 10 行，避免遮挡内容；点「结束」后弹窗询问是否导出到 Downloads。
 */
@Composable
fun DebugLogOverlay() {
    val running by AppLog.running.collectAsStateWithLifecycle()
    val lines by AppLog.lines.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var askExport by remember { mutableStateOf(false) }
    var savedPath by remember { mutableStateOf("") }
    var exported by remember { mutableStateOf(false) }
    var wasRunning by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    // 结束（无论从浮层还是设置页触发）后统一询问是否导出
    LaunchedEffect(running) {
        if (running) {
            wasRunning = true
        } else if (wasRunning) {
            wasRunning = false
            savedPath = ""
            exported = false
            askExport = true
        }
    }

    if (running) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 44.dp, end = 8.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    // 半透明背景：能看到并点到下面的按钮，文字仍然可读
                    .background(Color(0x4D000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "调试日志 ${lines.size} 行",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "结束",
                        color = Color(0xFF6EE787),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { AppLog.stop() }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = lines.takeLast(10).joinToString("\n").ifEmpty { "等待日志…" },
                    color = Color(0xFFE8E8E8),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    if (askExport) {
        AlertDialog(
            onDismissRequest = { askExport = false },
            title = { Text(if (exported) "日志已导出" else "调试已结束") },
            text = {
                Text(
                    if (savedPath.isNotBlank()) savedPath else "是否导出本次日志到 Downloads？（同时复制到剪贴板）",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (exported) {
                        askExport = false
                        return@TextButton
                    }
                    runCatching { AppLog.export(ctx) }
                        .onSuccess { path ->
                            runCatching { clipboard.setText(AnnotatedString(AppLog.dump())) }
                            savedPath = "已导出：$path\n已复制到剪贴板，可直接粘贴发送"
                            exported = true
                        }
                        .onFailure { savedPath = "导出失败：${it.message ?: "未知错误"}" }
                }) { Text(if (exported) "好的" else "导出") }
            },
            dismissButton = if (exported) null else {
                { TextButton(onClick = { askExport = false }) { Text("不导出") } }
            }
        )
    }
}
