package com.lantransfer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// 目录同步管理页：多同步文件夹、目标电脑、逐文件夹开关、手动/全部同步、扫描重置。
class SyncActivity : AppCompatActivity() {

    private lateinit var targetText: TextView
    private lateinit var folderContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private val pool = Executors.newSingleThreadExecutor()
    private val targetCandidates = mutableListOf<Device>()
    private val statusViews = HashMap<String, TextView>() // folderId -> 状态 TextView
    @Volatile private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync)

        targetText = findViewById(R.id.syncTargetText)
        folderContainer = findViewById(R.id.syncFolderContainer)
        progressBar = findViewById(R.id.syncProgressBar)
        progressText = findViewById(R.id.syncProgressText)

        findViewById<Button>(R.id.syncPickTargetBtn).setOnClickListener { pickTarget() }
        findViewById<Button>(R.id.syncAddBtn).setOnClickListener { addFolder() }
        findViewById<Button>(R.id.syncAllBtn).setOnClickListener { syncAll() }

        refreshTarget()
        refreshFolders()
    }

    private fun refreshTarget() {
        val target = SettingsStore.syncTarget(this)
        targetText.text = target.ifBlank { "未选择" }
    }

    // ---- 文件夹列表 ----

    private fun refreshFolders() {
        val folders = SettingsStore.syncFolders(this)
        statusViews.clear()
        folderContainer.removeAllViews()
        if (folders.isEmpty()) {
            val empty = TextView(this).apply {
                text = "尚未添加同步文件夹。点上方「添加同步文件夹」选择手机上的目录。"
                setTextColor(0xFF888888.toInt())
                textSize = 13f
                setPadding(0, 16, 0, 0)
            }
            folderContainer.addView(empty)
            return
        }
        for (f in folders) {
            folderContainer.addView(buildFolderRow(f))
        }
        refreshAllStatus()
    }

    private fun buildFolderRow(f: SettingsStore.SyncFolder): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val nameView = TextView(this).apply {
            text = f.name
            textSize = 15f
            setTextColor(0xFF1F2937.toInt())
        }
        val switch = Switch(this).apply {
            isChecked = f.enabled
            setOnCheckedChangeListener { _, checked ->
                updateFolder(f.copy(enabled = checked))
            }
        }
        top.addView(nameView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(switch)
        card.addView(top)

        val statusView = TextView(this).apply {
            text = "统计中…"
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
        }
        statusViews[f.id] = statusView
        card.addView(statusView)

        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        btns.addView(smallBtn("同步") { syncFolder(f) })
        btns.addView(smallBtn("重置") { resetFolder(f) })
        btns.addView(smallBtn("删除") { deleteFolder(f) })
        card.addView(btns)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0xFFE5E7EB.toInt())
        }
        card.addView(divider)
        return card
    }

    private fun smallBtn(text: String, onClick: () -> Unit): Button {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = 12
        }
        return Button(this).apply {
            this.text = text
            this.textSize = 12f
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun updateFolder(updated: SettingsStore.SyncFolder) {
        val list = SettingsStore.syncFolders(this).map { if (it.id == updated.id) updated else it }
        SettingsStore.setSyncFolders(this, list)
        Toast.makeText(this, if (updated.enabled) "已开启自动同步：${updated.name}" else "已关闭自动同步：${updated.name}", Toast.LENGTH_SHORT).show()
    }

    private fun refreshAllStatus() {
        val folders = SettingsStore.syncFolders(this)
        pool.execute {
            for (f in folders) {
                val st = SyncManager.computeFolderStatus(this, f)
                runOnUiThread {
                    statusViews[f.id]?.text =
                        "已同步 ${st.synced} / 共 ${st.total} 个文件（待同步 ${st.pending}）"
                }
            }
        }
    }

    // ---- 目标电脑 ----

    private fun pickTarget() {
        targetCandidates.clear()
        Toast.makeText(this, "正在扫描设备…", Toast.LENGTH_SHORT).show()
        Discovery.scan(
            DEFAULT_PORT,
            SettingsStore.deviceName(this),
            "Android",
            onFound = { d ->
                if (!d.isWeb) runOnUiThread {
                    val selfIp = Discovery.primaryIP()
                    if (d.ip != selfIp && !d.ip.startsWith("127.") && targetCandidates.none { it.addr == d.addr }) {
                        targetCandidates.add(d)
                    }
                }
            },
            onDone = {
                runOnUiThread { showTargetDialog() }
            }
        )
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
                SettingsStore.setSyncTarget(this, targetCandidates[which].addr)
                targetCandidates.clear()
                refreshTarget()
            }
            .setNegativeButton("取消") { _, _ -> targetCandidates.clear() }
            .show()
    }

    // ---- 添加 / 删除文件夹 ----

    private fun addFolder() {
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
            val name = treeName(treeUri)
            val id = "f" + System.currentTimeMillis()
            val list = SettingsStore.syncFolders(this).toMutableList()
            list.add(SettingsStore.SyncFolder(id, treeUri.toString(), name, true))
            SettingsStore.setSyncFolders(this, list)
            refreshFolders()
            Toast.makeText(this, "已添加同步文件夹：$name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteFolder(f: SettingsStore.SyncFolder) {
        AlertDialog.Builder(this)
            .setTitle("删除同步文件夹")
            .setMessage("确定移除「${f.name}」？不会删除手机或电脑上的任何文件。")
            .setPositiveButton("删除") { _, _ ->
                val list = SettingsStore.syncFolders(this).filter { it.id != f.id }
                SettingsStore.setSyncFolders(this, list)
                refreshFolders()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 同步 ----

    private fun syncFolder(f: SettingsStore.SyncFolder) {
        if (syncing) { Toast.makeText(this, "正在同步中…", Toast.LENGTH_SHORT).show(); return }
        if (SettingsStore.syncTarget(this).isBlank()) { Toast.makeText(this, "请先选择目标电脑", Toast.LENGTH_SHORT).show(); return }

        beginSync()
        pool.execute {
            SyncManager.syncFolder(this, f, progressCallback()) { sent, failed ->
                endSync(syncResultMsg(sent, failed))
                refreshAllStatus()
            }
        }
    }

    private fun syncAll() {
        if (syncing) { Toast.makeText(this, "正在同步中…", Toast.LENGTH_SHORT).show(); return }
        if (SettingsStore.syncTarget(this).isBlank()) { Toast.makeText(this, "请先选择目标电脑", Toast.LENGTH_SHORT).show(); return }
        val enabled = SettingsStore.syncFolders(this).filter { it.enabled }
        if (enabled.isEmpty()) { Toast.makeText(this, "没有开启自动同步的文件夹", Toast.LENGTH_SHORT).show(); return }

        beginSync()
        pool.execute {
            SyncManager.syncAllEnabled(this, progressCallback()) { sent, failed ->
                endSync(syncResultMsg(sent, failed))
                refreshAllStatus()
            }
        }
    }

    private fun resetFolder(f: SettingsStore.SyncFolder) {
        if (syncing) { Toast.makeText(this, "正在同步中…", Toast.LENGTH_SHORT).show(); return }
        if (SettingsStore.syncTarget(this).isBlank()) { Toast.makeText(this, "请先选择目标电脑", Toast.LENGTH_SHORT).show(); return }

        AlertDialog.Builder(this)
            .setTitle("扫描重置")
            .setMessage("将重新校验「${f.name}」与电脑同步目录的文件，并检测电脑端是否存在多余文件。确定继续？")
            .setPositiveButton("开始") { _, _ ->
                beginSync()
                pool.execute {
                    SyncManager.scanReset(this, f, progressCallback()) { sent, failed, extra ->
                        val base = syncResultMsg(sent, failed)
                        val msg = if (extra.isNotEmpty()) {
                            "$base\n电脑端存在 ${extra.size} 个多余文件（手机已删除）：\n" + extra.take(10).joinToString("\n") +
                                (if (extra.size > 10) "\n…等" else "")
                        } else "$base（无多余文件）"
                        runOnUiThread {
                            endSync(msg)
                            refreshAllStatus()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun progressCallback(): (Long, Long, String) -> Unit = { done, total, name ->
        runOnUiThread {
            val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
            progressBar.progress = pct
            progressText.text = "$pct%　${humanSize(done)} / ${humanSize(total)}　${name}"
        }
    }

    private fun beginSync() {
        syncing = true
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressBar.progress = 0
        progressText.text = "准备同步…"
    }

    private fun endSync(msg: String) {
        syncing = false
        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun syncResultMsg(sent: Int, failed: Int): String = when {
        sent == 0 && failed == 0 -> "没有需要同步的新文件"
        failed == 0 -> "同步完成：上传 $sent 个文件"
        else -> "同步结束：成功 $sent 个，失败 $failed 个"
    }

    // ---- 工具 ----

    private fun treeName(uri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: docId else docId
            } ?: docId
        } catch (_: Exception) { "源目录" }
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
    }
}
