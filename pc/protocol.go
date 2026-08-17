package main

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
)

const (
	protoVersion = "0.1.0"
	maxHeaderLen = 1 << 20 // 1 MiB
	maxPayload   = 1 << 30 // 1 GiB sanity cap
)

// Header is the JSON header carried before every payload.
type Header struct {
	Type       string `json:"type"`
	PayloadLen int64  `json:"payloadLen"`
	SessionID  string `json:"sessionId,omitempty"`
	FileID     string `json:"fileId,omitempty"`
	FileName   string `json:"fileName,omitempty"`
	FileSize   int64  `json:"fileSize,omitempty"`
	ChunkIndex int64  `json:"chunkIndex,omitempty"`
	TotalBytes int64  `json:"totalBytes,omitempty"`
	DeviceName string `json:"deviceName,omitempty"`
	DeviceType string `json:"deviceType,omitempty"`
	Version    string `json:"version,omitempty"`
	FileIndex  int    `json:"fileIndex,omitempty"`
	FileCount  int    `json:"fileCount,omitempty"`
	Message    string `json:"message,omitempty"`
	Port       int    `json:"port,omitempty"`
	IP         string `json:"ip,omitempty"`
	Text       string `json:"text,omitempty"`
	RelPath    string `json:"relPath,omitempty"`
	MD5        string `json:"md5,omitempty"`    // file_end 时整文件 MD5（32 位小写 hex）
	Mtime      int64  `json:"mtime,omitempty"`  // file_meta 时源文件修改时间（Unix 秒）
	Sync       bool   `json:"sync,omitempty"`   // file_meta 时为目录同步传输（落到同步专用目录）
	Offset     int64  `json:"offset,omitempty"` // 已废弃（断点续传移除），仅向后兼容
}

func helloHeader(name, dtype string) Header {
	return Header{Type: "hello", DeviceName: name, DeviceType: dtype, Version: protoVersion, Port: selfListenPort, IP: primaryIP()}
}

// writeFrame writes a length-prefixed header + payload.
func writeFrame(w io.Writer, h Header, payload []byte) error {
	h.PayloadLen = int64(len(payload))
	hb, err := json.Marshal(h)
	if err != nil {
		return err
	}
	if len(hb) > maxHeaderLen {
		return errors.New("header too large")
	}
	var lenbuf [4]byte
	binary.BigEndian.PutUint32(lenbuf[:], uint32(len(hb)))
	if _, err := w.Write(lenbuf[:]); err != nil {
		return err
	}
	if _, err := w.Write(hb); err != nil {
		return err
	}
	if len(payload) > 0 {
		if _, err := w.Write(payload); err != nil {
			return err
		}
	}
	return nil
}

// readFrame reads a full frame. The returned payload is only valid for the
// duration of the caller's processing (a fresh slice is allocated each call).
func readFrame(r io.Reader) (Header, []byte, error) {
	var lenbuf [4]byte
	if _, err := io.ReadFull(r, lenbuf[:]); err != nil {
		return Header{}, nil, err
	}
	hlen := binary.BigEndian.Uint32(lenbuf[:])
	if hlen > maxHeaderLen {
		return Header{}, nil, errors.New("header too large")
	}
	hb := make([]byte, hlen)
	if _, err := io.ReadFull(r, hb); err != nil {
		return Header{}, nil, err
	}
	var h Header
	if err := json.Unmarshal(hb, &h); err != nil {
		return Header{}, nil, err
	}
	var payload []byte
	if h.PayloadLen > 0 {
		if h.PayloadLen > maxPayload {
			return Header{}, nil, errors.New("payload too large")
		}
		payload = make([]byte, h.PayloadLen)
		if _, err := io.ReadFull(r, payload); err != nil {
			return Header{}, nil, err
		}
	}
	return h, payload, nil
}
