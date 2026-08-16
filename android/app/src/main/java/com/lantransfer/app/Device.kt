package com.lantransfer.app

const val DEFAULT_PORT = 53318
const val DEFAULT_HTTP_PORT = 53319

data class Device(
    val name: String,
    val type: String,
    val ip: String,          // tcp：设备 IP；web：客户端 IP（显示用）
    val port: Int = DEFAULT_PORT,
    // kind: "tcp"（可 TCP 直连）或 "web"（网页终端，走服务器中转）
    val kind: String = "tcp",
    // web 终端的 session id（中转目标编码 web:<sid>）
    val sid: String? = null,
    // web 终端所属服务器 IP（中转经此服务器）
    val serverIp: String? = null
) {
    val addr: String get() = "$ip:$port"
    val isWeb: Boolean get() = kind == "web"
    val isPc: Boolean get() = kind == "tcp" && type == "PC"

    companion object {
        fun web(name: String, clientIp: String, serverIp: String, sid: String): Device =
            Device(name = name, type = "网页", ip = clientIp, port = DEFAULT_HTTP_PORT,
                kind = "web", sid = sid, serverIp = serverIp)
    }
}
