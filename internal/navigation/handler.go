package navigation

import (
	"log/slog"
	"net/url"
	"strings"

	"dsh-anywhere/internal/config"
)

// OpenExternalPrefix 是注入脚本请求用系统浏览器打开地址时发送的消息前缀。
const OpenExternalPrefix = "dsh-anywhere:open-external:"

// Handler 处理注入脚本经 Wails 原生消息通道发来的请求。
type Handler struct {
	mainWindowID func() uint
	store        *config.Store
	openURL      func(url string) error
}

// NewHandler 返回 Handler；openURL 用系统默认浏览器打开地址。
func NewHandler(mainWindowID func() uint, store *config.Store, openURL func(url string) error) *Handler {
	return &Handler{mainWindowID: mainWindowID, store: store, openURL: openURL}
}

// HandleMessage 处理来自窗口 windowID 的原生消息。消息来自远程页面，内容不可信：
// 只有主窗口发来的、指向与 harness 不同源的 http/https 绝对地址才会被交给系统浏览器。
func (h *Handler) HandleMessage(windowID uint, msg string) {
	target, ok := strings.CutPrefix(msg, OpenExternalPrefix)
	if !ok || windowID != h.mainWindowID() || !isWebURL(target) {
		return
	}
	if harness, err := h.store.Load(); err == nil && SameOrigin(harness, target) {
		return // 同源地址留在主窗口内，交给浏览器会丢失会话 cookie
	}
	if err := h.openURL(target); err != nil {
		// 不记录地址：外部地址中可能带有凭据。
		slog.Warn("open external url failed")
	}
}

func isWebURL(raw string) bool {
	u, err := url.Parse(raw)
	return err == nil && (u.Scheme == "http" || u.Scheme == "https") && u.Hostname() != ""
}
