package appmenu

import (
	"runtime"
	"testing"

	"dsh-anywhere/internal/appinfo"

	"github.com/wailsapp/wails/v3/pkg/application"
)

// accelerator 返回 SetAccelerator 在宿主平台上规范化后的文本（Linux/Windows 上 CmdOrCtrl 显示为 Ctrl）。
func accelerator(shortcut string) string {
	return application.NewMenu().Add("x").SetAccelerator(shortcut).GetAccelerator()
}

// buildForTest 用记录器替代系统角色注入，避免依赖 Wails 全局应用实例，并返回记录到的角色顺序。
func buildForTest(goos string, reload, openSettings, showAbout func()) (*application.Menu, []application.Role) {
	var roles []application.Role
	menu := build(goos, func(_ *application.Menu, role application.Role) { roles = append(roles, role) },
		reload, openSettings, showAbout)
	return menu, roles
}

func noop() {}

func TestBuildHasItemsWithShortcuts(t *testing.T) {
	items := map[string]string{
		ReloadLabel:   accelerator(ReloadAccelerator),
		SettingsLabel: accelerator(SettingsAccelerator),
	}
	for _, goos := range []string{"linux", "windows", "darwin"} {
		t.Run(goos, func(t *testing.T) {
			menu, _ := buildForTest(goos, noop, noop, noop)

			for label, want := range items {
				item := menu.FindByLabel(label)
				if item == nil {
					t.Fatalf("menu has no %q item", label)
				}
				if got := item.GetAccelerator(); got != want || got == "" {
					t.Errorf("%q accelerator = %q, want %q", label, got, want)
				}
			}
			for _, sub := range []string{"文件", "视图"} {
				if m := menu.FindByLabel(sub); m == nil || !m.IsSubmenu() {
					t.Errorf("menu has no %s submenu", sub)
				}
			}
			if menu.FindByRole(application.EditMenu) == nil {
				t.Error("menu has no edit menu role")
			}
		})
	}
}

func TestItemsWireEachLabelToItsAction(t *testing.T) {
	var got []string
	reload := func() { got = append(got, "reload") }
	openSettings := func() { got = append(got, "settings") }
	want := map[string]string{ReloadLabel: "reload", SettingsLabel: "settings"}

	for _, it := range items(reload, openSettings) {
		got = nil
		it.action()
		if len(got) != 1 || got[0] != want[it.label] {
			t.Errorf("%q triggers %q, want %q", it.label, got, want[it.label])
		}
	}
}

func TestAboutItemCallsShowAbout(t *testing.T) {
	calls := 0
	it := aboutItem(func() { calls++ })
	if it.label != appinfo.AboutTitle() {
		t.Errorf("about label = %q, want %q", it.label, appinfo.AboutTitle())
	}
	if it.accelerator != "" {
		t.Errorf("about accelerator = %q, want none", it.accelerator)
	}
	it.action()
	if calls != 1 {
		t.Errorf("showAbout called %d times, want 1", calls)
	}
}

func TestAboutItemOnEveryPlatform(t *testing.T) {
	for _, goos := range []string{"linux", "windows", "darwin"} {
		t.Run(goos, func(t *testing.T) {
			menu, _ := buildForTest(goos, noop, noop, noop)
			item := menu.FindByLabel(AboutLabel)
			if item == nil {
				t.Fatalf("menu has no %q item", AboutLabel)
			}
			if item.GetAccelerator() != "" {
				t.Errorf("%q accelerator = %q, want none", AboutLabel, item.GetAccelerator())
			}
		})
	}
}

func TestAboutInAppMenuOnMacOS(t *testing.T) {
	menu, roles := buildForTest("darwin", noop, noop, noop)

	appMenu := menu.FindByLabel(appinfo.DisplayName)
	if appMenu == nil || !appMenu.IsSubmenu() {
		t.Fatalf("menu has no %q application submenu", appinfo.DisplayName)
	}
	sub := appMenu.GetSubmenu()
	if first := sub.ItemAt(0); first == nil || first.Label() != AboutLabel {
		t.Errorf("application menu first item = %v, want %q", first, AboutLabel)
	}
	if sep := sub.ItemAt(1); sep == nil || !sep.IsSeparator() {
		t.Errorf("application menu item 1 is not a separator")
	}
	if menu.FindByLabel(HelpLabel) != nil {
		t.Error("darwin menu should not have a help submenu")
	}

	want := []application.Role{
		application.ServicesMenu, application.Hide, application.HideOthers, application.UnHide, application.Quit,
	}
	if len(roles) != len(want) {
		t.Fatalf("application menu roles = %v, want %v", roles, want)
	}
	for i := range want {
		if roles[i] != want[i] {
			t.Errorf("application menu role %d = %v, want %v", i, roles[i], want[i])
		}
	}
}

func TestAboutInHelpMenuOffMacOS(t *testing.T) {
	for _, goos := range []string{"linux", "windows"} {
		t.Run(goos, func(t *testing.T) {
			menu, roles := buildForTest(goos, noop, noop, noop)

			help := menu.FindByLabel(HelpLabel)
			if help == nil || !help.IsSubmenu() {
				t.Fatalf("menu has no %q submenu", HelpLabel)
			}
			if menu.FindByLabel(appinfo.DisplayName) != nil {
				t.Errorf("%s menu should not have an application submenu", goos)
			}
			if len(roles) != 0 {
				t.Errorf("%s menu added application menu roles %v", goos, roles)
			}
		})
	}
}

func TestOnClickCallsAction(t *testing.T) {
	calls := 0
	onClick(func() { calls++ })(nil)
	if calls != 1 {
		t.Errorf("action called %d times, want 1", calls)
	}
}

func TestBuildUsesHostPlatform(t *testing.T) {
	menu := Build(noop, noop, noop)
	if menu.FindByLabel(SettingsLabel) == nil {
		t.Errorf("Build() has no %q item", SettingsLabel)
	}
	// macOS 宿主上真实 Build 会构建应用菜单；Hide/Quit 自建项不能依赖 Wails 全局应用实例。
	if runtime.GOOS == "darwin" {
		for _, role := range []application.Role{application.Hide, application.Quit} {
			if menu.FindByRole(role) == nil {
				t.Errorf("Build() on darwin has no item with role %v", role)
			}
		}
	}
}
