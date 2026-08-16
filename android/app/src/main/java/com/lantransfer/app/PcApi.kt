package com.lantransfer.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

// 手机内置的电脑服务器 HTTP 客户端（访问 transport.exe 的 53319 端口）。
object PcApi {

    data class RemoteFile(val name: String, val size: Long, val source: String, val path: String)
    data class Folder(val name: String, val path: String, val readonly: Boolean, val kind: String)
    data class DirEntry(val name: String, val isDir: Boolean, val size: Long, val mtime: Long)
    data class BrowseResult(val path: String, val writable: Boolean, val entries: List<DirEntry>)

    private fun base(ip: String) = "http://$ip:$DEFAULT_HTTP_PORT"

    private fun httpGet(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 10000
        c.requestMethod = "GET"
        c.setRequestProperty("X-Client", "app")
        val body = if (c.responseCode == 200) c.inputStream.readBytes() else null
        c.disconnect()
        body
    } catch (_: Exception) { null }

    // 拉取电脑服务器的网页终端列表（kind=web）
    fun listWebDevices(ip: String): List<Device> {
        val body = httpGet("${base(ip)}/api/devices") ?: return emptyList()
        val out = mutableListOf<Device>()
        try {
            val arr = JSONArray(String(body))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("kind", "tcp") != "web") continue
                val sid = o.optString("sid")
                if (sid.isNotEmpty()) {
                    out.add(Device.web(o.optString("name", "网页终端"), o.optString("ip", ""), ip, sid))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    // 列出电脑缓存目录（上传暂存 + 路径共享）的文件
    fun listStaged(ip: String): List<RemoteFile> {
        val body = httpGet("${base(ip)}/api/staged") ?: return emptyList()
        val out = mutableListOf<RemoteFile>()
        try {
            val arr = JSONArray(String(body))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(RemoteFile(
                    o.optString("name", "?"),
                    o.optLong("size", 0),
                    o.optString("source", ""),
                    o.optString("path", "")
                ))
            }
        } catch (_: Exception) {}
        return out
    }

    // 列出共享文件夹（含虚拟「缓存目录」）
    fun listFolders(ip: String): List<Folder> {
        val body = httpGet("${base(ip)}/api/folders") ?: return emptyList()
        val out = mutableListOf<Folder>()
        try {
            val arr = JSONArray(String(body))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Folder(
                    o.optString("name", "?"),
                    o.optString("path", ""),
                    o.optBoolean("readonly", false),
                    o.optString("kind", "folder")
                ))
            }
        } catch (_: Exception) {}
        return out
    }

    // 浏览目录（共享文件夹内或缓存目录）
    fun browse(ip: String, path: String): BrowseResult? {
        val url = if (path.isEmpty()) "${base(ip)}/api/browse"
        else "${base(ip)}/api/browse?path=${Uri.encode(path)}"
        val body = httpGet(url) ?: return null
        return try {
            val o = JSONObject(String(body))
            if (!o.optBoolean("ok", false)) return null
            val entries = mutableListOf<DirEntry>()
            val arr = o.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                entries.add(DirEntry(e.optString("name"), e.optBoolean("isDir"), e.optLong("size"), e.optLong("mtime")))
            }
            BrowseResult(o.optString("path", path), o.optBoolean("writable", true), entries)
        } catch (_: Exception) { null }
    }

    // 下载文件到本地输出流；path 非空 → 按路径（共享/文件夹），否则按缓存目录文件名
    fun download(ip: String, name: String, path: String, out: OutputStream, onProgress: ((Long) -> Unit)? = null): Boolean {
        return try {
            val url = if (path.isNotEmpty()) {
                "${base(ip)}/api/download/${Uri.encode(name)}?path=${Uri.encode(path)}"
            } else {
                "${base(ip)}/api/download/${Uri.encode(name)}?src=upload"
            }
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 120000
            c.setRequestProperty("X-Client", "app")
            if (c.responseCode != 200) { c.disconnect(); return false }
            c.inputStream.use { input ->
                val buf = ByteArray(1 shl 20)
                var written = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    written += n
                    onProgress?.invoke(written)
                }
            }
            c.disconnect()
            true
        } catch (_: Exception) { false }
    }

    // 上传文件到电脑缓存目录
    fun upload(ctx: Context, ip: String, files: List<PendingFile>): Boolean =
        multipartPost(ctx, ip, "/api/upload", emptyMap(), files)

    // 上传文件到共享目录（dir 为空 → 缓存目录）
    fun uploadToDir(ctx: Context, ip: String, dir: String, files: List<PendingFile>): Boolean =
        multipartPost(ctx, ip, "/api/upload-dir?dir=" + Uri.encode(dir), emptyMap(), files)

    // 经服务器中转发送文件给某个网页终端（targets=web:<sid>）
    fun sendFilesToWeb(ctx: Context, ip: String, sid: String, fromName: String, files: List<PendingFile>): Boolean =
        multipartPost(ctx, ip, "/api/send", mapOf("targets" to "web:$sid", "fromName" to fromName), files)

    // 经服务器中转发送文本给某个网页终端
    fun sendTextToWeb(ip: String, sid: String, fromName: String, text: String): Boolean {
        return try {
            val c = URL("${base(ip)}/api/sendtext").openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 10000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Client", "app")
            val payload = JSONObject().apply {
                put("targets", JSONArray().put("web:$sid"))
                put("text", text)
                put("fromName", fromName)
            }.toString()
            c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val ok = c.responseCode in 200..299
            c.disconnect()
            ok
        } catch (_: Exception) { false }
    }

    private fun multipartPost(
        ctx: Context, ip: String, path: String,
        fields: Map<String, String>, files: List<PendingFile>
    ): Boolean {
        return try {
            val boundary = "----LanTransfer" + System.currentTimeMillis()
            val c = URL("${base(ip)}$path").openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 120000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setRequestProperty("X-Client", "app")
            c.outputStream.use { raw ->
                val dos = DataOutputStream(raw)
                for ((k, v) in fields) {
                    dos.writeBytes("--$boundary\r\n")
                    dos.writeBytes("Content-Disposition: form-data; name=\"$k\"\r\n\r\n")
                    dos.write(v.toByteArray(Charsets.UTF_8))
                    dos.writeBytes("\r\n")
                }
                for (f in files) {
                    dos.writeBytes("--$boundary\r\n")
                    dos.write("Content-Disposition: form-data; name=\"files\"; filename=\"".toByteArray(Charsets.UTF_8))
                    dos.write(f.name.toByteArray(Charsets.UTF_8))
                    dos.writeBytes("\"\r\n")
                    dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
                    ctx.contentResolver.openInputStream(f.uri)?.use { it.copyTo(dos) }
                    dos.writeBytes("\r\n")
                }
                for (f in files) {
                    val rel = f.relPath.ifEmpty { f.name }
                    dos.writeBytes("--$boundary\r\n")
                    dos.writeBytes("Content-Disposition: form-data; name=\"relpaths\"\r\n\r\n")
                    dos.write(rel.toByteArray(Charsets.UTF_8))
                    dos.writeBytes("\r\n")
                }
                dos.writeBytes("--$boundary--\r\n")
                dos.flush()
            }
            val ok = c.responseCode in 200..299
            c.disconnect()
            ok
        } catch (_: Exception) { false }
    }
}
