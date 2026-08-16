package com.lantransfer.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors

// 目录同步（手机 → 电脑）：选定源目录后，以 relPath=同步_<设备名>/<相对路径> 增量发送到电脑 TCP 端口。
// 单向镜像：仅上传新增/变更文件（按 relPath + mtime + size 判定），不做删除传播（安全）。
object SyncManager {

    data class SyncStatus(val total: Int, val synced: Int, val pending: Int)

    private data class Synced(val mtime: Long, val size: Long)

    private val executor = Executors.newSingleThreadExecutor()

    // 文件夹名去非法字符（Windows 文件名不允许 \ / : * ? " < > |）
    private fun safeFolder(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "设备" }

    // 递归列出 SAF 目录树下所有文件（相对路径 + 大小 + 修改时间）
    fun listSource(ctx: Context, treeUri: Uri): List<PendingFile> =
        try { listDir(ctx, treeUri, DocumentsContract.getTreeDocumentId(treeUri), "") }
        catch (_: Exception) { emptyList() }

    private fun listDir(ctx: Context, treeUri: Uri, docId: String, relPrefix: String): List<PendingFile> {
        val out = mutableListOf<PendingFile>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val mtimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getString(idIdx)
                val name = c.getString(nameIdx) ?: continue
                val mime = c.getString(mimeIdx)
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                val mtime = if (mtimeIdx >= 0 && !c.isNull(mtimeIdx)) c.getLong(mtimeIdx) / 1000 else 0L
                val fullRel = if (relPrefix.isEmpty()) name else "$relPrefix/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    out.addAll(listDir(ctx, treeUri, id, fullRel))
                } else {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    out.add(PendingFile(docUri, name, size, fullRel, mtime))
                }
            }
        }
        return out
    }

    private fun loadSynced(ctx: Context): MutableMap<String, Synced> {
        val map = mutableMapOf<String, Synced>()
        try {
            val o = JSONObject(SettingsStore.syncMap(ctx))
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = o.getJSONObject(k)
                map[k] = Synced(v.optLong("mtime"), v.optLong("size"))
            }
        } catch (_: Exception) {}
        return map
    }

    private fun saveSynced(ctx: Context, map: Map<String, Synced>) {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, JSONObject().put("mtime", v.mtime).put("size", v.size))
        SettingsStore.setSyncMap(ctx, o.toString())
    }

    // 同步状态快照（已同步/总数/待同步），会遍历源目录，需在后台线程调用
    fun computeStatus(ctx: Context): SyncStatus {
        val treeStr = SettingsStore.syncTreeUri(ctx)
        if (treeStr.isEmpty()) return SyncStatus(0, 0, 0)
        val source = listSource(ctx, Uri.parse(treeStr))
        val synced = loadSynced(ctx)
        var syncedCount = 0
        for (f in source) {
            val e = synced[f.relPath]
            if (e != null && e.mtime == f.mtime && e.size == f.size) syncedCount++
        }
        return SyncStatus(source.size, syncedCount, source.size - syncedCount)
    }

    // 后台自动同步（供空闲触发）
    fun startBackgroundSync(ctx: Context) {
        val app = ctx.applicationContext
        executor.execute {
            SyncManager.syncOnce(app, null) { sent, failed ->
                SyncNotifications.postResult(app, sent, failed)
            }
        }
    }

    // 执行一次增量同步（需在后台线程调用）。
    // onProgress(done, total, 当前文件名)；onDone(成功数, 失败数)。
    fun syncOnce(
        ctx: Context,
        onProgress: ((Long, Long, String) -> Unit)? = null,
        onDone: ((Int, Int) -> Unit)? = null
    ) {
        val treeStr = SettingsStore.syncTreeUri(ctx)
        val target = SettingsStore.syncTarget(ctx)
        if (treeStr.isEmpty() || target.isBlank()) {
            onDone?.invoke(0, 0)
            return
        }
        val source = listSource(ctx, Uri.parse(treeStr))
        val synced = loadSynced(ctx)
        val pending = source.filter { f ->
            val e = synced[f.relPath]
            e == null || e.mtime != f.mtime || e.size != f.size
        }
        if (pending.isEmpty()) {
            SettingsStore.setSyncLast(ctx, System.currentTimeMillis())
            onProgress?.invoke(0L, 0L, "")
            onDone?.invoke(0, 0)
            return
        }
        val deviceName = SettingsStore.deviceName(ctx)
        val total = pending.sumOf { it.size }
        val sentCount = sendSync(ctx, target, pending, deviceName, { done, name ->
            onProgress?.invoke(done, total, name)
        }) { f ->
            synced[f.relPath] = Synced(f.mtime, f.size)
        }
        saveSynced(ctx, synced)
        SettingsStore.setSyncLast(ctx, System.currentTimeMillis())
        onProgress?.invoke(total, total, "")
        onDone?.invoke(sentCount, pending.size - sentCount)
    }

    // 连接电脑 TCP 端口发送一批文件（带 同步_ 前缀 + mtime），返回成功发送的文件数
    private fun sendSync(
        ctx: Context,
        target: String,
        files: List<PendingFile>,
        deviceName: String,
        onProgress: (Long, String) -> Unit,
        onFileDone: (PendingFile) -> Unit
    ): Int {
        val host = target.substringBefore(':')
        val port = target.substringAfter(':').toIntOrNull() ?: DEFAULT_PORT
        var done = 0L
        var sentCount = 0
        val prefix = "同步_${safeFolder(deviceName)}"
        return try {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), 3000)
                s.tcpNoDelay = false
                s.soTimeout = 30000
                val out = s.getOutputStream()
                val input = s.getInputStream()
                Protocol.write(out, Protocol.hello(deviceName, "Android"))
                if (Protocol.read(input) == null) return sentCount
                for ((index, f) in files.withIndex()) {
                    val meta = Protocol.Header().apply {
                        type = "file_meta"
                        fileId = index.toString()
                        fileName = f.name
                        fileSize = f.size
                        fileIndex = index
                        fileCount = files.size
                        relPath = "$prefix/${f.relPath}"
                        mtime = f.mtime
                    }
                    Protocol.write(out, meta)
                    val ack = Protocol.read(input)
                    if (ack != null && ack.first.type == "error") return sentCount

                    val digest = MessageDigest.getInstance("MD5")
                    val stream = ctx.contentResolver.openInputStream(f.uri)
                    if (stream == null) return sentCount
                    stream.use { fin ->
                        val buf = ByteArray(1 shl 20)
                        var idx = 0L
                        while (true) {
                            val n = fin.read(buf)
                            if (n <= 0) break
                            val chunk = if (n == buf.size) buf else buf.copyOf(n)
                            Protocol.write(out, Protocol.Header().apply {
                                type = "file_data"; fileId = index.toString(); chunkIndex = idx
                            }, chunk)
                            digest.update(chunk)
                            idx++
                            done += n
                            onProgress(done, f.name)
                        }
                    }
                    val md5hex = digest.digest().joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
                    Protocol.write(out, Protocol.Header().apply {
                        type = "file_end"; fileId = index.toString(); totalBytes = f.size; md5 = md5hex
                    })
                    val ack2 = Protocol.read(input)
                    if (ack2 != null && ack2.first.type == "error") return sentCount
                    sentCount++
                    onFileDone(f)
                }
                sentCount
            } finally {
                try { s.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) { sentCount }
    }
}
