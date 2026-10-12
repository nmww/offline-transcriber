package com.nightowl.transcriber.pipeline

import android.content.Context
import android.net.Uri
import com.nightowl.transcriber.data.ExportFormat
import com.nightowl.transcriber.data.Exporter
import com.nightowl.transcriber.engine.AsrEngine
import com.nightowl.transcriber.engine.ModelManager
import com.nightowl.transcriber.engine.Segment
import com.nightowl.transcriber.engine.VadDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** 转写状态机 */
sealed class Status {
    object Idle : Status()
    object Preparing : Status()
    object Running : Status()
    object Done : Status()
    object Cancelled : Status()
    data class Error(val message: String) : Status()
}

/** UI 与 Service 共享的实时状态总线：流式显示 + 进度都从这里驱动 */
object TranscribeBus {
    val segments = MutableStateFlow<List<Segment>>(emptyList())
    val status = MutableStateFlow<Status>(Status.Idle)
    val progress = MutableStateFlow(0f)
    val progressText = MutableStateFlow("")
    val fileName = MutableStateFlow("")
    val resultText = MutableStateFlow("")

    @Volatile
    var cancelRequested = false

    fun reset() {
        segments.value = emptyList()
        status.value = Status.Idle
        progress.value = 0f
        progressText.value = ""
        resultText.value = ""
        cancelRequested = false
    }
}

/**
 * 转写流水线（规格书 4.3）：
 *
 *   音轨抽取 → VAD 分句 → SenseVoice 识别 → 段交替 UI（流式） → 收尾导出 txt/srt
 *
 * 生产者（解码）与消费者（VAD+识别）通过协程顺序衔接，边抽边转；内存只保留段文本。
 */
class TranscribePipeline(private val context: Context) {

    suspend fun run(
        uri: Uri,
        displayName: String,
        formatter: ExportFormat
    ): Result<String> = withContext(Dispatchers.Default) {
        TranscribeBus.reset()
        TranscribeBus.fileName.value = displayName
        TranscribeBus.status.value = Status.Preparing

        val extractor = AudioExtractor(context)
        val engine: AsrEngine = ModelManager.createEngine(context)
        val vad: VadDetector = ModelManager.createVad(context)
        val resultBuilder = StringBuilder()

        try {
            val totalMs = extractor.durationMs(uri)
            TranscribeBus.status.value = Status.Running

            val accumulated = ArrayList<Segment>()

            extractor.extract(
                uri = uri,
                onProgress = { ms, total ->
                    val p = if (total > 0) (ms.toFloat() / total).coerceIn(0f, 1f) else 0f
                    TranscribeBus.progress.value = p
                    TranscribeBus.progressText.value = "${fmtTime(ms)} / ${fmtTime(total)}"
                },
                onChunk = { chunk ->
                    if (!coroutineContext.isActive || TranscribeBus.cancelRequested) return@extract

                    // 喂 VAD：按窗口切片
                    var offset = 0
                    while (offset < chunk.size) {
                        val len = kotlin.math.min(vad.windowSize, chunk.size - offset)
                        val frame = chunk.copyOfRange(offset, offset + len)
                        offset += len
                        val seg = vad.accept(frame)

                        if (seg != null && seg.samples.isNotEmpty()) {
                            val text = engine.transcribe(seg.samples)
                            if (text.isNotBlank()) {
                                // 绝对时间来自 VAD 内部维护的样本偏移，精确到帧
                                val startMs = seg.startSample * 1000L / 16000
                                val endMs = startMs + seg.samples.size * 1000L / 16000
                                val item = Segment(accumulated.size, text, startMs, endMs)
                                accumulated.add(item)
                                // ✦ 流式追加：UI 立刻刷新这一段
                                TranscribeBus.segments.value = accumulated.toList()
                                if (resultBuilder.isNotEmpty()) resultBuilder.append("\n\n")
                                resultBuilder.append(text)
                                TranscribeBus.resultText.value = resultBuilder.toString()
                            }
                        }
                    }
                }
            )

            if (TranscribeBus.cancelRequested) {
                TranscribeBus.status.value = Status.Cancelled
                // 已转部分仍然落盘，避免白干
                saveResults(uri, displayName, accumulated, formatter)
                return@withContext Result.success(resultBuilder.toString())
            }

            val finalText = Exporter.format(accumulated, formatter)
            TranscribeBus.resultText.value = finalText
            TranscribeBus.progress.value = 1f
            TranscribeBus.segments.value = accumulated.toList()
            saveResults(uri, displayName, accumulated, formatter)
            TranscribeBus.status.value = Status.Done
            Result.success(finalText)
        } catch (t: Throwable) {
            TranscribeBus.status.value = Status.Error(t.message ?: "转写失败")
            Result.failure(t)
        } finally {
            engine.close()
            vad.close()
        }
    }

    /** 同目录导出：优先原文件所在目录，不可写时回退 Documents/离线转写/（由 Exporter 处理） */
    private suspend fun saveResults(
        uri: Uri,
        displayName: String,
        segments: List<Segment>,
        formatter: ExportFormat
    ) {
        if (segments.isEmpty()) return
        val totalMs = AudioExtractor(context).durationMs(uri)
        Exporter.export(
            context = context,
            mediaUri = uri,
            displayName = displayName,
            segments = segments,
            fmt = formatter,
            durationMs = totalMs
        )
    }

    companion object {
        fun fmtTime(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
            else String.format("%d:%02d", m, s)
        }
    }
}
