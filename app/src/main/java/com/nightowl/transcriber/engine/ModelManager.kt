package com.nightowl.transcriber.engine

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File

/**
 * 模型管理。
 *
 * 获取模型的三条路：
 *   ① 应用内一键下载（推荐）：tokens.txt / silero_vad.onnx 已随 APK 内置（assets），
 *      仅 228MB 的 model.int8.onnx 需在线下载（见 pipeline.ModelDownloader）
 *   ② 公共目录自动恢复：模型会同时保存一份到公共「Download/离线转写/」（MediaStore），
 *      卸载/重装 App 后不清除；重装后首次下载模型时自动从公共区搬回（本地拷贝，秒级），
 *      无需重新联网下载
 *   ③ 手动导入：从手机存储选择文件（兜底）
 *
 * 工作副本统一在应用内部 filesDir/models/ 下（识别引擎按路径读文件）。
 */
object ModelManager {

    const val FILE_MODEL = "sense_voice_int8.onnx"
    const val FILE_TOKENS = "tokens.txt"
    const val FILE_VAD = "silero_vad.onnx"

    /** 公共区（Download/离线转写/）中模型文件的展示名 */
    const val PUBLIC_MODEL_NAME = "sense_voice_int8.onnx"
    const val PUBLIC_RELATIVE_DIR = "Download/离线转写/"

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

    // ---------- 公共目录持久化（Android 10+，卸载重装不丢） ----------

    /** 把已就绪的模型另存一份到公共 Download/离线转写/（幂等：已有同名同大小则跳过） */
    fun backupModelToPublic(context: Context, expectedBytes: Long) {
        if (Build.VERSION.SDK_INT < 29) return
        val src = modelFile(context)
        if (!src.exists() || src.length() != expectedBytes) return
        if (publicModelSize(context) == expectedBytes) return // 公共区已有一份完整的
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, PUBLIC_MODEL_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, PUBLIC_RELATIVE_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("openOutputStream null")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
            }
        } catch (_: Throwable) {
        }
    }

    /** 公共区模型文件大小；不存在或不可读返回 -1 */
    fun publicModelSize(context: Context): Long {
        if (Build.VERSION.SDK_INT < 29) return -1L
        return try {
            val resolver = context.contentResolver
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(PUBLIC_RELATIVE_DIR, PUBLIC_MODEL_NAME),
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getLong(1) else -1L
            } ?: -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    /**
     * 从公共区把模型恢复（拷贝）到应用内 models 目录。
     * 重装 App 后首次调用即完成"免下载"，本地拷贝 228MB 通常只需数秒。
     * 返回 true = 恢复成功且大小校验通过。
     */
    fun restoreModelFromPublic(context: Context, expectedBytes: Long): Boolean {
        val dst = modelFile(context)
        if (dst.exists() && dst.length() == expectedBytes) return true
        if (publicModelSize(context) != expectedBytes) return false
        return try {
            val resolver = context.contentResolver
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(PUBLIC_RELATIVE_DIR, PUBLIC_MODEL_NAME),
                null
            )?.use { c ->
                if (!c.moveToFirst()) return false
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val tmp = File(dst.parentFile, dst.name + ".restore")
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (tmp.length() == expectedBytes) {
                        if (dst.exists()) dst.delete()
                        return tmp.renameTo(dst)
                    }
                    tmp.delete()
                    false
                } ?: false
            } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- 手动导入 ----------

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
