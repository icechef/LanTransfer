package com.lantransfer.app

import android.content.Context
import android.os.Build

// 设置持久化：设备名 / 自动接收开关 / 接收目录。
// 接收目录两种模式：
//  - MediaStore（默认）：写到 Downloads 根目录或用户指定的子文件夹（receiveSubDir）。
//  - SAF：用户通过 ACTION_OPEN_DOCUMENT_TREE 授权的目录树（safTreeUri），优先级高于 MediaStore。
object SettingsStore {
    private const val PREFS = "settings"
    private const val KEY_NAME = "device_name"
    private const val KEY_AUTO_SAVE = "auto_save"
    private const val KEY_SUB_DIR = "receive_subdir"
    private const val KEY_SAF_TREE = "saf_tree_uri"
    private const val KEY_SHOW_IP = "show_ip"
    private const val KEY_SYNC_TREE = "sync_tree_uri"
    private const val KEY_SYNC_TARGET = "sync_target"
    private const val KEY_SYNC_ENABLED = "sync_enabled"
    private const val KEY_SYNC_MAP = "sync_map"
    private const val KEY_SYNC_LAST = "sync_last"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceName(ctx: Context): String =
        prefs(ctx).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: (Build.MODEL ?: "Android")

    fun setDeviceName(ctx: Context, name: String) {
        prefs(ctx).edit().putString(KEY_NAME, name.trim()).apply()
    }

    fun autoSave(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_SAVE, true)

    fun setAutoSave(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_SAVE, on).apply()
    }

    // MediaStore 子目录（相对 Downloads），空串表示 Downloads 根目录
    fun receiveSubDir(ctx: Context): String = prefs(ctx).getString(KEY_SUB_DIR, "") ?: ""

    fun setReceiveSubDir(ctx: Context, sub: String) {
        prefs(ctx).edit().putString(KEY_SUB_DIR, sub.trim()).apply()
    }

    // SAF 目录树 uri；空串表示未设置（走 MediaStore）
    fun safTreeUri(ctx: Context): String = prefs(ctx).getString(KEY_SAF_TREE, "") ?: ""

    fun setSafTreeUri(ctx: Context, uri: String) {
        prefs(ctx).edit().putString(KEY_SAF_TREE, uri).apply()
    }

    fun usingSaf(ctx: Context): Boolean = safTreeUri(ctx).isNotEmpty()

    // 是否在设备列表显示 IP（默认显示）
    fun showIp(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SHOW_IP, true)

    fun setShowIp(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SHOW_IP, on).apply()
    }

    fun receiveDirLabel(ctx: Context): String =
        if (usingSaf(ctx)) "自定义文件夹" else {
            val sub = receiveSubDir(ctx)
            if (sub.isEmpty()) "下载目录" else "下载目录/$sub"
        }

    // ---- 目录同步（手机 → 电脑）----

    // 同步源目录（SAF tree uri），空串表示未设置
    fun syncTreeUri(ctx: Context): String = prefs(ctx).getString(KEY_SYNC_TREE, "") ?: ""

    fun setSyncTreeUri(ctx: Context, uri: String) {
        prefs(ctx).edit().putString(KEY_SYNC_TREE, uri).apply()
    }

    // 同步目标电脑（"ip:port"），空串表示未设置
    fun syncTarget(ctx: Context): String = prefs(ctx).getString(KEY_SYNC_TARGET, "") ?: ""

    fun setSyncTarget(ctx: Context, addr: String) {
        prefs(ctx).edit().putString(KEY_SYNC_TARGET, addr.trim()).apply()
    }

    // 是否空闲时自动同步
    fun syncEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SYNC_ENABLED, false)

    fun setSyncEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SYNC_ENABLED, on).apply()
    }

    // 已同步文件清单（relPath -> {"mtime":x,"size":y} 的 JSON 字符串）
    fun syncMap(ctx: Context): String = prefs(ctx).getString(KEY_SYNC_MAP, "{}") ?: "{}"

    fun setSyncMap(ctx: Context, json: String) {
        prefs(ctx).edit().putString(KEY_SYNC_MAP, json).apply()
    }

    // 上次同步完成时间（epoch millis）
    fun syncLast(ctx: Context): Long = prefs(ctx).getLong(KEY_SYNC_LAST, 0L)

    fun setSyncLast(ctx: Context, millis: Long) {
        prefs(ctx).edit().putLong(KEY_SYNC_LAST, millis).apply()
    }
}
