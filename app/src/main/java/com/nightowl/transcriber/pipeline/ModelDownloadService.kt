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
import kotlinx.coroutines.launch

/**
 * 模型下载前台服务：锁屏继续下载，通知栏进度，完成后发「模型就绪」提醒。
 */
class ModelDownloadService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "model_download"
        private const val NOTI_PROGRESS = 2001
        private const val NOTI_DONE = 2002

        fun start(context: Context) {
            val i = Intent(context, ModelDownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureChannel()
        startForeground(NOTI_PROGRESS, progressNotification("准备下载…", 0, false))

        lifecycleScope.launch {
            // 先启动状态收集（进度实时刷通知），再开始下载
            launch {
                ModelDownloader.state.collect { st ->
                    when (st) {
                        is ModelDownloader.State.Downloading -> {
                            val mgr = getSystemService(NotificationManager::class.java)
                            mgr.notify(
                                NOTI_PROGRESS,
                                progressNotification(
                                    "已下载 ${st.downloadedMB}MB / ${st.totalMB}MB",
                                    (st.fileProgress * 100).toInt(), true
                                )
                            )
                        }
                        is ModelDownloader.State.Done -> {
                            notifyDone(ok = true)
                            stopForeground(true)
                            stopSelf()
                            return@collect
                        }
                        is ModelDownloader.State.Failed -> {
                            notifyDone(ok = false, reason = st.reason)
                            stopForeground(true)
                            stopSelf()
                            return@collect
                        }
                        else -> {}
                    }
                }
            }
            ModelDownloader.download(this@ModelDownloadService)
        }
        return START_NOT_STICKY
    }

    private fun notifyDone(ok: Boolean, reason: String = "") {
        val mgr = getSystemService(NotificationManager::class.java)
        val title = if (ok) "模型下载完成 ✅" else "模型下载失败"
        val text = if (ok) "识别模型已就绪，现在可以开始转写了" else "原因：$reason。可在设置中重试（支持断点续传）"
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        mgr.notify(NOTI_DONE, n)
    }

    private fun progressNotification(text: String, percent: Int, ongoing: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载识别模型")
            .setContentText(text)
            .setProgress(100, percent, !ongoing)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "模型下载", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
