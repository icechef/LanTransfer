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
| `md5` | string | `file_end` 时整文件 MD5（32 位小写 hex，用于校验和） |
| `mtime` | int64 | `file_meta` 时源文件的最后修改时间（Unix 秒），接收端据此还原文件修改日期 |
| `sync` | bool | `file_meta` 时为目录同步传输，电脑端落到专用同步目录（而非接收目录） |
| `offset` | int64 | 已废弃（断点续传已移除），保留字段仅为向后兼容，双端均忽略 |

## 消息类型

| type | 方向 | 说明 |
|---|---|---|
| `hello` | 双向 | 连接建立后**双方立即各发一条**，携带自身设备信息（握手/发现）。之后按需关闭或继续传输 |
| `file_meta` | 发→收 | 文件元数据，接收方准备落盘后回 `ack` |
| `file_data` | 发→收 | 文件块（1 MiB，最后一块可小于），payload 为块内容 |
| `file_end` | 发→收 | 单文件结束，携带 `totalBytes` 与 `md5`（整文件校验和），接收方校验后回 `ack` |
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
  file_meta → 落盘（存在同名 .part 且未收完则续传，回 ack{offset}）→ ack
  file_data → 写入文件（边写边算 MD5）
  file_end → 校验字节数 + MD5 → 通过则改名落盘并 ack，否则删 .part 回 error
close
```

### 校验与原子落盘（断点续传已移除）

- 接收端以 `<最终文件名>.part` 临时文件落盘，`file_meta` 时**总是新建/覆盖**（不复用旧 `.part`）。
- 两端都流式计算整文件 MD5，发送端在 `file_end` 携带，接收端比对；不一致视为损坏（删除 `.part` 并回 `error`）。
- 校验通过后 `.part` 改名为最终文件，并据 `mtime` 还原源文件的修改日期。
- 连接断开（断网）时接收端删除 `.part`，发送端整体判定失败——**断点续传已移除**，失败后需重新发送（两端行为一致，且避免 MediaStore 追加模式的不可靠续传）。
- 向后兼容：`file_end` 缺 `md5` 时跳过校验，缺 `mtime` 时保持落盘时的系统时间。

## 说明

- 接收方默认自动接收（对应"常驻后台直接接收"），无需逐文件确认。
- 接收目录：电脑端默认 `~/Downloads`（可配置）；Android 端写入系统 `Downloads`。
- 传输不加密，仅用于可信局域网。

## 目录同步（类 NAS）

- 手机端支持**多个同步源文件夹**，每个可单独开关自动同步；文件以 `file_meta{sync=true}` + `relPath = "<设备名>/<源目录名>/<相对路径>"` 发送到电脑端 TCP 端口。
- 电脑端把 `sync=true` 的文件统一落到**同步目录**（`syncDir`，默认 `接收目录/同步`，可在网页「设置」里单独配置），最终路径为 `同步目录/<设备名>/<源目录名>/<相对路径>`。
- 同步为**单向增量镜像**（手机 → 电脑）：手机端按文件夹记录已同步文件的 `relPath + mtime + size`，仅发送新增/变更的文件；不做删除传播（安全起见）。
- 「扫描重置」：清空同步进度后重新校验目录——手机拉取电脑端同步目录文件列表（`GET /api/sync/list?device=&folder=`），对比出电脑端「多余文件」（源里已删）并提示。
- 触发时机：设备空闲（充电中且熄屏）、手动「同步单个 / 全部同步」。
