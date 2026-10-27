package config

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func newTestStore(t *testing.T) (*Store, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "dsh-anywhere", FileName)
	return NewStore(path), path
}

func writeConfigFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestLoadMissingConfig(t *testing.T) {
	store, _ := newTestStore(t)

	got, err := store.Load()
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("Load() error = %v, want ErrNotFound", err)
	}
	if errors.Is(err, ErrInvalidConfig) {
		t.Errorf("Load() error = %v, must not be ErrInvalidConfig", err)
	}
	if got != "" {
		t.Errorf("Load() = %q, want empty", got)
	}
}

func TestLoadInvalidConfig(t *testing.T) {
	tests := []struct {
		name, content string
		cause         error
	}{
		{"corrupt json", "{not json", nil},
		{"truncated json", `{"harnessURL": "https://dsh.ex`, nil},
		{"wrong type", `{"harnessURL": 42}`, nil},
		{"empty url", `{"harnessURL": ""}`, ErrEmpty},
		{"missing field", `{}`, ErrEmpty},
		{"unsupported scheme", `{"harnessURL": "ftp://dsh.example.test/"}`, ErrUnsupportedScheme},
		{"relative url", `{"harnessURL": "/chat"}`, ErrNotAbsolute},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			store, path := newTestStore(t)
			writeConfigFile(t, path, tt.content)

			got, err := store.Load()
			if !errors.Is(err, ErrInvalidConfig) {
				t.Fatalf("Load() error = %v, want ErrInvalidConfig", err)
			}
			if tt.cause != nil && !errors.Is(err, tt.cause) {
				t.Errorf("Load() error = %v, want cause %v", err, tt.cause)
			}
			if got != "" {
				t.Errorf("Load() = %q, want empty", got)
			}
		})
	}
}

func TestLoadStripsHandEditedToken(t *testing.T) {
	store, path := newTestStore(t)
	writeConfigFile(t, path, `{"harnessURL": "https://dsh.example.test/?token=abc123"}`)

	got, err := store.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if got != "https://dsh.example.test/" {
		t.Errorf("Load() = %q, want %q", got, "https://dsh.example.test/")
	}
}

func TestLoadReadError(t *testing.T) {
	store, path := newTestStore(t)
	if err := os.MkdirAll(path, 0o700); err != nil { // 目录无法当作文件读取
		t.Fatal(err)
	}

	_, err := store.Load()
	if err == nil || errors.Is(err, ErrNotFound) || errors.Is(err, ErrInvalidConfig) {
		t.Fatalf("Load() error = %v, want a read error", err)
	}
}

func TestSaveThenLoadAfterRestart(t *testing.T) {
	_, path := newTestStore(t)

	clean, err := NewStore(path).Save("  https://dsh.example.test/  ")
	if err != nil {
		t.Fatalf("Save() error = %v", err)
	}
	if clean != "https://dsh.example.test/" {
		t.Errorf("Save() = %q, want %q", clean, "https://dsh.example.test/")
	}

	got, err := NewStore(path).Load() // 新的 Store 模拟应用重启
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if got != "https://dsh.example.test/" {
		t.Errorf("Load() = %q, want %q", got, "https://dsh.example.test/")
	}
}

func TestSavePersistsOnlyCleanURL(t *testing.T) {
	store, path := newTestStore(t)

	clean, err := store.Save("https://dsh.example.test/base/?lang=zh&token=abc123&token=def456#top")
	if err != nil {
		t.Fatalf("Save() error = %v", err)
	}
	if want := "https://dsh.example.test/base/?lang=zh#top"; clean != want {
		t.Errorf("Save() = %q, want %q", clean, want)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{"abc123", "def456", "token"} {
		if strings.Contains(string(data), secret) {
			t.Errorf("config file %q contains %q", data, secret)
		}
	}
}

func TestSaveOverwritesWithoutLeftovers(t *testing.T) {
	store, path := newTestStore(t)
	if _, err := store.Save("https://a.example.test/"); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Save("https://b.example.test/"); err != nil {
		t.Fatalf("second Save() error = %v", err)
	}

	got, err := NewStore(path).Load()
	if err != nil || got != "https://b.example.test/" {
		t.Errorf("Load() = %q, %v, want %q", got, err, "https://b.example.test/")
	}
	entries, err := os.ReadDir(filepath.Dir(path))
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != FileName {
		var names []string
		for _, e := range entries {
			names = append(names, e.Name())
		}
		t.Errorf("config dir contains %v, want only %s", names, FileName)
	}
}

func TestSaveRejectsInvalidWithoutTouchingConfig(t *testing.T) {
	store, path := newTestStore(t)
	if _, err := store.Save("https://a.example.test/"); err != nil {
		t.Fatal(err)
	}
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}

	for _, raw := range []string{"", "/chat", "ftp://dsh.example.test/"} {
		if _, err := store.Save(raw); err == nil {
			t.Errorf("Save(%q) error = nil, want rejection", raw)
		}
	}
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != string(before) {
		t.Errorf("config changed from %q to %q after rejected saves", before, after)
	}
}

