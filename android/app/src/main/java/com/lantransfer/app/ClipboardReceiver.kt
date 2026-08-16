package com.lantransfer.app

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

// 文本通知的「复制」动作：直接覆盖剪贴板，不必进 App
class ClipboardReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_COPY = "com.lantransfer.app.COPY_TEXT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY) return
        val text = intent.getStringExtra("text") ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
