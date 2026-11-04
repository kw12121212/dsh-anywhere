// Package tstunnel 是 dsh-anywhere 的 Android 嵌入面：自写薄包装把
// tailscale.com/tsnet（与官方客户端同源的 Go 内核）以 gomobile 可绑定的最小面
// 暴露给 Kotlin。方向为 App 进程内 userspace tailnet 客户端（复制自参考项目 private-content-assistant/android/tstunnel，2026-09 真机验证形态）：无 TUN、无 VPNService、
// 无 VPN 权限，WireGuard + netstack 在进程内承载，自身请求经 Loopback 暴露的
// SOCKS5 回环代理到达服务。
//
// gomobile 对返回值只支持单值或 (T, error)，因此 Loopback 的三个返回值拆成两个方法。
package tstunnel

import (
	"context"
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"

	"tailscale.com/hostinfo"
	"tailscale.com/tsnet"
)

func init() {
	// 节点在 tailnet 管理台里按 App 呈现，与官方客户端区分开。
	hostinfo.SetApp("dsh-tstunnel")
}

// Node 包一个进程内 userspace tailnet 节点。所有方法串行使用是预期形态
// （Kotlin 侧一个后台线程驱动生命周期），内部锁只保护 lastErr 的并发读写。
type Node struct {
	srv *tsnet.Server

	mu      sync.Mutex
	lastErr string
}

// NewNode 构造节点。hostname / dir / authKey 与 tsnet.Server 同义；authKey
// 可空（已注册节点凭持久状态免 key 重连）。
// dir 必须指向 App 私有目录：节点状态持久在那里，强杀后免 key 重连依赖它。
func NewNode(hostname, dir, authKey string) *Node {
	// logpolicy 选日志状态目录的顺序：GOOS 分支（android 无分支，STATE_DIRECTORY
	// 只在 linux 分支被读，对 GOOS=android 无效）→ 系统状态目录 → os.UserCacheDir
	// → cwd → os.MkdirTemp。Android app 进程里 XDG_CACHE_HOME/HOME 均未设而
	// UserCacheDir 报错、cwd 是 /、/tmp 不存在，最终 MkdirTemp 失败直接 panic
	//（mobile-manage 2026-09-24 模拟器与真机实测：no safe place found to store
	// log state）。把 XDG_CACHE_HOME 指到 App 私有缓存目录并预建其下的 Tailscale
	// 子目录，命中 UserCacheDir 分支且持久。
	cache := filepath.Join(filepath.Dir(dir), "cache")
	os.MkdirAll(filepath.Join(cache, "Tailscale"), 0700)
	os.Setenv("XDG_CACHE_HOME", cache)
	return &Node{srv: &tsnet.Server{
		Hostname: hostname,
		Dir:      dir,
		AuthKey:  authKey,
	}}
}

// EnableStderrLogs 让 tsnet 后端日志（控制面登录、netmap、路由）打到标准错误；
// 必须在 Start 之前调用。缺省（不调用）时后端日志只进 tsnet 自己的 logtail 缓冲，
// 桌面排障（Up 停在 NeedsLogin 看不到原因）需要它。
func (n *Node) EnableStderrLogs() {
	n.srv.Logf = log.Printf
}

// Start 拉起 Go IPN 栈，不等待登录完成。
func (n *Node) Start() error {
	if err := n.srv.Start(); err != nil {
		n.recordErr(err)
		return err
	}
	return nil
}

// upTimeout 是 Up 等待登录完成的期限。节点在管理台被删除或登出后 tsnet 会停在
// NeedsLogin 永久等待，Kotlin 侧持锁的 ensureUp 随之卡死；到期返回错误，由 Kotlin
// 侧收敛为失败态。容纳首次注册与冷启动的正常耗时。变量而非常量：测试注入短期限。
var upTimeout = 60 * time.Second

// Up 阻塞直到节点登录完成并可用（tagged auth key 注册或已持久状态的免 key
// 重连），最长 upTimeout。Kotlin 侧必须从后台线程调用，不得占用主线程。
func (n *Node) Up() error {
	ctx, cancel := context.WithTimeout(context.Background(), upTimeout)
	defer cancel()
	_, err := n.srv.Up(ctx)
	if err != nil {
		n.recordErr(err)
		return err
	}
	return nil
}

// Loopback 返回内嵌节点 SOCKS5 回环代理的监听地址（"127.0.0.1:port"）。
// App 自身请求把它设为代理即可经 tailnet 到达服务；凭据经 ProxyCredential。
func (n *Node) Loopback() (string, error) {
	addr, _, _, err := n.srv.Loopback()
	if err != nil {
		n.recordErr(err)
		return "", err
	}
	return addr, nil
}

// ProxyCredential 返回 SOCKS5 回环代理的口令（tsnet 生成时已是 hex 字符串，
// 原样返回不得再编码）。SOCKS5 认证的用户名固定为 "tsnet"、口令为该值
// （tsnet.go@v1.94.1 的 Loopback 实现口径，TstunnelSocksUser）。
func (n *Node) ProxyCredential() (string, error) {
	_, proxyCred, _, err := n.srv.Loopback()
	if err != nil {
		n.recordErr(err)
		return "", err
	}
	return proxyCred, nil
}

// TstunnelSocksUser 是 tsnet 回环 SOCKS5 代理的固定用户名（tsnet.go 的口径，
// 客户端用 Loopback 地址 + 该用户名 + ProxyCredential 口令认证）。
const TstunnelSocksUser = "tsnet"

// StatusJSON 返回 ipnstate.Status 的 JSON（状态轮询用；经进程内 LocalAPI，
// 挂起期间仍可读）。
func (n *Node) StatusJSON() (string, error) {
	lc, err := n.srv.LocalClient()
	if err != nil {
		n.recordErr(err)
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), statusTimeout)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		n.recordErr(err)
		return "", err
	}
	b, err := json.Marshal(st)
	if err != nil {
		n.recordErr(err)
		return "", err
	}
	return string(b), nil
}

// Errmsg 返回最近一次失败的错误文本（gomobile 的 error 会转 Java 异常，
// 这个兜底口用于日志与取证）。
func (n *Node) Errmsg() string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.lastErr
}

// Close 关停节点。之后 Node 不可复用，重新接入即新建 Node。
// （「同进程关闭再重建导致进程消失」的实测出自 iOS spike 的 libtailscale C 绑定
// （mobile-manage lessons）；Android 侧重连循环即 close→new 形态，但失败 up 后
// 带新 key 重建的构造性取证是遗留边界——上层 TunnelManager 默认不重建在役节点。）
func (n *Node) Close() error {
	if err := n.srv.Close(); err != nil {
		n.recordErr(err)
		return err
	}
	return nil
}

const statusTimeout = 10 * time.Second

func (n *Node) recordErr(err error) {
	n.mu.Lock()
	n.lastErr = err.Error()
	n.mu.Unlock()
}
