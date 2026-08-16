package com.lantransfer.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

// 统一接收落盘：
//  - 自动接收开：直接写 MediaStore Downloads（IS_PENDING 原子写）或用户授权的 SAF 目录树。
//  - 自动接收关：先写到 app 缓存暂存，完成后通知里给「保存/拒绝」；保存时再拷贝到目标目录。
// SAF 部分直接用框架 DocumentsContract 实现，避免引入额外依赖。
object ReceiveStorage {

    class Target(
        val uri: Uri?,             // 最终 uri（pending 暂存时为 null）
        val out: OutputStream,
        val name: String,
        val relDir: String,
        val pending: Boolean,
        val cacheFile: File?,      // pending 时的暂存文件
        val saf: Boolean
    ) {
        // 落盘完成：MediaStore 清除 pending 标记（SAF 无此概念）
        fun commit(ctx: Context) {
            if (!saf && uri != null) {
                try {
                    val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    ctx.contentResolver.update(uri, v, null, null)
                } catch (_: Exception) {}
            }
        }

        // 失败/放弃：关闭流并删除已写内容
        fun discard(ctx: Context) {
            try { out.close() } catch (_: Exception) {}
            if (pending) {
                try { cacheFile?.delete() } catch (_: Exception) {}
            } else if (uri != null) {
                try {
                    if (saf) DocumentsContract.deleteDocument(ctx.contentResolver, uri)
                    else ctx.contentResolver.delete(uri, null, null)
                } catch (_: Exception) {}
            }
        }

    }

    // 待确认保存：把暂存文件拷贝到目标目录，返回最终 uri
    fun savePending(ctx: Context, cachePath: String, name: String, relDir: String): Uri? {
        val src = File(cachePath)
        if (!src.exists()) return null
        return try {
            val dst = open(ctx, name, relDir, false) ?: return null
            src.inputStream().use { input -> dst.out.use { o -> input.copyTo(o) } }
            dst.commit(ctx)
            val u = dst.uri
            src.delete()
            u
        } catch (_: Exception) { null }
    }

    fun rejectPending(cachePath: String) {
        try { File(cachePath).delete() } catch (_: Exception) {}
    }

    fun open(ctx: Context, name: String, relDir: String, pending: Boolean): Target? {
        val safeName = safeName(name)
        val rel = safeRel(relDir)

        if (pending) {
            val dir = File(ctx.cacheDir, "pending")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, System.nanoTime().toString() + "_" + safeName)
            return try {
                Target(null, f.outputStream(), safeName, rel, true, f, false)
            } catch (_: Exception) { null }
        }

        if (SettingsStore.usingSaf(ctx)) {
            return openSaf(ctx, safeName, rel)
        }
        return openMediaStore(ctx, safeName, rel)
    }

    private fun openMediaStore(ctx: Context, name: String, rel: String): Target? {
        return try {
            val base = Environment.DIRECTORY_DOWNLOADS
            val sub = SettingsStore.receiveSubDir(ctx).trim()
            val baseRel = if (sub.isEmpty()) base else "$base/$sub"
            val fullRel = if (rel.isEmpty()) baseRel else "$baseRel/$rel"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, fullRel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            val o = ctx.contentResolver.openOutputStream(uri)
                ?: run { ctx.contentResolver.delete(uri, null, null); return null }
            Target(uri, o, name, rel, false, null, false)
        } catch (_: Exception) { null }
    }

    private fun openSaf(ctx: Context, name: String, rel: String): Target? {
        return try {
            val treeUri = Uri.parse(SettingsStore.safTreeUri(ctx))
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val dirId = ensureDir(ctx, treeUri, rootId, rel) ?: return null
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirId)
            val docUri = DocumentsContract.createDocument(
                ctx.contentResolver, dirUri, "application/octet-stream", name
            ) ?: return null
            val o = ctx.contentResolver.openOutputStream(docUri)
                ?: run { DocumentsContract.deleteDocument(ctx.contentResolver, docUri); return null }
            Target(docUri, o, name, rel, false, null, true)
        } catch (_: Exception) { null }
    }

    // 在 SAF 树内逐级确保子目录存在，返回最深目录的 document id
    private fun ensureDir(ctx: Context, treeUri: Uri, rootId: String, rel: String): String? {
        var curId = rootId
        for (part in rel.split('/')) {
            if (part.isEmpty()) continue
            val childId = findChild(ctx, treeUri, curId, part)
                ?: run {
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, curId)
                    val created = DocumentsContract.createDocument(
                        ctx.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, part
                    ) ?: return null
                    DocumentsContract.getDocumentId(created)
                }
            curId = childId
        }
        return curId
    }

    private fun findChild(ctx: Context, treeUri: Uri, parentId: String, name: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        ctx.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == name) return c.getString(idIdx)
            }
        }
        return null
    }

    private fun safeName(name: String): String {
        val n = name.replace('\\', '_').replace('/', '_').trim()
        return if (n.isEmpty() || n == "." || n == "..") "unnamed" else n
    }

    // 相对路径归一化：去掉 .. / 绝对路径等危险成分（对齐电脑端 safeRelPath）
    private fun safeRel(p: String): String {
        val out = mutableListOf<String>()
        for (part in p.replace('\\', '/').split('/')) {
            if (part.isEmpty() || part == "." || part == "..") continue
            out.add(part)
        }
        return out.joinToString("/")
    }
}
