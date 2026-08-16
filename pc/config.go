package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

// SharedFolder 是电脑端共享给手机的文件夹（可读可写或只读）。
type SharedFolder struct {
	Path     string `json:"path"`
	Name     string `json:"name"`
	Readonly bool   `json:"readonly"`
}

// RemotePermissions 控制「其他网页端（远程浏览器）」的权限；本机网页端和手机 App 不受限。
type RemotePermissions struct {
	ViewSharedFolders bool `json:"viewSharedFolders"` // 看到共享文件夹（浏览/下载）
	DeleteStaged      bool `json:"deleteStaged"`      // 删除暂存文件夹的文件
	ModifySettings    bool `json:"modifySettings"`    // 修改设置和共享文件夹
}

// AppConfig 是可持久化的运行时配置。
type AppConfig struct {
	ReceiveDir    string            `json:"receiveDir"`    // 接收目录（真正收到的文件）
	CacheDir      string            `json:"cacheDir"`      // 缓存目录（上传暂存 + 网页中转）
	AutoSave      bool              `json:"autoSave"`      // 是否自动保存（关闭则收到文件先进入待确认区）
	DeviceName    string            `json:"deviceName"`    // 设备别称（网页端设置，覆盖主机名）
	SharedFolders []SharedFolder    `json:"sharedFolders"` // 共享给手机的文件夹
	RemotePerms   RemotePermissions `json:"remotePerms"`   // 远程网页端权限
}

var (
	configMu sync.Mutex
	config   AppConfig
)

func configPath() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "transport_settings.json"
	}
	return filepath.Join(home, ".transport_tools.json")
}

// loadConfig 从配置文件加载；initialDir 作为首次运行的默认接收目录。
func loadConfig(initialDir string) AppConfig {
	cfg := AppConfig{ReceiveDir: initialDir, AutoSave: true}
	if data, err := os.ReadFile(configPath()); err == nil {
		var saved AppConfig
		if json.Unmarshal(data, &saved) == nil {
			if saved.ReceiveDir != "" {
				cfg.ReceiveDir = saved.ReceiveDir
			}
			cfg.AutoSave = saved.AutoSave
			cfg.CacheDir = saved.CacheDir
			cfg.DeviceName = saved.DeviceName
			cfg.SharedFolders = saved.SharedFolders
			cfg.RemotePerms = saved.RemotePerms
			if !saved.RemotePerms.ViewSharedFolders && !saved.RemotePerms.DeleteStaged && !saved.RemotePerms.ModifySettings {
				// 老配置无权限字段（全 false），默认允许看共享文件夹以保持向后兼容
				cfg.RemotePerms.ViewSharedFolders = true
			}
		}
	}
	// 首次运行默认缓存目录为接收目录下的 .cache 子目录，之后作为独立字段持久化
	if cfg.CacheDir == "" {
		cfg.CacheDir = filepath.Join(cfg.ReceiveDir, ".cache")
	}
	configMu.Lock()
	config = cfg
	configMu.Unlock()
	return cfg
}

func getConfig() AppConfig {
	configMu.Lock()
	defer configMu.Unlock()
	return config
}

// setConfig 更新并持久化配置。目录参数为空表示保持不变。
func setConfig(receiveDir, cacheDir string, autoSave bool) AppConfig {
	configMu.Lock()
	defer configMu.Unlock()
	if receiveDir != "" {
		config.ReceiveDir = receiveDir
	}
	if cacheDir != "" {
		config.CacheDir = cacheDir
	}
	config.AutoSave = autoSave
	if data, err := json.MarshalIndent(config, "", "  "); err == nil {
		_ = os.WriteFile(configPath(), data, 0o644)
	}
	return config
}

// setSharedFolders 更新并持久化共享文件夹列表。
func setSharedFolders(folders []SharedFolder) {
	configMu.Lock()
	defer configMu.Unlock()
	config.SharedFolders = folders
	if data, err := json.MarshalIndent(config, "", "  "); err == nil {
		_ = os.WriteFile(configPath(), data, 0o644)
	}
}

// setDeviceName 更新并持久化设备别称。
func setDeviceName(name string) {
	configMu.Lock()
	defer configMu.Unlock()
	config.DeviceName = name
	if data, err := json.MarshalIndent(config, "", "  "); err == nil {
		_ = os.WriteFile(configPath(), data, 0o644)
	}
}

// setRemotePerms 更新并持久化远程网页端权限。
func setRemotePerms(p RemotePermissions) {
	configMu.Lock()
	defer configMu.Unlock()
	config.RemotePerms = p
	if data, err := json.MarshalIndent(config, "", "  "); err == nil {
		_ = os.WriteFile(configPath(), data, 0o644)
	}
}
