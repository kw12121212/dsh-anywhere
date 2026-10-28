// Package appmenu 构建应用菜单。
package appmenu

import (
	"runtime"

	"dsh-anywhere/internal/appinfo"

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
	// HelpLabel 是帮助子菜单的文字；没有系统应用菜单的平台把“关于”放在这里。
	HelpLabel = "帮助"
)

// AboutLabel 是“关于”菜单项的文字，与应用身份同源。
var AboutLabel = appinfo.AboutTitle()

// roleEntry 描述 macOS 应用菜单中的一个系统角色；separatedBefore 表示在它之前插入分隔线。
type roleEntry struct {
	role            application.Role
	separatedBefore bool
}

// appMenuRoles 是 macOS 应用菜单在“关于”之后依次添加的系统角色与分隔位置，
// 顺序与 Wails v3.0.0 的 AppMenu 一致。
var appMenuRoles = []roleEntry{
	{role: application.ServicesMenu, separatedBefore: true},
	{role: application.Hide},
	{role: application.HideOthers},
	{role: application.UnHide},
	{role: application.Quit, separatedBefore: true},
}

// roleAdder 把一个系统角色加到菜单上。
// 它作为参数注入，使单元测试可以在没有 Wails 全局应用实例时核对角色顺序。
type roleAdder func(*application.Menu, application.Role)

// addRoleTo 是 roleAdder 的生产实现。
func addRoleTo(menu *application.Menu, role application.Role) {
	menu.AddRole(role)
}

// Build 返回应用菜单；reload、openSettings 与 showAbout 分别在用户选择对应命令时被调用。
func Build(reload, openSettings, showAbout func()) *application.Menu {
	return build(runtime.GOOS, addRoleTo, reload, openSettings, showAbout)
}

// item 描述一个自定义菜单项；accelerator 为空时不设快捷键。
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

// aboutItem 返回“关于”菜单项；它只出现在没有系统应用菜单的平台上。
func aboutItem(showAbout func()) item {
	return item{submenu: HelpLabel, label: AboutLabel, action: showAbout}
}

func build(goos string, add roleAdder, reload, openSettings, showAbout func()) *application.Menu {
	menu := application.NewMenu()
	if goos == "darwin" {
		// macOS 的应用菜单由本项目自建，才能让“关于”显示版本号。
		addAppMenu(menu, add, showAbout)
	}
	// 编辑菜单提供复制、粘贴等快捷键；macOS 的 webview 没有它时 Cmd+C/V 不可用。
	menu.AddRole(application.EditMenu)
	submenus := map[string]*application.Menu{}
	addItem := func(it item) {
		sub, ok := submenus[it.submenu]
		if !ok {
			sub = menu.AddSubmenu(it.submenu)
			submenus[it.submenu] = sub
		}
		entry := sub.Add(it.label)
		if it.accelerator != "" {
			entry.SetAccelerator(it.accelerator)
		}
		entry.OnClick(onClick(it.action))
	}
	for _, it := range items(reload, openSettings) {
		addItem(it)
	}
	if goos != "darwin" {
		// 其他平台没有系统应用菜单，关于入口放在帮助子菜单。
		addItem(aboutItem(showAbout))
	}
	return menu
}

// addAppMenu 自建 macOS 应用菜单：“关于”在前，随后是 Wails AppMenu 使用的系统角色。
func addAppMenu(menu *application.Menu, add roleAdder, showAbout func()) {
	appMenu := menu.AddSubmenu(appinfo.DisplayName)
	appMenu.Add(AboutLabel).OnClick(onClick(showAbout))
	for _, entry := range appMenuRoles {
		if entry.separatedBefore {
			appMenu.AddSeparator()
		}
		add(appMenu, entry.role)
	}
}

func onClick(action func()) func(*application.Context) {
	return func(*application.Context) { action() }
}
