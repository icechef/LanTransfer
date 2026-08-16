package com.lantransfer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

// 目录同步的后台通知（空闲触发时用）
object SyncNotifications {
    private const val CHANNEL = "sync"

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "目录同步", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    fun postResult(ctx: Context, sent: Int, failed: Int) {
        channel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = if (failed == 0) "目录同步完成" else "目录同步结束"
        val text = if (sent == 0 && failed == 0) "没有需要同步的新文件"
        else "上传 $sent 个，失败 $failed 个"
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        nm.notify(3000, n)
    }
}
