package com.nightowl.transcriber.data

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.nightowl.transcriber.engine.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 排版三选一（规格书 3.4 / 4.5） */
enum class ExportFormat(val label: String, val code: String) {
    DIALOG("对话语段式", "dialog"),
    PLAIN("纯文本连排", "plain"),
    LINE_BY_LINE("逐句一行", "line");

    companion object {
        fun from(code: String): ExportFormat = values().firstOrNull { it.code == code } ?: DIALOG
    }
}

/**
 * 导出器：把 VAD+ASR 产出的语段序列，落成
 *   ① 原名.转写.txt（按所选排版）
 *   ② 原名.srt（标准 SubRip 字幕，ASR 的时间戳副产物）
 */
object Exporter {

    /** 生成 txt 正文 */
    fun format(segments: List<Segment>, fmt: ExportFormat, showTimestamps: Boolean = true): String {
        return when (fmt) {
            ExportFormat.DIALOG -> buildString {
                for (s in segments) {
                    if (showTimestamps) {
                        append("【${ts(s.startMs, true)} - ${ts(s.endMs, true)}】\n")
                    }
                    append(s.text).append("\n\n")
                }
            }.trim()

            ExportFormat.PLAIN -> segments.joinToString("\n") { it.text }

            ExportFormat.LINE_BY_LINE -> buildString {
                for (s in segments) {
                    val sentences = splitSentences(s.text)
                    if (sentences.isEmpty()) appendLine(s.text) else sentences.forEach { appendLine(it) }
                }
            }.trim()
        }
    }

    /** 生成 srt 字幕内容 */
    fun toSrt(segments: List<Segment>): String = buildString {
        var i = 1
        for (s in segments) {
            appendLine(i++)
            appendLine("${ts(s.startMs, false)} --> ${ts(s.endMs, false)}")
            appendLine(s.text)
            appendLine()
        }
    }.trim()

    private fun ts(ms: Long, compact: Boolean): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        return if (compact) {
            // 对话语段排版：一小时以内用 mm:ss，更清爽（规格书 3.4 示例样式）
            if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.US, "%02d:%02d", m, s)
        } else {
            val millis = ms % 1000
            String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, millis)
        }
    }

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[。！？!?；;])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 文件命名：我的课.mp4 → 我的课.转写.txt / 我的课.srt */
    fun outputNames(displayName: String): Pair<String, String> {
        val base = displayName.substringBeforeLast('.')
        return Pair("$base.转写.txt", "$base.srt")
    }

    /**
     * 落盘主入口：优先【原媒体同目录】，失败回退公共 Documents/离线转写/。
     * 同时把记录写入 Room，供首页“最近转写”回看。
     * @return 实际保存目录（用于界面提示）
     */
    suspend fun export(
        context: Context,
        mediaUri: Uri,
        displayName: String,
        segments: List<Segment>,
        fmt: ExportFormat,
        showTimestamps: Boolean = true,
        durationMs: Long = 0
    ): String = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext ""
        val (txtName, srtName) = outputNames(displayName)
        val txtContent = format(segments, fmt, showTimestamps)
        val srtContent = toSrt(segments)

        // ① 同目录（默认行为）
        val sameDir = SameDirResolver.resolveParentDir(context, mediaUri)
        var saved = sameDir?.let { dir ->
            val okTxt = FileWriter.writeText(context, dir, txtName, txtContent)
            val okSrt = FileWriter.writeText(context, dir, srtName, srtContent)
            okTxt || okSrt
        } ?: false
        var savedDir: String? = sameDir?.absolutePath
        SameDirResolver.rememberDir(context, sameDir)

        // ② 上次成功目录
        if (!saved) {
            val last = SameDirResolver.lastDir(context)
            if (last != null) {
                saved = FileWriter.writeText(context, last, txtName, txtContent)
                if (saved) savedDir = last.absolutePath
            }
        }

        // ③ MediaStore 公共 Documents
        if (!saved) {
            val ok = FileWriter.writeViaMediaStore(context, txtName, txtContent)
            savedDir = if (ok) "Documents/离线转写" else null
        }

        // 入库（供历史回看）
        AppDatabase.get(context).transcriptDao().insert(
            Transcript(
                fileName = displayName,
                durationMs = durationMs,
                charCount = txtContent.length,
                createdAt = System.currentTimeMillis(),
                savePath = savedDir ?: "未知位置",
                text = txtContent
            )
        )

        val human = savedDir ?: "未知位置"
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "已保存至：$human", Toast.LENGTH_SHORT).show()
        }
        human
    }
}
