package main

import (
	"embed"
	"log"
	"path/filepath"

	"dsh-anywhere/internal/appinfo"
	"dsh-anywhere/internal/appmenu"
	"dsh-anywhere/internal/config"
	"dsh-anywhere/internal/guard"
	"dsh-anywhere/internal/mainwindow"
	"dsh-anywhere/internal/navigation"
	"dsh-anywhere/internal/probe"
	"dsh-anywhere/internal/session"
	"dsh-anywhere/internal/settings"
	"dsh-anywhere/internal/setup"
	"dsh-anywhere/internal/singleinstance"
	"dsh-anywhere/internal/webviewdata"
	"dsh-anywhere/internal/windowstate"

	"github.com/wailsapp/wails/v3/pkg/application"
	"github.com/wailsapp/wails/v3/pkg/events"
)

//go:embed all:frontend/dist
var assets embed.FS

func main() {
	store, err := config.DefaultStore()
	if err != nil {
		log.Fatal(err)
	}
	windowStore, err := windowstate.DefaultStore()
	if err != nil {
		log.Fatal(err)
	}
	runtimeGuard := guard.New()

	var mainWindow *application.WebviewWindow
	navigator := mainwindow.New(runtimeGuard, store, mainwindow.Window{
		ID:     func() uint { return mainWindow.ID() },
		SetURL: func(url string) { mainWindow.SetURL(url) },
	}, probe.New(probe.DefaultTimeout).Check, func(task func()) { go task() })
	activator := singleinstance.New()

	var app *application.App
	// 窗口方法在调用时才解引用 mainWindow，创建窗口之前仍是 nil。
	windowState := windowstate.New(windowStore, windowstate.WindowOps{
		Position:    func() (int, int) { return mainWindow.Position() },
		Size:        func() (int, int) { return mainWindow.Size() },
		IsMaximised: func() bool { return mainWindow.IsMaximised() },
		SetPosition: func(x, y int) { mainWindow.SetPosition(x, y) },
		SetSize:     func(width, height int) { mainWindow.SetSize(width, height) },
		Maximise:    func() { mainWindow.Maximise() },
		Show:        func() { mainWindow.Show() },
	}, func() []windowstate.Screen { return windowstate.FromWails(app.Screen.GetAll()) })
	settingsWindow := settings.ByName(func(name string) (application.Window, bool) { return app.Window.GetByName(name) })
	closeSettings := func() { settings.Close(settingsWindow) }
	openSettings := func() {
		settings.Open(settingsWindow, func() {
			app.Window.NewWithOptions(application.WebviewWindowOptions{
				Name:   settings.WindowName,
				Title:  "设置",
				Width:  560,
				Height: 320,
				URL:    settings.URL,
			})
		})
	}
	links := navigation.NewHandler(func() uint { return mainWindow.ID() }, store,
		func(url string) error { return app.Browser.OpenURL(url) })
	sessionNonce := session.NewNonce()
	expiry := session.NewHandler(sessionNonce, func() uint { return mainWindow.ID() }, runtimeGuard.IsRemote,
		navigator.RetrySession, navigator.OpenLocalURL)
	// 关于对话框使用应用身份常量：名称与版本由 internal/appinfo 提供，注入的版本号随构建变化。
	showAbout := func() {
		app.Dialog.Info().SetTitle(appinfo.AboutTitle()).SetMessage(appinfo.AboutMessage()).Show()
	}

	app = application.New(application.Options{
		Name: appinfo.Name,
		// 同一用户会话内只保留一个实例：再次启动时由已运行实例激活主窗口，新进程退出。
		SingleInstance: &application.SingleInstanceOptions{
			UniqueID: appinfo.Identifier,
			OnSecondInstanceLaunch: func(application.SecondInstanceData) {
				activator.Request()
			},
		},
		Services: []application.Service{
			application.NewService(config.NewService(store)),
			application.NewService(setup.NewService(store, navigator.OpenRemote)),
			application.NewService(mainwindow.NewService(navigator)),
			application.NewService(settings.NewService(store, func() bool { return runtimeGuard.IsRemote(mainWindow.ID()) },
				navigator.OpenRemote, openSettings, closeSettings)),
		},
		Assets: application.AssetOptions{
			Handler:    application.AssetFileServerFS(assets),
			Middleware: runtimeGuard.Middleware,
		},
		// 注入脚本经原生消息通道请求打开外部链接；远程页面不能通过它调用绑定服务。
		RawMessageHandler: func(w application.Window, msg string, _ *application.OriginInfo) {
			links.HandleMessage(w.ID(), msg)
			expiry.HandleMessage(w.ID(), msg)
		},
		Mac: application.MacOptions{
			ApplicationShouldTerminateAfterLastWindowClosed: true,
		},
		// Wails 在关闭窗口之前依次执行 shutdown 任务，因此这里能读到有效几何，
		// 且覆盖关闭窗口、菜单退出与 macOS Cmd+Q 三条退出路径。
		OnShutdown: func() {
			if err := windowState.Save(); err != nil {
				log.Printf("保存窗口状态失败：%v", err)
			}
		},
	})

	mainWindow = app.Window.NewWithOptions(application.WebviewWindowOptions{
		Title:  appinfo.DisplayName,
		Width:  windowstate.DefaultWidth,
		Height: windowstate.DefaultHeight,
		// 先隐藏创建：由 windowState.Restore 在映射前设好尺寸再显示，避免窗口先按默认
		// 尺寸出现；位置要到显示之后才设置，因为 Linux GTK 的窗口只有映射后才能移动。
		Hidden:             true,
		URL:                navigator.InitialURL(),
		UseApplicationMenu: true,
		JS:                 navigation.Script + session.Script(sessionNonce),
	})
	activator.Bind(singleinstance.Window{
		IsMinimised: mainWindow.IsMinimised,
		UnMinimise:  mainWindow.UnMinimise,
		Show:        func() { mainWindow.Show() },
		Focus:       mainWindow.Focus,
	})
	// 主窗口总是先加载本地页面（正在连接页或初始化界面）；该页面就绪时窗口已经创建，
	// 此时由 Start 探测并打开 harness（只在第一次生效）。单实例激活同样等到此时：
	// app.Run 之前 Wails 的平台实现尚未初始化，不能调用窗口方法。
	mainWindow.OnWindowEvent(events.Common.WindowRuntimeReady, func(*application.WindowEvent) {
		// 恢复必须在窗口创建之后、页面导航之前完成：显示器列表在 app.Run 内才可用。
		windowState.Restore()
		navigator.Start()
		activator.Start()
	})
	app.Menu.Set(appmenu.Build(navigator.Reload, openSettings, showAbout))
	// 关闭主窗口即退出应用：否则 Linux/Windows 上设置窗口会让进程继续运行，
	// 之后保存会对已销毁的主窗口导航。
	mainWindow.OnWindowEvent(events.Common.WindowClosing, func(*application.WindowEvent) { app.Quit() })

	// Linux 上让 dsh 会话 cookie 跨应用重启保留；必须在任何页面加载（app.Run）之前。
	dataDir, err := appinfo.DataDir()
	if err != nil {
		log.Fatal(err)
	}
	if err := webviewdata.PersistCookies(filepath.Join(dataDir, webviewdata.CookieFile)); err != nil {
		log.Fatal(err)
	}

	if err := app.Run(); err != nil {
		log.Fatal(err)
	}
}
