package main

import (
	"archive/zip"
	"embed"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

//go:embed web
var webFS embed.FS

type webServer struct {
	reg        *DeviceRegistry
	name       string
	dtype      string
	port       int // TCP listen port (discovery/transfer)
	scanPort   int // port probed during discovery scans
	httpPort   int
	receiveDir string
	cacheDir   string

	sess *SessionManager // 网页客户端身份 + 中转收件箱
}

func startWeb(addr string, s *webServer) error {
	sub, err := fs.Sub(webFS, "web")
	if err != nil {
		return err
	}
	mux := http.NewServeMux()
	mux.Handle("/", http.FileServer(http.FS(sub)))
	mux.HandleFunc("/api/info", s.handleInfo)
	mux.HandleFunc("/api/devices", s.handleDevices)
	mux.HandleFunc("/api/scan", s.handleScan)
	mux.HandleFunc("/api/upload", s.handleUpload)
	mux.HandleFunc("/api/send", s.handleSend)
	mux.HandleFunc("/api/files", s.handleFiles)
	mux.HandleFunc("/api/staged", s.handleStaged)
	mux.HandleFunc("/api/download/", s.handleDownload)
	mux.HandleFunc("/api/delete", s.handleDelete)
	mux.HandleFunc("/api/clear", s.handleClear)
	mux.HandleFunc("/api/share", s.handleShare)
	mux.HandleFunc("/api/texts", s.handleTexts)
	mux.HandleFunc("/api/sendtext", s.handleSendText)
	mux.HandleFunc("/api/name", s.handleName)
	mux.HandleFunc("/api/session", s.handleSession)
	mux.HandleFunc("/api/webname", s.handleWebName)
	mux.HandleFunc("/api/inbox", s.handleInbox)
	mux.HandleFunc("/api/transfers", s.handleTransfers)
	mux.HandleFunc("/api/settings", s.handleSettings)
	mux.HandleFunc("/api/pending", s.handlePending)
	mux.HandleFunc("/api/pending-confirm", s.handlePendingConfirm)
	mux.HandleFunc("/api/folders", s.handleFolders)
	mux.HandleFunc("/api/browse", s.handleBrowse)
	mux.HandleFunc("/api/upload-dir", s.handleUploadToDir)
	mux.HandleFunc("/api/reset", s.handleReset)
	mux.HandleFunc("/api/transfer/cancel", s.handleTransferCancel)
	return http.ListenAndServe(addr, mux)
}

func (s *webServer) handleInfo(w http.ResponseWriter, r *http.Request) {
	ips, _ := localIPv4s()
	var ipStrs []string
	for _, ip := range ips {
		ipStrs = append(ipStrs, ip.String())
	}
	preferred := primaryIP()
	if preferred == "" {
		preferred = preferredIP(ips)
	}
	lanURL := ""
	if preferred != "" {
		lanURL = fmt.Sprintf("http://%s:%d", preferred, s.httpPort)
	}
	qr := ""
	if lanURL != "" {
		if png, err := qrcode.Encode(lanURL, qrcode.Medium, 256); err == nil {
			qr = "data:image/png;base64," + base64.StdEncoding.EncodeToString(png)
		}
	}
	writeJSON(w, map[string]any{
		"name":        s.name,
		"type":        s.dtype,
		"ips":         ipStrs,
		"port":        s.port,
		"httpPort":    s.httpPort,
		"lanURL":      lanURL,
		"qr":          qr,
		"receiveDir":  s.receiveDir,
		"isLocal":     s.isLocalRequest(r),
		"remotePerms": getConfig().RemotePerms,
	})
}

// preferredIP returns the best IPv4 for others to reach us: private first,
// then any non-APIPA address, then anything.
func preferredIP(ips []net.IP) string {
	for _, ip := range ips {
		if isPrivate(ip) {
			return ip.String()
		}
	}
	for _, ip := range ips {
		if !isAPIPA(ip) {
			return ip.String()
		}
	}
	if len(ips) > 0 {
		return ips[0].String()
	}
	return ""
}

// selfDevice 返回主机（本电脑）作为 TCP 设备的描述，供网页设备在设备列表里看到主机。
func (s *webServer) selfDevice() *Device {
	ip := primaryIP()
	if ip == "" {
		ips, _ := localIPv4s()
		ip = preferredIP(ips)
	}
	if ip == "" {
		return nil
	}
	return &Device{Name: getSelfName(), Type: s.dtype, IP: ip, Port: s.port, Kind: "tcp"}
}

func (s *webServer) handleDevices(w http.ResponseWriter, r *http.Request) {
	devices := s.reg.list()
	for i := range devices {
		devices[i].Kind = "tcp"
	}
	// 把主机（本电脑）也作为可发送目标加入列表，让「其他」网页设备能看到主机并发送给它；
	// 本机浏览器（localhost/本机 IP）不显示主机自己，避免扫到自己。
	if !s.isLocalRequest(r) {
		if self := s.selfDevice(); self != nil {
			devices = append(devices, *self)
		}
	}
	// 融合网页客户端设备（排除当前会话自己，并过滤掉本机网页终端——即电脑自己开的浏览器页面）
	excludeSID := r.URL.Query().Get("sid")
	for _, c := range s.sess.list() {
		if c.SID == excludeSID {
			continue
		}
		// 网页终端的 IP 是本机地址（localhost / 本机局域网 IP）说明是电脑自己开的页面，跳过避免与 PC 设备重复
		if ip := net.ParseIP(c.IP); ip != nil && isLocalIP(ip) {
			continue
		}
		devices = append(devices, Device{
			Name: c.Name, Type: "网页", IP: c.IP, Kind: "web", SID: c.SID, LastSeen: c.LastSeen,
		})
	}
	sortDevices(devices)
	writeJSON(w, devices)
}

// isLocalRequest 判断请求是否来自本机（主机自己开的浏览器页面）。
func (s *webServer) isLocalRequest(r *http.Request) bool {
	ip := net.ParseIP(clientIP(r))
	return ip != nil && isLocalIP(ip)
}

// isAppRequest 判断请求是否来自手机 App（PcApi 带 X-Client: app 头，不受权限差分限制）。
func isAppRequest(r *http.Request) bool {
	return r.Header.Get("X-Client") == "app"
}

// canManage 本机网页端或手机 App 拥有完全权限。
func (s *webServer) canManage(r *http.Request) bool {
	return s.isLocalRequest(r) || isAppRequest(r)
}

func (s *webServer) canViewShares(r *http.Request) bool {
	if s.canManage(r) {
		return true
	}
	return getConfig().RemotePerms.ViewSharedFolders
}

func (s *webServer) canDeleteStaged(r *http.Request) bool {
	if s.canManage(r) {
		return true
	}
	return getConfig().RemotePerms.DeleteStaged
}

func (s *webServer) canModifySettings(r *http.Request) bool {
	if s.canManage(r) {
		return true
	}
	return getConfig().RemotePerms.ModifySettings
}

func (s *webServer) handleScan(w http.ResponseWriter, r *http.Request) {
	devices := scanSubnet(s.reg, s.scanPort, s.dtype)
	writeJSON(w, devices)
}

func (s *webServer) handleUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if err := r.ParseMultipartForm(1 << 30); err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	files := r.MultipartForm.File["files"]
	relpaths := r.MultipartForm.Value["relpaths"]
	var texts []string
	if t := r.FormValue("texts"); t != "" {
		_ = json.Unmarshal([]byte(t), &texts)
	}
	total := len(files) + len(texts)

	// 单个文件：直接落盘
	if total == 1 && len(files) == 1 {
		fh := files[0]
		rel := ""
		if len(relpaths) > 0 {
			rel = relpaths[0]
		}
		dst := filepath.Join(s.cacheDir, sanitizeName(fh.Filename))
		if rp := safeRelPath(rel); rp != "" {
			dst = filepath.Join(s.cacheDir, filepath.FromSlash(rp))
			_ = os.MkdirAll(filepath.Dir(dst), 0o755)
		}
		dst = uniquePath(dst)
		src, err := fh.Open()
		if err == nil {
			if f, err := os.Create(dst); err == nil {
				_, _ = io.Copy(f, src)
				f.Close()
			}
			src.Close()
		}
		writeJSON(w, map[string]any{"ok": true, "count": 1, "name": filepath.Base(dst)})
		return
	}
	// 单个文本：存成 txt
	if total == 1 && len(texts) == 1 {
		name := fmt.Sprintf("文本_%s.txt", time.Now().Format("150405"))
		name = uniquePath(filepath.Join(s.cacheDir, name))
		_ = os.WriteFile(name, []byte(texts[0]), 0o644)
		writeJSON(w, map[string]any{"ok": true, "count": 1, "name": filepath.Base(name)})
		return
	}

	// 多个 → 打包成 zip
	zipName := fmt.Sprintf("打包_%s.zip", time.Now().Format("20060102_150405"))
	zipPath := uniquePath(filepath.Join(s.cacheDir, zipName))
	zf, err := os.Create(zipPath)
	if err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	zw := zip.NewWriter(zf)
	for i, fh := range files {
		rel := ""
		if i < len(relpaths) {
			rel = relpaths[i]
		}
		name := safeRelPath(rel)
		if name == "" {
			name = sanitizeName(fh.Filename)
		}
		w2, err := zw.Create(name)
		if err != nil {
			continue
		}
		src, err := fh.Open()
		if err != nil {
			continue
		}
		_, _ = io.Copy(w2, src)
		src.Close()
	}
	for i, t := range texts {
		w2, _ := zw.Create(fmt.Sprintf("文本_%d.txt", i+1))
		_, _ = w2.Write([]byte(t))
	}
	_ = zw.Close()
	_ = zf.Close()
	writeJSON(w, map[string]any{"ok": true, "count": total, "name": filepath.Base(zipPath)})
}

