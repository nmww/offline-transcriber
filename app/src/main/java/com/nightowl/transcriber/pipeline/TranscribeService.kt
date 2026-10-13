package com.nightowl.transcriber.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nightowl.transcriber.MainActivity
import com.nightowl.transcriber.data.ExportFormat
import com.nightowl.transcriber.data.SettingsStore
import com.nightowl.transcriber.data.TaskStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 前台服务（LifecycleService）：保证转写在【锁屏/切后台】时继续跑，并通过通知栏反馈进度。
 * 规格书要求：异步后台执行 + 通知栏常驻。
 */
class TranscribeService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "transcribe"
        private const val DONE_CHANNEL_ID = "transcribe_done"
        internal const val NOTI_ID = 1001
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"

        fun start(context: Context, uri: String, displayName: String) {
            val i = Intent(context, TranscribeService::class.java).apply {
                putExtra(EXTRA_URI, uri)
                putExtra(EXTRA_NAME, displayName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            TranscribeBus.cancelRequested = true
            context.stopService(Intent(context, TranscribeService::class.java))
        }
    }

    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId) // LifecycleService 必须回传生命周期事件
        val uriStr = intent?.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY
        val name = intent.getStringExtra(EXTRA_NAME) ?: "未知文件"
        TaskStore.save(this, uriStr, name)   // 落盘：进程被杀重建后据此恢复/清理
        ensureChannel()
        startForeground(NOTI_ID, buildNotification(name, "准备中…", 0))

        lifecycleScope.launch { updateProgressNotifications() }

        job?.cancel()
        job = lifecycleScope.launch {
            val pipeline = TranscribePipeline(this@TranscribeService)
            val settings = SettingsStore(this@TranscribeService)
            val fmt = ExportFormat.from(settings.transcriptStyle.first())
            pipeline.run(android.net.Uri.parse(uriStr), name, fmt)
            notifyFinished()
            stopForeground(true)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** 转写结束提醒：完成/失败各一条高优先级通知（点击打开 App 看结果），取消则清掉进度通知 */
    private fun notifyFinished() {
        val st = TranscribeBus.status.value
        val mgr = getSystemService(NotificationManager::class.java)
        when (st) {
            is Status.Done -> showDone("「${TranscribeBus.fileName.value}」转写完成，点击查看结果", false)
            is Status.Error -> showDone("转写失败：${st.message}", true)
            else -> mgr.cancel(NOTI_ID)   // Cancelled：清掉进度通知，不再残留僵尸
        }
        TaskStore.clear(this)   // 任务结束，清除持久状态
    }

    private fun showDone(text: String, isError: Boolean) {
        runCatching {
            val mgr = getSystemService(NotificationManager::class.java)
            // 安卓不允许升级已建渠道的优先级，完成提醒必须用独立的新渠道才有横幅/声音
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mgr.createNotificationChannel(
                    NotificationChannel(DONE_CHANNEL_ID, "转写完成提醒", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(this, DONE_CHANNEL_ID)
                .setSmallIcon(if (!isError) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle("盘语")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            // 复用进度通知的 ID：结束时替换进度条，不堆两条
            mgr.notify(NOTI_ID, n)
        }
    }

    /** 把 Bus 的进度同步到通知栏 */
    private suspend fun updateProgressNotifications() {
        TranscribeBus.progress.collect { p ->
            val text = if (p >= 1f) "转写完成" else "已转 ${(p * 100).toInt()}%"
            val mgr = getSystemService(NotificationManager::class.java)
            val n = buildNotification(
                "盘语 · 转写中",
                "${TranscribeBus.fileName.value}　$text",
                (p * 100).toInt()
            )
            mgr.notify(NOTI_ID, n)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "转写进度", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, text: String, progress: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, progress, progress in 1..99)
            .setOnlyAlertOnce(true)   // 进度持续刷新时不反复响铃/震动
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        // 兜底清理：正常结束时已在 notifyFinished 清过；此处处理被系统强杀未能走完的情况
        TaskStore.clear(this)
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTI_ID) }
        super.onDestroy()
    }
}
