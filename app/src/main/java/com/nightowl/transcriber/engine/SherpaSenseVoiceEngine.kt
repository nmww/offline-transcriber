package com.nightowl.transcriber.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

/**
 * SenseVoice 离线识别引擎（sherpa-onnx v1.10.30 Kotlin API）。
 *
 * 模型：SenseVoice-Small INT8（228MB）+ tokens.txt —— 详见 README「模型准备」。
 * 注：本文件是唯一依赖 sherpa-onnx 配置类的位置；升级 sherpa-onnx 版本时只需改这里。
 */
class SherpaSenseVoiceEngine(
    modelPath: String,
    tokensPath: String,
    numThreads: Int = 2,
    language: String = ""   // 空串 = 自动检测语言
) : AsrEngine {

    private val recognizer: OfflineRecognizer

    init {
        val senseVoice = OfflineSenseVoiceModelConfig(
            model = modelPath,
            language = language,
            useInverseTextNormalization = true
        )
        // 注意：v1.10.30 的 OfflineModelConfig 中 tokens 为必填（无默认值）
        val modelConfig = OfflineModelConfig(
            tokens = tokensPath,
            senseVoice = senseVoice,
            numThreads = numThreads,
            debug = false,
            provider = "cpu"
        )
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )
        recognizer = OfflineRecognizer(config = config)
    }

    override fun transcribe(pcm: FloatArray, sampleRate: Int): String {
        return try {
            // 注意：OfflineStream.use 的 block 返回 Unit，故先取出结果再返回
            var text = ""
            recognizer.createStream().use { stream ->
                stream.acceptWaveform(pcm, sampleRate)
                recognizer.decode(stream)
                text = TextCleaner.cleanSenseVoice(recognizer.getResult(stream).text)
            }
            text
        } catch (t: Throwable) {
            // 单段失败不应中断整条流水线
            ""
        }
    }

    override fun close() {
        recognizer.release()
    }
}
