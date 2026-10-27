// Package session 识别 dsh web 会话失效并把主窗口切到本地的重新认证提示页。
package session

import (
	"crypto/rand"
	_ "embed"
	"encoding/hex"
	"strings"
)

// expiredPrefix 是注入脚本识别到会话失效时发送的消息前缀，后接本次启动的 nonce。
const expiredPrefix = "dsh-anywhere:session-expired:"

const noncePlaceholder = "__DSH_NONCE__"

// ReauthURL 是本地重新认证提示页的地址。
const ReauthURL = "/?view=reauth"

//go:embed script.js
var scriptTemplate string

// NewNonce 返回本次启动使用的随机 nonce（十六进制）。
func NewNonce() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b) // crypto/rand.Read 不会返回错误
	return hex.EncodeToString(b)
}

// Script 返回带 nonce 的会话失效识别脚本，与导航脚本一起通过 WebviewWindowOptions.JS 设置。
func Script(nonce string) string {
	return strings.ReplaceAll(scriptTemplate, noncePlaceholder, nonce)
}

// ExpiredMessage 返回带 nonce 的会话失效消息。
func ExpiredMessage(nonce string) string {
	return expiredPrefix + nonce
}

// Handler 处理注入脚本经 Wails 原生消息通道发来的会话失效消息。
type Handler struct {
	message      string
	mainWindowID func() uint
	isRemote     func(windowID uint) bool
	retry        func() bool
	openReauth   func(url string)
}

// NewHandler 返回 Handler：isRemote 报告窗口是否正显示远程内容；retry 在最近一次导航后第一次
// 失效时用干净地址重试一次并返回 true（mainwindow.Controller.RetrySession）；openReauth 让主窗口打开本地页面。
// nonce 必须与 Script 使用的相同。
func NewHandler(nonce string, mainWindowID func() uint, isRemote func(windowID uint) bool, retry func() bool, openReauth func(url string)) *Handler {
	return &Handler{message: ExpiredMessage(nonce), mainWindowID: mainWindowID, isRemote: isRemote, retry: retry, openReauth: openReauth}
}

// HandleMessage 在主窗口显示远程内容时收到会话失效消息：每次导航后先重试一次，
// 否则让主窗口打开重新认证提示页。消息来自不可信页面，只能触发这两个固定动作。
func (h *Handler) HandleMessage(windowID uint, msg string) {
	if msg != h.message || windowID != h.mainWindowID() || !h.isRemote(windowID) {
		return
	}
	if h.retry() {
		return
	}
	h.openReauth(ReauthURL)
}