func (s *webServer) handleSend(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if err := r.ParseMultipartForm(1 << 30); err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	targetsStr := r.FormValue("targets")
	if targetsStr == "" {
		targetsStr = r.FormValue("target")
	}
	files := r.MultipartForm.File["files"]
	relpaths := r.MultipartForm.Value["relpaths"]
	if targetsStr == "" || len(files) == 0 {
		http.Error(w, "missing targets or files", http.StatusBadRequest)
		return
	}
	targets := strings.Split(targetsStr, ",")
	fromName := r.FormValue("fromName")
	if fromName == "" {
		fromName = s.senderName(r.FormValue("fromSid"))
	}
	// 目标分流：web:<sid> 走服务器中转，其余走 TCP 直连。
	var tcpTargets, webTargets []string
	for _, t := range targets {
		t = strings.TrimSpace(t)
		if t == "" {
			continue
		}
		if strings.HasPrefix(t, "web:") {
			webTargets = append(webTargets, strings.TrimPrefix(t, "web:"))
		} else {
			tcpTargets = append(tcpTargets, t)
		}
	}
	tcpTargets = filterSelfTargets(tcpTargets)
	if len(tcpTargets) == 0 && len(webTargets) == 0 {
		writeJSON(w, map[string]any{"ok": false, "error": "无有效目标设备"})
		return
	}

	tmpDir, err := os.MkdirTemp("", "send")
	if err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}

	var items []sendItem
	for i, fh := range files {
		rel := ""
		if i < len(relpaths) {
			rel = relpaths[i]
		}
		dst := filepath.Join(tmpDir, fmt.Sprintf("%d_%s", i, sanitizeName(fh.Filename)))
		src, err := fh.Open()
		if err != nil {
			continue
		}
		f, err := os.Create(dst)
		if err != nil {
			src.Close()
			continue
		}
		_, _ = io.Copy(f, src)
		f.Close()
		src.Close()
		items = append(items, sendItem{path: dst, relPath: rel, name: sanitizeName(fh.Filename)})
	}

	var taskIDs []string
	webSent := 0

	// Web 中转：文件落 receiveDir + 投递收件箱通知（同步，本地复制较快）
	if len(webTargets) > 0 {
		var notices []InboxMessage
		for _, it := range items {
			name, size, err := s.stageToCacheDir(it)
			if err != nil {
				continue
			}
			notices = append(notices, InboxMessage{ID: newSID(), From: fromName, Kind: "file", FileName: name, Size: size, Time: time.Now()})
		}
		for _, sid := range webTargets {
			for _, n := range notices {
				s.sess.push(sid, n)
			}
			webSent++
		}
	}

	// TCP 直连（异步，返回 taskId 供进度查询）
	var wg sync.WaitGroup
	for _, t := range tcpTargets {
		task := transfers.create("send", batchName(items), t, totalSize(items))
		taskIDs = append(taskIDs, task.ID)
		wg.Add(1)
		go func(target string, tk *Transfer) {
			defer wg.Done()
			err := sendBatch(target, s.name, s.dtype, items, func(done, total int64) {
				transfers.update(tk.ID, done)
			}, tk.cancel)
			if err != nil {
				transfers.finish(tk.ID, "error", err.Error())
			} else {
				transfers.finish(tk.ID, "done", "")
			}
		}(t, task)
	}
	// 所有 TCP 发送完成后再清理临时目录
	go func() {
		wg.Wait()
		os.RemoveAll(tmpDir)
	}()

	if len(taskIDs) == 0 && webSent == 0 {
		writeJSON(w, map[string]any{"ok": false, "error": "无有效目标设备"})
		return
	}
	writeJSON(w, map[string]any{"ok": true, "tasks": taskIDs, "webSent": webSent})
}

