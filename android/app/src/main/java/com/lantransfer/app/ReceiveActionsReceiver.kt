package com.lantransfer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

// 处理「待确认接收」通知上的「保存 / 拒绝」动作
class ReceiveActionsReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SAVE = "com.lantransfer.app.SAVE_PENDING"
        const val ACTION_REJECT = "com.lantransfer.app.REJECT_PENDING"
        const val EXTRA_CACHE = "cache"
        const val EXTRA_NAME = "name"
        const val EXTRA_REL_DIR = "rel_dir"
        const val EXTRA_FROM = "from"
        const val EXTRA_SIZE = "size"
        private const val CHANNEL = "transfer_events"
        private var seq = 2000
    }

    override fun onReceive(context: Context, intent: Intent) {
        val cache = intent.getStringExtra(EXTRA_CACHE) ?: return
        val name = intent.getStringExtra(EXTRA_NAME) ?: "文件"
        val relDir = intent.getStringExtra(EXTRA_REL_DIR) ?: ""
        val from = intent.getStringExtra(EXTRA_FROM) ?: "未知设备"
        val size = intent.getLongExtra(EXTRA_SIZE, 0L)

        when (intent.action) {
            ACTION_SAVE -> {
                val uri = ReceiveStorage.savePending(context, cache, name, relDir)
                if (uri != null) {
                    HistoryStore.addFile(context, uri.toString(), name, size, from)
                    notifyResult(context, "已保存来自 $from 的文件", name, uri)
                } else {
                    notifyResult(context, "保存失败", name, null)
                }
            }
            ACTION_REJECT -> {
                ReceiveStorage.rejectPending(cache)
                notifyResult(context, "已拒绝来自 $from 的文件", name, null)
            }
        }
    }

    private fun notifyResult(context: Context, title: String, text: String, uri: Uri?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "传输通知", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val b = Notification.Builder(context, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val open = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            b.setContentIntent(PendingIntent.getActivity(
                context, seq++, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        }
        nm.notify(seq++, b.build())
    }
}
