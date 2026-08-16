//go:build windows

package main

import (
	"syscall"
	"unsafe"
)

var (
	shell32                 = syscall.NewLazyDLL("shell32.dll")
	procSHGetKnownFolderPath = shell32.NewProc("SHGetKnownFolderPath")
	ole32                    = syscall.NewLazyDLL("ole32.dll")
	procCoTaskMemFree        = ole32.NewProc("CoTaskMemFree")
)

// guid 是 Windows KNOWNFOLDERID 的 GUID 结构。
type guid struct {
	Data1 uint32
	Data2 uint16
	Data3 uint16
	Data4 [8]byte
}

// FOLDERID_Downloads = {374DE290-123F-4565-9164-39C4925E467B}
var folderidDownloads = guid{
	Data1: 0x374DE290,
	Data2: 0x123F,
	Data3: 0x4565,
	Data4: [8]byte{0x91, 0x64, 0x39, 0xC4, 0x92, 0x5E, 0x46, 0x7B},
}

// knownFolderDownloads 返回用户真实的「下载」文件夹（可能被重定向到其它盘，
// 而不是 USERPROFILE 下的 C:\Users\xx\Downloads）。失败返回空串。
func knownFolderDownloads() string {
	var p *uint16
	r, _, _ := procSHGetKnownFolderPath.Call(
		uintptr(unsafe.Pointer(&folderidDownloads)),
		0, // dwFlags
		0, // hToken
		uintptr(unsafe.Pointer(&p)),
	)
	if r != 0 || p == nil {
		return ""
	}
	defer procCoTaskMemFree.Call(uintptr(unsafe.Pointer(p)))
	return syscall.UTF16ToString((*[32768]uint16)(unsafe.Pointer(p))[:])
}