// batchName 返回一批文件的显示名（单个文件用文件名，多个用数量）。
func batchName(items []sendItem) string {
	if len(items) == 1 {
		n := items[0].name
		if n == "" {
			n = filepath.Base(items[0].path)
		}
		return n
	}
	return fmt.Sprintf("%d 个文件", len(items))
}

// totalSize 返回一批文件的总字节数。
func totalSize(items []sendItem) int64 {
	var total int64
	for _, it := range items {
		if st, err := os.Stat(it.path); err == nil {
			total += st.Size()
		}
	}
	return total
}

// stageToReceiveDir 把一个待发送文件复制进接收目录（保留文件夹相对路径），
// 返回最终文件名与大小。用于网页设备间的服务器中转。
func (s *webServer) stageToCacheDir(it sendItem) (string, int64, error) {
	dir := s.cacheDir
	base := it.name
	if base == "" {
		base = sanitizeName(filepath.Base(it.path))
	}
	if it.relPath != "" {
		rel := safeRelPath(it.relPath)
		if filepath.Dir(rel) != "." {
			dir = filepath.Join(s.cacheDir, filepath.FromSlash(filepath.Dir(rel)))
			_ = os.MkdirAll(dir, 0o755)
		}
		if filepath.Base(rel) != "" {
			base = sanitizeName(filepath.Base(rel))
		}
	}
	dst := uniquePath(filepath.Join(dir, base))
	src, err := os.Open(it.path)
	if err != nil {
		return "", 0, err
	}
	defer src.Close()
	f, err := os.Create(dst)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()
	n, err := io.Copy(f, src)
	if err != nil {
		return "", 0, err
	}
	return filepath.Base(dst), n, nil
}

