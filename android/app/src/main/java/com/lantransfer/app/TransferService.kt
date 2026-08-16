package com.lantransfer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class TransferService : Service() {

    companion object {
        private const val NOTIF_ID = 1
        private const val TEXT_NOTIF_ID = 2
        private const val CHANNEL_ID = "transfer"
        // 带 v2 后缀：旧版 channel 的 importance 一旦创建就不会被程序升级，换新 id 强制重建 HIGH 通道
        private const val CHANNEL_PROGRESS = "transfer_progress_v2"
        private const val CHANNEL_EVENTS = "transfer_events_v2"
        const val ACTION_RECEIVED = "com.lantransfer.app.RECEIVED"
        const val ACTION_RECEIVE_PROGRESS = "com.lantransfer.app.RECEIVE_PROGRESS"
        const val ACTION_TEXT = "com.lantransfer.app.TEXT"
        const val ACTION_PEER_FOUND = "com.lantransfer.app.PEER_FOUND"
        private val notifSeq = AtomicInteger(1000)
        private fun nextNotifId() = notifSeq.incrementAndGet()
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var serverThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIF_ID, buildNotification())
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "后台服务", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "传输进度", NotificationManager.IMPORTANCE_HIGH)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_EVENTS, "传输通知", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LanTransfer:receive").apply { acquire() }
        } catch (_: Exception) {}
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LanTransfer:wifi").apply { acquire() }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("局域网传输")
            .setContentText("后台接收服务运行中")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }

    private fun startServer() {
        if (running) return
        running = true
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(DEFAULT_PORT)
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    Thread { handle(client) }.start()
                }
            } catch (_: Exception) {}
        }
        serverThread!!.start()
    }

    private fun stopServer() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun handle(s: Socket) {
        var target: ReceiveStorage.Target? = null
        try {
            val out = s.getOutputStream()
            val input = s.getInputStream()
            Protocol.write(out, Protocol.hello(SettingsStore.deviceName(applicationContext), "Android"))
            val helloResp = Protocol.read(input) ?: return
            broadcastPeer(helloResp.first)

            var fromName = "未知设备"
            var currentName = ""
            var currentSize = 0L
            var written = 0L
            var lastNotify = 0L
            var lastSpeedTime = 0L
            var lastSpeedWritten = 0L
            var taskId = 0

            while (true) {
                val fr = Protocol.read(input) ?: break
                val h = fr.first
                val payload = fr.second
                when (h.type) {
                    "text" -> handleText(h.deviceName, h.text)

                    "file_meta" -> {
                        target?.discard(this)
                        target = null
                        fromName = h.deviceName ?: "未知设备"
                        val rel = h.relPath ?: ""
                        val relDir = if (rel.contains('/')) rel.substringBeforeLast('/') else ""
                        val name = if (rel.contains('/')) rel.substringAfterLast('/') else (h.fileName ?: "file.bin")
                        currentName = name
                        currentSize = h.fileSize
                        written = 0L
                        lastSpeedTime = 0L
                        lastSpeedWritten = 0L
                        taskId = nextNotifId()
                        val created = ReceiveStorage.open(this, name, relDir, !SettingsStore.autoSave(this))
                        if (created == null) {
                            Protocol.write(out, Protocol.Header().apply {
                                type = "error"; message = "create file failed"
                            })
                            postFailure(name, "无法创建文件")
                            return
                        }
                        target = created
                        reportProgress(taskId, fromName, name, currentSize, 0, 0.0)
                        Protocol.write(out, Protocol.Header().apply { type = "ack" })
                    }

                    "file_data" -> {
                        if (target != null && payload != null) {
                            target.out.write(payload)
                            written += payload.size
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastNotify >= 200) {
                                val dt = (now - lastSpeedTime) / 1000.0
                                val speed = if (lastSpeedTime > 0 && dt > 0) (written - lastSpeedWritten) / dt else 0.0
                                lastSpeedTime = now
                                lastSpeedWritten = written
                                lastNotify = now
                                reportProgress(taskId, fromName, currentName, currentSize, written, speed)
                            }
                        }
                    }

                    "file_end" -> {
                        val t = target
                        target = null
                        if (t != null) {
                            cancelProgress(taskId)
                            if (written != currentSize) {
                                t.discard(this)
                                postFailure(currentName, "接收大小不匹配")
                                Protocol.write(out, Protocol.Header().apply {
                                    type = "error"; message = "size mismatch"
                                })
                                return
                            }
                            if (t.pending) {
                                postPending(fromName, currentName, t.cacheFile!!.absolutePath, t.relDir, currentSize)
                            } else {
                                t.commit(this)
                                HistoryStore.addFile(this, t.uri.toString(), currentName, currentSize, fromName)
                                broadcastReceived(taskId, currentName, fromName, t.uri.toString())
                                if (!UiState.foreground) {
                                    postComplete(fromName, currentName, t.uri!!)
                                }
                            }
                        }
                        Protocol.write(out, Protocol.Header().apply { type = "ack" })
                    }
                }
            }
        } catch (_: Exception) {
            // 连接中断
        } finally {
            target?.discard(this)
            try { s.close() } catch (_: Exception) {}
        }
    }

    // 前台：广播进度给界面进度条；后台：更新通知栏进度（每个任务独立通知 id）
    private fun reportProgress(id: Int, from: String, name: String, total: Long, done: Long, speed: Double) {
        if (UiState.foreground) {
            broadcastProgress(id, from, name, done, total, speed)
        } else {
            postProgress(id, from, name, total, done, speed)
        }
    }

    // ---- 通知 ----

    private fun postProgress(id: Int, from: String, name: String, total: Long, done: Long, speed: Double) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b = Notification.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle("正在接收来自 $from 的文件")
            .setContentText("$name　${humanSize(speed.toLong())}/s")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (total > 0) {
            b.setProgress(100, ((done * 100) / total).toInt().coerceIn(0, 100), false)
        } else {
            b.setProgress(0, 0, true)
        }
        nm.notify(id, b.build())
    }

    private fun cancelProgress(id: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(id)
    }

    private fun postComplete(from: String, name: String, uri: Uri) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = Notification.Builder(this, CHANNEL_EVENTS)
            .setContentTitle("收到来自 $from 的文件")
            .setContentText(name)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent(uri))
            .build()
        nm.notify(nextNotifId(), n)
    }

    private fun postPending(from: String, name: String, cachePath: String, relDir: String, size: Long) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val appPi = PendingIntent.getActivity(
            this, nextNotifId(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_EVENTS)
            .setContentTitle("收到来自 $from 的文件")
            .setContentText("$name（待确认）")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(appPi)
            .addAction(0, "保存", actionPendingIntent(ReceiveActionsReceiver.ACTION_SAVE, cachePath, name, relDir, from, size))
            .addAction(0, "拒绝", actionPendingIntent(ReceiveActionsReceiver.ACTION_REJECT, cachePath, name, relDir, from, size))
            .build()
        nm.notify(nextNotifId(), n)
    }

    private fun postFailure(name: String, msg: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = Notification.Builder(this, CHANNEL_EVENTS)
            .setContentTitle("接收失败")
            .setContentText("$name：$msg")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        nm.notify(nextNotifId(), n)
    }

    private fun openPendingIntent(uri: Uri): PendingIntent {
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return PendingIntent.getActivity(
            this, nextNotifId(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionPendingIntent(action: String, cachePath: String, name: String, relDir: String, from: String, size: Long): PendingIntent {
        val i = Intent(this, ReceiveActionsReceiver::class.java).apply {
            this.action = action
            putExtra(ReceiveActionsReceiver.EXTRA_CACHE, cachePath)
            putExtra(ReceiveActionsReceiver.EXTRA_NAME, name)
            putExtra(ReceiveActionsReceiver.EXTRA_REL_DIR, relDir)
            putExtra(ReceiveActionsReceiver.EXTRA_FROM, from)
            putExtra(ReceiveActionsReceiver.EXTRA_SIZE, size)
        }
        return PendingIntent.getBroadcast(
            this, nextNotifId(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---- 广播 ----

    private fun broadcastProgress(id: Int, from: String, name: String, done: Long, total: Long, speed: Double) {
        val i = Intent(ACTION_RECEIVE_PROGRESS).apply {
            setPackage(packageName)
            putExtra("taskId", id)
            putExtra("from", from)
            putExtra("name", name)
            putExtra("done", done)
            putExtra("total", total)
            putExtra("speed", speed.toLong())
        }
        sendBroadcast(i)
    }

    private fun broadcastReceived(taskId: Int, name: String, from: String, uri: String) {
        val i = Intent(ACTION_RECEIVED).apply {
            setPackage(packageName)
            putExtra("taskId", taskId)
            putExtra("name", name)
            putExtra("from", from)
            putExtra("uri", uri)
        }
        sendBroadcast(i)
    }

    private fun broadcastText(from: String, text: String) {
        val i = Intent(ACTION_TEXT).apply {
            setPackage(packageName)
            putExtra("from", from)
            putExtra("text", text)
        }
        sendBroadcast(i)
    }

    private fun broadcastPeer(h: Protocol.Header) {
        val ip = h.ip?.takeIf { it.isNotEmpty() } ?: return
        if (ip == "127.0.0.1" || ip == "::1") return
        val i = Intent(ACTION_PEER_FOUND).apply {
            setPackage(packageName)
            putExtra("name", h.deviceName ?: "?")
            putExtra("type", h.deviceType ?: "?")
            putExtra("ip", ip)
            putExtra("port", if (h.port > 0) h.port else DEFAULT_PORT)
        }
        sendBroadcast(i)
    }

    private fun handleText(from: String?, text: String?) {
        val t = text ?: return
        val fromName = from ?: "未知设备"
        HistoryStore.addText(this, fromName, t)
        if (UiState.foreground) {
            broadcastText(fromName, t)
        } else {
            postTextNotification(fromName, t)
        }
    }

    private fun postTextNotification(from: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val copyIntent = Intent(this, ClipboardReceiver::class.java).apply {
            action = ClipboardReceiver.ACTION_COPY
            putExtra("text", text)
        }
        val copyPi = PendingIntent.getBroadcast(
            this, TEXT_NOTIF_ID, copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_EVENTS)
            .setContentTitle("$from 发送了文本")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(copyPi)
            .addAction(0, "复制", copyPi)
            .build()
        nm.notify(TEXT_NOTIF_ID, n)
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
