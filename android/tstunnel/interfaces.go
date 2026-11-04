package tstunnel

// Android 的 SELinux 对 untrusted app 禁止 netlink（tailscale issue 2293），
// Go 标准库 net.Interfaces() 在 Android 11+ 直接 EACCES，tsnet 启动路径里的
// netmon 轮询监视器会因此硬失败（实测 2026-09-24 真机：
// "tsnet: route ip+net: netlinkrib: permission denied"）。
// tailscale 的官方集成点是 netmon.RegisterInterfaceGetter——官方 Android App
// 正是用它注入 Java NetworkInterface 的枚举结果。本文件把同一集成点暴露给
// gomobile 绑定：Kotlin 在 Node.Start 之前把接口快照以 JSON 注入。
//
// 注意 AltAddrs 必须填：netmon.Interface.Addrs() 在 AltAddrs 为 nil 时回落到
// 内嵌 net.Interface.Addrs()（标准库，同样撞 netlink）。ForeachInterfaceAddress
// 只识别 *net.IPNet 形态的地址。

import (
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"runtime"
	"strings"
	"sync"

	"tailscale.com/net/netmon"
)

type ifEntry struct {
	Name      string   `json:"name"`
	Index     int      `json:"index"`
	MTU       int      `json:"mtu"`
	HW        string   `json:"hw"`
	Up        bool     `json:"up"`
	Loopback  bool     `json:"loopback"`
	P2P       bool     `json:"p2p"`
	Multicast bool     `json:"multicast"`
	Addrs     []string `json:"addrs"` // "192.168.0.10/24" 形态；zone 后缀会被剥掉
}

var injectedMu sync.Mutex
var injected []netmon.Interface

func init() {
	// 只在 Android 接管枚举：Kotlin 未注入时返回空列表（节点仍可经 DERP 中继工作），
	// 而不是让标准库撞 SELinux 的 EACCES 使 Start 硬失败。
	//
	// 桌面（tstunnel-run，macOS/Linux）必须走 tailscale 缺省的标准库枚举：无条件接管
	// 会让 netmon 看到 ifs={} v4=false v6=false → magicsock SetNetworkUp(false) →
	// 控制面客户端 setPaused(true)，登录永远停在 NeedsLogin 且不报任何错
	//（exit-browser-spike 2026-10-20 在 macOS 实测）。
	if runtime.GOOS != "android" {
		return
	}
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		injectedMu.Lock()
		defer injectedMu.Unlock()
		return injected, nil
	})
}

// SetInterfacesJSON 注入 App 侧（java.net.NetworkInterface）枚举的接口快照。
// 必须在 Node.Start 之前调用一次；网络切换后可再次调用刷新。
func SetInterfacesJSON(js string) error {
	var entries []ifEntry
	if err := json.Unmarshal([]byte(js), &entries); err != nil {
		return fmt.Errorf("interfaces json: %w", err)
	}
	out := make([]netmon.Interface, 0, len(entries))
	for _, e := range entries {
		ni := &net.Interface{Name: e.Name, Index: e.Index, MTU: e.MTU}
		if e.HW != "" {
			if mac, err := net.ParseMAC(e.HW); err == nil {
				ni.HardwareAddr = mac
			}
		}
		if e.Up {
			ni.Flags |= net.FlagUp
		}
		if e.Loopback {
			ni.Flags |= net.FlagLoopback
		}
		if e.P2P {
			ni.Flags |= net.FlagPointToPoint
		}
		if e.Multicast {
			ni.Flags |= net.FlagMulticast
		}
		// AltAddrs 必须非 nil：nil 会让 netmon.Interface.Addrs() 回落到内嵌
		// net.Interface.Addrs()（标准库 netlink，Android 上 EACCES——实测正是
		// "route ip+net: netlinkrib: permission denied" 的无地址接口路径）。
		addrs := make([]net.Addr, 0, len(e.Addrs))
		for _, a := range e.Addrs {
			// Inet6Address.getHostAddress 可能带 "%zone" 后缀，剥掉再解析
			p, err := netip.ParsePrefix(cutZone(a))
			if err != nil {
				continue
			}
			addr := p.Addr().Unmap()
			addrs = append(addrs, &net.IPNet{
				IP:   addr.AsSlice(),
				Mask: net.CIDRMask(p.Bits(), addr.BitLen()),
			})
		}
		out = append(out, netmon.Interface{Interface: ni, AltAddrs: addrs})
	}
	injectedMu.Lock()
	injected = out
	injectedMu.Unlock()
	return nil
}

func cutZone(s string) string {
	if i := strings.IndexByte(s, '%'); i >= 0 {
		return s[:i]
	}
	return s
}
