# LanTransfer

局域网文件传输工具，用于替代 LocalSend。支持电脑、手机、浏览器之间互传文件，不依赖外网，也无需单独部署服务端。

本项目源于 LocalSend 的组播发现在装有 VMware / Hyper-V 等虚拟网卡的机器上不可靠——设备之间常出现单向可见的情况。因此这里改用 TCP 扫描 /24 网段做发现，避开组播被虚拟网卡干扰的问题。

## 工作原理

- **发现**：扫描 /24 网段内每个 IP 的 `53318` 端口，64 并发，单次扫描耗时通常在几百毫秒内。不使用组播，因此不受虚拟网卡影响。
- **传输**：裸 TCP，1 MiB 块，不加密，不加额外封装。
- **校验**：整文件 MD5，收发两端流式计算并比对，不一致则丢弃。落盘时先写 `.part` 临时文件，校验通过后再改为正式文件名。
- **修改时间**：文件传输后保留源文件的修改时间（Android 端通过 MediaStore 还原 mtime，处理较复杂）。

## 怎么用

### 电脑端

运行 `transport.exe`（Windows，双击即可）。启动后控制台会打印本机 IP 与网页控制台地址。

```
transport.exe [选项]

-port 53318    TCP 监听端口（发现 + 传输）
-scan-port 0   扫描端口，默认与 -port 一致
-http 53319    网页控制台端口
-name xxx      设备名（默认使用主机名）
-dir xxx       接收目录（默认系统下载目录）
-serve         后台模式，不读取 stdin
-tray          Windows 托盘
-restart       重启已运行的实例
```

不传参数即为交互模式：

```
scan                          扫描网段并列出设备
list                          列出已发现的设备
send 192.168.1.5 文件1 文件2   发送文件
text 192.168.1.5 一段文字      发送文本
exit                          退出
```

### 手机端

安装 `LanTransfer-debug.apk` 并授予通知权限。App 通过前台服务常驻，退到后台或熄屏时仍可接收文件，接收结果保存在系统 Downloads 目录。

打开 App 后自动扫描并列出电脑端设备，选择目标即可发送文件或文本。手机之间同样可以互传。

### 浏览器

浏览器打开 `http://<电脑IP>:53319`。网页控制台右上角提供二维码，手机扫描后可直接进入。网页端支持设备发现、文件与文本发送、进度查看、暂存文件管理。由于浏览器无法监听 TCP 端口，网页端发往其他网页端的内容会经电脑中转。

## 网页控制台功能

- 设备发现与实时传输进度
- 文件发送（可多选目标设备）、文本发送、剪贴板
- 文件上传暂存，供其他设备取用
- 收到的文本、历史记录
- 设置：接收目录 / 缓存目录 / 同步目录 / 自动保存 / 设备名
- 共享文件夹：将电脑目录共享给手机浏览、下载、上传，可设为只读
- 远程权限：其他浏览器访问时，默认仅可查看共享文件夹，删除文件、修改设置需单独开启

## 目录同步

手机可将多个文件夹（SAF 目录树）同步到电脑，实现单向的类 NAS 备份。

- 每个文件夹独立开关，增量同步（仅发送新增与变更的文件）
- 电脑端落盘路径为 `同步目录/<设备名>/<源目录名>/<相对路径>`，默认 `接收目录/同步`
- 触发条件：设备空闲（充电且熄屏）时自动同步，或手动触发
- 仅单向（手机 → 电脑），不会删除电脑端文件

## 从源码构建

### 电脑端

```bash
cd pc
go build -o transport.exe .
```

Go 1.26，依赖只有 `github.com/skip2/go-qrcode`。

### 手机端

```bash
cd android
# JDK 17 + Android SDK + Gradle 8.9
export JAVA_HOME=<jdk17>
export ANDROID_HOME=<sdk>
export ANDROID_USER_HOME=<安卓配置目录>
gradle-8.9/bin/gradle.bat clean assembleDebug --no-daemon
```

minSdk 29 / targetSdk 34。签名使用仓库内的 `android/debug.keystore`。

## 目录结构

```
├── pc/                  Go 电脑端
│   ├── main.go          入口、命令行、交互命令
│   ├── protocol.go      帧协议编解码
│   ├── transfer.go      收发 + 进度
│   ├── discovery.go     TCP 子网扫描发现
│   ├── web.go           HTTP API
│   ├── web/index.html   网页控制台（单文件，原生 JS）
│   ├── folders.go       共享文件夹浏览/上传/路径校验
│   ├── session.go       网页会话 + 收件箱
│   ├── progress.go      传输进度
│   ├── config.go        配置持久化
│   ├── share.go         路径共享
│   └── tray_windows.go  Windows 托盘
├── android/             Kotlin 手机端
│   └── app/src/main/java/com/lantransfer/app/
│       ├── MainActivity.kt           主界面
│       ├── TransferService.kt        前台服务（常驻接收）
│       ├── Discovery.kt / Protocol.kt
│       ├── ReceiveStorage.kt         接收落盘（MediaStore Downloads）
│       ├── FileMtime.kt / FileMtimeWriter.kt   mtime 解析与还原
│       ├── PcApi.kt / PcFilesActivity.kt       访问电脑共享文件夹
│       ├── SyncManager.kt / SyncActivity.kt / SyncTriggerReceiver.kt   目录同步
│       ├── SettingsStore.kt / SettingsActivity.kt
│       ├── HistoryStore.kt / HistoryActivity.kt
│       └── ClipboardReceiver.kt / ReceiveActionsReceiver.kt
├── protocol.md          协议规范（两端共用，修改协议需两端同步）
└── dist/                构建产物（已 gitignore）
```

## 协议

两端共用一套裸 TCP 帧协议，细节见 [`protocol.md`](protocol.md)。概要如下：

- 端口 `53318`（TCP 传输）、`53319`（HTTP 网页）
- 帧 = `[4 字节大端头长度][JSON 头][payload]`
- 消息类型：`hello` / `file_meta` / `file_data` / `file_end` / `ack` / `error` / `text`
- 文件块 1 MiB，`file_end` 携带整文件 MD5

## 配置

电脑端配置保存在 `~/.transport_tools.json`，首次运行自动生成。主要字段：

| 字段 | 默认 | 说明 |
|---|---|---|
| `receiveDir` | 系统下载目录 | 接收目录 |
| `cacheDir` | `<receiveDir>/.cache` | 缓存（上传暂存 + 网页中转） |
| `syncDir` | `<receiveDir>/同步` | 目录同步落盘位置 |
| `autoSave` | `true` | 关闭后收到的文件进入待确认区 |
| `deviceName` | 主机名 | 设备名 |
| `sharedFolders` | `[]` | 共享给手机的文件夹 `{path, name, readonly}` |
| `remotePerms` | 见下 | 远程浏览器权限 |

`remotePerms`：`viewSharedFolders`（默认 true）、`deleteStaged`、`modifySettings`（后两个默认 false）。

## 已知限制

- 明文传输，不加密，仅适用于可信局域网，不应暴露到公网。
- 断点续传已移除（Android MediaStore 追加写入不可靠），传输中断后需重新发送。
- 目录同步为单向（手机 → 电脑），不做删除传播。
- 电脑端主要在 Windows 环境测试，其他平台未充分验证。

## 许可

本仓库为私有项目，保留所有权利。
