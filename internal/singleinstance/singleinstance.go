// Package singleinstance 把第二个实例的启动通知转换为对已运行实例主窗口的激活。
package singleinstance

import "sync"

// Window 是激活主窗口所需的最小操作集合，由 main.go 用 Wails 窗口方法提供。
type Window struct {
	IsMinimised func() bool
	UnMinimise  func()
	Show        func()
	Focus       func()
}

// Activator 决定何时、以什么顺序执行激活。
//
// Wails 在 application.New 内就注册单实例监听，而主窗口创建与 app.Run 都晚于它；
// app.Run 之前调用窗口方法会因平台实现尚未初始化而 panic。因此 Start 之前收到的
// 请求只记录为待处理，由 Start 在窗口就绪后补做。
type Activator struct {
	mu     sync.Mutex
	window Window

	started bool
	pending bool
}

// New 返回 Activator。先 Bind 窗口操作，再由窗口就绪事件调用 Start。
func New() *Activator {
	return &Activator{}
}

// Bind 记录主窗口的操作集合，必须在 Start 之前调用。
func (a *Activator) Bind(window Window) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.window = window
}

// Start 表示窗口已就绪，可以安全调用窗口方法；有提前到达的请求时立即激活。
func (a *Activator) Start() {
	a.mu.Lock()
	defer a.mu.Unlock()

	a.started = true
	if a.pending {
		a.pending = false
		a.activate()
	}
}

// Request 处理一次第二个实例的启动通知：窗口就绪后立即激活，否则等待 Start。
func (a *Activator) Request() {
	a.mu.Lock()
	defer a.mu.Unlock()

	if !a.started {
		a.pending = true
		return
	}
	a.activate()
}

// activate 在持有 mu 时执行：最小化窗口必须先恢复，再显示并聚焦。
func (a *Activator) activate() {
	if a.window.IsMinimised() {
		a.window.UnMinimise()
	}
	a.window.Show()
	a.window.Focus()
}
