package com.lantransfer.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors

data class PendingFile(val uri: Uri, val name: String, val size: Long, val relPath: String)

class MainActivity : AppCompatActivity() {

    private val devices = mutableListOf<Device>()
    private val pendingFiles = mutableListOf<PendingFile>()

    private lateinit var status: TextView
    private lateinit var deviceList: ListView
    private lateinit var pendingList: ListView
    private lateinit var sendBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var receiveProgressBar: ProgressBar
    private lateinit var receiveProgressText: TextView
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var pendingAdapter: PendingAdapter

    // 应用内横幅（QQ 式弹窗，用于完成/文本提示）
    private lateinit var banner: LinearLayout
    private lateinit var bannerTitle: TextView
    private lateinit var bannerSub: TextView
    private var bannerClick: (() -> Unit)? = null
    private val hideBannerRunnable = Runnable { hideBanner() }

    private val sendPool = Executors.newSingleThreadExecutor()

    // 并发接收任务进度（taskId -> 进度），合并显示总进度
    private data class ReceiveTask(val name: String, val from: String, val done: Long, val total: Long, val speed: Long)
    private val receiveTasks = HashMap<Int, ReceiveTask>()

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val a = intent?.action ?: return
            when (a) {
                TransferService.ACTION_RECEIVE_PROGRESS -> {
                    val taskId = intent.getIntExtra("taskId", -1)
                    val from = intent.getStringExtra("from") ?: ""
                    val name = intent.getStringExtra("name") ?: ""
                    val done = intent.getLongExtra("done", 0L)
                    val total = intent.getLongExtra("total", 0L)
                    val speed = intent.getLongExtra("speed", 0L)
                    receiveTasks[taskId] = ReceiveTask(name, from, done, total, speed)
                    renderReceiveProgress()
                }
                TransferService.ACTION_RECEIVED -> {
                    val taskId = intent.getIntExtra("taskId", -1)
                    val from = intent.getStringExtra("from") ?: ""
                    val name = intent.getStringExtra("name") ?: ""
                    val uri = intent.getStringExtra("uri") ?: ""
                    receiveTasks.remove(taskId)
                    renderReceiveProgress()
                    showBanner("收到来自 $from 的文件", "$name（点击打开）", autoHide = true) { openUri(uri) }
                }
                TransferService.ACTION_TEXT -> {
                    val from = intent.getStringExtra("from") ?: ""
                    val text = intent.getStringExtra("text") ?: ""
                    showBanner("$from 发送了文本", text, autoHide = true) { copyToClipboard(text) }
                }
                TransferService.ACTION_PEER_FOUND -> {
                    val name = intent.getStringExtra("name") ?: "?"
                    val type = intent.getStringExtra("type") ?: "?"
                    val ip = intent.getStringExtra("ip") ?: return
                    val port = intent.getIntExtra("port", DEFAULT_PORT)
                    if (addDevice(Device(name, type, ip, port))) {
                        status.text = "发现新设备：$name（$ip:$port）"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        deviceList = findViewById(R.id.deviceList)
        pendingList = findViewById(R.id.pendingList)
        sendBtn = findViewById(R.id.sendBtn)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        receiveProgressBar = findViewById(R.id.receiveProgressBar)
        receiveProgressText = findViewById(R.id.receiveProgressText)
        banner = findViewById(R.id.receiveBanner)
        bannerTitle = findViewById(R.id.bannerTitle)
        bannerSub = findViewById(R.id.bannerSub)

        deviceAdapter = DeviceAdapter()
        deviceList.adapter = deviceAdapter
        pendingAdapter = PendingAdapter()
        pendingList.adapter = pendingAdapter

        banner.setOnClickListener { bannerClick?.invoke() }

        findViewById<Button>(R.id.scanBtn).setOnClickListener { scan() }
        findViewById<Button>(R.id.sendBtn).setOnClickListener { doSend() }
        findViewById<Button>(R.id.startServiceBtn).setOnClickListener { startTransferService() }
        findViewById<Button>(R.id.connectBtn).setOnClickListener { connectManual() }
        findViewById<Button>(R.id.sendTextBtn).setOnClickListener { promptSendText() }
        findViewById<Button>(R.id.sendClipBtn).setOnClickListener { sendClipboard() }
        findViewById<Button>(R.id.addFileBtn).setOnClickListener { pickFiles() }
        findViewById<Button>(R.id.addFolderBtn).setOnClickListener { pickFolder() }
        findViewById<Button>(R.id.mediaBtn).setOnClickListener { pickMedia() }
        findViewById<Button>(R.id.clearBtn).setOnClickListener { clearPending() }
        findViewById<Button>(R.id.pcFilesBtn).setOnClickListener { openPcFiles() }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.historyBtn).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        updateSendButton()
        requestNotifications()
        startTransferService()
        handleIncomingText(intent)
        handleShareIntent(intent)
        scan() // 进入应用自动扫描
    }

    override fun onResume() {
        super.onResume()
        UiState.foreground = true
        status.text = "本机：${SettingsStore.deviceName(this)}　IP: ${Discovery.primaryIP()}"
        val filter = IntentFilter().apply {
            addAction(TransferService.ACTION_RECEIVED)
            addAction(TransferService.ACTION_RECEIVE_PROGRESS)
            addAction(TransferService.ACTION_TEXT)
            addAction(TransferService.ACTION_PEER_FOUND)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(eventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(eventReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        UiState.foreground = false
        try { unregisterReceiver(eventReceiver) } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingText(intent)
        handleShareIntent(intent)
    }

    private fun handleIncomingText(intent: Intent?) {
        val text = intent?.getStringExtra("received_text") ?: return
        val from = intent.getStringExtra("received_from") ?: ""
        copyToClipboard(text)
        AlertDialog.Builder(this)
            .setTitle(if (from.isEmpty()) "收到文本" else "收到来自 $from 的文本")
            .setMessage(text)
            .setPositiveButton("已复制到剪贴板", null)
            .show()
    }

    // 系统分享菜单入口
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uris = mutableListOf<Uri>()
        intent.data?.let { uris.add(it) }
        intent.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
        }
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        val added = uris.distinctBy { it.toString() }.map { resolveFile(it) }
        if (added.isNotEmpty()) afterAdd(added)
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun openUri(uriStr: String) {
        if (uriStr.isEmpty()) return
        val uri = Uri.parse(uriStr)
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSendButton() {
        sendBtn.text = "发送（${pendingFiles.size} 个文件）"
    }

    private fun clearPending() {
        pendingFiles.clear()
        pendingAdapter.notifyDataSetChanged()
        updateSendButton()
    }

    private fun addDevice(d: Device): Boolean {
        if (d.isWeb) {
            if (devices.none { it.isWeb && it.sid == d.sid }) {
                devices.add(d)
                deviceAdapter.notifyDataSetChanged()
                return true
            }
            return false
        }
        val selfIp = Discovery.primaryIP()
        if (d.ip == selfIp || d.ip.startsWith("127.")) return false
        if (devices.none { !it.isWeb && it.addr == d.addr }) {
            devices.add(d)
            deviceAdapter.notifyDataSetChanged()
            return true
        }
        return false
    }

    // ---- 设备选择弹窗 ----

    private fun showDevicePicker(multiSelect: Boolean, onPicked: (List<Device>) -> Unit) {
        if (devices.isEmpty()) {
            Toast.makeText(this, "未发现设备，请先扫描", Toast.LENGTH_SHORT).show()
            return
        }
        val names = devices.map {
            if (it.isWeb) "${it.name}（网页）" else "${it.name}（${it.type}）\n${it.addr}"
        }.toTypedArray()
        if (multiSelect) {
            val checked = BooleanArray(devices.size)
            AlertDialog.Builder(this)
                .setTitle("选择发送设备（可多选）")
                .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("发送") { _, _ ->
                    val picked = devices.filterIndexed { i, _ -> checked[i] }
                    if (picked.isEmpty()) Toast.makeText(this, "请至少选择一台设备", Toast.LENGTH_SHORT).show()
                    else onPicked(picked)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("选择发送设备")
                .setItems(names) { _, which -> onPicked(listOf(devices[which])) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ---- 设备列表（仅显示） ----

    private inner class DeviceAdapter : BaseAdapter() {
        override fun getCount() = devices.size
        override fun getItem(position: Int) = devices[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_device, parent, false)
            val d = devices[position]
            v.findViewById<TextView>(R.id.deviceName).text = d.name
            val showIp = SettingsStore.showIp(this@MainActivity)
            v.findViewById<TextView>(R.id.deviceSub).text = when {
                d.isWeb -> if (showIp) "网页终端　${d.ip}" else "网页终端"
                showIp -> "${d.type}　${d.addr}"
                else -> d.type
            }
            return v
        }
    }

    // ---- 待发送列表 ----

    private inner class PendingAdapter : BaseAdapter() {
        override fun getCount() = pendingFiles.size
        override fun getItem(position: Int) = pendingFiles[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_pending, parent, false)
            val f = pendingFiles[position]
            val label = if (f.relPath.isEmpty()) f.name else f.relPath
            v.findViewById<TextView>(R.id.pendingName).text = label
            v.findViewById<TextView>(R.id.pendingSize).text = humanSize(f.size)
            v.findViewById<Button>(R.id.pendingRemove).setOnClickListener {
                pendingFiles.removeAt(position)
                notifyDataSetChanged()
                updateSendButton()
            }
            return v
        }
    }

    // ---- 选择文件 / 文件夹 / 媒体 ----

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, 100)
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, 101)
    }

    private fun pickMedia() {
        val items = arrayOf("图片", "视频", "音频")
        AlertDialog.Builder(this)
            .setTitle("选择媒体类型")
            .setItems(items) { _, which ->
                val mime = when (which) {
                    0 -> "image/*"
                    1 -> "video/*"
                    else -> "audio/*"
                }
                val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    type = mime
                }
                @Suppress("DEPRECATION")
                startActivityForResult(i, 100)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return

        if (requestCode == 100) {
            val uris = mutableListOf<Uri>()
            data.data?.let { uris.add(it) }
            data.clipData?.let { cd ->
                for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
            }
            val added = uris.map { resolveFile(it) }
            afterAdd(added)
        } else if (requestCode == 101) {
            val treeUri = data.data ?: return
            status.text = "正在读取文件夹…"
            sendPool.execute {
                val files = try {
                    listFolder(treeUri, DocumentsContract.getTreeDocumentId(treeUri), "")
                } catch (_: Exception) { emptyList() }
                runOnUiThread {
                    if (files.isEmpty()) {
                        Toast.makeText(this, "文件夹为空或读取失败", Toast.LENGTH_SHORT).show()
                    } else {
                        afterAdd(files)
                    }
                }
            }
        }
    }

    // 添加文件后：弹「立即发送 / 加入列表」选择
    private fun afterAdd(added: List<PendingFile>) {
        if (added.isEmpty()) {
            Toast.makeText(this, "未选择文件", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("已选择 ${added.size} 个文件")
            .setItems(arrayOf("立即发送", "加入待发送列表")) { _, which ->
                if (which == 0) {
                    showDevicePicker(multiSelect = true) { targets -> doSendTo(targets, added) }
                } else {
                    pendingFiles.addAll(added)
                    pendingAdapter.notifyDataSetChanged()
                    updateSendButton()
                    status.text = "已加入 ${added.size} 个文件到待发送列表"
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
        return PendingFile(uri, name, size, "")
    }

    private fun listFolder(treeUri: Uri, docId: String, relPrefix: String): List<PendingFile> {
        val out = mutableListOf<PendingFile>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        contentResolver.query(children, cols, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (c.moveToNext()) {
                val id = c.getString(idIdx)
                val name = c.getString(nameIdx) ?: continue
                val mime = c.getString(mimeIdx)
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                val fullRel = if (relPrefix.isEmpty()) name else "$relPrefix/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    out.addAll(listFolder(treeUri, id, fullRel))
                } else {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    out.add(PendingFile(docUri, name, size, fullRel))
                }
            }
        }
        return out
    }

    // ---- 发送 ----

    private fun doSend() {
        if (pendingFiles.isEmpty()) {
            Toast.makeText(this, "请先添加要发送的文件", Toast.LENGTH_SHORT).show()
            return
        }
        showDevicePicker(multiSelect = true) { targets -> doSendTo(targets, pendingFiles.toList()) }
    }

    private fun doSendTo(targets: List<Device>, files: List<PendingFile>) {
        val perDevice = files.sumOf { it.size }
        val grandTotal = perDevice * targets.size
        val startAt = SystemClock.elapsedRealtime()
        val doneHolder = longArrayOf(0L)
        val selfName = SettingsStore.deviceName(this)

        sendPool.execute {
            var ok = 0
            var fail = 0
            runOnUiThread { showProgress() }
            for (t in targets) {
                val base = doneHolder[0]
                val success = try {
                    if (t.isWeb) {
                        PcApi.sendFilesToWeb(this, t.serverIp ?: t.ip, t.sid ?: "", selfName, files)
                    } else {
                        sendToTarget(t.addr, files) { n ->
                            doneHolder[0] = base + n
                            val d = doneHolder[0]
                            runOnUiThread { updateProgress(d, grandTotal, startAt) }
                        }
                        true
                    }
                } catch (_: Exception) { false }
                doneHolder[0] = base + perDevice
                runOnUiThread { updateProgress(doneHolder[0], grandTotal, startAt) }
                if (success) ok++ else fail++
            }
            runOnUiThread {
                hideProgress()
                status.text = "发送完成：成功 $ok 台，失败 $fail 台"
                Toast.makeText(this, "发送完成：成功 $ok 台，失败 $fail 台", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendToTarget(target: String, files: List<PendingFile>, onProgress: (Long) -> Unit) {
        val host = target.substringBefore(':')
        val port = target.substringAfter(':').toInt()
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 3000)
            s.tcpNoDelay = false
            val out = s.getOutputStream()
            val input = s.getInputStream()
            Protocol.write(out, Protocol.hello(SettingsStore.deviceName(this), "Android"))
            if (Protocol.read(input) == null) throw RuntimeException("握手失败")
            var sent = 0L
            for ((index, f) in files.withIndex()) {
                sendOne(out, input, f, index, files.size)
                sent += f.size
                onProgress(sent)
            }
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun sendOne(out: OutputStream, input: InputStream, f: PendingFile, index: Int, total: Int) {
        val meta = Protocol.Header().apply {
            type = "file_meta"
            fileId = index.toString()
            fileName = f.name
            fileSize = f.size
            fileIndex = index
            fileCount = total
            relPath = f.relPath.takeIf { it.isNotEmpty() }
        }
        Protocol.write(out, meta)
        val ack = Protocol.read(input)
        if (ack != null && ack.first.type == "error") throw RuntimeException(ack.first.message)

        val digest = MessageDigest.getInstance("MD5")
        val offset = ack?.first?.offset ?: 0L

        contentResolver.openInputStream(f.uri)!!.use { fin ->
            // 续传：跳过并 hash 前缀
            if (offset > 0) {
                val skipBuf = ByteArray(1 shl 20)
                var remaining = offset
                while (remaining > 0) {
                    val n = fin.read(skipBuf, 0, minOf(skipBuf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    digest.update(skipBuf, 0, n)
                    remaining -= n
                }
            }
            // 发送剩余部分（边发边 hash）
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
            }
        }

        val md5hex = digest.digest().joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
        Protocol.write(out, Protocol.Header().apply {
            type = "file_end"; fileId = index.toString(); totalBytes = f.size; md5 = md5hex
        })
        val ack2 = Protocol.read(input)
        if (ack2 != null && ack2.first.type == "error") throw RuntimeException(ack2.first.message)
    }

    // ---- 文本 ----

    private fun promptSendText() {
        val input = EditText(this)
        input.hint = "输入要发送的文本"
        AlertDialog.Builder(this)
            .setTitle("发送文本")
            .setView(input)
            .setPositiveButton("下一步") { _, _ ->
                val t = input.text.toString()
                if (t.isEmpty()) {
                    Toast.makeText(this, "文本为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showDevicePicker(multiSelect = false) { targets -> doSendText(targets.first(), t) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.text?.toString()
        if (t.isNullOrEmpty()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        showDevicePicker(multiSelect = false) { targets -> doSendText(targets.first(), t) }
    }

    private fun doSendText(device: Device, content: String) {
        status.text = "正在发送文本…"
        sendPool.execute {
            val ok = try {
                if (device.isWeb) {
                    PcApi.sendTextToWeb(device.serverIp ?: device.ip, device.sid ?: "", SettingsStore.deviceName(this), content)
                } else {
                    sendTextTcp(device.addr, content)
                    true
                }
            } catch (_: Exception) { false }
            runOnUiThread {
                status.text = if (ok) "文本已发送" else "发送失败"
                Toast.makeText(this, if (ok) "文本已发送" else "发送失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendTextTcp(target: String, content: String) {
        val host = target.substringBefore(':')
        val port = target.substringAfter(':').toInt()
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 3000)
        val out = s.getOutputStream()
        val input = s.getInputStream()
        Protocol.write(out, Protocol.hello(SettingsStore.deviceName(this), "Android"))
        if (Protocol.read(input) == null) throw RuntimeException("握手失败")
        Protocol.write(out, Protocol.Header().apply {
            type = "text"; text = content
            deviceName = SettingsStore.deviceName(this@MainActivity)
        })
        s.close()
    }

    // ---- 进度 UI ----

    private fun showProgress() {
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressBar.progress = 0
    }

    private fun updateProgress(done: Long, total: Long, startAt: Long) {
        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        progressBar.progress = pct
        val el = (SystemClock.elapsedRealtime() - startAt) / 1000.0
        val speed = if (el > 0) done / el else 0.0
        progressText.text = "$pct%　${humanSize(done)} / ${humanSize(total)}　${humanSize(speed.toLong())}/s"
    }

    private fun hideProgress() {
        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE
    }

    // 合并渲染所有并发接收任务的总进度
    private fun renderReceiveProgress() {
        if (receiveTasks.isEmpty()) {
            receiveProgressBar.visibility = View.GONE
            receiveProgressText.visibility = View.GONE
            return
        }
        val totalDone = receiveTasks.values.sumOf { it.done }
        val totalSize = receiveTasks.values.sumOf { it.total }
        val totalSpeed = receiveTasks.values.sumOf { it.speed }
        val count = receiveTasks.size
        receiveProgressBar.visibility = View.VISIBLE
        receiveProgressText.visibility = View.VISIBLE
        val pct = if (totalSize > 0) ((totalDone * 100) / totalSize).toInt().coerceIn(0, 100) else 0
        receiveProgressBar.progress = pct
        val label = if (count > 1) "正在接收 $count 个文件" else "正在接收：${receiveTasks.values.first().name}"
        receiveProgressText.text = "$label　${humanSize(totalDone)} / ${humanSize(totalSize)}　${humanSize(totalSpeed)}/s"
    }

    // ---- 应用内横幅 ----

    private fun showBanner(title: String, sub: String, autoHide: Boolean, onClick: (() -> Unit)?) {
        bannerTitle.text = title
        bannerSub.text = sub
        bannerClick = onClick
        banner.visibility = View.VISIBLE
        banner.translationY = -220f
        banner.animate().translationY(0f).setDuration(200).start()
        banner.removeCallbacks(hideBannerRunnable)
        if (autoHide) banner.postDelayed(hideBannerRunnable, 4000)
    }

    private fun hideBanner() {
        bannerClick = null
        banner.animate().translationY(-220f).setDuration(200)
            .withEndAction { banner.visibility = View.GONE }
            .start()
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    // ---- 电脑文件 / 网页终端 ----

    private fun openPcFiles() {
        val pcs = devices.filter { it.isPc }
        if (pcs.isEmpty()) {
            Toast.makeText(this, "未发现电脑，请先扫描", Toast.LENGTH_SHORT).show()
            return
        }
        val names = pcs.map { "${it.name}（${it.ip}）" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择电脑")
            .setItems(names) { _, which ->
                startActivity(Intent(this, PcFilesActivity::class.java).putExtra("ip", pcs[which].ip))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 扫描/连接后，额外拉取电脑服务器上的网页终端
    private fun discoverWebDevices() {
        val pcs = devices.filter { it.isPc }.toList()
        for (pc in pcs) {
            val webs = PcApi.listWebDevices(pc.ip)
            if (webs.isEmpty()) continue
            runOnUiThread {
                for (w in webs) addDevice(w)
            }
        }
    }

    // ---- 其他 ----

    private fun connectManual() {
        val input = findViewById<EditText>(R.id.manualIpInput)
        val host = input.text.toString().trim()
        if (host.isEmpty()) {
            Toast.makeText(this, "请输入对方 IP", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "正在连接 $host …"
        sendPool.execute {
            val d = Discovery.probeOne(host, DEFAULT_PORT, SettingsStore.deviceName(this), "Android")
            runOnUiThread {
                if (d != null) {
                    addDevice(d)
                    status.text = "已连接：${d.name}（${d.addr}）"
                    Toast.makeText(this, "已连接 ${d.name}", Toast.LENGTH_SHORT).show()
                    Thread { discoverWebDevices() }.start()
                } else {
                    status.text = "连接失败"
                    Toast.makeText(this, "连接 $host 失败（检查 IP 和端口）", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun startTransferService() {
        val i = Intent(this, TransferService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    private fun scan() {
        status.text = "扫描中…"
        devices.clear()
        deviceAdapter.notifyDataSetChanged()
        Discovery.scan(
            DEFAULT_PORT,
            SettingsStore.deviceName(this),
            "Android",
            onFound = { d ->
                runOnUiThread { addDevice(d) }
            },
            onDone = {
                runOnUiThread {
                    status.text = "扫描完成，共 ${devices.size} 台设备"
                    Thread { discoverWebDevices() }.start()
                }
            }
        )
    }
}
