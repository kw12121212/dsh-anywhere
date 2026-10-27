// Package mainwindow 负责主窗口在本地页面与远程 harness 页面之间的导航，
// 并保证远程内容加载前窗口已在 guard 中被标记为远程。
package mainwindow

import (
	"net/url"
	"sync"

	"dsh-anywhere/internal/config"
	"dsh-anywhere/internal/guard"
	"dsh-anywhere/internal/probe"
	"dsh-anywhere/internal/setup"
)

const (
	// ConnectingURL 是启动时检查 harness 地址期间显示的本地页面。
	ConnectingURL = "/?view=connecting"
	// loadFailedPath 是 harness 地址不可达时显示的本地错误页，查询参数为 url 与 reason。
	loadFailedPath = "/?view=load-failed"
)

// LoadFailedURL 返回展示 cleanURL 不可达原因的本地错误页地址。cleanURL 必须不带 token。
func LoadFailedURL(cleanURL string, reason probe.Reason) string {
	return loadFailedPath + "&" + url.Values{"url": {cleanURL}, "reason": {string(reason)}}.Encode()
}

// Window 是主窗口导航所需的最小操作集合，由 main.go 用 Wails 窗口方法提供。
type Window struct {
	ID     func() uint
	SetURL func(url string)
}

// Controller 发起主窗口的全部导航。
//
// 所有导航副作用（guard 标记与 SetURL）都在 navMu 内执行，后台探测完成时的代次比较也在同一
// 临界区内，保证窗口最终显示的页面与远程标记一致，且只有最后一次发起的导航生效。Wails 的菜单、
// 窗口事件、绑定调用与原生消息回调都运行在独立 goroutine 中，持有 navMu 调用 SetURL
// （内部 InvokeSync 到主线程）不会与主线程互相等待。
type Controller struct {
	guard  *guard.Guard
	store  *config.Store
	window Window
	check  func(url string) probe.Reason
	run    func(func())

	startOnce sync.Once
	navMu     sync.Mutex

	mu sync.Mutex
	// generation 每发起一次导航加一；后台探测完成时代次已变就放弃，只让最后一次导航生效。
	generation uint64
	// sessionRetry 在最近一次 OpenRemote 之后、尚未重试时为 true，见 RetrySession。
	sessionRetry bool
}

// New 返回 Controller：check 探测干净地址是否可达（probe.Prober.Check），run 在后台执行探测
// （生产环境为 func(f func()) { go f() }）。
func New(g *guard.Guard, store *config.Store, window Window, check func(url string) probe.Reason, run func(func())) *Controller {
	return &Controller{guard: g, store: store, window: window, check: check, run: run}
}

// InitialURL 返回主窗口创建时加载的本地页面：已保存有效地址时为“正在连接”页，否则为初始化界面。
func (c *Controller) InitialURL() string {
	if _, err := c.store.Load(); err == nil {
		return ConnectingURL
	}
	return setup.LocalURL
}

// Start 在已保存有效地址时开始打开 harness（先探测），只在第一次调用时生效。
// 必须在主窗口真正创建之后调用（main.go 在第一个 WindowRuntimeReady 事件中调用）：
// app.Run 之前对窗口 SetURL 会在原生窗口创建时被初始 URL 覆盖，探测很快失败时错误页会丢失。
func (c *Controller) Start() {
	c.startOnce.Do(func() {
		if clean, err := c.store.Load(); err == nil {
			c.OpenRemote(clean)
		}
	})
}

// OpenRemote 在后台用 url 的干净形式探测可达性：可达时先把主窗口标记为远程，再导航到 url
// （可能带 token）；不可达时打开本地错误页。较晚发起的导航会使尚未完成的探测失效。
func (c *Controller) OpenRemote(rawURL string) {
	gen := c.nextGeneration()
	clean, err := config.Normalize(rawURL)
	c.run(func() {
		reason := probe.Other
		if err == nil {
			reason = c.check(clean)
		} else {
			clean = "" // 地址无法规范化时不回显原文，避免 token 进入错误页
		}
		c.navMu.Lock()
		defer c.navMu.Unlock()
		if !c.isCurrent(gen) {
			return
		}
		if reason != probe.Reachable {
			c.openLocal(LoadFailedURL(clean, reason))
			return
		}
		c.openRemote(rawURL, true)
	})
}

