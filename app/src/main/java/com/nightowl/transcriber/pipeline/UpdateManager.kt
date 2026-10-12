package com.nightowl.transcriber.pipeline

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内热更新：检查 GitHub Release → 应用内下载新 APK → 唤起系统安装。
 *
 * - 模型文件存放在应用内部存储（filesDir），覆盖安装【不会】清除 → 已下载的 228MB 模型不会重复下载
 * - 版本比较：与上次「已下载/已忽略」的 Release tag 比对（tag 由 CI 递增，v1.0.N 单调递增）
 */
object UpdateManager {

    private const val LATEST_API = "https://api.github.com/repos/nmww/offline-transcriber/releases/latest"
    private const val PREF = "update"
    private const val KEY_LAST_SEEN = "last_seen_tag"

    data class ReleaseInfo(
        val tag: String,
        val apkUrl: String,
        val sizeMb: Long,
        val publishedAt: String
    )

    sealed class State {
        object Idle : State()
        object Checking : State()
        data class Available(val info: ReleaseInfo) : State()
        object UpToDate : State()
        data class Downloading(val percent: Int, val downloadedMb: Long, val totalMb: Long) : State()
        data class ReadyToInstall(val info: ReleaseInfo, val apkFile: File) : State()
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

    private fun lastSeenTag(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LAST_SEEN, "")!!

    /** 检查最新版本；有新版返回 Available（含 APK 直链），否则 UpToDate */
    suspend fun check(context: Context): State = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        try {
            val conn = URL(LATEST_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val root = org.json.JSONObject(body)
            val tag = root.optString("tag_name", "")
            val published = root.optString("published_at", "")
            var apkUrl = ""
            var sizeMb = 0L
            val assets = root.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name", "").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", "")
                        sizeMb = a.optLong("size", 0L) / (1024 * 1024)
                        break
                    }
                }
            }
            if (tag.isEmpty() || apkUrl.isEmpty()) {
                val st = State.Failed("Release 中未找到 APK")
                _state.value = st
                return@withContext st
            }

            val st = if (tag != lastSeenTag(context) && hasNewerBuild(tag, lastSeenTag(context))) {
                State.Available(ReleaseInfo(tag, apkUrl, sizeMb, published))
            } else {
                State.UpToDate
            }
            _state.value = st
            st
        } catch (t: Throwable) {
            val st = State.Failed(t.message ?: "网络错误")
            _state.value = st
            st
        }
    }

    /** tag 形如 v1.0.N，比较尾号 build 号 */
    private fun hasNewerBuild(latest: String, seen: String): Boolean {
        fun buildOf(tag: String): Int =
            tag.substringAfterLast('.').filter { it.isDigit() }.toIntOrNull() ?: -1
        return buildOf(latest) > buildOf(seen)
    }

    /** 下载最新 APK 到 cacheDir，完成后进入 ReadyToInstall */
    suspend fun download(context: Context, info: ReleaseInfo): Boolean = withContext(Dispatchers.IO) {
        cancelRequested = false
        val out = File(context.cacheDir, "update-${info.tag}.apk")
        var downloaded = if (out.exists() && out.name == "update-${info.tag}.apk") out.length() else 0L
        // tag 变了就重下
        if (out.exists() && out.length() == 0L) downloaded = 0

        try {
            val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val resuming = code == 206
            if (!resuming) downloaded = 0
            val total = if (resuming) downloaded + conn.contentLengthLong else conn.contentLengthLong

            conn.inputStream.use { input ->
                java.io.FileOutputStream(out, resuming).use { fout ->
                    val buf = ByteArray(64 * 1024)
                    var lastEmit = 0L
                    while (true) {
                        if (cancelRequested) {
                            _state.value = State.Idle
                            return@withContext false
                        }
                        val n = input.read(buf)
                        if (n == -1) break
                        fout.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastEmit > 1024 * 1024) {
                            lastEmit = downloaded
                            _state.value = State.Downloading(
                                percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0,
                                downloadedMb = downloaded / (1024 * 1024),
                                totalMb = total / (1024 * 1024)
                            )
                        }
                    }
                }
            }
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_SEEN, info.tag).apply()
            _state.value = State.ReadyToInstall(info, out)
            true
        } catch (t: Throwable) {
            _state.value = State.Failed(t.message ?: "下载失败")
            false
        }
    }

    /** 唤起系统安装器（覆盖安装，应用数据与已下载模型全部保留） */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }
}
