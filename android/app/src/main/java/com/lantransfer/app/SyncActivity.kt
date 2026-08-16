package com.lantransfer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// 目录同步设置与管理页：选源目录、选目标电脑、空闲自动同步开关、状态与手动同步。
class SyncActivity : AppCompatActivity() {

    private lateinit var sourceText: TextView
    private lateinit var targetText: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var syncNowBtn: Button

    private val pool = Executors.newSingleThreadExecutor()
    @Volatile private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync)

        sourceText = findViewById(R.id.syncSourceText)
        targetText = findViewById(R.id.syncTargetText)
        autoSwitch = findViewById(R.id.syncAutoSwitch)
        statusText = findViewById(R.id.syncStatusText)
        progressBar = findViewById(R.id.syncProgressBar)
        progressText = findViewById(R.id.syncProgressText)
        syncNowBtn = findViewById(R.id.syncNowBtn)

        findViewById<Button>(R.id.syncPickSourceBtn).setOnClickListener { pickSource() }
        findViewById<Button>(R.id.syncPickTargetBtn).setOnClickListener { pickTarget() }
        autoSwitch.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setSyncEnabled(this, checked)
        }
        syncNowBtn.setOnClickListener { syncNow() }

        refreshConfig()
        refreshStatus()
    }

    private fun refreshConfig() {
        val tree = SettingsStore.syncTreeUri(this)
        sourceText.text = if (tree.isEmpty()) "未选择" else treeName(Uri.parse(tree))
        val target = SettingsStore.syncTarget(this)
        targetText.text = target.ifBlank { "未选择" }
        autoSwitch.isChecked = SettingsStore.syncEnabled(this)
    }

    private fun refreshStatus() {
        pool.execute {
            val st = SyncManager.computeStatus(this)
            val last = SettingsStore.syncLast(this)
            val lastStr = if (last > 0) "，上次同步 ${formatTime(last)}" else ""
            runOnUiThread {
                statusText.text = "已同步 ${st.synced} / 共 ${st.total} 个文件（待同步 ${st.pending}）$lastStr"
            }
        }
    }

    private fun pickSource() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, 400)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 400 && resultCode == Activity.RESULT_OK && data?.data != null) {
            val treeUri = data.data!!
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            SettingsStore.setSyncTreeUri(this, treeUri.toString())
            refreshConfig()
            refreshStatus()
            Toast.makeText(this, "同步源目录已设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickTarget() {
        Toast.makeText(this, "正在扫描设备…", Toast.LENGTH_SHORT).show()
        Discovery.scan(
            DEFAULT_PORT,
            SettingsStore.deviceName(this),
            "Android",
            onFound = { d ->
                if (!d.isWeb) runOnUiThread { addTargetCandidate(d) }
            },
            onDone = {
                runOnUiThread { showTargetDialog() }
            }
        )
    }

    private val targetCandidates = mutableListOf<Device>()
    private fun addTargetCandidate(d: Device) {
        val selfIp = Discovery.primaryIP()
        if (d.ip == selfIp || d.ip.startsWith("127.")) return
        if (targetCandidates.none { it.addr == d.addr }) targetCandidates.add(d)
    }

    private fun showTargetDialog() {
        if (targetCandidates.isEmpty()) {
            Toast.makeText(this, "未发现电脑，请确认电脑端已运行", Toast.LENGTH_SHORT).show()
            return
        }
        val names = targetCandidates.map { "${it.name}（${it.addr}）" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择目标电脑")
            .setItems(names) { _, which ->
                val d = targetCandidates[which]
                SettingsStore.setSyncTarget(this, d.addr)
                targetCandidates.clear()
                refreshConfig()
            }
            .setNegativeButton("取消") { _, _ -> targetCandidates.clear() }
            .show()
    }

    private fun syncNow() {
        if (syncing) { Toast.makeText(this, "正在同步中…", Toast.LENGTH_SHORT).show(); return }
        if (SettingsStore.syncTreeUri(this).isEmpty()) { Toast.makeText(this, "请先选择源目录", Toast.LENGTH_SHORT).show(); return }
        if (SettingsStore.syncTarget(this).isBlank()) { Toast.makeText(this, "请先选择目标电脑", Toast.LENGTH_SHORT).show(); return }

        syncing = true
        syncNowBtn.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressBar.progress = 0
        progressText.text = "准备同步…"

        pool.execute {
            SyncManager.syncOnce(this, { done, total, name ->
                runOnUiThread {
                    val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                    progressBar.progress = pct
                    progressText.text = "$pct%　${humanSize(done)} / ${humanSize(total)}　${name}"
                }
            }) { sent, failed ->
                runOnUiThread {
                    syncing = false
                    syncNowBtn.isEnabled = true
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.GONE
                    val msg = when {
                        sent == 0 && failed == 0 -> "没有需要同步的新文件"
                        failed == 0 -> "同步完成：上传 $sent 个文件"
                        else -> "同步结束：成功 $sent 个，失败 $failed 个"
                    }
                    statusText.text = msg
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }
            }
        }
    }

    private fun treeName(uri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: docId else docId
            } ?: docId
        } catch (_: Exception) { "已选择" }
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
    }
}
