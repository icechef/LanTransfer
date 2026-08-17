package main

import (
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"time"
)

// preRestart 在重启/退出前调用的钩子（由托盘在 Windows 上设置，用于清理图标）。
// 非 Windows 构建该钩子为 nil。
var preRestart func()

// trayHTTPPort 记录网页控制台端口，供托盘「打开网页」菜单使用。
var trayHTTPPort int

// restartSelf 启动自身的新进程（继承同样的参数，去掉 -restart 以免新进程又请求重启形成死循环），
// -tray 保留（让新进程继续在托盘运行），然后退出当前进程。新进程重新监听端口，达到热重启。
func restartSelf() {
	if preRestart != nil {
		preRestart()
	}
	exe, err := os.Executable()
	if err != nil {
		fmt.Println("restart failed:", err)
		return
	}
	args := os.Args[1:]
	filtered := make([]string, 0, len(args))
	for _, a := range args {
		if a == "-restart" {
			continue
		}
		filtered = append(filtered, a)
	}
	cmd := exec.Command(exe, filtered...)
	setupRestartIO(cmd)
	if err := cmd.Start(); err != nil {
		fmt.Println("restart failed:", err)
		return
	}
	// 给新进程一点时间接管端口，再退出
	time.Sleep(300 * time.Millisecond)
	os.Exit(0)
}

// doRestartRequest 向本机运行中的实例发送重启请求。
func doRestartRequest(httpPort int) error {
	url := "http://127.0.0.1:" + strconv.Itoa(httpPort) + "/api/restart"
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Post(url, "application/json", nil)
	if err != nil {
		return err
	}
	resp.Body.Close()
	return nil
}
