package com.lantransfer.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

// 统一接收落盘：
//  - 自动接收开：直接写 MediaStore Downloads（.part 临时条目，IS_PENDING）或用户授权的 SAF 目录树。
//  - 自动接收关：先写到 app 缓存暂存，完成后通知里给「保存/拒绝」；保存时再拷贝到目标目录。
// 接收方边写边算 MD5，file_end 时校验通过才 commit（.part → 最终名、清 IS_PENDING、还原 mtime）。
// 断点续传已移除：.part 仅作为「写完整才改名」的原子缓冲，连接断开即丢弃。
object ReceiveStorage {

    class Target(
        val uri: Uri?,             // MediaStore .part 条目 uri；pending 时为 null
        val out: OutputStream,
        val name: String,          // 最终文件名（不含 .part）
        val relDir: String,
        val pending: Boolean,
        val cacheFile: File?,      // pending 时的暂存文件
        val saf: Boolean,
        val digest: MessageDigest, // 流式 MD5，接收方边写边 update
        val mtime: Long            // 源文件修改时间（Unix 秒，0 表示未知）
    ) {
        // 落盘完成（MD5 校验已通过）：.part → 最终名，清 pending，还原修改时间
        fun commit(ctx: Context) {
            if (pending) return
            if (saf) {
                // SAF 文档没有标准写 mtime 的接口，尽力而为（部分 provider 支持 update）
                if (mtime > 0) {
                    try {
                        val v = ContentValues().apply {
                            put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, mtime * 1000)
                        }
                        ctx.contentResolver.update(uri!!, v, null, null)
                    } catch (_: Exception) {}
                }
                return
            }
            try {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    if (mtime > 0) put(MediaStore.MediaColumns.DATE_MODIFIED, mtime)
                }
                ctx.contentResolver.update(uri!!, v, null, null)
            } catch (_: Exception) {}
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

    // 待确认保存：把暂存文件拷贝到目标目录，返回最终 uri；mtime 还原源文件修改时间
    fun savePending(ctx: Context, cachePath: String, name: String, relDir: String, mtime: Long): Uri? {
        val src = File(cachePath)
        if (!src.exists()) return null
        return try {
            val dst = open(ctx, name, relDir, false, mtime) ?: return null
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

    fun open(ctx: Context, name: String, relDir: String, pending: Boolean, mtime: Long): Target? {
        val safeName = safeName(name)
        val rel = safeRel(relDir)
        val digest = MessageDigest.getInstance("MD5")

        if (pending) {
            val dir = File(ctx.cacheDir, "pending")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, System.nanoTime().toString() + "_" + safeName)
            return try {
                Target(null, f.outputStream(), safeName, rel, true, f, false, digest, mtime)
            } catch (_: Exception) { null }
        }

        if (SettingsStore.usingSaf(ctx)) {
            return openSaf(ctx, safeName, rel, digest, mtime)
        }
        return openMediaStore(ctx, safeName, rel, digest, mtime)
    }

    private fun openMediaStore(ctx: Context, name: String, rel: String, digest: MessageDigest, mtime: Long): Target? {
        return try {
            val base = Environment.DIRECTORY_DOWNLOADS
            val sub = SettingsStore.receiveSubDir(ctx).trim()
            val baseRel = if (sub.isEmpty()) base else "$base/$sub"
            val fullRel = if (rel.isEmpty()) baseRel else "$baseRel/$rel"
            val partName = name + ".part"

            // 清理同名残留 .part（旧失败遗留），再新建
            deleteParts(ctx, partName, fullRel)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, partName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, fullRel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val o = ctx.contentResolver.openOutputStream(uri, "w")
                ?: run {
                    try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                    return null
                }
            Target(uri, o, name, rel, false, null, false, digest, mtime)
        } catch (_: Exception) { null }
    }

    // 删除指定目录下所有同名 .part 条目（清理失败遗留）
    private fun deleteParts(ctx: Context, partName: String, fullRel: String) {
        try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val proj = arrayOf(MediaStore.MediaColumns._ID)
            val sel = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1"
            ctx.contentResolver.query(collection, proj, sel, arrayOf(partName), null)?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                    if (partInDir(ctx, uri, fullRel)) {
                        try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // 校验某个 MediaStore 条目是否位于指定相对目录下
    private fun partInDir(ctx: Context, uri: Uri, fullRel: String): Boolean {
        return try {
            val proj = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH)
            ctx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val p = c.getString(0) ?: ""
                    val want = fullRel.let { if (it.endsWith("/")) it else "$it/" }
                    p.equals(want, ignoreCase = true)
                } else false
            } ?: false
        } catch (_: Exception) { false }
    }

    private fun openSaf(ctx: Context, name: String, rel: String, digest: MessageDigest, mtime: Long): Target? {
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
            Target(docUri, o, name, rel, false, null, true, digest, mtime)
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
