//go:build windows

package main

import (
	"bytes"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"sync"
	"syscall"
	"unsafe"
)

var (
	user32   = syscall.NewLazyDLL("user32.dll")
	kernel32 = syscall.NewLazyDLL("kernel32.dll")

	procRegisterClassExW = user32.NewProc("RegisterClassExW")
	procCreateWindowExW  = user32.NewProc("CreateWindowExW")
	procDefWindowProcW   = user32.NewProc("DefWindowProcW")
	procGetMessageW      = user32.NewProc("GetMessageW")
	procTranslateMessage = user32.NewProc("TranslateMessage")
	procDispatchMessageW = user32.NewProc("DispatchMessageW")
	procCreatePopupMenu  = user32.NewProc("CreatePopupMenu")
	procAppendMenuW      = user32.NewProc("AppendMenuW")
	procTrackPopupMenu   = user32.NewProc("TrackPopupMenu")
	procGetCursorPos     = user32.NewProc("GetCursorPos")
	procSetForegroundWin = user32.NewProc("SetForegroundWindow")
	procShowWindow       = user32.NewProc("ShowWindow")
	procLoadIconW        = user32.NewProc("LoadIconW")
	procPostQuitMessage  = user32.NewProc("PostQuitMessage")
	procDestroyMenu      = user32.NewProc("DestroyMenu")
	procDestroyWindow    = user32.NewProc("DestroyWindow")
	procGetConsoleWindow = kernel32.NewProc("GetConsoleWindow")
	procGetModuleHandleW = kernel32.NewProc("GetModuleHandleW")
	procShellNotifyIconW = shell32.NewProc("Shell_NotifyIconW")

	procAllocConsole  = kernel32.NewProc("AllocConsole")
	procFreeConsole  = kernel32.NewProc("FreeConsole")
	procGetStdHandle = kernel32.NewProc("GetStdHandle")
	procSetStdHandle = kernel32.NewProc("SetStdHandle")
)

const (
	WM_USER         = 0x0400
	WM_TRAYICON     = WM_USER + 1
	WM_COMMAND      = 0x0111
	WM_LBUTTONDBLCLK = 0x0203
	WM_RBUTTONUP    = 0x0205
	WM_CONTEXTMENU  = 0x007B
	WM_DESTROY      = 0x0002

	NIM_ADD    = 0x00000000
	NIM_DELETE = 0x00000002

	NIF_MESSAGE = 0x00000001
	NIF_ICON    = 0x00000002
	NIF_TIP     = 0x00000004

	MF_STRING = 0x00000000

	IDI_APPLICATION = 32512 // MAKEINTRESOURCE(32512)

	ID_SHOW    = 1001
	ID_OPEN    = 1002
	ID_RESTART = 1003
	ID_EXIT    = 1004

	WS_EX_TOOLWINDOW = 0x00000080

	SW_SHOW = 5

	STD_OUTPUT_HANDLE = 0xFFFFFFF5 // -11
	STD_ERROR_HANDLE  = 0xFFFFFFF4 // -12
)

// WNDCLASSEXW（64 位布局：lpfnWndProc 之后是 cbClsExtra/cbWndExtra 两个 int32，不可省略，否则字段整体错位）
type wndClassEx struct {
	cbSize        uint32
	style         uint32
	lpfnWndProc   uintptr
	cbClsExtra    int32
	cbWndExtra    int32
	hInstance     syscall.Handle
	hIcon         syscall.Handle
	hCursor       syscall.Handle
	hbrBackground syscall.Handle
	lpszMenuName  *uint16
	lpszClassName *uint16
	hIconSm       syscall.Handle
}

// NOTIFYICONDATAW（含 szTip[128] 的现代前缀）
type notifyIconData struct {
	cbSize           uint32
	hWnd             syscall.Handle
	uID              uint32
	uFlags           uint32
	uCallbackMessage uint32
	hIcon            syscall.Handle
	szTip            [128]uint16
	dwState          uint32
	dwStateMask      uint32
}

var hWndTray syscall.Handle
var consoleHidden bool

// 日志三通：tray 模式下 stdout/stderr 经管道汇总，同时写入日志文件、内存环形缓冲
// （供“显示控制台”回放历史）以及（若已显示）控制台窗口。
var (
	logFile *os.File
	conOut  io.Writer
	logRing bytes.Buffer
	logMu   sync.Mutex
)

