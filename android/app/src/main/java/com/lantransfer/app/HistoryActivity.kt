package com.lantransfer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private val rows = mutableListOf<Any>() // HistoryStore.FileEntry 或 TextEntry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        rows.clear()
        rows.addAll(HistoryStore.listFiles(this))
        rows.addAll(HistoryStore.listTexts(this))

        val list = findViewById<ListView>(R.id.historyList)
        list.adapter = HistoryAdapter()

        findViewById<TextView>(R.id.historyEmpty).visibility =
            if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class HistoryAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getItemViewType(position: Int) = if (rows[position] is HistoryStore.FileEntry) 0 else 1
        override fun getViewTypeCount() = 2

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = rows[position]
            return if (item is HistoryStore.FileEntry) fileView(item, convertView, parent)
            else textView(item as HistoryStore.TextEntry, convertView, parent)
        }

        private fun fileView(f: HistoryStore.FileEntry, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_history_file, parent, false)
            v.findViewById<TextView>(R.id.hFileTitle).text = f.name
            v.findViewById<TextView>(R.id.hFileSub).text =
                "${humanSize(f.size)}　·　${f.from}　·　${formatTime(f.time)}"
            v.findViewById<Button>(R.id.hFileOpen).setOnClickListener { openFile(f.uri) }
            v.findViewById<Button>(R.id.hFileShare).setOnClickListener { shareFile(f.uri, f.name) }
            return v
        }

        private fun textView(t: HistoryStore.TextEntry, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_history_text, parent, false)
            v.findViewById<TextView>(R.id.hTextSub).text = "${t.from}　·　${formatTime(t.time)}"
            v.findViewById<TextView>(R.id.hTextBody).text = t.text
            v.findViewById<Button>(R.id.hTextCopy).setOnClickListener { copyText(t.text) }
            return v
        }
    }

    private fun openFile(uriStr: String) {
        val uri = Uri.parse(uriStr)
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        try {
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(uriStr: String, name: String) {
        val uri = Uri.parse(uriStr)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(i, "分享 $name"))
        } catch (_: Exception) {
            Toast.makeText(this, "无法分享", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
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
