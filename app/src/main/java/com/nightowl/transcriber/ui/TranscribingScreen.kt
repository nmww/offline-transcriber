package com.nightowl.transcriber.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nightowl.transcriber.engine.Segment
import com.nightowl.transcriber.pipeline.TranscribeBus
import com.nightowl.transcriber.pipeline.TranscribePipeline
import kotlinx.coroutines.launch

/**
 * 转写页（规格书 3.3 ②）：环形进度 + 流式追加文字。
 *
 * 动效遵守 3.4：新段落淡入 + 8dp 上移 + 自动滚到底（200ms）。
 */
@Composable
fun TranscribingScreen(
    onCancel: () -> Unit,
    onBackground: () -> Unit
) {
    val segments by TranscribeBus.segments.collectAsState()
    val progress by TranscribeBus.progress.collectAsState()
    val progressText by TranscribeBus.progressText.collectAsState()
    val fileName by TranscribeBus.fileName.collectAsState()
    val listState = rememberLazyListState()

    // 新段落到来时自动滚到底
    LaunchedEffect(segments.size) {
        if (segments.isNotEmpty()) launch { listState.animateScrollToItem(segments.lastIndex) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "取消")
            }
        }

        Spacer(modifier = Modifier.size(24.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(120.dp),
                    strokeWidth = 8.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(progressText, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                }
            }
            Spacer(modifier = Modifier.size(16.dp))
            val status by TranscribeBus.status.collectAsState()
            val heading = when (status) {
                com.nightowl.transcriber.pipeline.Status.Preparing -> "正在加载识别模型…"
                else -> "正在转写：$fileName"
            }
            Text(heading, style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground)
        }

        Spacer(modifier = Modifier.size(24.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            LazyColumn(state = listState, modifier = Modifier.padding(16.dp)) {
                items(segments, key = { it.index }) { seg ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { 8 })
                    ) {
                        SegmentBlock(seg)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.size(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("暂停")
            }
            OutlinedButton(onClick = onBackground, modifier = Modifier.weight(1f)) {
                Text("后台转写")
            }
        }
        Spacer(modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SegmentBlock(seg: Segment) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            "【${TranscribePipeline.fmtTime(seg.startMs)} - ${TranscribePipeline.fmtTime(seg.endMs)}】",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            seg.text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
