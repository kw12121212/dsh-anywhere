package tstunnel

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// SetInterfacesJSON 是 Android 侧注入网卡快照的唯一入口，JSON 口径必须从严：
// 坏 JSON 报错、好 JSON 接受（含空数组——Kotlin 未注入时节点仍可经 DERP 工作）。
func TestSetInterfacesJSON(t *testing.T) {
	cases := []struct {
		name    string
		js      string
		wantErr bool
	}{
		{"bad-json", "{not json", true},
		{"empty-array", "[]", false},
		{"entry-with-addrs", `[{"name":"wlan0","index":3,"mtu":1500,"up":true,"addrs":["192.168.1.10/24"]}]`, false},
		{"zone-suffix-accepted", `[{"name":"rmnet0","addrs":["fe80::1%rmnet0/64"]}]`, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := SetInterfacesJSON(tc.js)
			if tc.wantErr && err == nil {
				t.Fatalf("SetInterfacesJSON(%q) 应报错", tc.js)
			}
			if !tc.wantErr && err != nil {
				t.Fatalf("SetInterfacesJSON(%q) 不应报错: %v", tc.js, err)
			}
		})
	}
}

// NewNode 必须在构造时把 XDG_CACHE_HOME 指到 dir 的兄弟 cache 目录并预建其下
// Tailscale 子目录——Android app 进程里这是 logpolicy 不 panic 的前提
// （mobile-manage 2026-09-24 实测）。桌面测试验证同一行为。
func TestNewNodeSetsCacheEnv(t *testing.T) {
	dir := t.TempDir()
	node := NewNode("test-host", dir, "")
	if node == nil {
		t.Fatal("NewNode 返回 nil")
	}
	cache := os.Getenv("XDG_CACHE_HOME")
	if cache != filepath.Join(filepath.Dir(dir), "cache") {
		t.Fatalf("XDG_CACHE_HOME=%q，期望 %q", cache, filepath.Join(filepath.Dir(dir), "cache"))
	}
	st, err := os.Stat(filepath.Join(cache, "Tailscale"))
	if err != nil || !st.IsDir() {
		t.Fatalf("Tailscale 缓存子目录未预建: %v", err)
	}
}

// Errmsg 记录最近一次失败文本，供 Kotlin 侧日志与取证兜底。
func TestErrmsg(t *testing.T) {
	n := &Node{}
	if n.Errmsg() != "" {
		t.Fatalf("初始 Errmsg 应为空，得到 %q", n.Errmsg())
	}
	n.recordErr(errString("boom"))
	if got := n.Errmsg(); !strings.Contains(got, "boom") {
		t.Fatalf("Errmsg=%q，应含 boom", got)
	}
}

type errString string

func (e errString) Error() string { return string(e) }

// Up 必须有期限：节点在管理台被删除或登出后，tsnet 会停在 NeedsLogin 永久等待，
// Kotlin 侧 ensureUp 持锁阻塞，上传、分享与粘贴新 key 全部卡死。控制面不可达时
// 同样只能等到期限返回错误。
func TestUpTimesOut(t *testing.T) {
	old := upTimeout
	upTimeout = 2 * time.Second
	t.Cleanup(func() { upTimeout = old })

	n := NewNode("pca-test", filepath.Join(t.TempDir(), "state"), "")
	n.srv.ControlURL = "http://127.0.0.1:1" // 拒绝连接：永远登录不上
	n.srv.Logf = func(string, ...any) {}
	t.Cleanup(func() { _ = n.Close() })

	done := make(chan error, 1)
	go func() { done <- n.Up() }()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("登录不上时 Up 应返回错误")
		}
		if n.Errmsg() == "" {
			t.Fatal("Up 失败应记录 errmsg 供 Kotlin 侧展示")
		}
	case <-time.After(30 * time.Second):
		t.Fatal("Up 在期限后仍未返回")
	}
}
