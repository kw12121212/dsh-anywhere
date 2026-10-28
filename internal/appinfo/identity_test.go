package appinfo

import "testing"

func TestDisplayNameIsProductName(t *testing.T) {
	if DisplayName != "DSH Anywhere" {
		t.Errorf("DisplayName = %q, want %q", DisplayName, "DSH Anywhere")
	}
}

func TestNameStaysMachineName(t *testing.T) {
	// 配置目录与 Wails 应用名依赖 Name，改名会让已有配置与 dsh 会话失效。
	if Name != "dsh-anywhere" {
		t.Errorf("Name = %q, want %q", Name, "dsh-anywhere")
	}
}

func TestVersionDefaultsToDev(t *testing.T) {
	if Version != "dev" {
		t.Errorf("Version = %q, want default %q", Version, "dev")
	}
}

func TestAboutTitleNamesProduct(t *testing.T) {
	if got, want := AboutTitle(), "关于 DSH Anywhere"; got != want {
		t.Errorf("AboutTitle() = %q, want %q", got, want)
	}
}

func TestAboutMessageShowsNameAndInjectedVersion(t *testing.T) {
	orig := Version
	Version = "1.2.3"
	t.Cleanup(func() { Version = orig })

	if got, want := AboutMessage(), "DSH Anywhere\n版本 1.2.3"; got != want {
		t.Errorf("AboutMessage() = %q, want %q", got, want)
	}
}
