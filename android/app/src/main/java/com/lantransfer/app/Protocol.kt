package com.lantransfer.app

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

object Protocol {
    const val VERSION = "0.1.0"

    class Header {
        var type: String = ""
        var payloadLen: Long = 0
        var sessionId: String? = null
        var fileId: String? = null
        var fileName: String? = null
        var fileSize: Long = 0
        var chunkIndex: Long = 0
        var totalBytes: Long = 0
        var deviceName: String? = null
        var deviceType: String? = null
        var version: String? = null
        var fileIndex: Int = 0
        var fileCount: Int = 0
        var message: String? = null
        var port: Int = 0
        var ip: String? = null
        var text: String? = null
        var relPath: String? = null
        var md5: String? = null      // file_end 时整文件 MD5（32 位小写 hex）
        var mtime: Long = 0          // file_meta 时源文件修改时间（Unix 秒）
        var sync: Boolean = false    // file_meta 时为目录同步传输（落到电脑同步专用目录）
        var offset: Long = 0         // 已废弃（断点续传移除），仅向后兼容

        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("type", type)
            o.put("payloadLen", payloadLen)
            sessionId?.let { o.put("sessionId", it) }
            fileId?.let { o.put("fileId", it) }
            fileName?.let { o.put("fileName", it) }
            if (fileSize != 0L) o.put("fileSize", fileSize)
            if (chunkIndex != 0L) o.put("chunkIndex", chunkIndex)
            if (totalBytes != 0L) o.put("totalBytes", totalBytes)
            deviceName?.let { o.put("deviceName", it) }
            deviceType?.let { o.put("deviceType", it) }
            version?.let { o.put("version", it) }
            if (fileIndex != 0) o.put("fileIndex", fileIndex)
            if (fileCount != 0) o.put("fileCount", fileCount)
            message?.let { o.put("message", it) }
            if (port != 0) o.put("port", port)
            ip?.let { o.put("ip", it) }
            text?.let { o.put("text", it) }
            relPath?.let { o.put("relPath", it) }
            md5?.let { o.put("md5", it) }
            if (mtime != 0L) o.put("mtime", mtime)
            if (sync) o.put("sync", true)
            if (offset != 0L) o.put("offset", offset)
            return o
        }

        companion object {
            fun fromJson(o: JSONObject): Header {
                val h = Header()
                h.type = o.optString("type")
                h.payloadLen = o.optLong("payloadLen", 0)
                h.sessionId = o.optString("sessionId").takeIf { it.isNotEmpty() }
                h.fileId = o.optString("fileId").takeIf { it.isNotEmpty() }
                h.fileName = o.optString("fileName").takeIf { it.isNotEmpty() }
                h.fileSize = o.optLong("fileSize", 0)
                h.chunkIndex = o.optLong("chunkIndex", 0)
                h.totalBytes = o.optLong("totalBytes", 0)
                h.deviceName = o.optString("deviceName").takeIf { it.isNotEmpty() }
                h.deviceType = o.optString("deviceType").takeIf { it.isNotEmpty() }
                h.version = o.optString("version").takeIf { it.isNotEmpty() }
                h.fileIndex = o.optInt("fileIndex", 0)
                h.fileCount = o.optInt("fileCount", 0)
                h.message = o.optString("message").takeIf { it.isNotEmpty() }
                h.port = o.optInt("port", 0)
                h.ip = o.optString("ip").takeIf { it.isNotEmpty() }
                h.text = o.optString("text").takeIf { it.isNotEmpty() }
                h.relPath = o.optString("relPath").takeIf { it.isNotEmpty() }
                h.md5 = o.optString("md5").takeIf { it.isNotEmpty() }
                h.mtime = o.optLong("mtime", 0)
                h.sync = o.optBoolean("sync", false)
                h.offset = o.optLong("offset", 0)
                return h
            }
        }
    }

    fun hello(name: String, type: String): Header {
        val h = Header()
        h.type = "hello"
        h.deviceName = name
        h.deviceType = type
        h.version = VERSION
        h.port = DEFAULT_PORT
        h.ip = Discovery.primaryIP()
        return h
    }

    fun write(out: OutputStream, h: Header, payload: ByteArray? = null) {
        val p = payload ?: ByteArray(0)
        h.payloadLen = p.size.toLong()
        val json = h.toJson().toString().toByteArray(Charsets.UTF_8)
        val dout = DataOutputStream(out)
        dout.writeInt(json.size)
        dout.write(json)
        if (p.isNotEmpty()) dout.write(p)
        dout.flush()
    }

    fun read(input: InputStream): Pair<Header, ByteArray?>? {
        return try {
            val din = DataInputStream(input)
            val len = din.readInt()
            if (len <= 0 || len > (1 shl 20)) return null
            val jsonBytes = ByteArray(len)
            din.readFully(jsonBytes)
            val h = Header.fromJson(JSONObject(String(jsonBytes, Charsets.UTF_8)))
            var payload: ByteArray? = null
            if (h.payloadLen > 0) {
                if (h.payloadLen > (1L shl 30)) return null
                payload = ByteArray(h.payloadLen.toInt())
                din.readFully(payload)
            }
            h to payload
        } catch (e: Exception) {
            null
        }
    }
}
