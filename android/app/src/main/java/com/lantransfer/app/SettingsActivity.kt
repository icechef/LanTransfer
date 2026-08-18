package com.lantransfer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var nameInput: EditText
    private lateinit var autoSaveSwitch: Switch
    private lateinit var showIpSwitch: Switch
    private lateinit var dirLabel: TextView
    private lateinit var subdirInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        nameInput = findViewById(R.id.nameInput)
        autoSaveSwitch = findViewById(R.id.autoSaveSwitch)
        showIpSwitch = findViewById(R.id.showIpSwitch)
        dirLabel = findViewById(R.id.dirLabel)
        subdirInput = findViewById(R.id.subdirInput)

        nameInput.setText(SettingsStore.deviceName(this))
        autoSaveSwitch.isChecked = SettingsStore.autoSave(this)
        showIpSwitch.isChecked = SettingsStore.showIp(this)
        subdirInput.setText(SettingsStore.receiveSubDir(this))
        refreshDirLabel()

        findViewById<Button>(R.id.saveBtn).setOnClickListener {
            save()
            finish()
        }
        findViewById<Button>(R.id.pickDirBtn).setOnClickListener { pickDir() }
        findViewById<Button>(R.id.resetDirBtn).setOnClickListener {
            SettingsStore.setSafTreeUri(this, "")
            refreshDirLabel()
            Toast.makeText(this, "已恢复默认下载目录", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.mtimeTestBtn).setOnClickListener { runMtimeSelfTest() }
        findViewById<Button>(R.id.manageStorageBtn).setOnClickListener { requestAllFilesAccess() }
    }

    // 设备端 mtime 能力自检：在 Downloads 建临时文件、用固定时间戳跑 applyMtime、报告结果。
    // 让你一次看清本机文件系统写入到底成功与否，不必再靠一轮轮试。
    private fun runMtimeSelfTest() {
        Thread {
            val report = FileMtimeWriter.selfTest(this)
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("mtime 能力自检")
                    .setMessage(report)
                    .setPositiveButton("确定", null)
                    .show()
            }
        }.start()
    }

    // 兜底：若文件系统写入失败，请求「所有文件访问权限」后重试自检。
    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "无法打开授权页", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Android 11 以下无需此权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshDirLabel() {
        dirLabel.text = "当前接收目录：${SettingsStore.receiveDirLabel(this)}"
    }

    private fun save() {
        SettingsStore.setDeviceName(this, nameInput.text.toString())
        SettingsStore.setAutoSave(this, autoSaveSwitch.isChecked)
        SettingsStore.setShowIp(this, showIpSwitch.isChecked)
        SettingsStore.setReceiveSubDir(this, subdirInput.text.toString())
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }

    private fun pickDir() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, 200)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == Activity.RESULT_OK && data?.data != null) {
            val treeUri = data.data!!
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            SettingsStore.setSafTreeUri(this, treeUri.toString())
            refreshDirLabel()
            Toast.makeText(this, "接收目录已设置", Toast.LENGTH_SHORT).show()
        }
    }
}
