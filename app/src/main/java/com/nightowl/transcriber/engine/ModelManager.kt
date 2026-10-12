package com.nightowl.transcriber.engine

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 模型管理。
 *
 * 获取模型的两条路：
 *   ① 应用内一键下载（推荐）：tokens.txt / silero_vad.onnx 已随 APK 内置（assets），
 *      仅 228MB 的 model.int8.onnx 需在线下载（见 pipeline.ModelDownloader）
 *   ② 手动导入：从手机存储选择文件（保留，作为下载失败的兜底）
 *
 * 所有文件统一落在应用内部 filesDir/models/ 下。
 */
object ModelManager {

    const val FILE_MODEL = "sense_voice_int8.onnx"
    const val FILE_TOKENS = "tokens.txt"
    const val FILE_VAD = "silero_vad.onnx"

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(context: Context): File = File(modelsDir(context), FILE_MODEL)
    fun tokensFile(context: Context): File = File(modelsDir(context), FILE_TOKENS)
    fun vadFile(context: Context): File = File(modelsDir(context), FILE_VAD)

    /**
     * 把 APK 内置的 tokens.txt / silero_vad.onnx 解包到 models 目录（幂等）。
     * 在 App 启动时调用一次。
     */
    fun ensureBaseFiles(context: Context) {
        copyAssetIfNeeded(context, FILE_TOKENS)
        copyAssetIfNeeded(context, FILE_VAD)
    }

    /** 识别模型是否就绪（主模型存在且 >200MB，tokens 存在） */
    fun isReady(context: Context): Boolean {
        ensureBaseFiles(context)
        val m = modelFile(context)
        return m.exists() && m.length() > 200L * 1024 * 1024 && tokensFile(context).exists()
    }

    fun modelPath(context: Context): String = modelFile(context).absolutePath
    fun tokensPath(context: Context): String = tokensFile(context).absolutePath

    private fun copyAssetIfNeeded(context: Context, assetName: String) {
        val out = File(modelsDir(context), assetName)
        if (out.exists() && out.length() > 0) return
        try {
            context.assets.open(assetName).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (t: Throwable) {
            out.delete()
        }
    }

    /** 手动导入兜底：把用户选中的 model/tokens/vad 文件拷贝到 models 目录 */
    fun importFile(context: Context, uri: Uri, displayName: String): Boolean {
        val targetName = when {
            displayName.endsWith(".onnx") && displayName.contains("vad") -> FILE_VAD
            displayName.endsWith(".onnx") -> FILE_MODEL
            displayName == "tokens.txt" -> FILE_TOKENS
            else -> return false
        }
        val out = File(modelsDir(context), targetName)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 构造识别引擎；无 Silero 时自动回退能量 VAD */
    fun createEngine(context: Context): AsrEngine =
        SherpaSenseVoiceEngine(modelPath(context), tokensPath(context))

    fun createVad(context: Context): VadDetector {
        return if (vadFile(context).exists()) {
            try {
                SileroVad(modelPath = vadFile(context).absolutePath)
            } catch (t: Throwable) {
                EnergyVadDetector()
            }
        } else {
            EnergyVadDetector()
        }
    }
}
