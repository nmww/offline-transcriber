package com.nightowl.transcriber.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nightowl.transcriber.data.ExportFormat
import com.nightowl.transcriber.data.Exporter
import com.nightowl.transcriber.engine.Segment

/**
 * 结果页（规格书 3.3 ③）：可编辑正文 + 字数统计 + 复制/txt/srt 三操作。
 */
@Composable
fun ResultScreen(
    title: String,
    initialText: String,
    segments: List<Segment>,
    elapsedText: String,
    savePath: String,
    format: ExportFormat,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var text by remember(initialText) { mutableStateOf(initialText) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(title, style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp)
            ) {
                androidx.compose.material3.TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    // 去掉输入框默认的下划线/底色，做成纯文稿观感
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
            }
        }

        Spacer(modifier = Modifier.size(8.dp))
        Text(
            "${text.length} 字 · 转写用时 $elapsedText",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            "已保存至：$savePath",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.size(12.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val btnShape = RoundedCornerShape(14.dp)
            val btnHeight = Modifier.height(48.dp)
            Button(
                onClick = { copyText(context, text) },
                modifier = Modifier.weight(1f).then(btnHeight),
                shape = btnShape
            ) { Text("复制") }
            Spacer(modifier = Modifier.size(12.dp))
            OutlinedButton(
                onClick = { shareText(context, Exporter.format(segments, format)) },
                modifier = Modifier.weight(1f).then(btnHeight),
                shape = btnShape
            ) { Text("导出 txt") }
            Spacer(modifier = Modifier.size(12.dp))
            OutlinedButton(
                onClick = { shareText(context, Exporter.toSrt(segments)) },
                modifier = Modifier.weight(1f).then(btnHeight),
                shape = btnShape
            ) { Text("字幕 srt") }
        }
        Spacer(modifier = Modifier.size(16.dp))
    }
}

private fun copyText(context: Context, s: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("transcript", s))
    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, s: String) {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, s) }
    context.startActivity(Intent.createChooser(i, "导出转写结果"))
}
