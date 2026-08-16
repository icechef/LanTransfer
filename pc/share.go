package main

import (
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// SharedItem 表示一个只记录路径、不复制内容的共享文件。
type SharedItem struct {
	Path string    `json:"path"`
	Name string    `json:"name"`
	Size int64     `json:"size"`
	Time time.Time `json:"time"`
}

type shareRegistry struct {
	mu    sync.Mutex
	items []SharedItem
}

var shares = &shareRegistry{}

var errNotFile = errors.New("not a regular file")

func (sr *shareRegistry) add(path string) (SharedItem, error) {
	st, err := os.Stat(path)
	if err != nil {
		return SharedItem{}, err
	}
	if st.IsDir() {
		return SharedItem{}, errNotFile
	}
	sr.mu.Lock()
	defer sr.mu.Unlock()
	for _, it := range sr.items {
		if it.Path == path {
			return it, nil
		}
	}
	item := SharedItem{Path: path, Name: filepath.Base(path), Size: st.Size(), Time: time.Now()}
	sr.items = append(sr.items, item)
	return item, nil
}

// remove 只移除路径记录，不删除真实文件。
func (sr *shareRegistry) remove(path string) bool {
	sr.mu.Lock()
	defer sr.mu.Unlock()
	for i, it := range sr.items {
		if it.Path == path {
			sr.items = append(sr.items[:i], sr.items[i+1:]...)
			return true
		}
	}
	return false
}

func (sr *shareRegistry) list() []SharedItem {
	sr.mu.Lock()
	defer sr.mu.Unlock()
	out := make([]SharedItem, len(sr.items))
	copy(out, sr.items)
	return out
}

// contains 判断某路径是否在「路径共享」列表里。
func (sr *shareRegistry) contains(path string) bool {
	sr.mu.Lock()
	defer sr.mu.Unlock()
	for _, it := range sr.items {
		if it.Path == path {
			return true
		}
	}
	return false
}
