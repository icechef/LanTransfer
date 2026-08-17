package main

import (
	"crypto/md5"
	"encoding/hex"
	"errors"
	"fmt"
	"hash"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const chunkSize = 1 << 20 // 1 MiB

// serveConn handles an inbound connection: exchange hello, then receive files.
func serveConn(conn net.Conn, reg *DeviceRegistry, selfType string) {
	defer conn.Close()
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetReadBuffer(4 << 20)
		_ = tc.SetWriteBuffer(4 << 20)
	}

	// Send hello first (both sides write hello before reading).
	if err := writeFrame(conn, helloHeader(getSelfName(), selfType), nil); err != nil {
		return
	}
	h, _, err := readFrame(conn)
	if err != nil {
		return
	}
	// 优先用对方 hello 里自报的 IP（跨网卡/热点回环时，remoteIP 可能被 NAT 成本机 IP）
	peerIP := h.IP
	if peerIP == "" {
		peerIP = remoteIP(conn)
	}
	// 只跳过明确的回环地址；不再用 isLocalIP 过滤，否则热点回环的手机连接会被误判成本机而丢
	if ip := net.ParseIP(peerIP); ip == nil || ip.IsLoopback() {
		receiveLoop(conn, h.DeviceName)
		return
	}
	// 自连接（本机发给自己，如远程网页端向主机发送时服务器连自己的监听端口）不注册，
	// 避免把自己加入设备列表；但照常接收落盘。
	if peerIP == primaryIP() {
		receiveLoop(conn, h.DeviceName)
		return
	}
	peer := Device{Name: h.DeviceName, Type: h.DeviceType, IP: peerIP}
	if h.Port > 0 {
		peer.Port = h.Port
	} else {
		// 兼容不带 port 的旧客户端：默认使用标准端口，而不是对方的临时源端口
		peer.Port = selfListenPort
	}
	reg.add(peer)

	receiveLoop(conn, h.DeviceName)
}

func remoteIP(conn net.Conn) string {
	if ta, ok := conn.RemoteAddr().(*net.TCPAddr); ok {
		return ta.IP.String()
	}
	if host, _, err := net.SplitHostPort(conn.RemoteAddr().String()); err == nil {
		return host
	}
	return ""
}

// TextMsg is a received text/clipboard message.
type TextMsg struct {
	From string    `json:"from"`
	Text string    `json:"text"`
	Time time.Time `json:"time"`
}

var (
	textsMu       sync.Mutex
	receivedTexts []TextMsg
)

func addText(from, text string) {
	textsMu.Lock()
	defer textsMu.Unlock()
	receivedTexts = append(receivedTexts, TextMsg{From: from, Text: text, Time: time.Now()})
	if len(receivedTexts) > 200 {
		receivedTexts = receivedTexts[len(receivedTexts)-200:]
	}
}

func listTexts() []TextMsg {
	textsMu.Lock()
	defer textsMu.Unlock()
	out := make([]TextMsg, len(receivedTexts))
	copy(out, receivedTexts)
	return out
}

// safeRelPath 归一化相对路径，去掉 .. / 绝对路径等危险成分。
func safeRelPath(p string) string {
	p = strings.ReplaceAll(p, "\\", "/")
	var out []string
	for _, part := range strings.Split(p, "/") {
		if part == "" || part == "." || part == ".." {
			continue
		}
		out = append(out, part)
	}
	return strings.Join(out, "/")
}

