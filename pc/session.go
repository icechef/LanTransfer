package main

import (
	"crypto/rand"
	"encoding/hex"
	"sync"
	"time"
)

// WebClient 是一个通过浏览器访问的网页客户端（独立身份）。
type WebClient struct {
	SID      string    `json:"sid"`
	Name     string    `json:"name"`
	IP       string    `json:"ip"`
	LastSeen time.Time `json:"lastSeen"`
}

// InboxMessage 是投递给某个网页客户端的一条中转消息。
type InboxMessage struct {
	ID       string    `json:"id"`
	From     string    `json:"from"`
	Kind     string    `json:"kind"` // "file" | "text"
	FileName string    `json:"fileName,omitempty"`
	Text     string    `json:"text,omitempty"`
	Size     int64     `json:"size,omitempty"`
	Time     time.Time `json:"time"`
}

// SessionManager 管理网页客户端身份与中转收件箱。
type SessionManager struct {
	mu      sync.Mutex
	clients map[string]*WebClient
	inbox   map[string][]InboxMessage
}

func newSessionManager() *SessionManager {
	return &SessionManager{
		clients: map[string]*WebClient{},
		inbox:   map[string][]InboxMessage{},
	}
}

func newSID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// getOrCreate 用 sid 找回已有客户端，否则新建；name 非空时更新名字。
func (sm *SessionManager) getOrCreate(sid, name, ip string) *WebClient {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	if sid != "" {
		if c, ok := sm.clients[sid]; ok {
			if name != "" {
				c.Name = name
			}
			c.LastSeen = time.Now()
			if ip != "" {
				c.IP = ip
			}
			return c
		}
	}
	if name == "" {
		name = "网页设备"
	}
	nsid := newSID()
	c := &WebClient{SID: nsid, Name: name, IP: ip, LastSeen: time.Now()}
	sm.clients[nsid] = c
	return c
}

func (sm *SessionManager) setName(sid, name string) *WebClient {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	c, ok := sm.clients[sid]
	if !ok {
		return nil
	}
	c.Name = name
	c.LastSeen = time.Now()
	return c
}

func (sm *SessionManager) get(sid string) *WebClient {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	return sm.clients[sid]
}

func (sm *SessionManager) list() []*WebClient {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	out := make([]*WebClient, 0, len(sm.clients))
	for _, c := range sm.clients {
		out = append(out, c)
	}
	return out
}

func (sm *SessionManager) push(toSID string, m InboxMessage) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.inbox[toSID] = append(sm.inbox[toSID], m)
}

// pop 取出并清空某个客户端的全部未读消息。
func (sm *SessionManager) pop(sid string) []InboxMessage {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	msgs := sm.inbox[sid]
	sm.inbox[sid] = nil
	if msgs == nil {
		return []InboxMessage{}
	}
	return msgs
}
