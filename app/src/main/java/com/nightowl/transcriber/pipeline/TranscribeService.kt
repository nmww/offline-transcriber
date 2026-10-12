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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 前台服务（LifecycleService）：保证转写在【锁屏/切后台】时继续跑，并通过通知栏反馈进度。
 * 规格书要求：异步后台执行 + 通知栏常驻。
 */
class TranscribeService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "transcribe"
        private const val NOTI_ID = 1001
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
        ensureChannel()
        startForeground(NOTI_ID, buildNotification(name, "准备中…", 0))

        lifecycleScope.launch { updateProgressNotifications() }

        job?.cancel()
        job = lifecycleScope.launch {
            val pipeline = TranscribePipeline(this@TranscribeService)
            val settings = SettingsStore(this@TranscribeService)
            val fmt = ExportFormat.from(settings.transcriptStyle.first())
            pipeline.run(android.net.Uri.parse(uriStr), name, fmt)
            stopForeground(true)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** 把 Bus 的进度同步到通知栏 */
    private suspend fun updateProgressNotifications() {
        TranscribeBus.progress.collect { p ->
            val text = if (p >= 1f) "转写完成" else "已转 ${(p * 100).toInt()}%"
            val mgr = getSystemService(NotificationManager::class.java)
            val n = buildNotification(TranscribeBus.fileName.value, text, (p * 100).toInt())
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
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}
