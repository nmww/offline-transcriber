package com.nightowl.transcriber.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 输出路径解析：让结果文件【生成在视频/音频的同级目录】。
 *
 * Android 的 SAF content:// Uri 无法直接拿父目录，因此做多轮解析：
 *   1) content:// 各厂商 Provider → 真实文件路径 → 同目录写 File
 *   2) file:// 直接取父目录
 *   3) 均失败 → 回退公共 Documents/离线转写/，并 toast 告知（见 Exporter）
 */
object SameDirResolver {

    private const val PREF = "savedir"
    private const val KEY_LAST_DIR = "last_dir"

    /** 尝试把 Document Uri 解析成可写的同级目录 File */
    fun resolveParentDir(context: Context, mediaUri: Uri): File? {
        val path = resolvePath(context, mediaUri) ?: return null
        val f = File(path)
        val parent = f.parentFile ?: return null
        return if (parent.exists() && parent.canWrite()) parent else null
    }

    /** 各 Provider → 真实路径解析（已把主流 Download / ExternalStorage / Media Provider 覆盖） */
    private fun resolvePath(context: Context, uri: Uri): String? {
        when {
            DocumentsContract.isDocumentUri(context, uri) -> {
                val docId = DocumentsContract.getDocumentId(uri)
                when (uri.authority) {
                    "com.android.externalstorage.documents" -> {
                        // type:id → /storage/type/id
                        val parts = docId.split(":")
                        if (parts.size >= 2) {
                            val storage = "/storage/${parts[0]}/${parts[1]}"
                            if (File(storage).exists()) return storage
                        }
                    }
                    "com.android.providers.downloads.documents" -> {
                        if (docId.startsWith("raw:")) return docId.removePrefix("raw:")
                        return queryString(context, uri, MediaStore.MediaColumns.DATA)
                    }
                    "com.android.providers.media.documents" -> {
                        val parts = docId.split(":")
                        if (parts.size >= 2) {
                            val cUri = MediaStore.Files.getContentUri(parts[0])
                            return queryString(context, cUri, MediaStore.MediaColumns.DATA, parts[1])
                        }
                    }
                }
            }
            "content".equals(uri.scheme, true) -> return queryString(context, uri, MediaStore.MediaColumns.DATA)
            "file".equals(uri.scheme, true) -> return uri.path
        }
        return null
    }

    private fun queryString(
        context: Context,
        uri: Uri,
        column: String,
        idClause: String? = null
    ): String? {
        return try {
            val sel = if (idClause != null) "_id=?" else null
            val args = if (idClause != null) arrayOf(idClause) else null
            context.contentResolver.query(uri, arrayOf(column), sel, args, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 记住用户上一次成功保存的目录，作为下次回退目录 */
    fun rememberDir(context: Context, dir: File?) {
        if (dir == null) return
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit { putString(KEY_LAST_DIR, dir.absolutePath) }
    }

    fun lastDir(context: Context): File? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LAST_DIR, null)
        return p?.let { File(it).takeIf { f -> f.exists() && f.canWrite() } }
    }

    /** 公共回退目录 Documents/离线转写/ */
    fun fallbackDir(): File =
        File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS), "离线转写").apply { mkdirs() }
}

/**
 * 同目录写出的实际执行：优先 File 写；失败时用 MediaStore 写入公共 Documents。
 */
internal object FileWriter {
    suspend fun writeText(context: Context, dir: File, fileName: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val out = File(dir, fileName)
                out.writeText(content, Charsets.UTF_8)
                true
            } catch (t: Throwable) {
                false
            }
        }

    suspend fun writeViaMediaStore(context: Context, displayName: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/离线转写")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return@withContext false
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                true
            } catch (t: Throwable) {
                false
            }
        }
}
