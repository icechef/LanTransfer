package com.lantransfer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// 接收历史（文本 + 文件）。文件条目记录 uri，供历史页打开/分享。
object HistoryStore {
    private const val PREFS = "history"
    private const val KEY_TEXTS = "texts"
    private const val KEY_FILES = "files"
    private const val MAX = 200

    data class TextEntry(val from: String, val text: String, val time: Long)
    data class FileEntry(val uri: String, val name: String, val size: Long, val from: String, val time: Long)

    fun addText(context: Context, from: String, text: String) {
        val list = listTexts(context).toMutableList()
        list.add(0, TextEntry(from, text, System.currentTimeMillis()))
        if (list.size > MAX) list.subList(MAX, list.size).clear()
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().apply {
            put("from", e.from); put("text", e.text); put("time", e.time)
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TEXTS, arr.toString()).apply()
    }

    fun addFile(context: Context, uri: String, name: String, size: Long, from: String) {
        val list = listFiles(context).toMutableList()
        list.add(0, FileEntry(uri, name, size, from, System.currentTimeMillis()))
        if (list.size > MAX) list.subList(MAX, list.size).clear()
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().apply {
            put("uri", e.uri); put("name", e.name); put("size", e.size)
            put("from", e.from); put("time", e.time)
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_FILES, arr.toString()).apply()
    }

    fun listTexts(context: Context): List<TextEntry> {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TEXTS, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TextEntry(o.optString("from"), o.optString("text"), o.optLong("time"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun listFiles(context: Context): List<FileEntry> {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FILES, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FileEntry(o.optString("uri"), o.optString("name"), o.optLong("size"),
                    o.optString("from"), o.optLong("time"))
            }
        } catch (_: Exception) { emptyList() }
    }
}
