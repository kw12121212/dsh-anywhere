// Package appmenu 构建应用菜单。
package appmenu

import (
	"runtime"

	"github.com/wailsapp/wails/v3/pkg/application"
)

const (
	// SettingsLabel 是“设置…”菜单项的文字。
	SettingsLabel = "设置…"
	// SettingsAccelerator 在 macOS 上为 Cmd+,，其他平台为 Ctrl+,。
	SettingsAccelerator = "CmdOrCtrl+,"
	// ReloadLabel 是“重新加载”菜单项的文字。
	ReloadLabel = "重新加载"
	// ReloadAccelerator 在 macOS 上为 Cmd+R，其他平台为 Ctrl+R。
	ReloadAccelerator = "CmdOrCtrl+R"
)

// Build 返回应用菜单；reload 与 openSettings 分别在用户选择“重新加载”与“设置…”时被调用。
func Build(reload, openSettings func()) *application.Menu {
	return build(runtime.GOOS, reload, openSettings)
}

// item 描述一个带快捷键的菜单项。
type item struct {
	submenu, label, accelerator string
	action                      func()
}

// items 列出自定义菜单项及其回调；build 按此表构建菜单，测试据此核对接线。
func items(reload, openSettings func()) []item {
	return []item{
		{submenu: "文件", label: SettingsLabel, accelerator: SettingsAccelerator, action: openSettings},
		{submenu: "视图", label: ReloadLabel, accelerator: ReloadAccelerator, action: reload},
	}
}

func build(goos string, reload, openSettings func()) *application.Menu {
	menu := application.NewMenu()
	if goos == "darwin" {
		menu.AddRole(application.AppMenu)
	}
	// 编辑菜单提供复制、粘贴等快捷键；macOS 的 webview 没有它时 Cmd+C/V 不可用。
	menu.AddRole(application.EditMenu)
	submenus := map[string]*application.Menu{}
	for _, it := range items(reload, openSettings) {
		sub, ok := submenus[it.submenu]
		if !ok {
			sub = menu.AddSubmenu(it.submenu)
			submenus[it.submenu] = sub
		}
		sub.Add(it.label).SetAccelerator(it.accelerator).OnClick(onClick(it.action))
	}
	return menu
}

func onClick(action func()) func(*application.Context) {
	return func(*application.Context) { action() }
}
