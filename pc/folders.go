package main

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// resolveSharedPath 解析手机请求的路径，返回绝对路径、是否可写、是否允许访问。
// path 为空 → 缓存目录（可写）；否则必须是缓存目录或某个共享文件夹内的路径。
func resolveSharedPath(path string) (abs string, writable bool, ok bool) {
	cfg := getConfig()
	if path == "" {
		return cfg.CacheDir, true, true
	}
	cleaned := filepath.Clean(path)
	if isWithin(cfg.CacheDir, cleaned) {
		return cleaned, true, true
	}
	for _, f := range cfg.SharedFolders {
		if isWithin(f.Path, cleaned) {
			return cleaned, !f.Readonly, true
		}
	}
	return "", false, false
}

// isWithin 判断 p 是否等于 base 或位于 base 的子目录内。
// 用 filepath.Rel 而非 base+分隔符 前缀匹配：盘符根目录（如 D:\）经 Clean 会
// 保留末尾分隔符，base+sep 会得到 D:\\ 双反斜杠，导致任何子路径都无法匹配。
func isWithin(base, p string) bool {
	rel, err := filepath.Rel(filepath.Clean(base), filepath.Clean(p))
	if err != nil {
		return false
	}
	if rel == "." {
		return true
	}
	return rel != ".." && !strings.HasPrefix(rel, ".."+string(os.PathSeparator)) && !filepath.IsAbs(rel)
}

// isSharedFolderPath 判断路径是否位于某个共享文件夹内（而非缓存目录）。
func isSharedFolderPath(path string) bool {
	cleaned := filepath.Clean(path)
	for _, f := range getConfig().SharedFolders {
		if isWithin(f.Path, cleaned) {
			return true
		}
	}
	return false
}

// handleFolders 列出共享文件夹（含虚拟的「缓存目录」）；POST 添加、DELETE 删除。
func (s *webServer) handleFolders(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
		if !s.canModifySettings(r) {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		var req struct {
			Path     string `json:"path"`
			Name     string `json:"name"`
			Readonly bool   `json:"readonly"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		path := strings.TrimSpace(req.Path)
		if path == "" {
			http.Error(w, "empty path", http.StatusBadRequest)
			return
		}
		st, err := os.Stat(path)
		if err != nil || !st.IsDir() {
			writeJSON(w, map[string]any{"ok": false, "error": "目录不存在"})
			return
		}
		name := strings.TrimSpace(req.Name)
		if name == "" {
			name = filepath.Base(path)
		}
		cfg := getConfig()
		for _, f := range cfg.SharedFolders {
			if f.Path == path {
				writeJSON(w, map[string]any{"ok": false, "error": "已添加"})
				return
			}
		}
		cfg.SharedFolders = append(cfg.SharedFolders, SharedFolder{Path: path, Name: name, Readonly: req.Readonly})
		setSharedFolders(cfg.SharedFolders)
		writeJSON(w, map[string]any{"ok": true})
		return
	}
	if r.Method == http.MethodDelete {
		if !s.canModifySettings(r) {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		path := r.URL.Query().Get("path")
		cfg := getConfig()
		var out []SharedFolder
		for _, f := range cfg.SharedFolders {
			if f.Path != path {
				out = append(out, f)
			}
		}
		setSharedFolders(out)
		writeJSON(w, map[string]any{"ok": true})
		return
	}

	cfg := getConfig()
	type folderEntry struct {
		Name     string `json:"name"`
		Path     string `json:"path"`
		Readonly bool   `json:"readonly"`
		Kind     string `json:"kind"` // "cache" | "folder"
	}
	out := []folderEntry{{Name: "缓存目录", Path: "", Readonly: false, Kind: "cache"}}
	// 远程网页端无「查看共享文件夹」权限时，只返回缓存目录
	if s.canViewShares(r) {
		for _, f := range cfg.SharedFolders {
			out = append(out, folderEntry{Name: f.Name, Path: f.Path, Readonly: f.Readonly, Kind: "folder"})
		}
	}
	writeJSON(w, out)
}

// handleBrowse 浏览目录内容（子目录 + 文件），校验路径在共享范围内。
func (s *webServer) handleBrowse(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Query().Get("path")
	abs, writable, ok := resolveSharedPath(path)
	if !ok {
		writeJSON(w, map[string]any{"ok": false, "error": "无权限访问该目录"})
		return
	}
	// 共享文件夹需「查看共享文件夹」权限（缓存目录不受限）
	if path != "" && isSharedFolderPath(path) && !s.canViewShares(r) {
		writeJSON(w, map[string]any{"ok": false, "error": "无权限访问该目录"})
		return
	}
	entries, err := os.ReadDir(abs)
	if err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	type entry struct {
		Name  string `json:"name"`
		IsDir bool   `json:"isDir"`
		Size  int64  `json:"size"`
		Mtime int64  `json:"mtime"`
	}
	out := make([]entry, 0)
	for _, e := range entries {
		info, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, entry{Name: e.Name(), IsDir: e.IsDir(), Size: info.Size(), Mtime: info.ModTime().Unix()})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].IsDir != out[j].IsDir {
			return out[i].IsDir
		}
		return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name)
	})
	writeJSON(w, map[string]any{"ok": true, "path": abs, "writable": writable, "entries": out})
}

// handleUploadToDir 上传文件到指定目录（multipart，字段 files/relpaths）。
func (s *webServer) handleUploadToDir(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	dir := r.URL.Query().Get("dir")
	abs, writable, ok := resolveSharedPath(dir)
	if !ok || !writable {
		writeJSON(w, map[string]any{"ok": false, "error": "目录不可写"})
		return
	}
	if err := r.ParseMultipartForm(1 << 30); err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	files := r.MultipartForm.File["files"]
	relpaths := r.MultipartForm.Value["relpaths"]
	lastModified := r.MultipartForm.Value["lastModified"]
	n := 0
	for i, fh := range files {
		rel := ""
		if i < len(relpaths) {
			rel = relpaths[i]
		}
		dst := filepath.Join(abs, sanitizeName(fh.Filename))
		if rp := safeRelPath(rel); rp != "" {
			dst = filepath.Join(abs, filepath.FromSlash(rp))
			_ = os.MkdirAll(filepath.Dir(dst), 0o755)
		}
		dst = uniquePath(dst)
		src, err := fh.Open()
		if err != nil {
			continue
		}
		if f, err := os.Create(dst); err == nil {
			_, _ = io.Copy(f, src)
			f.Close()
			n++
		}
		src.Close()
		// 还原源文件的修改时间（PcApi 上传时透传 lastModified，毫秒时间戳）
		if i < len(lastModified) {
			applyLastModified(dst, lastModified[i])
		}
	}
	writeJSON(w, map[string]any{"ok": n > 0, "count": n})
}
