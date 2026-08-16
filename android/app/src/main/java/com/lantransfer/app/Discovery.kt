package com.lantransfer.app

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.Executors

object Discovery {
    private val pool = Executors.newFixedThreadPool(64)

    private fun localIPv4s(): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is Inet4Address) {
                        out.add(a.address.clone())
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun isAPIPA(b: ByteArray): Boolean =
        (b[0].toInt() and 0xff) == 169 && (b[1].toInt() and 0xff) == 254

    private fun isPrivate(b: ByteArray): Boolean {
        val a = b[0].toInt() and 0xff
        val c = b[1].toInt() and 0xff
        return a == 10 || (a == 172 && c in 16..31) || (a == 192 && c == 168)
    }

    // 本机"对外"IP：优先取默认路由网卡 IP（跨网卡/热点场景下，这才是对端能连到的地址）
    fun primaryIP(): String {
        try {
            val s = DatagramSocket()
            s.connect(InetSocketAddress("8.8.8.8", 80))
            val ip = (s.localAddress as? Inet4Address)?.hostAddress
            s.close()
            if (!ip.isNullOrEmpty()) return ip
        } catch (_: Exception) {
        }
        return localIPv4s().firstOrNull { isPrivate(it) }
            ?.let { InetAddress.getByAddress(it).hostAddress } ?: ""
    }

    fun scan(
        port: Int,
        selfName: String,
        selfType: String,
        onFound: (Device) -> Unit,
        onDone: (() -> Unit)? = null
    ) {
        Thread {
            val bases = localIPv4s()
            var remaining = 0
            val lock = Object()

            fun dec() {
                synchronized(lock) {
                    remaining--
                    if (remaining == 0) onDone?.invoke()
                }
            }

            for (base in bases) {
                if (isAPIPA(base)) continue
                for (i in 1..254) {
                    val ip = byteArrayOf(base[0], base[1], base[2], i.toByte())
                    // 跳过本机自己的 IP，避免扫到自己
                    if (bases.any { it.contentEquals(ip) }) continue
                    synchronized(lock) { remaining++ }
                    pool.execute {
                        try {
                            probe(ip, port, selfName, selfType, onFound)
                        } finally {
                            dec()
                        }
                    }
                }
            }
            synchronized(lock) {
                if (remaining == 0) onDone?.invoke()
            }
        }.start()
    }

    private fun probe(ip: ByteArray, port: Int, selfName: String, selfType: String, onFound: (Device) -> Unit) {
        try {
            val addr = InetAddress.getByAddress(ip)
            val s = Socket()
            s.connect(InetSocketAddress(addr, port), 250)
            s.soTimeout = 1000
            Protocol.write(s.getOutputStream(), Protocol.hello(selfName, selfType))
            val resp = Protocol.read(s.getInputStream())
            s.close()
            if (resp != null) {
                val h = resp.first
                val p = if (h.port > 0) h.port else port
                val ip = h.ip?.takeIf { it.isNotEmpty() } ?: (addr.hostAddress ?: "")
                onFound(Device(h.deviceName ?: "?", h.deviceType ?: "?", ip, p))
            }
        } catch (_: Exception) {
        }
    }

    // 手动直连某个 IP:端口（发现兜底）：握手成功则返回该设备
    fun probeOne(host: String, port: Int, selfName: String, selfType: String): Device? {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 2000)
            s.soTimeout = 3000
            Protocol.write(s.getOutputStream(), Protocol.hello(selfName, selfType))
            val resp = Protocol.read(s.getInputStream())
            s.close()
            if (resp != null) {
                val h = resp.first
                val p = if (h.port > 0) h.port else port
                val ip = h.ip?.takeIf { it.isNotEmpty() } ?: host
                Device(h.deviceName ?: "?", h.deviceType ?: "?", ip, p)
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
