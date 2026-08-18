package com.lantransfer.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// 浏览/下载/上传电脑的缓存目录与共享文件夹（含子目录）
class PcFilesActivity : AppCompatActivity() {

    private data class Row(val name: String, val isDir: Boolean, val size: Long, val path: String, val mtime: Long)

    private lateinit var ip: String
    private val rows = mutableListOf<Row>()
    private lateinit var adapter: RowAdapter
    private lateinit var emptyView: TextView
    private lateinit var backBtn: Button
    private lateinit var uploadBtn: Button
    private lateinit var titleView: TextView
    private val stack = ArrayDeque<Pair<Boolean, String>>() // (是否根, 路径)
    private var isRoot = true
    private var current = ""       // 浏览视图时的绝对路径
    private var writable = true
    private val pool = Executors.newSingleThreadExecutor()
    private val downloadSeq = AtomicInteger(5000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pc_files)

        ip = intent.getStringExtra("ip") ?: ""
        if (ip.isEmpty()) {
            Toast.makeText(this, "缺少服务器地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        emptyView = findViewById(R.id.pcEmpty)
        backBtn = findViewById(R.id.pcBackBtn)
        uploadBtn = findViewById(R.id.pcUploadBtn)
        titleView = findViewById(R.id.pcTitle)
        val list = findViewById<ListView>(R.id.pcFileList)
        adapter = RowAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val row = rows.getOrNull(position) ?: return@setOnItemClickListener
            if (row.isDir) enter(row.path) else download(row)
        }

        findViewById<Button>(R.id.pcRefreshBtn).setOnClickListener { load() }
        backBtn.setOnClickListener { back() }
        uploadBtn.setOnClickListener { pickAndUpload() }

        load()
    }

    private fun load() {
        pool.execute {
            if (isRoot) {
                // 根：列出共享文件夹（含缓存目录）
                val folders = PcApi.listFolders(ip)
                runOnUiThread {
                    rows.clear()
                    for (f in folders) rows.add(Row(if (f.kind == "cache") f.name else "${f.name}${if (f.readonly) "（只读）" else ""}", true, 0, f.path, 0))
                    adapter.notifyDataSetChanged()
                    emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    backBtn.isEnabled = false
                    titleView.text = "电脑文件（$ip）"
                    uploadBtn.isEnabled = true
                    writable = true
                }
            } else {
                val r = PcApi.browse(ip, current)
                runOnUiThread {
                    rows.clear()
                    if (r != null) {
                        current = r.path // 服务器返回绝对路径（缓存目录或共享文件夹）
                        writable = r.writable
                        for (e in r.entries) {
                            val p = if (current.endsWith("/")) current + e.name else "$current/${e.name}"
                            rows.add(Row(e.name, e.isDir, e.size, p, e.mtime))
                        }
                    }
                    adapter.notifyDataSetChanged()
                    emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    backBtn.isEnabled = true
                    titleView.text = current
                    uploadBtn.isEnabled = writable
                }
            }
        }
    }

    private fun enter(path: String) {
        stack.push(isRoot to current)
        isRoot = false
        current = path
        load()
    }

    private fun back() {
        if (stack.isEmpty()) {
            isRoot = true
            current = ""
        } else {
            val (r, p) = stack.pop()
            isRoot = r
            current = p
        }
        load()
    }

    private fun pickAndUpload() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, 300)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 300 || resultCode != Activity.RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
        }
        val pending = uris.map { resolveFile(it) }
        pool.execute {
            val ok = PcApi.uploadToDir(this, ip, current, pending)
            runOnUiThread {
                Toast.makeText(this, if (ok) "已上传 ${pending.size} 个文件" else "上传失败（目录可能只读）", Toast.LENGTH_SHORT).show()
                load()
            }
        }
    }

    private fun resolveFile(uri: Uri): PendingFile {
        var name = "file"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0) name = c.getString(ni) ?: name
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val mtime = resolveFileMtime(this, uri)
        return PendingFile(uri, name, size, "", mtime)
    }

    private fun download(row: Row) {
        val notifId = downloadSeq.incrementAndGet()
        Toast.makeText(this, "开始下载 ${row.name}", Toast.LENGTH_SHORT).show()
        pool.execute {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, row.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                runOnUiThread { Toast.makeText(this, "无法创建下载文件", Toast.LENGTH_SHORT).show() }
                return@execute
            }
            val ok = contentResolver.openOutputStream(uri)?.use { out ->
                PcApi.download(ip, row.name, row.path, out) { done ->
                    postDownloadProgress(notifId, row.name, done, row.size)
                }
            } ?: false
            if (ok) {
                // pending 三明治：清 IS_PENDING + 设 DATE_MODIFIED 同一次 update；
                // 再文件系统层写入——真正决定文件管理器显示的修改时间（DATE_MODIFIED 列不影响显示）
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    if (row.mtime > 0) put(MediaStore.MediaColumns.DATE_MODIFIED, row.mtime)
                }
                contentResolver.update(uri, v, null, null)
                if (row.mtime > 0) FileMtimeWriter.applyMtime(this, uri, row.mtime)
                runOnUiThread { Toast.makeText(this, "接收完成 ${row.name}", Toast.LENGTH_SHORT).show() }
                postDownloadDone(notifId, row.name, uri)
            } else {
                contentResolver.delete(uri, null, null)
                cancelDownload(notifId)
                runOnUiThread { Toast.makeText(this, "下载失败：${row.name}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun postDownloadProgress(id: Int, name: String, done: Long, total: Long) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel("download", "接收进度", NotificationManager.IMPORTANCE_LOW))
        }
        val b = Notification.Builder(this, "download")
            .setContentTitle("正在接收 $name")
            .setContentText(humanSize(done))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (total > 0) b.setProgress(100, ((done * 100) / total).toInt().coerceIn(0, 100), false)
        else b.setProgress(0, 0, true)
        nm.notify(id, b.build())
    }

    private fun postDownloadDone(id: Int, name: String, uri: Uri) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val openPi = PendingIntent.getActivity(
            this, id, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "download")
            .setContentTitle("接收完成")
            .setContentText(name)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .build()
        nm.notify(id, n)
    }

    private fun cancelDownload(id: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(id)
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_pc_file, parent, false)
            val f = rows[position]
            v.findViewById<TextView>(R.id.pcFileName).text = f.name
            v.findViewById<TextView>(R.id.pcFileSub).text = if (f.isDir) "文件夹" else humanSize(f.size)
            v.findViewById<ImageView>(R.id.pcFileIcon).setImageResource(
                if (f.isDir) R.drawable.ic_folder else R.drawable.ic_file
            )
            v.findViewById<Button>(R.id.pcFileDownload).visibility =
                if (f.isDir) View.GONE else View.VISIBLE
            v.findViewById<Button>(R.id.pcFileDownload).setOnClickListener { download(f) }
            return v
        }
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }
}
