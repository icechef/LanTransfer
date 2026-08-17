//go:build !windows

package main

import (
	"os"
	"os/exec"
)

// setupRestartIO 非 Windows 平台：直接继承父进程的标准句柄（与改动前一致）。
func setupRestartIO(cmd *exec.Cmd) {
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
}
