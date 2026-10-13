package com.nightowl.transcriber.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 当前进行中转写任务（仅保留一个）的磁盘持久化。
 *
 * 用途：TranscribeBus 是内存单例，一旦 App 进程被系统（如 ColorOS「应用速冻」）杀死重建，
 * 进度/状态全部归零，导致「通知栏还显示转写中、App 里却看不到任务」的不一致。
 * 这里把「正在转写哪个文件」落盘，App 重启时据此恢复或清理僵尸通知。
 */
object TaskStore {
    private const val NAME = "active_task"
    private const val K_URI = "uri"
    private const val K_NAME = "name"
    private const val K_START = "start"

    data class ActiveTask(val uri: String, val name: String, val startMs: Long)

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun save(ctx: Context, uri: String, name: String) {
        sp(ctx).edit()
            .putString(K_URI, uri)
            .putString(K_NAME, name)
            .putLong(K_START, System.currentTimeMillis())
            .apply()
    }

    fun load(ctx: Context): ActiveTask? {
        val s = sp(ctx)
        val uri = s.getString(K_URI, null) ?: return null
        return ActiveTask(uri, s.getString(K_NAME, "") ?: "", s.getLong(K_START, 0L))
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().clear().apply()
    }
}
