package com.lantransfer.app

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File

// 从 Uri 解析源文件修改时间（Unix 秒），覆盖 file:// 与各类 content/SAF/MediaStore 来源。
// 取不到时回退 0（保持协议「缺 mtime 则不还原」语义）。
//
// 关键坑（本次修复）：单文件选择（ACTION_OPEN_DOCUMENT / 分享意图）走 resolveFile，
// 旧实现用 query(uri, null, ...) 的「null 投影」。多数 DocumentsProvider 在 null 投影下
// 不返回 COLUMN_LAST_MODIFIED，且 MediaStore 的 DATE_MODIFIED 对这类单文档 URI 常为 0，
// 于是 mtime 恒为 0、接收端跳过还原。SAF 目录树（listFolder）显式投影 COLUMN_LAST_MODIFIED
// 所以能取到——这就是「手机→PC指定文件夹✅、手机→手机❌」同文件两种结果的根源。
//
// 修复：① 改用显式投影，强制 Provider 返回时间列；② 增加 _data 真实路径兜底（最可靠）；
// ③ 增加 DocumentsProvider 文档树回退（getDocumentId→buildDocumentUri 再查）。
// 优先顺序：COLUMN_LAST_MODIFIED(ms) > DATE_MODIFIED(秒) > DATA 路径 > 0。
fun resolveFileMtime(ctx: Context, uri: Uri): Long {
    fun readFrom(c: Cursor): Long {
        if (!c.moveToFirst()) return 0L
        // 1) COLUMN_LAST_MODIFIED（毫秒，SAF/MediaStore 公开文档）
        val li = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        if (li >= 0 && !c.isNull(li)) {
            val v = c.getLong(li)
            if (v > 0) return v / 1000
        }
        // 2) DATE_MODIFIED（秒，MediaStore 旧列）
        val di = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        if (di >= 0 && !c.isNull(di)) {
            val v = c.getLong(di)
            if (v > 0) return v
        }
        // 3) DATA 真实路径兜底（最可靠，绕开 Provider 的时间列缺失）
        val dti = c.getColumnIndex(MediaStore.MediaColumns.DATA)
        if (dti >= 0 && !c.isNull(dti)) {
            val path = c.getString(dti)
            if (!path.isNullOrEmpty()) {
                val lm = File(path).lastModified()
                if (lm > 0) return lm / 1000
            }
        }
        return 0L
    }

    // file:// 直读本地文件（contentResolver.query 对 file scheme 不支持）
    if (uri.scheme == "file") {
        val p = uri.path
        if (p != null) {
            val lm = File(p).lastModified()
            if (lm > 0) {
                val r = lm / 1000
                Log.d("LanTransfer", "resolveFileMtime file=$p -> $r")
                return r
            }
        }
        return 0L
    }

    val projection = arrayOf(
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.DATE_MODIFIED,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    // 先查原始 URI（显式投影）
    val direct = try {
        ctx.contentResolver.query(uri, projection, null, null, null)?.use { readFrom(it) } ?: 0L
    } catch (_: Exception) { 0L }
    if (direct > 0) {
        Log.d("LanTransfer", "resolveFileMtime uri=$uri -> $direct (direct)")
        return direct
    }

    // 文档树回退：单文档 content URI 经 getDocumentId 重建 URI 再查，更易取到 LAST_MODIFIED
    try {
        val docId = DocumentsContract.getDocumentId(uri)
        val docUri = DocumentsContract.buildDocumentUri(uri.authority, docId)
        val via = ctx.contentResolver.query(docUri, projection, null, null, null)?.use { readFrom(it) } ?: 0L
        if (via > 0) {
            Log.d("LanTransfer", "resolveFileMtime uri=$uri -> $via (tree-fallback)")
            return via
        }
    } catch (_: Exception) { /* 非 DocumentsProvider：忽略 */ }

    Log.d("LanTransfer", "resolveFileMtime uri=$uri -> 0 (failed)")
    return 0L
}
