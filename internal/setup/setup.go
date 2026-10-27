// Package setup 判定是否进入初始化界面，并在用户完成初始化后保存地址、打开 harness。
package setup

import (
	"errors"
	"strings"

	"dsh-anywhere/internal/config"
)

// State 描述主窗口启动时是否需要初始化界面。
type State struct {
	// NeedsSetup 为 true 时主窗口应显示初始化界面。
	NeedsSetup bool `json:"needsSetup"`
	// InvalidConfig 为 true 时初始化界面应提示已有配置无效。
	InvalidConfig bool `json:"invalidConfig"`
}

// Decide 根据已保存的配置判定初始化状态：从未配置时需要初始化；
// 配置损坏、地址无效或无法读取时需要初始化并提示已有配置无效。
func Decide(store *config.Store) State {
	_, err := store.Load()
	switch {
	case err == nil:
		return State{}
	case errors.Is(err, config.ErrNotFound):
		return State{NeedsSetup: true}
	default:
		return State{NeedsSetup: true, InvalidConfig: true}
	}
}

// LocalURL 是主窗口中本地前端（初始化界面）的地址。
const LocalURL = "/"

// Service 是绑定给本地初始化界面的 Wails 服务。
type Service struct {
	store    *config.Store
	navigate func(url string)
}

// NewService 返回 Service；navigate 由主窗口提供，用于从 Go 侧打开 harness 页面。
func NewService(store *config.Store, navigate func(url string)) *Service {
	return &Service{store: store, navigate: navigate}
}

// State 返回当前的初始化状态。
func (s *Service) State() State {
	return Decide(s.store)
}

// Complete 校验并保存 raw，成功后用原始地址（可能带 token）打开 harness 一次，
// 让 dsh 签发会话 cookie，并返回已保存的干净地址。失败时不导航。
func (s *Service) Complete(raw string) (string, error) {
	clean, err := s.store.Save(raw)
	if err != nil {
		return "", err
	}
	s.navigate(strings.TrimSpace(raw))
	return clean, nil
}