// receiveLoop reads file_meta/file_data/file_end/text frames and writes files.
func receiveLoop(conn net.Conn, peerName string) {
	var f *os.File
	var size, written int64
	var taskID string
	var mtime int64
	var active bool // 当前是否有未收完的文件
	var hash hash.Hash
	var partPath, finalPath string

	// 连接级清理：异常退出（断连/读超时/取消）时丢弃半成品并把任务标记为失败，
	// 避免网页端进度条卡在 running。
	defer func() {
		if f != nil {
			_ = f.Close()
			_ = os.Remove(partPath)
		}
		if active && taskID != "" {
			transfers.finish(taskID, "error", "连接中断")
		}
	}()

	for {
		// 读超时：对端长时间停滞（断网/挂起）时快速失败，避免进度条卡死
		_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		h, payload, err := readFrame(conn)
		if err != nil {
			return
		}
		switch h.Type {
		case "text":
			addText(h.DeviceName, h.Text)

		case "file_meta":
			if f != nil {
				_ = f.Close()
				f = nil
				_ = os.Remove(partPath)
				if active && taskID != "" {
					transfers.finish(taskID, "error", "interrupted")
					active = false
				}
			}
			cfg := getConfig()
			dir := cfg.ReceiveDir
			if h.Sync {
				dir = cfg.SyncDir // 目录同步落到专用同步目录
			} else if !cfg.AutoSave {
				dir = filepath.Join(cfg.ReceiveDir, ".pending")
			}
			_ = os.MkdirAll(dir, 0o755)
			name := sanitizeName(h.FileName)
			if h.RelPath != "" {
				rel := safeRelPath(h.RelPath)
				if filepath.Dir(rel) != "." {
					dir = filepath.Join(dir, filepath.FromSlash(filepath.Dir(rel)))
					_ = os.MkdirAll(dir, 0o755)
				}
				if filepath.Base(rel) != "" {
					name = sanitizeName(filepath.Base(rel))
				}
			}
			finalPath = filepath.Join(dir, name) // 重传同名文件直接覆盖，不再加 (1) 后缀
			partPath = finalPath + ".part"
			mtime = h.Mtime
			hash = md5.New()
			written = 0
			// 断点续传已移除：总是新建 .part 覆盖重写
			nf, err := os.Create(partPath)
			if err != nil {
				_ = writeFrame(conn, Header{Type: "error", Message: err.Error()}, nil)
				return
			}
			f = nf
			size = h.FileSize
			taskID = transfers.create("receive", name, peerName, h.FileSize).ID
			active = true
			if err := writeFrame(conn, Header{Type: "ack"}, nil); err != nil {
				return
			}

		case "file_data":
			if f != nil {
				if _, err := f.Write(payload); err != nil {
					_ = writeFrame(conn, Header{Type: "error", Message: err.Error()}, nil)
					return
				}
				if hash != nil {
					_, _ = hash.Write(payload)
				}
				written += int64(len(payload))
				transfers.update(taskID, written)
			}

		case "file_end":
			if f != nil {
				_ = f.Close()
				f = nil
				if written != size {
					_ = os.Remove(partPath)
					transfers.finish(taskID, "error", "received size mismatch")
					active = false
					_ = writeFrame(conn, Header{Type: "error", Message: "received size mismatch"}, nil)
					return
				}
				// MD5 校验（发送端未携带则跳过，向后兼容）
				if h.MD5 != "" && hash != nil {
					if got := hex.EncodeToString(hash.Sum(nil)); got != h.MD5 {
						_ = os.Remove(partPath)
						transfers.finish(taskID, "error", "md5 mismatch")
						active = false
						_ = writeFrame(conn, Header{Type: "error", Message: "md5 mismatch"}, nil)
						return
					}
				}
				// 覆盖同名旧文件（Windows 上 rename 到已存在目标会失败，先删）
				_ = os.Remove(finalPath)
				if err := os.Rename(partPath, finalPath); err != nil {
					_ = os.Remove(partPath)
					transfers.finish(taskID, "error", err.Error())
					active = false
					_ = writeFrame(conn, Header{Type: "error", Message: err.Error()}, nil)
					return
				}
				// 还原源文件的修改日期
				if mtime > 0 {
					_ = os.Chtimes(finalPath, time.Unix(mtime, 0), time.Unix(mtime, 0))
				}
				// 记录接收来源（供主机网页端「已收到来自xx」提示）
				recordReceivedFrom(filepath.Base(finalPath), peerName)
			}
			active = false
			transfers.finish(taskID, "done", "")
			if err := writeFrame(conn, Header{Type: "ack"}, nil); err != nil {
				return
			}
		}
	}
}

// sendItem 表示一个待发送文件（含文件夹内的相对路径）。
type sendItem struct {
	path    string
	relPath string
	name    string // 原始文件名（为空时回退到 path 的 basename）
}

// sendBatch 连接目标并发送一批文件（支持文件夹相对路径）。
// onProgress 每写完一块回调一次，参数为 (累计已发字节, 总字节)。
func sendBatch(target, selfName, selfType string, items []sendItem, onProgress func(done, total int64), cancel <-chan struct{}) error {
	conn, err := net.DialTimeout("tcp", target, 3*time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetReadBuffer(4 << 20)
		_ = tc.SetWriteBuffer(4 << 20)
	}

	if err := writeFrame(conn, helloHeader(selfName, selfType), nil); err != nil {
		return err
	}
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
	if _, _, err := readFrame(conn); err != nil {
		return err
	}

	var total int64
	for _, it := range items {
		if st, err := os.Stat(it.path); err == nil {
			total += st.Size()
		}
	}
	var done int64
	for i, it := range items {
		base := done // 该文件开始前的累计字节；sendOne 回调的是当前文件的累计值，故用 base+n
		if err := sendOne(conn, it, i, len(items), func(n int64) {
			if onProgress != nil {
				onProgress(base+n, total)
			}
		}, cancel); err != nil {
			return err
		}
		if st, err := os.Stat(it.path); err == nil {
			done += st.Size()
		}
	}
	return nil
}

