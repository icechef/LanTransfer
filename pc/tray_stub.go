//go:build !windows

package main

// runTray 在非 Windows 平台是空实现（系统托盘仅 Windows 支持）。
func runTray() {}
