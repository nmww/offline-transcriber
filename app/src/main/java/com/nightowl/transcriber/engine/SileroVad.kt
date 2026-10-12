package com.nightowl.transcriber.engine

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Silero VAD 封装（629KB，sherpa-onnx v1.10.30 Kotlin API）。
 *
 * 作用：跳过静音、自动按自然停顿分句，让长音频既省时、又让输出天然形成“对话语段”。
 * startSample 由官方 Vad 内部维护（相对整段音频的绝对样本偏移），时间戳因此精确。
 * 无 Silero 模型时自动回退 [EnergyVadDetector]（能量门限，零依赖）。
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

    override fun accept(samples: FloatArray): VadSegment? {
        vad.acceptWaveform(samples)
        if (vad.empty()) return null
        val seg = vad.front()
        vad.pop()
        if (seg.samples.isEmpty()) return null
        return VadSegment(startSample = seg.start, samples = seg.samples.copyOf())
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

    override fun accept(samples: FloatArray): VadSegment? {
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

        val shouldFlush = inSpeech && silenceFrames >= minSilenceFrames
        if (shouldFlush && collected.isNotEmpty()) {
            val out = collected.toFloatArray()
            val start = collectedStart
            collected.clear()
            inSpeech = false
            silenceFrames = 0
            return VadSegment(startSample = start.toInt(), samples = out)
        }

        if (inSpeech) {
            if (collected.isEmpty()) collectedStart = frameStart
            collected.addAll(samples.toList())
        } else if (collected.isNotEmpty()) {
            collected.clear()   // 防静音无限累积
        }
        return null
    }

    override fun reset() {
        collected.clear()
        speechFrames = 0
        silenceFrames = 0
        inSpeech = false
    }

    override fun close() = reset()
}
