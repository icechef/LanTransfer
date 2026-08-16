# 局域网文件传输协议（transport_tools）

所有端（Go 电脑端 / Kotlin 手机端）共用本协议，走 **裸 TCP**，无组播、无加密。

## 端口

- `53318`：TCP 监听（发现 + 文件传输共用）。
- `53319`：HTTP 网页（仅电脑端，供浏览器/手机扫码兜底）。

## 帧格式

```
[4 字节大端 头长度 N][N 字节 JSON 头][payload 二进制]
```

- 头长度：`uint32` 大端，为 JSON 头的字节数。
- JSON 头：UTF-8 编码的 JSON 对象，**必须包含 `payloadLen` 字段**（payload 的字节数，无 payload 时为 0）。
- payload：二进制数据，仅 `file_data` 使用（文件块）。

## JSON 头字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | string | 消息类型，见下 |
| `payloadLen` | int64 | payload 字节数（读写双方都以此为准） |
| `sessionId` | string | 一次传输会话 ID（可选） |
| `fileId` | string | 文件 ID（一次会话内唯一） |
| `fileName` | string | 文件名（不含路径） |
| `fileSize` | int64 | 文件总字节数 |
| `chunkIndex` | int64 | 块序号，从 0 递增 |
| `totalBytes` | int64 | 已累计发送/接收字节数 |
| `deviceName` | string | 设备名 |
| `deviceType` | string | `PC` 或 `Android` |
| `version` | string | 协议版本 |
| `fileIndex` / `fileCount` | int | 第几个文件 / 总文件数（进度展示用） |
| `message` | string | 错误信息等 |
| `port` | int | 对端监听端口 |

## 消息类型

| type | 方向 | 说明 |
|---|---|---|
| `hello` | 双向 | 连接建立后**双方立即各发一条**，携带自身设备信息（握手/发现）。之后按需关闭或继续传输 |
| `file_meta` | 发→收 | 文件元数据，接收方准备落盘后回 `ack` |
| `file_data` | 发→收 | 文件块（1 MiB，最后一块可小于），payload 为块内容 |
| `file_end` | 发→收 | 单文件结束，携带 `totalBytes`，接收方校验后回 `ack` |
| `ack` | 双向 | 确认 |
| `error` | 双向 | 出错，`message` 携带原因 |

## 流程

### 发现（TCP 子网扫描，无组播）

1. 扫描方并发拨号 `/24` 内每个 IP 的 `:53318`（64 并发、每 IP 150–200ms 超时）。
2. 连上后双方各发 `hello` 并各读对方 `hello`，互相登记到设备列表。
3. 任一方随后关闭连接即完成发现；不关闭则继续传输。

### 发送（发方视角）

```
connect → hello ↔ hello
for each file:
  file_meta → (ack)
  file_data × N（1 MiB 块）
  file_end → (ack)
close
```

### 接收（收方视角）

```
accept → hello ↔ hello
loop:
  file_meta → 创建文件（重名加后缀）→ ack
  file_data → 写入文件
  file_end → 校验字节数 → ack
close
```

## 说明

- 接收方默认自动接收（对应"常驻后台直接接收"），无需逐文件确认。
- 接收目录：电脑端默认 `~/Downloads`（可配置）；Android 端写入系统 `Downloads`。
- 传输不加密，仅用于可信局域网。
