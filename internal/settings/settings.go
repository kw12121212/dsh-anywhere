// Package settings 实现独立的本地设置窗口：查看与修改 harness 地址。
package settings

import (
	"strings"

	"dsh-anywhere/internal/config"

	"github.com/wailsapp/wails/v3/pkg/application"
)

// WindowName 是设置窗口在 Wails 窗口管理器中的名称。
const WindowName = "settings"

// URL 是设置窗口加载的本地页面。
const URL = "/?view=settings"

// Window 是设置窗口所需的最小操作集合，application.Window 满足它。
type Window interface {
	Show() application.Window
	Focus()
	Close()
}

// Lookup 返回已存在的设置窗口；不存在时 ok 为 false。
type Lookup func() (window Window, ok bool)

// ByName 把 Wails 窗口管理器的按名称查找（app.Window.GetByName）适配为设置窗口的 Lookup。
func ByName(get func(name string) (application.Window, bool)) Lookup {
	return func() (Window, bool) {
		w, ok := get(WindowName)
		if !ok {
			return nil, false
		}
		return w, true
	}
}

// Open 在设置窗口已存在时显示并聚焦它，否则调用 create 新建。
func Open(lookup Lookup, create func()) {
	if w, ok := lookup(); ok {
		w.Show()
		w.Focus()
		return
	}
	create()
}

// Close 关闭已存在的设置窗口。
func Close(lookup Lookup) {
	if w, ok := lookup(); ok {
		w.Close()
	}
}

// Service 是绑定给设置窗口的 Wails 服务。
type Service struct {
	store            *config.Store
	mainShowsHarness func() bool
	openRemote       func(url string)
	openWindow       func()
	closeWindow      func()
}

// NewService 返回 Service；mainShowsHarness 报告主窗口是否正显示远程 harness 页面，
// openRemote 让主窗口打开 harness 地址，openWindow 打开或聚焦设置窗口，closeWindow 关闭设置窗口。
func NewService(store *config.Store, mainShowsHarness func() bool, openRemote func(url string), openWindow, closeWindow func()) *Service {
	return &Service{store: store, mainShowsHarness: mainShowsHarness, openRemote: openRemote, openWindow: openWindow, closeWindow: closeWindow}
}

// Open 打开或聚焦设置窗口，供本地页面（如重新认证提示页）的“打开设置”按钮调用。
// 远程页面的调用会被 guard 拒绝。
func (s *Service) Open() {
	s.openWindow()
}

// Save 校验并保存 raw，关闭设置窗口并返回已保存的干净地址。
// 地址未改动、输入不带 token 且主窗口正显示 harness 时主窗口不重新加载；否则主窗口用原始地址
// 打开一次以建立 dsh 会话（主窗口在本地页面时也会回到 harness）。
// 失败时返回错误，窗口保持打开，主窗口不导航。
func (s *Service) Save(raw string) (string, error) {
	previous, prevErr := s.store.Load()
	clean, err := s.store.Save(raw)
	if err != nil {
		return "", err
	}
	trimmed := strings.TrimSpace(raw)
	if prevErr != nil || clean != previous || trimmed != clean || !s.mainShowsHarness() {
		s.openRemote(trimmed)
	}
	s.closeWindow()
	return clean, nil
}

// Cancel 不修改配置，关闭设置窗口。
func (s *Service) Cancel() {
	s.closeWindow()
}
