package main

import (
	"sync"
	"time"
)

// Transfer 是一次文件传输任务（发送或接收）的进度快照。
type Transfer struct {
	ID     string    `json:"id"`
	Dir    string    `json:"dir"`    // "send" | "receive"
	Name   string    `json:"name"`   // 文件名
	Size   int64     `json:"size"`   // 总字节
	Done   int64     `json:"done"`   // 已完成字节
	Status string    `json:"status"` // "running" | "done" | "error"
	Peer   string    `json:"peer"`   // 对端
	Err    string    `json:"error,omitempty"`
	Start  time.Time `json:"start"`
}

type transferRegistry struct {
	mu    sync.Mutex
	items map[string]*Transfer
	order []string
}

var transfers = &transferRegistry{items: map[string]*Transfer{}}

func (tr *transferRegistry) create(dir, name, peer string, size int64) *Transfer {
	tr.mu.Lock()
	defer tr.mu.Unlock()
	t := &Transfer{ID: newSID(), Dir: dir, Name: name, Size: size, Status: "running", Peer: peer, Start: time.Now()}
	tr.items[t.ID] = t
	tr.order = append(tr.order, t.ID)
	return t
}

func (tr *transferRegistry) update(id string, done int64) {
	tr.mu.Lock()
	defer tr.mu.Unlock()
	if t, ok := tr.items[id]; ok {
		t.Done = done
	}
}

func (tr *transferRegistry) finish(id, status, errMsg string) {
	tr.mu.Lock()
	defer tr.mu.Unlock()
	if t, ok := tr.items[id]; ok {
		t.Status = status
		t.Err = errMsg
		if status == "done" {
			t.Done = t.Size
		}
	}
}

// list 返回活跃任务在前、其余按时间倒序，最多 50 条。
func (tr *transferRegistry) list() []*Transfer {
	tr.mu.Lock()
	defer tr.mu.Unlock()
	var running, rest []*Transfer
	for i := len(tr.order) - 1; i >= 0; i-- {
		t := tr.items[tr.order[i]]
		if t.Status == "running" {
			running = append(running, t)
		} else {
			rest = append(rest, t)
		}
	}
	out := make([]*Transfer, 0, len(running)+len(rest))
	out = append(out, running...)
	out = append(out, rest...)
	if len(out) > 50 {
		out = out[:50]
	}
	return out
}
