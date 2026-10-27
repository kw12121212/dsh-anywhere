package windowstate

import (
	"sync"
	"sync/atomic"
)

// WindowOps 是恢复与保存窗口状态所需的窗口操作，由 main.go 用 Wails 窗口方法提供。
type WindowOps struct {
	Position    func() (int, int)
	Size        func() (int, int)
	IsMaximised func() bool
	SetPosition func(x, y int)
	SetSize     func(width, height int)
	Maximise    func()
	Show        func()
}

// StateStore 读写窗口状态；Store 满足它，单元测试可用记录器替换。
type StateStore interface {
	Load() (State, error)
	Save(State) error
}

// Manager 在主窗口就绪时恢复保存的几何，在应用退出时保存当前几何。
type Manager struct {
	store   StateStore
	window  WindowOps
	screens func() []Screen

	once     sync.Once
	restored atomic.Bool
}

// New 返回 Manager。screens 在 Restore 时才调用：显示器列表只有 app.Run 之后才可用。
func New(store StateStore, window WindowOps, screens func() []Screen) *Manager {
	return &Manager{store: store, window: window, screens: screens}
}

// Restore 应用保存的几何并显示主窗口，只在第一次调用时生效。
//
// 必须由窗口的第一个 WindowRuntimeReady 事件调用：app.Run 之前既拿不到显示器列表，
// 也不能调用会触达平台实现的窗口方法。顺序固定为设置尺寸、显示窗口、设置位置、最大化：
// Linux 的 GTK 窗口只有在映射之后才有可移动的 native surface，隐藏阶段的位置设置是空操作，
// 而尺寸可以在映射前通过默认尺寸生效，因此先在隐藏状态设置尺寸，可以避免窗口先按默认
// 尺寸出现再跳动。
func (m *Manager) Restore() {
	m.once.Do(func() {
		var saved *State
		if state, err := m.store.Load(); err == nil {
			saved = &state
		}
		placement := Resolve(saved, m.screens())
		m.window.SetSize(placement.Width, placement.Height)
		m.window.Show()
		if placement.Move {
			m.window.SetPosition(placement.X, placement.Y)
		}
		if placement.Maximised {
			m.window.Maximise()
		}
		m.restored.Store(true)
	})
}

// Save 把主窗口当前的位置、尺寸与最大化状态写入状态文件。
//
// 两种情况下跳过且不报错：Restore 尚未执行（窗口未就绪，读到的几何是占位值），以及
// 宽或高不为正（窗口已销毁）。写入会覆盖上一次的有效状态，因此宁可保留旧状态。
func (m *Manager) Save() error {
	if !m.restored.Load() {
		return nil
	}
	x, y := m.window.Position()
	width, height := m.window.Size()
	if width <= 0 || height <= 0 {
		return nil
	}
	return m.store.Save(State{
		X:         x,
		Y:         y,
		Width:     width,
		Height:    height,
		Maximised: m.window.IsMaximised(),
	})
}
