package com.nightowl.transcriber.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nightowl.transcriber.data.Transcript
import com.nightowl.transcriber.pipeline.Status
import com.nightowl.transcriber.pipeline.TranscribeBus
import com.nightowl.transcriber.pipeline.TranscribePipeline

/**
 * 首页（规格书 3.3 ①）——一屏只有一件事：选择文件。
 *
 * 视觉要点：主操作为【全宽 · 66dp 高 · 18dp 圆角】大按钮，居中图标+文字；
 * 下方一条浅灰说明；再往下是「进行中任务」卡（仅转写时显示）与「最近转写」列表或空态。
 */
@Composable
fun HomeScreen(
    recent: List<Transcript>,
    onPickFile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenResult: (Transcript) -> Unit,
    onOpenTask: () -> Unit,
    onCancelTask: () -> Unit,
    onDelete: (Transcript) -> Unit,
    onDeleteAll: () -> Unit
) {
    val taskStatus by TranscribeBus.status.collectAsState()
    // 删除确认对话框状态
    var pendingDelete by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Transcript?>(null) }
    var showClearAll by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        // ── 标题栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 26.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "离线转写",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // ── 主操作：全宽大按钮 ──
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp)
                .clip(RoundedCornerShape(Tokens.Button.dp))
                .clickable { onPickFile() }
                .then(
                    // 轻微投影，让主按钮从背景中"浮"起来
                    Modifier
                ),
            shape = RoundedCornerShape(Tokens.Button.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    "选择文件",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.size(12.dp))
        Text(
            "支持视频 / 音频 · 全程离线不上传",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.size(34.dp))

        // ── 进行中任务卡：转写中在首页可见，可点进去看实时进度，也可直接取消 ──
        if (taskStatus is Status.Preparing || taskStatus is Status.Running) {
            ActiveTaskCard(
                name = TranscribeBus.fileName.collectAsState().value,
                progress = TranscribeBus.progress.collectAsState().value,
                progressText = TranscribeBus.progressText.collectAsState().value,
                preparing = taskStatus is Status.Preparing,
                onOpen = onOpenTask,
                onCancel = onCancelTask
            )
            Spacer(modifier = Modifier.size(22.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "最近转写",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.weight(1f)
            )
            if (recent.isNotEmpty()) {
                androidx.compose.material3.TextButton(onClick = { showClearAll = true }) {
                    Text("清空", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                }
            }
        }
        Spacer(modifier = Modifier.size(10.dp))

        if (recent.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn {
                items(recent, key = { it.id }) { item ->
                    HistoryRow(item, onOpenResult, onLongPress = { pendingDelete = item })
                    Spacer(modifier = Modifier.size(12.dp))
                }
            }
        }
    }

    // ── 删除确认 ──
    pendingDelete?.let { item ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条转写记录？") },
            text = { Text("「${item.fileName}」的记录将从列表移除（已导出的文件不受影响）") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onDelete(item); pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
    if (showClearAll) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("清空全部转写记录？") },
            text = { Text("共 ${recent.size} 条记录将被移除，此操作不可恢复（已导出的文件不受影响）") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onDeleteAll(); showClearAll = false
                }) { Text("全部删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showClearAll = false }) { Text("取消") }
            }
        )
    }
}

/** 进行中任务卡：文件名 + 手绘细进度条（免版本兼容）+ 查看/取消 */
@Composable
private fun ActiveTaskCard(
    name: String,
    progress: Float,
    progressText: String,
    preparing: Boolean,
    onOpen: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(Tokens.Card.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (preparing) "正在准备…" else name.ifBlank { "转写中…" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        if (preparing) "加载识别模型中，请稍候" else progressText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                androidx.compose.material3.TextButton(onClick = onCancel) {
                    Text("取消", color = MaterialTheme.colorScheme.error)
                }
            }
            if (!preparing) {
                Spacer(modifier = Modifier.size(10.dp))
                // 细进度条（手绘，避免 Material3 版本 API 差异）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0.01f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

/** 空态：圆形浅色图标底 + 双行文案，避免大片空白带来的"未完成感" */
@Composable
private fun EmptyState() {    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(modifier = Modifier.size(18.dp))
        Text(
            "还没有转写记录",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            "选择一个文件即可开始",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(item: Transcript, onOpen: (Transcript) -> Unit, onLongPress: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpen(item) },
                onLongClick = onLongPress
            ),
        shape = RoundedCornerShape(Tokens.Card.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.size(5.dp))
                Text(
                    "${TranscribePipeline.fmtTime(item.durationMs)} · ${item.charCount} 字 · ${relTime(item.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun relTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    val day = 24 * 3600 * 1000
    return when {
        diff < 3600_000 -> "刚刚"
        diff < day -> "${diff / 3600_000} 小时前"
        diff < 7 * day -> "${diff / day} 天前"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date(ts))
    }
}

/** 形状 Token 引用（避免各处散写 dp 数值） */
internal object Tokens {
    const val Card = com.nightowl.transcriber.ui.theme.Tokens.RadiusCard
    const val Button = com.nightowl.transcriber.ui.theme.Tokens.RadiusButton
}