// openRemote 与 openLocal 是导航副作用，调用方必须持有 navMu。
func (c *Controller) openRemote(url string, allowRetry bool) {
	c.setSessionRetry(allowRetry)
	c.guard.SetRemote(c.window.ID(), true)
	c.window.SetURL(url)
}

// openLocal 先导航到本地页面，之后才取消远程标记，避免远程页面在离开前获得访问权限。
func (c *Controller) openLocal(url string) {
	c.setSessionRetry(false)
	c.window.SetURL(url)
	c.guard.SetRemote(c.window.ID(), false)
}

func (c *Controller) nextGeneration() uint64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.generation++
	return c.generation
}

func (c *Controller) isCurrent(gen uint64) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.generation == gen
}

// RetrySession 在最近一次 OpenRemote 之后第一次收到会话失效时，用已保存的干净地址重新打开 harness
// 并返回 true；已经重试过或之后发生了其他导航时返回 false。重试不探测：主机刚刚响应过。
//
// 从本地页面（wails://）发起的导航被 WebKitGTK 视为跨站，dsh 的 SameSite=Strict 会话 cookie
// 不随请求（包括签发 cookie 后的重定向）发送，于是得到 401；此时页面已位于 harness 源，
// 再用干净地址导航就是同站请求，会带上已有的 cookie。带 token 的导航之后重试还用干净地址
// 覆盖了 Wails 内存中保留的带 token 地址。会话确实失效时，重试仍得到 401，随后显示提示页。
func (c *Controller) RetrySession() bool {
	c.navMu.Lock()
	defer c.navMu.Unlock()
	c.mu.Lock()
	retry := c.sessionRetry
	c.sessionRetry = false // 读取与清除在同一把锁内，并发的失效消息只会触发一次重试
	c.mu.Unlock()
	if !retry {
		return false
	}
	clean, err := c.store.Load()
	if err != nil {
		return false
	}
	c.nextGeneration() // 使尚未完成的探测失效
	c.openRemote(clean, false)
	return true
}

func (c *Controller) setSessionRetry(retry bool) {
	c.mu.Lock()
	c.sessionRetry = retry
	c.mu.Unlock()
}

// OpenLocal 导航回本地初始化界面。
func (c *Controller) OpenLocal() {
	c.OpenLocalURL(setup.LocalURL)
}

// OpenLocalURL 导航到本地页面 url，之后才取消远程标记；尚未完成的探测随之失效。
func (c *Controller) OpenLocalURL(url string) {
	c.navMu.Lock()
	defer c.navMu.Unlock()
	c.nextGeneration()
	c.openLocal(url)
}

// Reload 重新加载主窗口：已保存有效地址时（无论当前显示 harness、正在连接页、错误页、
// 重新认证提示页还是刚完成的初始化界面）重新探测并打开 harness；否则回到本地初始化界面。
//
// 不使用 WebviewWindow.Reload：Wails v3.0.0 在 Linux 上的 reload 实际加载的是 wails://
// 本地首页，而不是当前页面。
func (c *Controller) Reload() {
	clean, err := c.store.Load()
	if err != nil {
		c.OpenLocal()
		return
	}
	c.OpenRemote(clean)
}

// Service 是绑定给主窗口本地页面（加载失败错误页）的 Wails 服务。远程页面的调用会被 guard 拒绝。
type Service struct {
	controller *Controller
}

// NewService 返回 Service。
func NewService(c *Controller) *Service {
	return &Service{controller: c}
}

// Retry 重新检查已保存的地址并在可达时打开 harness（“重试”按钮）。
func (s *Service) Retry() {
	s.controller.Reload()
}
