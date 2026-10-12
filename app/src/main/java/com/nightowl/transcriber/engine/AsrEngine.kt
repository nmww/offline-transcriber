package com.nightowl.transcriber.engine

/**
 * ASR 引擎适配层。
 *
 * sherpa-onnx v1.10.30 官方 Kotlin API 源码已随工程内置
 * （位于 com.k2fsa.sherpa.onnx 包，含 OfflineRecognizer 与 Vad 等），
 * 原生库 jniLibs/arm64-v8a/ 下有 libsherpa-onnx-jni.so 与 libonnxruntime.so。
 * 因此不再需要任何 maven 坐标依赖。
 */

/** 一个转写语段：文字 + 起止毫秒（起止时间来自整段音频中的绝对位置） */
data class Segment(
    val index: Int,
    val text: String,
    val startMs: Long,
    val endMs: Long
)

/** VAD 产出的语段：startSample 为相对整段音频起点的样本偏移（16kHz） */
data class VadSegment(
    val startSample: Int,
    val samples: FloatArray
)

interface AsrEngine {
    fun close()

    /** 识别一段 16kHz 单声道 float PCM，返回规范化后的文字（已清洗 SenseVoice 标签） */
    fun transcribe(pcm: FloatArray, sampleRate: Int = 16000): String
}

/** VAD 语音端点检测：把长音频切成「有人说话」的语段 */
interface VadDetector {
    /** 每帧采样数（16kHz 下 512 采样 = 32ms） */
    val windowSize: Int

    /**
     * 喂入采样；返回非空时表示产出一个完整语段（含绝对起始样本位置）。
     * 调用方每次喂入的样本数应 <= [windowSize]。
     */
    fun accept(samples: FloatArray): VadSegment?

    fun reset()
    fun close()
}

/** 清洗 SenseVoice 输出中的 <|zh|> <|NEUTRAL|> <|Speech|> 等控制标签 */
object TextCleaner {
    private val TAG_RE = Regex("<\\|[^|]*\\|>")

    fun cleanSenseVoice(raw: String): String {
        return TAG_RE.replace(raw, "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }
}
