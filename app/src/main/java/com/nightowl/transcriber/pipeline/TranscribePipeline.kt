package com.nightowl.transcriber.pipeline

import android.content.Context
import android.net.Uri
import com.nightowl.transcriber.data.ExportFormat
import com.nightowl.transcriber.data.Exporter
import com.nightowl.transcriber.engine.AsrEngine
import com.nightowl.transcriber.engine.EnergyVadDetector
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
    val elapsedText = MutableStateFlow("")   // 实际转写用时（完成时写入）

    @Volatile
    var cancelRequested = false

    fun reset() {
        segments.value = emptyList()
        status.value = Status.Idle
        progress.value = 0f
        progressText.value = ""
        resultText.value = ""
        elapsedText.value = ""
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

        // ✦ 诊断面包屑：模型文件状态（文件大小/存在性）——native 层 exit 无法被 Java 捕获，
        //   必须在进入引擎前把状态写进日志（OTLog：logcat 镜像 + 文件双写）
        com.nightowl.transcriber.diag.OTLog.i("OTDiag", "pipeline start uri=$uri name=$displayName")
        com.nightowl.transcriber.diag.OTLog.i(
            "OTDiag",
            "model=${ModelManager.modelFile(context).exists()}/${ModelManager.modelFile(context).length()}" +
                " tokens=${ModelManager.tokensFile(context).exists()}/${ModelManager.tokensFile(context).length()}" +
                " vad=${ModelManager.vadFile(context).exists()}/${ModelManager.vadFile(context).length()}"
        )
        if (!ModelManager.isReady(context)) {
            TranscribeBus.status.value = Status.Error("模型未就绪：请到「设置」完成模型下载")
            return@withContext Result.failure(IllegalStateException("model not ready"))
        }

        com.nightowl.transcriber.diag.OTLog.i("OTDiag", "creating ASR engine…")
        val engine: AsrEngine = ModelManager.createEngine(context)
        com.nightowl.transcriber.diag.OTLog.i("OTDiag", "ASR engine created OK")
        com.nightowl.transcriber.diag.OTLog.i("OTDiag", "creating VAD…")
        var vad: VadDetector = ModelManager.createVad(context)
        com.nightowl.transcriber.diag.OTLog.i("OTDiag", "VAD created OK")
        val resultBuilder = StringBuilder()
        val startedAt = System.currentTimeMillis()
        val accumulated = ArrayList<Segment>()
        var skippedSegments = 0
        // ✦ 音频通路统计：0 字排查用（总样本/峰值/块数）
        var fedChunks = 0
        var fedSamples = 0L
        var peakAmp = 0f
        // ✦ 前 4 秒音频转储（Silero 无输出时落盘供离线分析）
        val debugBuf = FloatArray(64000)
        var debugLen = 0

        // ✦ 明确告知用户当前处于"加载模型"阶段（首次加载需数秒，不是卡死）
        TranscribeBus.status.value = Status.Preparing
        TranscribeBus.progressText.value = "正在加载识别模型…"

        /** 识别一个语段并流式追加（空结果计数，不中断流水线） */
        fun handleSegment(seg: com.nightowl.transcriber.engine.VadSegment) {
            val segMs = seg.samples.size * 1000L / 16000
            val text = engine.transcribe(seg.samples)
            if (accumulated.size + skippedSegments < 5) {
                com.nightowl.transcriber.diag.OTLog.i(
                    "OTDiag",
                    "seg#${accumulated.size + skippedSegments} len=${segMs}ms peak=${seg.samples.maxOrNull()?.let { kotlin.math.abs(it) } ?: 0f} text='${text.take(40)}'"
                )
            }
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
            } else {
                skippedSegments++
            }
        }

        try {
            val totalMs = extractor.durationMs(uri)
            TranscribeBus.status.value = Status.Running

            /** 一遍完整解码+VAD+识别（Silero 无输出时用能量 VAD 再跑一遍） */
            suspend fun runPass(v: VadDetector) {
                extractor.extract(
                    uri = uri,
                    onProgress = { ms, total ->
                        val p = if (total > 0) (ms.toFloat() / total).coerceIn(0f, 1f) else 0f
                        TranscribeBus.progress.value = p
                        TranscribeBus.progressText.value = "${fmtTime(ms)} / ${fmtTime(total)}"
                    },
                    onChunk = { chunk ->
                        if (!coroutineContext.isActive || TranscribeBus.cancelRequested) return@extract

                        fedChunks++
                        fedSamples += chunk.size
                        if (chunk.isNotEmpty()) {
                            val p = chunk.maxOf { kotlin.math.abs(it) }
                            if (p > peakAmp) peakAmp = p
                        }
                        if (debugLen < debugBuf.size) {
                            val n = minOf(chunk.size, debugBuf.size - debugLen)
                            System.arraycopy(chunk, 0, debugBuf, debugLen, n)
                            debugLen += n
                        }

                        // 喂 VAD：按窗口切片（尾帧不足窗口时丢弃，<32ms 无影响）
                        var offset = 0
                        while (offset + v.windowSize <= chunk.size) {
                            val frame = chunk.copyOfRange(offset, offset + v.windowSize)
                            offset += v.windowSize
                            // ✦ 关键：一次喂入可能产出多段，必须全部处理
                            for (seg in v.accept(frame)) {
                                handleSegment(seg)
                            }
                        }
                    }
                )

                // ✦ 关键：音频喂完必须 flush，把 VAD 内部残余语音冲出（否则结尾整段丢失）
                for (seg in v.flush()) {
                    handleSegment(seg)
                }
            }

            runPass(vad)

            com.nightowl.transcriber.diag.OTLog.i(
                "OTDiag",
                "decode done: chunks=$fedChunks totalSamples=$fedSamples≈${fedSamples / 16000}s peak=$peakAmp segs=${accumulated.size} skipped=$skippedSegments"
            )

            // ✦ 兜底：Silero 全程没切出任何段，但音频明显有声 → 换能量 VAD 重跑一遍
            if (accumulated.isEmpty() && skippedSegments == 0 &&
                peakAmp > 0.05f && !TranscribeBus.cancelRequested
            ) {
                dumpDebugPcm(debugLen, debugBuf, "debug_first4s.pcm")
                com.nightowl.transcriber.diag.OTLog.i(
                    "OTDiag",
                    "Silero 0段(peak=$peakAmp) → 换能量VAD重跑（前4s已转储 debug_first4s.pcm）"
                )
                runCatching { vad.close() }
                vad = EnergyVadDetector()
                fedChunks = 0; fedSamples = 0; peakAmp = 0f; debugLen = 0
                runPass(vad)
                com.nightowl.transcriber.diag.OTLog.i(
                    "OTDiag",
                    "fallback done: chunks=$fedChunks samples=$fedSamples peak=$peakAmp segs=${accumulated.size} skipped=$skippedSegments"
                )
            }

            // ✦ 第三层兜底：VAD 全军覆没但音频确实有声 → 固定 30s 窗口硬切直喂识别
            //   （等价于第一版"整段喂模型"的行为，保证任何有声视频都有输出）
            if (accumulated.isEmpty() && skippedSegments == 0 &&
                peakAmp > 0.05f && !TranscribeBus.cancelRequested
            ) {
                dumpDebugPcm(debugLen, debugBuf, "debug_energy.pcm")
                com.nightowl.transcriber.diag.OTLog.i(
                    "OTDiag",
                    "能量VAD也0段(peak=$peakAmp) → 固定30s窗口直切（转储 debug_energy.pcm）"
                )
                fedChunks = 0; fedSamples = 0; peakAmp = 0f; debugLen = 0
                val win = FloatArray(30 * 16000)
                var winLen = 0
                var absSamples = 0L
                extractor.extract(
                    uri = uri,
                    onProgress = { ms, total ->
                        val p = if (total > 0) (ms.toFloat() / total).coerceIn(0f, 1f) else 0f
                        TranscribeBus.progress.value = p
                        TranscribeBus.progressText.value = "${fmtTime(ms)} / ${fmtTime(total)}"
                    },
                    onChunk = { chunk ->
                        if (!coroutineContext.isActive || TranscribeBus.cancelRequested) return@extract
                        fedSamples += chunk.size
                        var off = 0
                        while (off < chunk.size) {
                            val n = minOf(chunk.size - off, win.size - winLen)
                            System.arraycopy(chunk, off, win, winLen, n)
                            off += n; winLen += n
                            if (winLen == win.size) {
                                handleSegment(VadSegment(startSample = absSamples.toInt(), samples = win.copyOf()))
                                absSamples += winLen; winLen = 0
                            }
                        }
                    }
                )
                if (winLen > 16000) { // 尾部 >1s 才值得识别
                    handleSegment(VadSegment(startSample = absSamples.toInt(), samples = win.copyOf(winLen)))
                }
                com.nightowl.transcriber.diag.OTLog.i(
                    "OTDiag",
                    "fixedwindow done: samples=$fedSamples segs=${accumulated.size} skipped=$skippedSegments"
                )
            }

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
            TranscribeBus.elapsedText.value = fmtHuman(System.currentTimeMillis() - startedAt)
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

    /** 把前几秒重采样音频写成 16bit PCM，供离线分析（VAD 无输出时才调用） */
    private fun dumpDebugPcm(len: Int, buf: FloatArray, filename: String) {
        runCatching {
            java.io.DataOutputStream(
                java.io.BufferedOutputStream(
                    java.io.FileOutputStream(java.io.File(context.filesDir, filename))
                )
            ).use { out ->
                for (i in 0 until len) {
                    out.writeShort((buf[i].coerceIn(-1f, 1f) * 32767).toInt())
                }
            }
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

        /** 人类可读的耗时（xx分xx秒），用于「转写用时」展示 */
        fun fmtHuman(ms: Long): String {
            val totalSec = ms / 1000
            return if (totalSec >= 60) "${totalSec / 60}分${totalSec % 60}秒" else "${totalSec}秒"
        }
    }
}
