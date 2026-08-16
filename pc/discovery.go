package main

import (
	"net"
	"sort"
	"strconv"
	"sync"
	"time"
)

// Device describes a discovered peer.
type Device struct {
	Name     string    `json:"name"`
	Type     string    `json:"type"`
	IP       string    `json:"ip"`
	Port     int       `json:"port"`
	LastSeen time.Time `json:"lastSeen"`
	Kind     string    `json:"kind,omitempty"` // "tcp"（可直连）或 "web"（网页客户端，走服务器中转）
	SID      string    `json:"sid,omitempty"`  // 网页客户端的 session id
}

// Addr returns "ip:port".
func (d Device) Addr() string { return net.JoinHostPort(d.IP, strconv.Itoa(d.Port)) }

// DeviceRegistry holds discovered peers, keyed by ip:port.
type DeviceRegistry struct {
	mu    sync.RWMutex
	items map[string]Device
}

func newDeviceRegistry() *DeviceRegistry {
	return &DeviceRegistry{items: map[string]Device{}}
}

func (r *DeviceRegistry) add(d Device) {
	r.mu.Lock()
	defer r.mu.Unlock()
	d.LastSeen = time.Now()
	r.items[d.Addr()] = d
}

func (r *DeviceRegistry) list() []Device {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]Device, 0, len(r.items))
	for _, d := range r.items {
		out = append(out, d)
	}
	sortDevices(out)
	return out
}

// clear 清空设备列表（用于「重置设备列表」，去除失联设备）。
func (r *DeviceRegistry) clear() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.items = map[string]Device{}
}

// sortDevices 稳定排序：按 IP 字典序、再按端口、再按设备名，避免列表顺序乱跳。
func sortDevices(out []Device) {
	sort.Slice(out, func(i, j int) bool {
		if out[i].IP != out[j].IP {
			return out[i].IP < out[j].IP
		}
		if out[i].Port != out[j].Port {
			return out[i].Port < out[j].Port
		}
		return out[i].Name < out[j].Name
	})
}

// localIPv4s returns all non-loopback IPv4 addresses of this host.
func localIPv4s() ([]net.IP, error) {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return nil, err
	}
	var out []net.IP
	for _, a := range addrs {
		if ipnet, ok := a.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
			if ip4 := ipnet.IP.To4(); ip4 != nil {
				out = append(out, ip4)
			}
		}
	}
	return out, nil
}

func isLocalIP(ip net.IP) bool {
	if ip.IsLoopback() {
		return true
	}
	local, err := localIPv4s()
	if err != nil {
		return false
	}
	for _, l := range local {
		if l.Equal(ip) {
			return true
		}
	}
	return false
}

// isPrivate reports whether ip is an RFC1918 private address.
func isPrivate(ip net.IP) bool {
	ip4 := ip.To4()
	if ip4 == nil {
		return false
	}
	return ip4[0] == 10 ||
		(ip4[0] == 172 && ip4[1] >= 16 && ip4[1] <= 31) ||
		(ip4[0] == 192 && ip4[1] == 168)
}

// isAPIPA reports whether ip is a link-local 169.254.x.x address.
func isAPIPA(ip net.IP) bool {
	ip4 := ip.To4()
	if ip4 == nil {
		return false
	}
	return ip4[0] == 169 && ip4[1] == 254
}

// primaryIP returns the IPv4 that routes to the internet (the "real" LAN IP).
// It uses a connectionless UDP dial, which performs route lookup without
// sending any packets, so it is safe and cheap.
func primaryIP() string {
	conn, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return ""
	}
	defer conn.Close()
	if ua, ok := conn.LocalAddr().(*net.UDPAddr); ok {
		if ip := ua.IP.To4(); ip != nil {
			return ip.String()
		}
	}
	return ""
}

// scanSubnet probes every host in the /24 of each local interface, skipping
// this node's own listen address. Discovered peers are added to the registry
// and returned.
func scanSubnet(reg *DeviceRegistry, port int, selfType string) []Device {
	ips, err := localIPv4s()
	if err != nil {
		return nil
	}
	const workers = 64
	sem := make(chan struct{}, workers)
	var wg sync.WaitGroup

	for _, base := range ips {
		ip4 := base.To4()
		if ip4 == nil {
			continue
		}
		// APIPA (169.254.x.x) links have no routable peers; skip them.
		if isAPIPA(base) {
			continue
		}
		for i := 1; i <= 254; i++ {
			target := net.IPv4(ip4[0], ip4[1], ip4[2], byte(i))
			// 本机 IP（含虚拟网卡）一律跳过，避免扫到自己
			if isLocalIP(target) {
				continue
			}
			wg.Add(1)
			go func(ip net.IP) {
				defer wg.Done()
				sem <- struct{}{}
				defer func() { <-sem }()
				probe(reg, ip, port, selfType)
			}(target)
		}
	}
	wg.Wait()
	return reg.list()
}

func probe(reg *DeviceRegistry, ip net.IP, port int, selfType string) {
	addr := net.JoinHostPort(ip.String(), strconv.Itoa(port))
	conn, err := net.DialTimeout("tcp", addr, 200*time.Millisecond)
	if err != nil {
		return
	}
	defer conn.Close()

	if err := writeFrame(conn, helloHeader(getSelfName(), selfType), nil); err != nil {
		return
	}
	_ = conn.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	h, _, err := readFrame(conn)
	if err != nil {
		return
	}
	reg.add(Device{Name: h.DeviceName, Type: h.DeviceType, IP: ip.String(), Port: port})
}
