package com.nightowl.transcriber.engine

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 模型管理：负责识别模型的「导入 → 校验 → 定位」。
 *
 * 产品原则：APK 本体 ~10MB，不打包 228MB 模型；用户从手机存储选一次模型文件，之后永久离线可用。
 * 模型文件统一落在应用内部 filesDir/models/ 下。
 */
object ModelManager {

    const val FILE_MODEL = "sense_voice_int8.onnx"
    const val FILE_TOKENS = "tokens.txt"
    const val FILE_VAD = "silero_vad.onnx"

    private const val PREF = "model"
    private const val KEY_MODEL = "model_path"
    private const val KEY_TOKENS = "tokens_path"
    private const val KEY_VAD = "vad_path"

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun isReady(context: Context): Boolean {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val m = p.getString(KEY_MODEL, null)
        val t = p.getString(KEY_TOKENS, null)
        return m != null && File(m).exists() && t != null && File(t).exists()
    }

    fun vadPath(context: Context): String? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val v = p.getString(KEY_VAD, null)
        return if (v != null && File(v).exists()) v else null
    }

    fun modelPath(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_MODEL, "")!!

    fun tokensPath(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_TOKENS, "")!!

    /** 把用户选中的 model/tokens/vad 文件拷贝到内部目录并登记 */
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
            val key = when (targetName) {
                FILE_VAD -> KEY_VAD
                FILE_TOKENS -> KEY_TOKENS
                else -> KEY_MODEL
            }
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(key, out.absolutePath).apply()
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 构造识别引擎；无 Silero 时自动回退能量 VAD */
    fun createEngine(context: Context): AsrEngine =
        SherpaSenseVoiceEngine(modelPath(context), tokensPath(context))

    fun createVad(context: Context): VadDetector {
        val vPath = vadPath(context)
        return if (vPath != null) {
            try {
                SileroVad(modelPath = vPath)
            } catch (t: Throwable) {
                EnergyVadDetector()
            }
        } else {
            EnergyVadDetector()
        }
    }
}