// filterSelfTargets 过滤掉空目标与本机目标
func filterSelfTargets(list []string) []string {
	var out []string
	for _, t := range list {
		t = strings.TrimSpace(t)
		if t == "" {
			continue
		}
		if host, _, err := net.SplitHostPort(t); err == nil {
			if ip := net.ParseIP(host); ip != nil && isLocalIP(ip) {
				continue
			}
		}
		out = append(out, t)
	}
	return out
}

type fileEntry struct {
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	Mtime  int64  `json:"mtime"`
	Source string `json:"source"`         // "receive"（他人发来）/"upload"（本机上传暂存）/"share"（路径共享）
	Path   string `json:"path,omitempty"` // share 来源的真实路径
}

func (s *webServer) handleFiles(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, listDirFiles(s.receiveDir, "receive"))
}

// handleStaged 返回缓存目录（上传暂存 + 网页中转）与路径共享的文件。
func (s *webServer) handleStaged(w http.ResponseWriter, r *http.Request) {
	files := listDirFiles(s.cacheDir, "upload")
	for _, it := range shares.list() {
		files = append(files, fileEntry{Name: it.Name, Size: it.Size, Mtime: it.Time.Unix(), Source: "share", Path: it.Path})
	}
	sort.Slice(files, func(i, j int) bool { return files[i].Mtime > files[j].Mtime })
	writeJSON(w, files)
}

