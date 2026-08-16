package com.lantransfer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

// 空闲自动同步触发：熄屏且充电时触发一次目录同步（手机 → 电脑）。
class SyncTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!SettingsStore.syncEnabled(context)) return
        if (SettingsStore.syncTreeUri(context).isEmpty()) return
        if (SettingsStore.syncTarget(context).isBlank()) return

        // 仅当「熄屏 + 充电」时触发，符合「设备空闲时同步」
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return
        if (!isCharging(context)) return

        SyncManager.startBackgroundSync(context)
    }

    private fun isCharging(context: Context): Boolean {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) bm.isCharging else true
        } catch (_: Exception) { true }
    }
}