// setupTrayLogging 把进程标准输出/错误重定向到管道，由后台 goroutine 分流。
// tray 模式默认无控制台，这样日志不会丢失，点“显示控制台”能看到历史与实时输出。
func setupTrayLogging() {
	if f, err := os.OpenFile(filepath.Join(os.TempDir(), "lantransfer-tray.log"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644); err == nil {
		logFile = f
	}
	r, w, err := os.Pipe()
	if err != nil {
		return
	}
	procSetStdHandle.Call(STD_OUTPUT_HANDLE, w.Fd())
	procSetStdHandle.Call(STD_ERROR_HANDLE, w.Fd())
	os.Stdout = w
	os.Stderr = w
	log.SetOutput(os.Stderr) // 让 log 包也走新句柄（默认引用的是旧 os.Stderr）
	go func() {
		buf := make([]byte, 4096)
		for {
			n, rerr := r.Read(buf)
			if n > 0 {
				chunk := append([]byte(nil), buf[:n]...)
				logMu.Lock()
				if logFile != nil {
					logFile.Write(chunk)
				}
				if conOut != nil {
					conOut.Write(chunk)
				}
				logRing.Write(chunk)
				if logRing.Len() > 1<<20 { // 上限 1MB，丢弃前半
					t := logRing.Bytes()[1<<19:]
					logRing.Reset()
					logRing.Write(t)
				}
				logMu.Unlock()
			}
			if rerr != nil {
				return
			}
		}
	}()
}

func wndProc(hwnd syscall.Handle, msg uint32, wparam, lparam uintptr) uintptr {
	switch msg {
	case WM_TRAYICON:
		switch uint32(lparam) & 0xFFFF {
		case WM_LBUTTONDBLCLK:
			toggleConsole()
		case WM_RBUTTONUP, WM_CONTEXTMENU:
			showMenu(hwnd)
		}
		return 0
	case WM_COMMAND:
		id := uint32(wparam) & 0xFFFF
		switch id {
		case ID_SHOW:
			toggleConsole()
		case ID_OPEN:
			openWeb()
		case ID_RESTART:
			restartSelf()
		case ID_EXIT:
			removeTrayIcon(hwnd)
			procPostQuitMessage.Call(0)
		}
		return 0
	case WM_DESTROY:
		procPostQuitMessage.Call(0)
		return 0
	}
	r, _, _ := procDefWindowProcW.Call(uintptr(hwnd), uintptr(msg), wparam, lparam)
	return r
}

// runTray 创建隐藏控制台 + 托盘图标，并进入消息循环（直到退出）。必须在独立锁定的 OS 线程上运行。
func runTray() {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	hInstance, _, _ := procGetModuleHandleW.Call(0)
	hInst := syscall.Handle(hInstance)

	className, _ := syscall.UTF16PtrFromString("LanTransferTrayWnd")
	wc := wndClassEx{
		cbSize:        uint32(unsafe.Sizeof(wndClassEx{})),
		lpfnWndProc:   syscall.NewCallback(wndProc),
		hInstance:     hInst,
		lpszClassName: className,
	}
	if atom, _, err := procRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc))); atom == 0 {
		// 类已存在(1410)可容忍：仍尝试创建窗口
		if ee, ok := err.(syscall.Errno); !ok || ee != 1410 {
			fmt.Printf("tray: register class failed: %v\n", err)
			return
		}
	}

	hwnd, _, err := procCreateWindowExW.Call(
		WS_EX_TOOLWINDOW, // 不出现在任务栏/Alt+Tab
		uintptr(unsafe.Pointer(className)),
		0,
		0, // dwStyle=0：不可见窗口
		0, 0, 0, 0,
		0, // hWndParent=NULL（桌面），避免 HWND_MESSAGE 在某些环境报 1400
		0,
		uintptr(hInst),
		0,
	)
	if hwnd == 0 {
		fmt.Printf("tray: create window failed: %v\n", err)
		return
	}
	hWndTray = syscall.Handle(hwnd)

	// 设置托盘清理钩子（重启/退出前移除图标，避免幽灵图标）
	preRestart = func() { removeTrayIcon(hWndTray) }

	if !addTrayIcon(hWndTray, hInst) {
		fmt.Println("tray: add icon failed")
	}

	// 启动即把日志重定向到管道（历史不丢），再脱离控制台 → 仅留托盘
	setupTrayLogging()
	setConsoleVisible(false)

	// 消息循环
	var msg struct {
		hwnd    syscall.Handle
		message uint32
		wparam  uintptr
		lparam  uintptr
		time    uint32
		pt      struct{ x, y int32 }
	}
	for {
		r, _, _ := procGetMessageW.Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0)
		if int32(r) <= 0 { // -1 出错 或 0 WM_QUIT
			break
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&msg)))
		procDispatchMessageW.Call(uintptr(unsafe.Pointer(&msg)))
	}
	removeTrayIcon(hWndTray)
}