// listDirFiles 列出目录顶层文件（按 mtime 倒序）。
func listDirFiles(dir, source string) []fileEntry {
	entries, _ := os.ReadDir(dir)
	files := make([]fileEntry, 0)
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		if strings.HasSuffix(e.Name(), ".part") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		files = append(files, fileEntry{Name: e.Name(), Size: info.Size(), Mtime: info.ModTime().Unix(), Source: source})
	}
	sort.Slice(files, func(i, j int) bool { return files[i].Mtime > files[j].Mtime })
	return files
}

func (s *webServer) handleDownload(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/api/download/")
	name = sanitizeName(name)
	// 强制以附件形式下载，避免浏览器直接打开
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename*=UTF-8''%s", url.PathEscape(name)))
	// 路径共享的文件：从原路径读取（共享文件夹内的路径，或旧的「路径共享」单文件）
	if sharePath := r.URL.Query().Get("path"); sharePath != "" {
		if abs, _, ok := resolveSharedPath(sharePath); ok {
			http.ServeFile(w, r, abs)
			return
		}
		if shares.contains(sharePath) {
			http.ServeFile(w, r, sharePath)
			return
		}
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	// 缓存目录的文件：从 cacheDir 读取
	if r.URL.Query().Get("src") == "upload" {
		http.ServeFile(w, r, filepath.Join(s.cacheDir, name))
		return
	}
	http.ServeFile(w, r, filepath.Join(s.receiveDir, name))
}

func (s *webServer) handleDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	// 路径共享：只移除记录，不删除真实文件（属修改共享）
	if path := r.URL.Query().Get("path"); path != "" {
		if !s.canModifySettings(r) {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		shares.remove(path)
		writeJSON(w, map[string]any{"ok": true})
		return
	}
	// 删除文件（接收目录 / 缓存目录）需「删除暂存」权限
	if !s.canDeleteStaged(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	name := sanitizeName(r.URL.Query().Get("name"))
	if name == "" || name == "unnamed" {
		writeJSON(w, map[string]any{"ok": false, "error": "invalid name"})
		return
	}
	dir := s.receiveDir
	if r.URL.Query().Get("src") == "upload" {
		dir = s.cacheDir
	}
	if err := os.Remove(filepath.Join(dir, name)); err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(w, map[string]any{"ok": true})
}

// handleShare 添加一条路径共享（只记录路径，不复制文件）。
func (s *webServer) handleShare(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		Path string `json:"path"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	// 允许粘贴带引号的路径（如 Windows「复制为路径」的结果）
	path := strings.Trim(strings.TrimSpace(req.Path), "\"'")
	if path == "" {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	item, err := shares.add(path)
	if err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(w, map[string]any{"ok": true, "name": item.Name, "size": item.Size})
}

// handleClear 清空指定目录下的所有顶层文件（不递归子目录）。
// src=upload 时清空缓存目录，否则清空接收目录。
func (s *webServer) handleClear(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !s.canDeleteStaged(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	dir := s.receiveDir
	if r.URL.Query().Get("src") == "upload" {
		dir = s.cacheDir
	}
	entries, _ := os.ReadDir(dir)
	n := 0
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		if err := os.Remove(filepath.Join(dir, e.Name())); err == nil {
			n++
		}
	}
	writeJSON(w, map[string]any{"ok": true, "deleted": n})
}

func (s *webServer) handleTexts(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, listTexts())
}

func (s *webServer) handleSendText(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		Target   string   `json:"target"`
		Targets  []string `json:"targets"`
		Text     string   `json:"text"`
		FromSID  string   `json:"fromSid"`
		FromName string   `json:"fromName"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Text == "" {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	fromName := req.FromName
	if fromName == "" {
		fromName = s.senderName(req.FromSID)
	}
	targets := req.Targets
	if len(targets) == 0 && req.Target != "" {
		targets = []string{req.Target}
	}
	var tcpTargets, webTargets []string
	for _, t := range targets {
		t = strings.TrimSpace(t)
		if t == "" {
			continue
		}
		if strings.HasPrefix(t, "web:") {
			webTargets = append(webTargets, strings.TrimPrefix(t, "web:"))
		} else {
			tcpTargets = append(tcpTargets, t)
		}
	}
	tcpTargets = filterSelfTargets(tcpTargets)
	if len(tcpTargets) == 0 && len(webTargets) == 0 {
		writeJSON(w, map[string]any{"ok": false, "error": "无有效目标设备"})
		return
	}
	var errs []string
	sent := 0
	for _, t := range tcpTargets {
		if err := sendText(t, s.name, s.dtype, req.Text); err != nil {
			errs = append(errs, t+": "+err.Error())
		} else {
			sent++
		}
	}
	for _, sid := range webTargets {
		s.sess.push(sid, InboxMessage{ID: newSID(), From: fromName, Kind: "text", Text: req.Text, Time: time.Now()})
		sent++
	}
	if sent == 0 {
		writeJSON(w, map[string]any{"ok": false, "error": strings.Join(errs, "; ")})
		return
	}
	writeJSON(w, map[string]any{"ok": true, "sent": sent, "total": len(tcpTargets) + len(webTargets), "errors": errs})
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(v)
}

// handleName 修改本机设备名（影响后续 hello 里的 deviceName）。
func (s *webServer) handleName(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		Name string `json:"name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Name == "" {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	s.name = strings.TrimSpace(req.Name)
	setSelfName(s.name)
	setDeviceName(s.name)
	writeJSON(w, map[string]any{"ok": true, "name": s.name})
}

// handleSession 建立/恢复网页客户端身份。前端带 localStorage 里的 sid 调用，
// 服务器认出则沿用、认不出则新建；name 非空时恢复名字。
func (s *webServer) handleSession(w http.ResponseWriter, r *http.Request) {
	sid := r.URL.Query().Get("sid")
	name := r.URL.Query().Get("name")
	c := s.sess.getOrCreate(sid, name, clientIP(r))
	writeJSON(w, map[string]any{"sid": c.SID, "name": c.Name})
}

// handleWebName 修改某个网页客户端的名字。
func (s *webServer) handleWebName(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		SID  string `json:"sid"`
		Name string `json:"name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.SID == "" || req.Name == "" {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	c := s.sess.setName(req.SID, strings.TrimSpace(req.Name))
	if c == nil {
		writeJSON(w, map[string]any{"ok": false, "error": "session not found"})
		return
	}
	writeJSON(w, map[string]any{"ok": true, "sid": c.SID, "name": c.Name})
}

// handleInbox 取出并清空某网页客户端的未读中转消息。
func (s *webServer) handleInbox(w http.ResponseWriter, r *http.Request) {
	sid := r.URL.Query().Get("sid")
	if sid == "" {
		writeJSON(w, []InboxMessage{})
		return
	}
	writeJSON(w, s.sess.pop(sid))
}

// handleTransfers 返回发送/接收进度任务列表（活跃在前）。
func (s *webServer) handleTransfers(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, transfers.list())
}

// handleReset 清空设备列表，用于去除失联设备。
func (s *webServer) handleReset(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !s.canModifySettings(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	s.reg.clear()
	writeJSON(w, map[string]any{"ok": true})
}

// handleTransferCancel 取消一个运行中的发送任务。
func (s *webServer) handleTransferCancel(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	id := r.URL.Query().Get("id")
	if id == "" {
		writeJSON(w, map[string]any{"ok": false, "error": "missing id"})
		return
	}
	if transfers.cancel(id) {
		transfers.finish(id, "error", "cancelled")
		writeJSON(w, map[string]any{"ok": true})
	} else {
		writeJSON(w, map[string]any{"ok": false, "error": "task not found or already finished"})
	}
}

// handleSettings 读取或更新配置（接收目录 + 缓存目录 + 自动保存开关 + 远程权限）。
func (s *webServer) handleSettings(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
		if !s.canModifySettings(r) {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		var req struct {
			ReceiveDir  string            `json:"receiveDir"`
			CacheDir    string            `json:"cacheDir"`
			AutoSave    bool              `json:"autoSave"`
			RemotePerms *RemotePermissions `json:"remotePerms"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		cfg := setConfig(strings.TrimSpace(req.ReceiveDir), strings.TrimSpace(req.CacheDir), req.AutoSave)
		if req.RemotePerms != nil {
			setRemotePerms(*req.RemotePerms)
			cfg = getConfig()
		}
		if req.ReceiveDir != "" {
			_ = os.MkdirAll(cfg.ReceiveDir, 0o755)
		}
		if req.CacheDir != "" {
			_ = os.MkdirAll(cfg.CacheDir, 0o755)
		}
		s.receiveDir = cfg.ReceiveDir
		s.cacheDir = cfg.CacheDir
		writeJSON(w, map[string]any{"ok": true, "receiveDir": cfg.ReceiveDir, "cacheDir": cfg.CacheDir, "autoSave": cfg.AutoSave, "remotePerms": cfg.RemotePerms})
		return
	}
	cfg := getConfig()
	writeJSON(w, map[string]any{"receiveDir": cfg.ReceiveDir, "cacheDir": cfg.CacheDir, "autoSave": cfg.AutoSave, "remotePerms": cfg.RemotePerms})
}

// handlePending 列出待确认接收区的文件（关闭自动保存时收到）。
func (s *webServer) handlePending(w http.ResponseWriter, r *http.Request) {
	cfg := getConfig()
	dir := filepath.Join(cfg.ReceiveDir, ".pending")
	entries, _ := os.ReadDir(dir)
	files := make([]fileEntry, 0)
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		if strings.HasSuffix(e.Name(), ".part") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		files = append(files, fileEntry{Name: e.Name(), Size: info.Size(), Mtime: info.ModTime().Unix(), Source: "pending"})
	}
	writeJSON(w, files)
}

// handlePendingConfirm 处理待确认文件：action=save 移到接收目录，discard 删除。
func (s *webServer) handlePendingConfirm(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	name := sanitizeName(r.URL.Query().Get("name"))
	action := r.URL.Query().Get("action")
	if name == "" || name == "unnamed" {
		writeJSON(w, map[string]any{"ok": false, "error": "invalid name"})
		return
	}
	cfg := getConfig()
	src := filepath.Join(cfg.ReceiveDir, ".pending", name)
	if action == "discard" {
		_ = os.Remove(src)
		writeJSON(w, map[string]any{"ok": true})
		return
	}
	dst := uniquePath(filepath.Join(cfg.ReceiveDir, name))
	if err := os.Rename(src, dst); err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(w, map[string]any{"ok": true})
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// senderName 返回发送方网页客户端的名字（用于中转消息的 from 字段）。
func (s *webServer) senderName(sid string) string {
	if sid == "" {
		return s.name
	}
	if c := s.sess.get(sid); c != nil {
		return c.Name
	}
	return s.name
}
