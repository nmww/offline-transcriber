package com.nightowl.transcriber.pipeline

import android.content.Context
import com.nightowl.transcriber.engine.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型在线下载器：一键下载 SenseVoice INT8 主模型（228MB）。
 *
 * - tokens.txt / silero_vad.onnx 已随 APK 内置（assets），仅主模型需要下载
 * - 多源 fallback：hf-mirror（国内直连）→ huggingface（需代理）
 * - 断点续传：.part 临时文件 + Range 头，中断后可继续
 * - 状态经 StateFlow 暴露给设置页与通知
 */
object ModelDownloader {

    private val SOURCES = listOf(
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx"
    )

    private const val PART_SUFFIX = ".part"
    private const val BUFFER = 64 * 1024

    /** 官方 model.int8.onnx 的确切字节数（hf-mirror 实测），下载完成必须完全一致，防止损坏模型引发原生层崩溃 */
    const val EXPECTED_BYTES = 239_233_841L

    sealed class State {
        object Idle : State()
        data class Downloading(val fileProgress: Float, val downloadedMB: Long, val totalMB: Long) : State()
        object Done : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile
    var cancelRequested = false
        private set

    fun requestCancel() {
        cancelRequested = true
    }

    /** 是否已有可续传的临时文件 */
    fun hasPartialFile(context: Context): Boolean = partFile(context).exists()

    private fun partFile(context: Context): File =
        File(ModelManager.modelsDir(context), ModelManager.FILE_MODEL + PART_SUFFIX)

    suspend fun download(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (_state.value is State.Downloading) return@withContext false
        cancelRequested = false
        val target = File(ModelManager.modelsDir(context), ModelManager.FILE_MODEL)

        // 已存在且大小合理 → 直接完成
        if (target.exists() && target.length() > 200L * 1024 * 1024) {
            _state.value = State.Done
            return@withContext true
        }

        // 优先从公共区（Download/离线转写/，卸载重装不丢）恢复：本地拷贝，秒级，不联网
        _state.value = State.Downloading(0f, 0, 228)
        if (ModelManager.restoreModelFromPublic(context, EXPECTED_BYTES)) {
            ModelManager.ensureBaseFiles(context)
            _state.value = State.Done
            return@withContext true
        }

        _state.value = State.Downloading(0f, 0, 228)
        var lastError = "未知错误"

        for (url in SOURCES) {
            if (cancelRequested) break
            try {
                if (downloadFrom(url, context)) {
                    // 双写：公共区留一份，卸载/重装后可秒恢复
                    ModelManager.backupModelToPublic(context, EXPECTED_BYTES)
                    _state.value = State.Done
                    return@withContext true
                }
                lastError = "已取消"
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                // 换下一个源
            }
        }
        _state.value = if (cancelRequested) State.Idle else State.Failed(lastError)
        false
    }

    /** 从单一源下载；支持断点续传。返回 true=成功 */
    private fun downloadFrom(source: String, context: Context): Boolean {
        val part = partFile(context)
        var downloaded = if (part.exists()) part.length() else 0L

        val conn = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")

            val resuming = code == 206
            if (!resuming && downloaded > 0) {
                // 服务器不支持续传，从头开始
                part.delete()
                downloaded = 0
            }

            val total = if (resuming) downloaded + conn.contentLengthLong
            else conn.contentLengthLong

            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, true).use { out ->
                    val buf = ByteArray(BUFFER)
                    var lastEmit = 0L
                    while (true) {
                        if (cancelRequested) return false
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastEmit > 1024 * 1024) { // 每 1MB 刷一次状态
                            lastEmit = downloaded
                            val mb = downloaded / (1024f * 1024f)
                            val totalMb = if (total > 0) total / (1024f * 1024f) else 228f
                            _state.value = State.Downloading(
                                fileProgress = if (total > 0) downloaded.toFloat() / total else 0f,
                                downloadedMB = downloaded / (1024 * 1024),
                                totalMB = totalMb.toLong()
                            )
                        }
                    }
                }
            }

            // 校验完整性：服务器给出总量时必须一致；与已知官方大小比对
            if (total > 0 && part.length() != total) {
                throw IllegalStateException("下载不完整：${part.length()}/$total")
            }
            if (part.length() != EXPECTED_BYTES) {
                throw IllegalStateException("文件大小异常：${part.length()}（期望 $EXPECTED_BYTES）")
            }

            // 原子落位
            val target = File(ModelManager.modelsDir(context), ModelManager.FILE_MODEL)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            return true
        } finally {
            conn.disconnect()
        }
    }
}