// sendFiles connects to target and sends all files (flat, no folders).
func sendFiles(target, selfName, selfType string, files []string) error {
	items := make([]sendItem, len(files))
	for i, f := range files {
		items[i] = sendItem{path: f}
	}
	return sendBatch(target, selfName, selfType, items, nil, nil)
}

// sendText 连接目标并发送一条文本/剪贴板消息。
func sendText(target, selfName, selfType, text string) error {
	conn, err := net.DialTimeout("tcp", target, 3*time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()
	if err := writeFrame(conn, helloHeader(selfName, selfType), nil); err != nil {
		return err
	}
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
	if _, _, err := readFrame(conn); err != nil {
		return err
	}
	return writeFrame(conn, Header{Type: "text", Text: text, DeviceName: selfName}, nil)
}

func sendOne(conn net.Conn, it sendItem, fi, total int, onProgress func(int64), cancel <-chan struct{}) error {
	f, err := os.Open(it.path)
	if err != nil {
		return err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return err
	}
	name := it.name
	if name == "" {
		name = filepath.Base(it.path)
	}
	fileID := strconv.Itoa(fi)

	meta := Header{Type: "file_meta", FileID: fileID, FileName: name, FileSize: st.Size(), FileIndex: fi, FileCount: total, RelPath: it.relPath, Mtime: st.ModTime().Unix()}
	// 诊断日志：用于定位 web→手机 mtime 丢失（确认帧 Mtime 是源值还是「现在」）
	log.Printf("[sendOne] %s mtime=%d size=%d", name, meta.Mtime, st.Size())
	_ = conn.SetDeadline(time.Now().Add(60 * time.Second))
	if err := writeFrame(conn, meta, nil); err != nil {
		return err
	}
	_ = conn.SetDeadline(time.Now().Add(60 * time.Second))
	ackH, _, err := readFrame(conn)
	if err != nil {
		return err
	}
	if ackH.Type == "error" {
		return errors.New(ackH.Message)
	}

	hash := md5.New()
	buf := make([]byte, chunkSize)
	var idx int64
	var sent int64
	for {
		// 检查取消
		if cancel != nil {
			select {
			case <-cancel:
				return errors.New("cancelled")
			default:
			}
		}
		n, rerr := f.Read(buf)
		if n > 0 {
			// 写超时：断网/对端停滞时快速失败，避免进度条卡死
			_ = conn.SetDeadline(time.Now().Add(60 * time.Second))
			if err := writeFrame(conn, Header{Type: "file_data", FileID: fileID, ChunkIndex: idx}, buf[:n]); err != nil {
				return err
			}
			_, _ = hash.Write(buf[:n])
			idx++
			sent += int64(n)
			if onProgress != nil {
				onProgress(sent)
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return rerr
		}
	}

	_ = conn.SetDeadline(time.Now().Add(60 * time.Second))
	if err := writeFrame(conn, Header{Type: "file_end", FileID: fileID, TotalBytes: st.Size(), MD5: hex.EncodeToString(hash.Sum(nil))}, nil); err != nil {
		return err
	}
	_ = conn.SetDeadline(time.Now().Add(60 * time.Second))
	if h, _, err := readFrame(conn); err != nil {
		return err
	} else if h.Type == "error" {
		return errors.New(h.Message)
	}
	return nil
}

func sanitizeName(name string) string {
	name = filepath.Base(name)
	name = strings.ReplaceAll(name, "\\", "")
	name = strings.ReplaceAll(name, "/", "")
	if name == "" || name == "." || name == ".." {
		return "unnamed"
	}
	return name
}

func uniquePath(path string) string {
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return path
	}
	ext := filepath.Ext(path)
	base := strings.TrimSuffix(path, ext)
	for i := 1; ; i++ {
		p := fmt.Sprintf("%s (%d)%s", base, i, ext)
		if _, err := os.Stat(p); os.IsNotExist(err) {
			return p
		}
	}
}
