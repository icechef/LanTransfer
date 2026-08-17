package com.lantransfer.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

// 从 Uri 解析源文件修改时间（Unix 秒），覆盖 file:// 与各类 content/SAF/MediaStore 来源。
// 取不到时回退 0（保持协议「缺 mtime 则不还原」语义）。
//
// 之所以需要单独抽出来：单文件选择（ACTION_OPEN_DOCUMENT / 分享意图）走 resolveFile，
// 部分 content provider 不暴露时间列、file:// scheme 下 contentResolver.query 直接抛异常或返回 null，
// 都会导致 mtime 恒为 0、接收端跳过还原。SAF 目录树（listFolder/listDir）已在游标内读到
// COLUMN_LAST_MODIFIED，无需走这里。
fun resolveFileMtime(ctx: Context, uri: Uri): Long {
    // file:// 直读本地文件（contentResolver.query 对 file scheme 不支持）
    if (uri.scheme == "file") {
        val p = uri.path
        if (p != null) {
            val lm = File(p).lastModified()
            if (lm > 0) return lm / 1000
        }
        return 0L
    }
    return try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                // MediaStore 来源：DATE_MODIFIED（秒）
                val di = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                if (di >= 0 && !c.isNull(di)) {
                    val v = c.getLong(di)
                    if (v > 0) return v
                }
                // SAF document 来源：COLUMN_LAST_MODIFIED（毫秒）
                val li = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (li >= 0 && !c.isNull(li)) {
                    val v = c.getLong(li)
                    if (v > 0) return v / 1000
                }
            }
            0L
        } ?: 0L
    } catch (_: Exception) { 0L }
}