func TestSaveRejectedLeavesNoConfigFile(t *testing.T) {
	tests := []struct {
		raw  string
		want error
	}{
		{"/chat", ErrNotAbsolute},
		{"ftp://dsh.example.test/", ErrUnsupportedScheme},
		{"file:///tmp/x", ErrUnsupportedScheme},
	}
	for _, tt := range tests {
		t.Run(tt.raw, func(t *testing.T) {
			store, path := newTestStore(t)

			if _, err := store.Save(tt.raw); !errors.Is(err, tt.want) {
				t.Fatalf("Save(%q) error = %v, want %v", tt.raw, err, tt.want)
			}
			if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
				t.Errorf("config file exists after rejected save: %v", err)
			}
		})
	}
}

func TestSaveInterruptedKeepsPreviousConfig(t *testing.T) {
	store, path := newTestStore(t)
	if _, err := store.Save("https://a.example.test/"); err != nil {
		t.Fatal(err)
	}
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	interrupted := errors.New("interrupted before rename")
	orig := rename
	rename = func(string, string) error { return interrupted }
	t.Cleanup(func() { rename = orig })

	got, err := store.Save("https://b.example.test/?token=abc123")
	if !errors.Is(err, interrupted) {
		t.Fatalf("Save() error = %v, want wrapping %v", err, interrupted)
	}
	if got != "" {
		t.Errorf("Save() = %q, want empty on error", got)
	}
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != string(before) {
		t.Errorf("config changed from %q to %q after interrupted save", before, after)
	}
	entries, err := os.ReadDir(filepath.Dir(path))
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != FileName {
		var names []string
		for _, e := range entries {
			names = append(names, e.Name())
		}
		t.Errorf("config dir contains %v after interrupted save, want only %s", names, FileName)
	}
}

func TestSaveRestrictsPermissions(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix 权限位不适用于 Windows")
	}
	store, path := newTestStore(t)
	if _, err := store.Save("https://dsh.example.test/"); err != nil {
		t.Fatal(err)
	}

	fi, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if perm := fi.Mode().Perm(); perm != 0o600 {
		t.Errorf("config file mode = %o, want 600", perm)
	}
	di, err := os.Stat(filepath.Dir(path))
	if err != nil {
		t.Fatal(err)
	}
	if perm := di.Mode().Perm(); perm != 0o700 {
		t.Errorf("config dir mode = %o, want 700", perm)
	}
}

func TestSaveWriteError(t *testing.T) {
	parent := t.TempDir()
	blocker := filepath.Join(parent, "dsh-anywhere")
	if err := os.WriteFile(blocker, nil, 0o600); err != nil { // 目录位置被普通文件占用
		t.Fatal(err)
	}
	store := NewStore(filepath.Join(blocker, FileName))

	got, err := store.Save("https://dsh.example.test/?token=abc123")
	if err == nil {
		t.Fatal("Save() error = nil, want write error")
	}
	if strings.Contains(err.Error(), "abc123") {
		t.Errorf("Save() error %q leaks token", err)
	}
	if got != "" {
		t.Errorf("Save() = %q, want empty on error", got)
	}
}

func TestDefaultStoreUsesAppConfigDir(t *testing.T) {
	base := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", base)
	t.Setenv("APPDATA", base)
	t.Setenv("HOME", base)

	store, err := DefaultStore()
	if err != nil {
		t.Fatalf("DefaultStore() error = %v", err)
	}
	if !strings.HasSuffix(store.path, filepath.Join("dsh-anywhere", FileName)) {
		t.Errorf("DefaultStore() path = %q, want suffix %q", store.path, filepath.Join("dsh-anywhere", FileName))
	}
	if rel, err := filepath.Rel(base, store.path); err != nil || !filepath.IsLocal(rel) {
		t.Errorf("DefaultStore() path = %q, want a path under %q", store.path, base)
	}
}

func TestDefaultStoreWithoutUserConfigDir(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", "")
	t.Setenv("APPDATA", "")
	t.Setenv("HOME", "")

	if _, err := DefaultStore(); err == nil {
		t.Fatal("DefaultStore() error = nil, want error when no user config dir")
	}
}
