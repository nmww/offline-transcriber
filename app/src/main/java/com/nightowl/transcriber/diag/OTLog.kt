package com.nightowl.transcriber.diag

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志与异常管理。
 *
 * 能力：
 *  ① 应用内 logcat 镜像 —— 后台线程把本进程的 logcat 持续写入 files/logs/app.log
 *     （轮转：单个 2MB，最多保留 3 份），Java 层任何 Log.i/OTDiag 面包屑都会留下痕迹
 *  ② Java 未捕获异常 —— 写入「崩溃-时间.log」（堆栈 + 设备信息 + 事故前 100 行应用日志）
 *  ③ Native 层静默退出（如 sherpa-onnx exit(-1)）—— 无法在本进程捕获，但 .session 标记
 *     会在下次启动时发现「上次会话未正常收尾」，自动把上次日志快照存为「异常退出-时间.log」
 *  ④ 设置页可查看 / 分享 / 清空全部日志（FileProvider 分享，无需任何存储权限）
 *
 * 所有日志只存在应用私有目录，不上传任何数据。
 */
object OTLog {

    private const val TAG = "OTLog"
    private const val MAX_LOG_BYTES = 2L * 1024 * 1024
    private const val MAX_ROTATIONS = 3          // app.log.1 ~ app.log.2
    private const val SNAPSHOT_BYTES = 256L * 1024
    private const val MAX_CRASH_FILES = 10       // 崩溃/异常退出报告最多保留 10 份

    private lateinit var dir: File
    private var appVersion = "unknown"
    private val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA)

    fun logDir(context: Context): File = File(context.filesDir, "logs")

    /** App 启动时调用一次 */
    fun init(context: Context) {
        dir = logDir(context)
        dir.mkdirs()
        appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        val appLog = File(dir, "app.log")

        // ① 上次会话收尾检测：.session 存在 = 上次没有走到正常关闭（含 native exit / 被杀）
        val session = File(dir, ".session")
        if (session.exists()) {
            runCatching {
                val tail = tailOf(appLog, SNAPSHOT_BYTES)
                if (tail.isNotBlank()) {
                    val prev = session.readText().trim()
                    File(dir, "异常退出-${prev.ifBlank { fmt.format(Date()) }}.log")
                        .writeText("上次会话未正常结束（进程被杀或 native 退出）\n===== 上次会话日志尾部 =====\n$tail\n")
                    trimReports()
                }
                session.delete()
            }
        }
        // ② 本会话标记（正常路径由 activityDestroyed 清除；异常退出则留给下次启动发现）
        runCatching {
            session.writeText(fmt.format(Date()) + "-pid" + android.os.Process.myPid())
        }

        // ③ logcat 镜像线程
        startLogcatMirror(appLog)

        // ④ 未捕获异常守卫
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(dir, "崩溃-${fmt.format(Date())}.log").writeText(
                    "时间: ${Date()}\n线程: ${t.name}\n版本: $appVersion\n设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}\n\n" +
                        "===== 堆栈 =====\n$sw\n\n===== 事故前应用日志（末 100 行）=====\n" +
                        tailOf(File(dir, "app.log"), SNAPSHOT_BYTES).split('\n').takeLast(100).joinToString("\n") + "\n"
                )
                trimReports()
            }
            prev?.uncaughtException(t, e)
        }

        i(TAG, "OTLog init ok, dir=$dir")
    }

    /** 应用完全退出时调用（尽量收尾；收不到也不影响——下次启动会兜底快照） */
    fun onAppExit() {
        runCatching { File(dir, ".session").delete() }
    }

    // ---------- 写日志 ----------

    fun i(tag: String, msg: String) { android.util.Log.i(tag, msg); appendFile("I/$tag: $msg") }
    fun w(tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.w(tag, msg, t); appendFile("W/$tag: $msg ${t?.let { android.util.Log.getStackTraceString(it) } ?: ""}")
    }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.e(tag, msg, t); appendFile("E/$tag: $msg ${t?.let { android.util.Log.getStackTraceString(it) } ?: ""}")
    }

    private fun appendFile(line: String) {
        if (!::dir.isInitialized) return
        runCatching {
            val f = File(dir, "app.log")
            if (f.length() > MAX_LOG_BYTES) rotate()
            f.appendText("${fmt.format(Date())} $line\n")
        }
    }

    private fun rotate() {
        runCatching {
            File(dir, "app.log.${MAX_ROTATIONS - 1}").delete()
            for (i in MAX_ROTATIONS - 2 downTo 1) {
                File(dir, "app.log.$i").renameTo(File(dir, "app.log.${i + 1}"))
            }
            File(dir, "app.log").renameTo(File(dir, "app.log.1"))
        }
    }

    // ---------- 镜像线程 ----------

    @Volatile private var mirroring = false

    private fun startLogcatMirror(appLog: File) {
        if (mirroring) return
        mirroring = true
        Thread {
            try {
                // 应用只能读到本 UID 的日志；--pid 进一步收窄到本进程（Android 7+）
                val pb = if (Build.VERSION.SDK_INT >= 24)
                    ProcessBuilder("logcat", "-v", "time", "--pid=${android.os.Process.myPid()}")
                else
                    ProcessBuilder("logcat", "-v", "time")
                val pid = android.os.Process.myPid().toString()
                val p = pb.start()
                p.inputStream.bufferedReader().useLines { lines ->
                    for (l in lines) {
                        if (Build.VERSION.SDK_INT < 24 && !l.contains(" $pid ")) continue
                        runCatching {
                            if (appLog.length() > MAX_LOG_BYTES) rotate()
                            appLog.appendText(l + "\n")
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                mirroring = false
            }
        }.apply { isDaemon = true; name = "ot-logcat-mirror" }.start()
    }

    // ---------- 查询 / 清理（供设置页） ----------

    /** 异常报告（崩溃-*.log / 异常退出-*.log），按时间倒序 */
    fun reportFiles(): List<File> =
        if (!::dir.isInitialized) emptyList()
        else dir.listFiles { f -> f.name.startsWith("崩溃-") || f.name.startsWith("异常退出-") }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun clearAll(context: Context) {
        runCatching {
            logDir(context).listFiles()?.forEach { if (it.name != ".session") it.delete() }
        }
    }

    private fun trimReports() {
        runCatching {
            reportFiles().drop(MAX_CRASH_FILES).forEach { it.delete() }
        }
    }

    private fun tailOf(f: File, max: Long): String {
        if (!f.exists() || f.length() <= max) return if (f.exists()) f.readText() else ""
        val buf = ByteArray(max.toInt())
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(f.length() - max)
            raf.readFully(buf)
        }
        // 跳过可能被截断的首行
        return String(buf, Charsets.UTF_8).substringAfter('\n', missingDelimiterValue = "")
    }
}
