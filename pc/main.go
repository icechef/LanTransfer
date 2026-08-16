package main

import (
	"bufio"
	"flag"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// selfListenPort is used by discovery to skip our own listener during scans.
var selfListenPort int

// 全局显示名：网页端 /api/name 可修改，TCP hello 与扫描自报都用它，
// 这样手机发现的「PC」显示的是网页自定义名，而不是电脑主机名。
var (
	nameMu   sync.Mutex
	selfName string
)

func setSelfName(n string) {
	nameMu.Lock()
	selfName = n
	nameMu.Unlock()
}

func getSelfName() string {
	nameMu.Lock()
	defer nameMu.Unlock()
	return selfName
}

func main() {
	var (
		port     int
		scanPort int
		httpPort int
		name     string
		dir      string
		serve    bool
	)
	host, _ := os.Hostname()
	flag.IntVar(&port, "port", 53318, "TCP listen port (discovery + transfer)")
	flag.IntVar(&scanPort, "scan-port", 0, "port probed during discovery scans (defaults to -port)")
	flag.IntVar(&httpPort, "http", 53319, "HTTP web port (dashboard + browser fallback)")
	flag.StringVar(&name, "name", host, "device name")
	flag.StringVar(&dir, "dir", defaultReceiveDir(), "receive directory")
	flag.BoolVar(&serve, "serve", false, "run as a background service (no stdin interaction)")
	flag.Parse()
	if scanPort == 0 {
		scanPort = port
	}

	// 是否显式指定了 -name（显式指定则优先于持久化的设备别称）
	nameExplicit := false
	flag.Visit(func(f *flag.Flag) {
		if f.Name == "name" {
			nameExplicit = true
		}
	})

	selfListenPort = port
	selfName = name

	if err := os.MkdirAll(dir, 0o755); err != nil {
		fmt.Println("cannot create receive dir:", err)
		os.Exit(1)
	}

	reg := newDeviceRegistry()

	// 加载持久化配置（接收目录 + 缓存目录 + 自动保存开关），-dir 作为首次运行的默认值
	cfg := loadConfig(dir)
	_ = os.MkdirAll(cfg.ReceiveDir, 0o755)
	_ = os.MkdirAll(cfg.CacheDir, 0o755)

	// 用持久化的设备别称覆盖主机名（除非显式 -name）
	if !nameExplicit && cfg.DeviceName != "" {
		name = cfg.DeviceName
		selfName = name
	}

	// TCP listener: discovery + file receive.
	go func() {
		addr := net.JoinHostPort("0.0.0.0", strconv.Itoa(port))
		ln, err := net.Listen("tcp", addr)
		if err != nil {
			fmt.Println("listen error:", err)
			return
		}
		fmt.Printf("[listening] tcp :%d\n", port)
		for {
			conn, err := ln.Accept()
			if err != nil {
				continue
			}
			go serveConn(conn, reg, "PC")
		}
	}()

	// Web dashboard + browser fallback.
	ws := &webServer{reg: reg, name: name, dtype: "PC", port: port, scanPort: scanPort, httpPort: httpPort, receiveDir: cfg.ReceiveDir, cacheDir: cfg.CacheDir, sess: newSessionManager()}
	go func() {
		addr := net.JoinHostPort("0.0.0.0", strconv.Itoa(httpPort))
		if err := startWeb(addr, ws); err != nil {
			fmt.Println("web error:", err)
		}
	}()

	// Give the listener a moment, then print self info.
	time.Sleep(200 * time.Millisecond)
	ips, _ := localIPv4s()
	fmt.Printf("设备名: %s  类型: PC  接收目录: %s\n", name, cfg.ReceiveDir)
	mainIP := primaryIP()
	if mainIP == "" {
		mainIP = preferredIP(ips)
	}
	if mainIP != "" {
		fmt.Printf("  网页控制台: http://%s:%d\n", mainIP, httpPort)
	}
	// 列出其它网卡地址（仅提示，手机可能连不到虚拟网卡/169.254 地址）
	extra := 0
	for _, ip := range ips {
		if ip.String() != mainIP {
			extra++
		}
	}
	if extra > 0 {
		fmt.Printf("  （另有 %d 个网卡地址，见网页控制台）\n", extra)
	}

	if serve {
		fmt.Println("服务模式运行中（Ctrl+C 退出）...")
		for {
			time.Sleep(time.Hour)
		}
	}

	fmt.Println("命令: scan | list | send <ip[:port]> <文件...> | exit")

	sc := bufio.NewScanner(os.Stdin)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" {
			continue
		}
		fields := strings.Fields(line)
		switch fields[0] {
		case "scan":
			devs := scanSubnet(reg, scanPort, "PC")
			printDevices(devs)
		case "list":
			printDevices(reg.list())
		case "send":
			if len(fields) < 3 {
				fmt.Println("用法: send <ip[:port]> <文件...>")
				continue
			}
			target := fields[1]
			if !strings.Contains(target, ":") {
				target = net.JoinHostPort(target, strconv.Itoa(port))
			}
			start := time.Now()
			err := sendFiles(target, name, "PC", fields[2:])
			elapsed := time.Since(start)
			if err != nil {
				fmt.Println("发送失败:", err)
			} else {
				fmt.Printf("发送成功，耗时 %s\n", elapsed)
			}
		case "text":
			if len(fields) < 3 {
				fmt.Println("用法: text <ip[:port]> <文本内容...>")
				continue
			}
			target := fields[1]
			if !strings.Contains(target, ":") {
				target = net.JoinHostPort(target, strconv.Itoa(port))
			}
			if err := sendText(target, name, "PC", strings.Join(fields[2:], " ")); err != nil {
				fmt.Println("发送失败:", err)
			} else {
				fmt.Println("文本已发送")
			}
		case "exit", "quit":
			return
		default:
			fmt.Println("未知命令:", fields[0])
		}
	}
}

func printDevices(devs []Device) {
	if len(devs) == 0 {
		fmt.Println("（无设备）")
		return
	}
	for _, d := range devs {
		fmt.Printf("  %-20s %-8s %s\n", d.Name, d.Type, d.Addr())
	}
}

func defaultReceiveDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "received"
	}
	dl := filepath.Join(home, "Downloads")
	if st, err := os.Stat(dl); err == nil && st.IsDir() {
		return dl
	}
	return home
}
