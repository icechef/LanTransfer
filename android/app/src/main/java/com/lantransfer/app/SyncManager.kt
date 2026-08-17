package com.lantransfer.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors

// 目录同步（手机 → 电脑）：支持多个源文件夹，文件以 relPath=<设备名>/<源目录名>/<相对路径>
// 增量发送到电脑 TCP 端口（file_meta 带 sync 标志，电脑端落到专用同步目录）。
// 单向镜像：仅上传新增/变更文件（按 relPath + mtime + size 判定），不做删除传播（安全）。
object SyncManager {

    data class SyncStatus(val total: Int, val synced: Int, val pending: Int)

    private data class Synced(val mtime: Long, val size: Long)

    private val executor = Executors.newSingleThreadExecutor()

    // 文件夹名去非法字符（Windows 文件名不允许 \ / : * ? " < > |）
    fun safeFolder(name: String): String =
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

    private fun loadSynced(ctx: Context, folderId: String): MutableMap<String, Synced> {
        val map = mutableMapOf<String, Synced>()
        try {
            val o = JSONObject(SettingsStore.syncMap(ctx, folderId))
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = o.getJSONObject(k)
                map[k] = Synced(v.optLong("mtime"), v.optLong("size"))
            }
        } catch (_: Exception) {}
        return map
    }

    private fun saveSynced(ctx: Context, folderId: String, map: Map<String, Synced>) {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, JSONObject().put("mtime", v.mtime).put("size", v.size))
        SettingsStore.setSyncMap(ctx, folderId, o.toString())
    }

    // 单个文件夹的同步状态快照（已同步/总数/待同步），会遍历源目录，需在后台线程调用
    fun computeFolderStatus(ctx: Context, folder: SettingsStore.SyncFolder): SyncStatus {
        val source = listSource(ctx, Uri.parse(folder.uri))
        val synced = loadSynced(ctx, folder.id)
        var syncedCount = 0
        for (f in source) {
            val e = synced[f.relPath]
            if (e != null && e.mtime == f.mtime && e.size == f.size) syncedCount++
        }
        return SyncStatus(source.size, syncedCount, source.size - syncedCount)
    }

    // 后台自动同步（供空闲触发）：同步所有开启自动同步的文件夹
    fun startBackgroundSync(ctx: Context) {
        val app = ctx.applicationContext
        executor.execute {
            val folders = SettingsStore.syncFolders(app).filter { it.enabled }
            var sent = 0
            var failed = 0
            for (f in folders) {
                syncFolder(app, f, null) { s, fl -> sent += s; failed += fl }
            }
            SyncNotifications.postResult(app, sent, failed)
        }
    }

    // 同步所有开启的文件夹（手动「全部同步」），需在后台线程调用
    fun syncAllEnabled(
        ctx: Context,
        onProgress: ((Long, Long, String) -> Unit)? = null,
        onDone: ((Int, Int) -> Unit)? = null
    ) {
        val folders = SettingsStore.syncFolders(ctx).filter { it.enabled }
        if (folders.isEmpty()) { onDone?.invoke(0, 0); return }
        var sent = 0
        var failed = 0
        for (f in folders) {
            syncFolder(ctx, f, onProgress) { s, fl -> sent += s; failed += fl }
        }
        SettingsStore.setSyncLast(ctx, System.currentTimeMillis())
        onDone?.invoke(sent, failed)
    }

    // 增量同步单个文件夹（需在后台线程调用）。
    // onProgress(done, total, 当前文件名)；onDone(成功数, 失败数)。
    fun syncFolder(
        ctx: Context,
        folder: SettingsStore.SyncFolder,
        onProgress: ((Long, Long, String) -> Unit)? = null,
        onDone: ((Int, Int) -> Unit)? = null
    ) {
        val target = SettingsStore.syncTarget(ctx)
        if (target.isBlank()) { onDone?.invoke(0, 0); return }
        val source = listSource(ctx, Uri.parse(folder.uri))
        val synced = loadSynced(ctx, folder.id)
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
        val dev = safeFolder(SettingsStore.deviceName(ctx))
        val fol = safeFolder(folder.name)
        val total = pending.sumOf { it.size }
        val sentCount = sendSync(ctx, target, dev, fol, pending, { done, name ->
            onProgress?.invoke(done, total, name)
        }) { f ->
            synced[f.relPath] = Synced(f.mtime, f.size)
        }
        saveSynced(ctx, folder.id, synced)
        SettingsStore.setSyncLast(ctx, System.currentTimeMillis())
        onProgress?.invoke(total, total, "")
        onDone?.invoke(sentCount, pending.size - sentCount)
    }

    // 扫描重置：清空同步进度，重新做目录校验；若电脑端同步目录存在「多余文件」（源里已删），
    // 通过 onDone 的第三个参数返回相对路径列表，供界面提示。
    // onProgress(done, total, 当前文件名)；onDone(成功数, 失败数, 多余文件列表)。
    fun scanReset(
        ctx: Context,
        folder: SettingsStore.SyncFolder,
        onProgress: ((Long, Long, String) -> Unit)? = null,
        onDone: ((Int, Int, List<String>) -> Unit)? = null
    ) {
        val target = SettingsStore.syncTarget(ctx)
        if (target.isBlank()) { onDone?.invoke(0, 0, emptyList()); return }
        val source = listSource(ctx, Uri.parse(folder.uri))
        val dev = safeFolder(SettingsStore.deviceName(ctx))
        val fol = safeFolder(folder.name)

        // 重置进度
        saveSynced(ctx, folder.id, emptyMap())

        // 电脑端同步目录已有文件
        val ip = target.substringBefore(':')
        val remote = PcApi.listSyncFiles(ip, dev, fol)
        val remoteByRel = remote.associateBy { it.relPath }

        // 多余文件：电脑端有、手机源里没有（源里删除后遗留）
        val sourceRels = source.map { it.relPath }.toSet()
        val extraFiles = remote.filter { it.relPath !in sourceRels }.map { it.relPath }

        // 待上传：手机有、电脑无，或 mtime/size 不同
        val toUpload = source.filter { f ->
            val r = remoteByRel[f.relPath]
            r == null || r.mtime != f.mtime || r.size != f.size
        }

        // 记录本次成功上传的文件，最终同步清单 = 电脑端已一致的文件 + 刚上传成功的文件
        val uploaded = mutableSetOf<String>()
        if (toUpload.isNotEmpty()) {
            val total = toUpload.sumOf { it.size }
            val sentCount = sendSync(ctx, target, dev, fol, toUpload, { done, name ->
                onProgress?.invoke(done, total, name)
            }) { f -> uploaded.add(f.relPath) }
            onProgress?.invoke(total, total, "")
            val newMap = mutableMapOf<String, Synced>()
            for (f in source) {
                val r = remoteByRel[f.relPath]
                if ((r != null && r.mtime == f.mtime && r.size == f.size) || f.relPath in uploaded) {
                    newMap[f.relPath] = Synced(f.mtime, f.size)
                }
            }
            saveSynced(ctx, folder.id, newMap)
            SettingsStore.setSyncLast(ctx, System.currentTimeMillis())
            onDone?.invoke(sentCount, toUpload.size - sentCount, extraFiles)
        } else {
            val newMap = source.associate { it.relPath to Synced(it.mtime, it.size) }
            saveSynced(ctx, folder.id, newMap)
            SettingsStore.setSyncLast(ctx, System.currentTimeMillis())
            onDone?.invoke(0, 0, extraFiles)
        }
    }

    // 连接电脑 TCP 端口发送一批文件（带 sync 标志 + <设备名>/<源目录名>/<相对路径> 结构 + mtime），
    // 返回成功发送的文件数；onFileDone 每成功一个文件回调一次。
    private fun sendSync(
        ctx: Context,
        target: String,
        device: String,
        folder: String,
        files: List<PendingFile>,
        onProgress: (Long, String) -> Unit,
        onFileDone: (PendingFile) -> Unit
    ): Int {
        val host = target.substringBefore(':')
        val port = target.substringAfter(':').toIntOrNull() ?: DEFAULT_PORT
        var done = 0L
        var sentCount = 0
        val prefix = "$device/$folder"
        return try {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), 3000)
                s.tcpNoDelay = false
                s.soTimeout = 30000
                val out = s.getOutputStream()
                val input = s.getInputStream()
                Protocol.write(out, Protocol.hello(SettingsStore.deviceName(ctx), "Android"))
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
                        sync = true
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
