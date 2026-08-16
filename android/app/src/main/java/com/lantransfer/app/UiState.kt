package com.lantransfer.app

// 前台状态标记：MainActivity 可见时为 true，TransferService 据此决定
// 接收进度/完成走「应用内横幅」还是「系统通知栏」。
object UiState {
    @Volatile var foreground = false
}
