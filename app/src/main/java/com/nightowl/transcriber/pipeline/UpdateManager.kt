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
 * 应用内热更新：检查新版本 → 应用内下载新 APK → 唤起系统安装。
 *
 * 提速要点：
 * - 查版本不再直连 GitHub API（手机直连慢且易超时），改走国内加速镜像拉取
 *   Release 里的 latest.json（仅几百字节，秒回）；全部失败再回退 GitHub API
 * - APK 下载同样多镜像竞速排序（gh-proxy.com 实测最快）+ 断点续传
 *
 * - 模型文件存放在应用内部存储（filesDir）+ 公共 Download/离线转写/ 双备份，
 *   覆盖安装【不会】清除；卸载重装后模型也会从公共区自动恢复，无需重新下载
 * - 版本比较：tag 形如 v1.0.N，N 与 APK versionCode 一致（CI 注入），单调递增
 */
object UpdateManager {

    /** latest.json 直链（release asset，无需 GitHub API）；releases/latest/download 自动指向最新一版 */
    private val VERSION_PROBES = listOf(
        "https://gh-proxy.com/https://github.com/nmww/offline-transcriber/releases/latest/download/latest.json",
        "https://ghfast.top/https://github.com/nmww/offline-transcriber/releases/latest/download/latest.json",
        "https://ghproxy.net/https://github.com/nmww/offline-transcriber/releases/latest/download/latest.json",
    )

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

    /** 本机已安装的 versionCode */
    private fun installedBuild(context: Context): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode
        }
    } catch (_: Throwable) {
        -1
    }

    /** 检查最新版本；有新版返回 Available（含 APK 直链），否则 UpToDate */
    suspend fun check(context: Context): State = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        try {
            // 先走镜像 latest.json（快），失败回退 GitHub API
            val info = probeLatestJson() ?: probeLatestApi()

            if (info == null || info.tag.isEmpty() || info.apkUrl.isEmpty()) {
                val st = State.Failed("暂时查不到新版本（网络受限）")
                _state.value = st
                return@withContext st
            }

            val seen = lastSeenTag(context)
            val latestBuild = buildOf(info.tag)
            val st = if (latestBuild > installedBuild(context) && latestBuild > buildOf(seen)) {
                State.Available(info)
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

    /** tag 形如 v1.0.N，取尾号 build 号 */
    private fun buildOf(tag: String): Int =
        tag.substringAfterLast('.').filter { it.isDigit() }.toIntOrNull() ?: -1

    /** 通过镜像拉 latest.json（几百字节，秒回） */
    private fun probeLatestJson(): ReleaseInfo? {
        for (url in VERSION_PROBES) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 10_000
                conn.instanceFollowRedirects = true
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val obj = org.json.JSONObject(body)
                val tag = obj.optString("tag", "")
                if (tag.isEmpty()) continue
                val apkName = obj.optString("apk", "OfflineTranscriber-$tag.apk")
                val apkUrl = "https://github.com/nmww/offline-transcriber/releases/download/$tag/$apkName"
                return ReleaseInfo(tag, apkUrl, obj.optLong("sizeMb", 0L), obj.optString("publishedAt", ""))
            } catch (_: Throwable) {
                // 换下一个镜像
            }
        }
        return null
    }

    /** 回退：GitHub Releases API */
    private fun probeLatestApi(): ReleaseInfo? {
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
            if (tag.isEmpty() || apkUrl.isEmpty()) return null
            return ReleaseInfo(tag, apkUrl, sizeMb, published)
        } catch (_: Throwable) {
            return null
        }
    }

    /** 国内加速代理（实测速度排序），最后一个是 GitHub 原始直链兜底 */
    private fun apkSources(apkUrl: String): List<String> = listOf(
        "https://gh-proxy.com/$apkUrl",
        "https://ghfast.top/$apkUrl",
        "https://ghproxy.net/$apkUrl",
        apkUrl
    )

    /** 下载最新 APK 到 cacheDir（多源 fallback + 断点续传），完成后进入 ReadyToInstall */
    suspend fun download(context: Context, info: ReleaseInfo): Boolean = withContext(Dispatchers.IO) {
        cancelRequested = false
        val out = File(context.cacheDir, "update-${info.tag}.apk")
        var downloaded = if (out.exists()) out.length() else 0L

        for (src in apkSources(info.apkUrl)) {
            if (cancelRequested) return@withContext false
            try {
                if (downloadFrom(src, out)) {
                    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .edit().putString(KEY_LAST_SEEN, info.tag).apply()
                    _state.value = State.ReadyToInstall(info, out)
                    return@withContext true
                }
            } catch (t: Throwable) {
                // 换下一个源（已下载字节保留，支持跨源续传）
            }
        }
        _state.value = if (cancelRequested) State.Idle else State.Failed("所有下载源均失败，请稍后重试")
        false
    }

    private fun downloadFrom(source: String, out: File): Boolean {
        var downloaded = if (out.exists()) out.length() else 0L
        val conn = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
        }
        try {
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
                        if (cancelRequested) return false
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
            return downloaded > 0
        } finally {
            conn.disconnect()
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
