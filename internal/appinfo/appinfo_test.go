package appinfo

import (
	"errors"
	"path/filepath"
	"testing"
)

func stubUserConfigDir(t *testing.T, dir string, err error) {
	t.Helper()
	orig := userConfigDir
	userConfigDir = func() (string, error) { return dir, err }
	t.Cleanup(func() { userConfigDir = orig })
}

func TestNameIsDshAnywhere(t *testing.T) {
	if Name != "dsh-anywhere" {
		t.Errorf("Name = %q, want %q", Name, "dsh-anywhere")
	}
}

func TestIdentifierIsBundleIdentifier(t *testing.T) {
	if Identifier != "work.keepwriting.dsh-anywhere" {
		t.Errorf("Identifier = %q, want %q", Identifier, "work.keepwriting.dsh-anywhere")
	}
}

func TestConfigDirJoinsAppNameToUserConfigDir(t *testing.T) {
	base := t.TempDir()
	stubUserConfigDir(t, base, nil)

	got, err := ConfigDir()
	if err != nil {
		t.Fatalf("ConfigDir() error = %v", err)
	}
	if want := filepath.Join(base, "dsh-anywhere"); got != want {
		t.Errorf("ConfigDir() = %q, want %q", got, want)
	}
}

func TestConfigDirUsesOSUserConfigDir(t *testing.T) {
	base := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", base) // Linux
	t.Setenv("APPDATA", base)         // Windows
	t.Setenv("HOME", base)            // macOS: $HOME/Library/Application Support

	got, err := ConfigDir()
	if err != nil {
		t.Fatalf("ConfigDir() error = %v", err)
	}
	if filepath.Base(got) != "dsh-anywhere" {
		t.Errorf("ConfigDir() = %q, want last element %q", got, "dsh-anywhere")
	}
	if rel, err := filepath.Rel(base, filepath.Dir(got)); err != nil || !filepath.IsLocal(rel) {
		t.Errorf("ConfigDir() = %q, want a path under %q", got, base)
	}
}

func TestConfigDirReportsMissingUserConfigDir(t *testing.T) {
	want := errors.New("no config dir")
	stubUserConfigDir(t, "", want)

	got, err := ConfigDir()
	if !errors.Is(err, want) {
		t.Fatalf("ConfigDir() error = %v, want wrapping %v", err, want)
	}
	if got != "" {
		t.Errorf("ConfigDir() = %q, want empty path on error", got)
	}
}

func TestDataDirUsesXDGDataHome(t *testing.T) {
	base := t.TempDir()
	t.Setenv("XDG_DATA_HOME", base)

	got, err := DataDir()
	if err != nil {
		t.Fatal(err)
	}
	if want := filepath.Join(base, "dsh-anywhere"); got != want {
		t.Errorf("DataDir() = %q, want %q", got, want)
	}
}

func TestDataDirFallsBackToHome(t *testing.T) {
	home := t.TempDir()
	for _, xdg := range []string{"", "relative/dir"} {
		t.Setenv("XDG_DATA_HOME", xdg)
		orig := userHomeDir
		userHomeDir = func() (string, error) { return home, nil }
		got, err := DataDir()
		userHomeDir = orig
		if err != nil {
			t.Fatal(err)
		}
		if want := filepath.Join(home, ".local", "share", "dsh-anywhere"); got != want {
			t.Errorf("XDG_DATA_HOME=%q: DataDir() = %q, want %q", xdg, got, want)
		}
	}
}

func TestDataDirReportsMissingHome(t *testing.T) {
	t.Setenv("XDG_DATA_HOME", "")
	want := errors.New("no home")
	orig := userHomeDir
	userHomeDir = func() (string, error) { return "", want }
	t.Cleanup(func() { userHomeDir = orig })

	if _, err := DataDir(); !errors.Is(err, want) {
		t.Errorf("DataDir() error = %v, want wrapping %v", err, want)
	}
}