func addTrayIcon(hwnd, hInst syscall.Handle) bool {
	hIcon, _, _ := procLoadIconW.Call(0, uintptr(IDI_APPLICATION)) // 0=系统实例，加载标准应用图标
	nid := notifyIconData{
		hWnd:             hwnd,
		uID:              1,
		uFlags:           NIF_MESSAGE | NIF_ICON | NIF_TIP,
		uCallbackMessage: WM_TRAYICON,
		hIcon:            syscall.Handle(hIcon),
	}
	nid.cbSize = uint32(unsafe.Sizeof(nid))
	copyTip(nid.szTip[:], "LanTransfer 文件传输")
	r, _, _ := procShellNotifyIconW.Call(NIM_ADD, uintptr(unsafe.Pointer(&nid)))
	return r != 0
}

func removeTrayIcon(hwnd syscall.Handle) {
	nid := notifyIconData{hWnd: hwnd, uID: 1}
	nid.cbSize = uint32(unsafe.Sizeof(nid))
	procShellNotifyIconW.Call(NIM_DELETE, uintptr(unsafe.Pointer(&nid)))
}

func showMenu(hwnd syscall.Handle) {
	menu, _, _ := procCreatePopupMenu.Call()
	if menu == 0 {
		return
	}
	hMenu := syscall.Handle(menu)
	label := "隐藏控制台"
	if consoleHidden {
		label = "显示控制台"
	}
	addMenuItem(hMenu, ID_SHOW, label)
	addMenuItem(hMenu, ID_OPEN, "打开网页控制台")
	addMenuItem(hMenu, ID_RESTART, "重启")
	addMenuItem(hMenu, ID_EXIT, "退出")

	var pt struct{ x, y int32 }
	procGetCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
	procSetForegroundWin.Call(uintptr(hwnd))
	procTrackPopupMenu.Call(uintptr(hMenu), 0, uintptr(pt.x), uintptr(pt.y), 0, uintptr(hwnd), 0)
	procDestroyMenu.Call(uintptr(hMenu))
}

func addMenuItem(menu syscall.Handle, id uint32, text string) {
	s, _ := syscall.UTF16PtrFromString(text)
	procAppendMenuW.Call(uintptr(menu), MF_STRING, uintptr(id), uintptr(unsafe.Pointer(s)))
}

// setConsoleVisible 通过 AllocConsole/FreeConsole 彻底显隐控制台。
// tray 模式启动即 FreeConsole 脱离控制台（进程不再挂任何控制台 → 彻底只剩托盘），
// 需要时再 AllocConsole 现建一个控制台，隐藏时 FreeConsole 销毁之。
func setConsoleVisible(visible bool) {
	if visible {
		showConsole()
	} else {
		hideConsole()
	}
}

func showConsole() {
	if hasConsole() {
		return
	}
	procAllocConsole.Call()
	if h, _, _ := procGetStdHandle.Call(STD_OUTPUT_HANDLE); h != 0 {
		conOut = os.NewFile(h, "conout")
	}
	if cw, _, _ := procGetConsoleWindow.Call(); cw != 0 {
		procShowWindow.Call(cw, SW_SHOW)
	}
	// 回放历史日志，让弹出的控制台立即有内容
	logMu.Lock()
	history := logRing.String()
	logMu.Unlock()
	if conOut != nil {
		if history != "" {
			fmt.Fprint(conOut, history)
		}
		fmt.Fprintln(conOut, "\n[LanTransfer] 控制台已连接，以上是历史日志，以下为实时输出。")
	}
	consoleHidden = false
}

func hideConsole() {
	if !hasConsole() {
		consoleHidden = true
		return
	}
	conOut = nil
	procFreeConsole.Call()
	consoleHidden = true
}

func hasConsole() bool {
	h, _, _ := procGetConsoleWindow.Call()
	return h != 0
}

func toggleConsole() {
	// consoleHidden=true 表示当前已隐藏 → 点击应“显示”(visible=true)
	setConsoleVisible(consoleHidden)
}

func openWeb() {
	url := "http://127.0.0.1:" + strconv.Itoa(trayHTTPPort)
	cmd := exec.Command("cmd", "/c", "start", "", url)
	_ = cmd.Start()
}

func copyTip(dst []uint16, s string) {
	src := syscall.StringToUTF16(s)
	n := len(src)
	if n > len(dst) {
		n = len(dst)
	}
	copy(dst, src[:n])
}
