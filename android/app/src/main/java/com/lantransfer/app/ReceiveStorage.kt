package com.lantransfer.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

// 统一接收落盘：
//  - 自动接收开：直接写 MediaStore Downloads（.part 临时条目，IS_PENDING，续传友好）或用户授权的 SAF 目录树。
//  - 自动接收关：先写到 app 缓存暂存，完成后通知里给「保存/拒绝」；保存时再拷贝到目标目录。
// 接收方边写边算 MD5，file_end 时校验通过才 commit（.part → 最终名、清 IS_PENDING）。
object ReceiveStorage {

    class Target(
        val uri: Uri?,             // MediaStore .part 条目 uri；pending 时为 null
        val out: OutputStream,
        val name: String,          // 最终文件名（不含 .part）
        val relDir: String,
        val pending: Boolean,
        val cacheFile: File?,      // pending 时的暂存文件
        val saf: Boolean,
        val offset: Long,          // 实际续传起点（未续传为 0）
        val digest: MessageDigest  // 流式 MD5，接收方边写边 update
    ) {
        // 落盘完成（MD5 校验已通过）：.part → 最终名，清 pending
        fun commit(ctx: Context) {
            if (pending || saf) return
            try {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
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

    // 待确认保存：把暂存文件拷贝到目标目录，返回最终 uri
    fun savePending(ctx: Context, cachePath: String, name: String, relDir: String): Uri? {
        val src = File(cachePath)
        if (!src.exists()) return null
        return try {
            val dst = open(ctx, name, relDir, false, 0L) ?: return null
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

    // 查找 MediaStore 里未完成（IS_PENDING）的同名 .part 条目的已写字节数；无则返回 0
    fun findPartial(ctx: Context, name: String, relDir: String): Long {
        if (SettingsStore.usingSaf(ctx)) return 0L
        return try {
            val base = Environment.DIRECTORY_DOWNLOADS
            val sub = SettingsStore.receiveSubDir(ctx).trim()
            val baseRel = if (sub.isEmpty()) base else "$base/$sub"
            val rel = safeRel(relDir)
            val fullRel = if (rel.isEmpty()) baseRel else "$baseRel/$rel"
            val partName = safeName(name) + ".part"
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
            val sel = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1"
            ctx.contentResolver.query(collection, proj, sel, arrayOf(partName), null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    val size = if (!c.isNull(1)) c.getLong(1) else 0L
                    val uri = ContentUris.withAppendedId(collection, id)
                    // 只认落在同一接收目录下的 .part
                    if (partInDir(ctx, uri, fullRel)) size else 0L
                } else 0L
            } ?: 0L
        } catch (_: Exception) { 0L }
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

    // offset > 0 表示续传（MediaStore 已有未收满的 .part）
    fun open(ctx: Context, name: String, relDir: String, pending: Boolean, offset: Long): Target? {
        val safeName = safeName(name)
        val rel = safeRel(relDir)
        val digest = MessageDigest.getInstance("MD5")

        if (pending) {
            val dir = File(ctx.cacheDir, "pending")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, System.nanoTime().toString() + "_" + safeName)
            return try {
                Target(null, f.outputStream(), safeName, rel, true, f, false, 0L, digest)
            } catch (_: Exception) { null }
        }

        if (SettingsStore.usingSaf(ctx)) {
            return openSaf(ctx, safeName, rel, digest)
        }
        return openMediaStore(ctx, safeName, rel, offset, digest)
    }

    private fun openMediaStore(ctx: Context, name: String, rel: String, offset: Long, digest: MessageDigest): Target? {
        return try {
            val base = Environment.DIRECTORY_DOWNLOADS
            val sub = SettingsStore.receiveSubDir(ctx).trim()
            val baseRel = if (sub.isEmpty()) base else "$base/$sub"
            val fullRel = if (rel.isEmpty()) baseRel else "$baseRel/$rel"
            val partName = name + ".part"

            var actualOffset = offset
            var uri: Uri? = null
            if (offset > 0) {
                uri = findPartUri(ctx, partName, fullRel)
            }
            if (uri == null) {
                actualOffset = 0L
                // 清理同名残留 .part（旧失败遗留），再新建
                deleteParts(ctx, partName, fullRel)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, partName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, fullRel)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            }

            // 续传：预 hash 已有前缀
            if (actualOffset > 0) {
                ctx.contentResolver.openInputStream(uri)?.use { hashInto(digest, it) }
            }

            val o = if (actualOffset > 0) {
                ctx.contentResolver.openOutputStream(uri, "wa")
            } else {
                ctx.contentResolver.openOutputStream(uri, "w")
            }
            if (o == null) {
                // 追加失败则删掉 .part，让下次从 0 重传
                if (actualOffset > 0) try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                else try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                return null
            }
            Target(uri, o, name, rel, false, null, false, actualOffset, digest)
        } catch (_: Exception) { null }
    }

    private fun findPartUri(ctx: Context, partName: String, fullRel: String): Uri? {
        return try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val proj = arrayOf(MediaStore.MediaColumns._ID)
            val sel = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1"
            ctx.contentResolver.query(collection, proj, sel, arrayOf(partName), null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    val uri = ContentUris.withAppendedId(collection, id)
                    if (partInDir(ctx, uri, fullRel)) uri else null
                } else null
            }
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

    private fun hashInto(digest: MessageDigest, input: InputStream) {
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            digest.update(buf, 0, n)
        }
    }

    private fun openSaf(ctx: Context, name: String, rel: String, digest: MessageDigest): Target? {
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
            Target(docUri, o, name, rel, false, null, true, 0L, digest)
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
