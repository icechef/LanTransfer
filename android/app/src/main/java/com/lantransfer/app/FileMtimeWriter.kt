package com.lantransfer.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 把源文件修改时间写进「文件系统层」——这是手机文件管理器真正读取的来源。
 *
 * 历史教训（前几次修复全部失败的根因）：
 * 之前只依赖 contentResolver.update() 写 MediaStore 的 DATE_MODIFIED 列。但该列官方明写
 * read-only，且 MediaProvider 在条目处于非 pending 态时会直接丢弃该列写入；更关键的是——
 * 文件管理器 stat() 的是文件系统 mtime，数据库列根本不影响你看到的那个时间。
 * 所以唯一可靠的路径是 File.setLastModified()（我们创建的文件本 App 即 owner，无需权限）
 * 再触发一次扫描让数据库收敛。
 */
object FileMtimeWriter {

    data class Result(val fsOk: Boolean, val dbOk: Boolean, val path: String?)

    /**
     * 把 mtimeSeconds（Unix 秒）还原到 uri 对应文件的文件系统层。
     * 返回文件系统写入是否成功、数据库写入是否成功、解析到的真实路径。
     */
    fun applyMtime(ctx: Context, uri: Uri?, mtimeSeconds: Long): Result {
        if (uri == null) return Result(false, false, null)
        if (mtimeSeconds <= 0) return Result(false, false, null)
        val path = resolveRealPath(ctx, uri)
        var fsOk = false
        var pathUsed: String? = null
        if (path != null) {
            val f = File(path)
            // 改名/落盘后短暂窗口内物理文件可能尚未可见，少量重试避免误判
            var tries = 0
            while (tries < 3 && !f.exists()) {
                try { Thread.sleep(120) } catch (_: Exception) {}
                tries++
            }
            if (f.exists()) {
                val wantMs = mtimeSeconds * 1000
                val wrote = try {
                    f.setLastModified(wantMs)
                } catch (_: Exception) { false }
                val got = f.lastModified()
                // exFAT/FAT 时间精度约 2s，给容差
                if (wrote && abs(got - wantMs) <= 2000) {
                    fsOk = true
                    pathUsed = path
                    // 让 MediaStore 数据库重新 stat() 该文件，把刚写对的 fs mtime 读进 DB，
                    // 避免数据库与文件系统互相打架
                    MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null)
                }
            }
        }
        // 数据库尽力而为（相册类应用读取，对文件管理器无效；非 pending 行可能被丢弃，故仅作补充）
        var dbOk = false
        if (isMediaStoreUri(uri)) {
            try {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.DATE_MODIFIED, mtimeSeconds) }
                dbOk = ctx.contentResolver.update(uri, v, null, null) > 0
            } catch (_: Exception) {}
        }
        return Result(fsOk, dbOk, pathUsed)
    }

    private fun isMediaStoreUri(uri: Uri): Boolean {
        val s = uri.toString()
        return s.contains("media/external") || s.contains("media/internal") ||
                (uri.authority?.endsWith("media") == true)
    }

    // 把 content/SAF/MediaStore uri 解析为真实文件系统路径
    private fun resolveRealPath(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        // SAF（外部存储 primary 卷等）→ 映射为真实路径
        if (uri.authority == "com.android.externalstorage.documents") {
            return try {
                mapDocIdToPath(DocumentsContract.getDocumentId(uri))
            } catch (_: Exception) { null }
        }
        // MediaStore：优先读 _data 列
        try {
            val p = queryColumn(ctx, uri, MediaStore.MediaColumns.DATA)
            if (!p.isNullOrEmpty()) return p
        } catch (_: Exception) {}
        // 回退：RELATIVE_PATH + DISPLAY_NAME 拼接（我们自己的文件这两个值确定）
        try {
            val rel = queryColumn(ctx, uri, MediaStore.MediaColumns.RELATIVE_PATH)
            val name = queryColumn(ctx, uri, MediaStore.MediaColumns.DISPLAY_NAME)
            if (!rel.isNullOrEmpty() && !name.isNullOrEmpty()) {
                return "${Environment.getExternalStorageDirectory().absolutePath}/$rel/$name"
            }
        } catch (_: Exception) {}
        return null
    }

    private fun queryColumn(ctx: Context, uri: Uri, col: String): String? {
        return try {
            ctx.contentResolver.query(uri, arrayOf(col), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(col)
                    if (i >= 0 && !c.isNull(i)) c.getString(i) else null
                } else null
            }
        } catch (_: Exception) { null }
    }

    // com.android.externalstorage.documents 的 docId 形如 "primary:Download/foo.txt"
    // 或 "volumeId:some/path" → 映射为 /storage/... 真实路径
    private fun mapDocIdToPath(docId: String): String? {
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return null
        val volume = parts[0]
        val rest = parts[1].trimStart('/')
        return if (volume == "primary") {
            "${Environment.getExternalStorageDirectory().absolutePath}/$rest"
        } else {
            "/storage/$volume/$rest"
        }
    }

    fun formatDate(sec: Long): String {
        return if (sec <= 0) "未知" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(sec * 1000))
    }

    /**
     * 设备端自检：在 Downloads 建临时文件 → 用固定时间戳（2020-01-01）跑 applyMtime →
     * 回读 fs 与 DB → 删除临时文件 → 返回报告。
     * 让你一次就能看到本机文件系统写入到底成功与否，不必再靠一轮轮试。
     */
    fun selfTest(ctx: Context): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "lantransfer_mtime_test.tmp")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return "自检失败：无法创建测试文件（存储不可用？）"
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write("lantransfer self-test".toByteArray()) }
            val fixed = 1577836800L // 2020-01-01 00:00:00 UTC
            ctx.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            val res = applyMtime(ctx, uri, fixed)
            var dbMtime = -1L
            try {
                ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { c ->
                    if (c.moveToFirst()) dbMtime = c.getLong(0)
                }
            } catch (_: Exception) {}
            // 清理临时文件
            try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            val manageHint = if (!res.fsOk)
                "\n\n文件系统写入失败：可点「请求所有文件访问权限」授权后重试。"
            else ""
            buildString {
                append("文件系统写入：${if (res.fsOk) "成功 ✅" else "失败 ❌"}\n")
                append("数据库写入：${if (res.dbOk) "成功" else "失败/被忽略"}\n")
                append("文件路径：${res.path ?: "未解析"}\n")
                append("期望日期：${formatDate(fixed)}\n")
                append("回读数据库日期：${formatDate(dbMtime)}")
                append(manageHint)
            }
        } catch (e: Exception) {
            try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            "自检异常：${e.message}"
        }
    }
}
