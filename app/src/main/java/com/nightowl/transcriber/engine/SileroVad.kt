package com.nightowl.transcriber.engine

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Silero VAD 封装（629KB，sherpa-onnx v1.10.30 Kotlin API）。
 *
 * 关键正确性规则（照官方 VadAsr demo）：
 *   1. 每次 acceptWaveform 之后必须【循环】 empty()/front()/pop() 把队列取空
 *      —— 一次喂入可能产出多段（maxSpeechDuration 强切、快速停顿等）
 *   2. 音频喂完必须调用 flush()，把内部尚在缓冲的语音（没有静音收尾）冲出
 *      —— 否则整条结尾语段会丢失
 */
class SileroVad(
    modelPath: String,
    override val windowSize: Int = 512,
    threshold: Float = 0.5f,
    minSilenceDuration: Float = 0.5f,
    minSpeechDuration: Float = 0.25f,
    maxSpeechDuration: Float = 20f,
    numThreads: Int = 1
) : VadDetector {

    private val vad: Vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelPath,
                threshold = threshold,
                minSilenceDuration = minSilenceDuration,
                minSpeechDuration = minSpeechDuration,
                windowSize = windowSize,
                maxSpeechDuration = maxSpeechDuration
            ),
            sampleRate = 16000,
            numThreads = numThreads,
            provider = "cpu",
            debug = false
        )
    )

    override fun accept(samples: FloatArray): List<VadSegment> {
        vad.acceptWaveform(samples)
        return drain()
    }

    override fun flush(): List<VadSegment> {
        vad.flush()
        return drain()
    }

    /** 把官方 VAD 内部队列全部取出 */
    private fun drain(): List<VadSegment> {
        val out = ArrayList<VadSegment>(2)
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            if (seg.samples.isNotEmpty()) {
                out.add(VadSegment(startSample = seg.start, samples = seg.samples.copyOf()))
            }
        }
        return out
    }

    override fun reset() {
        vad.reset()
        vad.clear()
    }

    override fun close() {
        vad.release()
    }
}

/**
 * 兜底 VAD：仅按能量门限切分。无 Silero 模型时使用，效果较差但保证功能不中断。
 */
class EnergyVadDetector(
    override val windowSize: Int = 512,
    private val speechThreshold: Float = 0.01f,     // RMS 门限
    private val minSpeechFrames: Int = 8,           // 约 0.25s
    private val minSilenceFrames: Int = 16          // 约 0.5s
) : VadDetector {

    private var consumedSamples = 0L     // 绝对样本游标
    private val collected = ArrayList<Float>()
    private var collectedStart = 0L
    private var speechFrames = 0
    private var silenceFrames = 0
    private var inSpeech = false

    override fun accept(samples: FloatArray): List<VadSegment> {
        val frameStart = consumedSamples
        consumedSamples += samples.size

        var sum = 0.0
        for (s in samples) sum += (s * s).toDouble()
        val rms = kotlin.math.sqrt(sum / samples.size.coerceAtLeast(1)).toFloat()
        val isSpeech = rms > speechThreshold

        if (isSpeech) {
            speechFrames++
            silenceFrames = 0
            if (speechFrames >= minSpeechFrames) inSpeech = true
        } else {
            silenceFrames++
            speechFrames = 0
        }

        if (inSpeech) {
            if (collected.isEmpty()) collectedStart = frameStart
            collected.addAll(samples.toList())
        }

        val shouldFlush = inSpeech && silenceFrames >= minSilenceFrames
        if (shouldFlush && collected.isNotEmpty()) {
            val out = collected.toFloatArray()
            val start = collectedStart
            collected.clear()
            inSpeech = false
            silenceFrames = 0
            return listOf(VadSegment(startSample = start.toInt(), samples = out))
        }
        return emptyList()
    }

    override fun flush(): List<VadSegment> {
        // 音频结束：把仍在缓冲中的语音冲出（不做静音等待）
        return if (inSpeech && collected.isNotEmpty()) {
            val out = collected.toFloatArray()
            val start = collectedStart
            collected.clear()
            inSpeech = false
            listOf(VadSegment(startSample = start.toInt(), samples = out))
        } else {
            emptyList()
        }
    }

    override fun reset() {
        collected.clear()
        speechFrames = 0
        silenceFrames = 0
        inSpeech = false
    }

    override fun close() = reset()
}
