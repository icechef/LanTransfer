//go:build windows

package main

import (
	"os"
	"os/exec"
	"syscall"
)

// setupRestartIO 配置重启时子进程的标准句柄与创建标志。
// tray 模式下进程已 FreeConsole，os.Stdin/out/err 句柄可能失效（或重定向到管道），
// 直接传给 CreateProcess 会报 “The request is not supported”。此时不继承，交给
// CreateProcess 默认处理：无控制台则子进程无控制台（tray 自行处理日志）。
// 有控制台（-serve）则继承，保持原有日志行为。
func setupRestartIO(cmd *exec.Cmd) {
	if hasConsole() {
		cmd.Stdin = os.Stdin
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{
		CreationFlags: 0x00000200, // CREATE_NEW_PROCESS_GROUP：让新进程独立于父进程，避免一起被终止/Ctrl-C 串扰
	}
}
