package appmenu

import (
	"testing"

	"github.com/wailsapp/wails/v3/pkg/application"
)

// accelerator 返回 SetAccelerator 在宿主平台上规范化后的文本（Linux/Windows 上 CmdOrCtrl 显示为 Ctrl）。
func accelerator(shortcut string) string {
	return application.NewMenu().Add("x").SetAccelerator(shortcut).GetAccelerator()
}

func TestBuildHasItemsWithShortcuts(t *testing.T) {
	items := map[string]string{
		ReloadLabel:   accelerator(ReloadAccelerator),
		SettingsLabel: accelerator(SettingsAccelerator),
	}
	for _, goos := range []string{"linux", "windows", "darwin"} {
		t.Run(goos, func(t *testing.T) {
			menu := build(goos, func() {}, func() {})

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

func TestOnClickCallsAction(t *testing.T) {
	calls := 0
	onClick(func() { calls++ })(nil)
	if calls != 1 {
		t.Errorf("action called %d times, want 1", calls)
	}
}

func TestBuildUsesHostPlatform(t *testing.T) {
	if Build(func() {}, func() {}).FindByLabel(SettingsLabel) == nil {
		t.Errorf("Build() has no %q item", SettingsLabel)
	}
}
