// Package guard 阻止远程页面调用本地 Wails 服务。
//
// Wails v3.0.0 的桌面构建对 /wails/runtime 不做来源校验，并会向导航后的任意页面注入
// _wails 对象；WebKitGTK 对自定义 scheme 的跨源请求也不做 CORS 预检。Guard 作为最外层
// 资源中间件：窗口显示远程内容时拒绝它发往 /wails/ 的全部请求，拒绝 Origin 不是本地源的
// /wails/ 请求，并要求所有 runtime 调用符合本地 @wailsio/runtime 的请求形态。
package guard

import (
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"sync"
)

const (
	// windowIDHeader 由 Wails 原生层按发起请求的 webview 写入并覆盖，页面无法伪造。
	windowIDHeader = "x-wails-window-id"
	clientIDHeader = "x-wails-client-id"
	wailsPrefix    = "/wails/"
	runtimePath    = "/wails/runtime"
)

// Guard 记录哪些窗口正在显示远程内容。零值可用。
type Guard struct {
	mu     sync.Mutex
	remote map[uint]bool
}

// New 返回一个没有远程窗口的 Guard。
func New() *Guard {
	return &Guard{}
}

// SetRemote 标记窗口 id 是否正在显示远程内容。导航到远程地址之前必须先标记，
// 切回本地页面之后再取消标记。
func (g *Guard) SetRemote(id uint, remote bool) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if remote {
		if g.remote == nil {
			g.remote = make(map[uint]bool)
		}
		g.remote[id] = true
	} else {
		delete(g.remote, id)
	}
}

// IsRemote 报告窗口 id 是否被标记为显示远程内容。
func (g *Guard) IsRemote(id uint) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.remote[id]
}

// Middleware 返回拒绝不可信 runtime 请求的资源中间件，用于 application.AssetOptions.Middleware。
func (g *Guard) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if reason := g.rejectReason(r); reason != "" {
			// 只记录路径，不记录查询参数：查询中可能带有调用参数。
			slog.Warn("blocked wails request", "path", r.URL.Path, "method", r.Method,
				"window", r.Header.Get(windowIDHeader), "reason", reason)
			http.Error(w, http.StatusText(http.StatusForbidden), http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (g *Guard) rejectReason(r *http.Request) string {
	if !strings.HasPrefix(r.URL.Path, wailsPrefix) {
		return ""
	}
	// 桌面平台的原生层总会写入 window-id 头；缺失或无法解析时由下面的 Origin 与请求形态校验兜底。
	if id, err := strconv.ParseUint(r.Header.Get(windowIDHeader), 10, 0); err == nil && g.IsRemote(uint(id)) {
		return "window shows remote content"
	}
	if origin := r.Header.Get("Origin"); origin != "" && !originAllowed(origin) {
		return "cross-origin request"
	}
	if r.URL.Path != runtimePath {
		return ""
	}
	switch {
	case r.Method != http.MethodPost:
		return "runtime call must be POST"
	case !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json"):
		return "runtime call must be JSON"
	case r.Header.Get(clientIDHeader) == "":
		return "runtime call missing client id"
	}
	return ""
}
