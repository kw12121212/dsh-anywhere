package webviewdata

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestPersistCookies(t *testing.T) {
	path := filepath.Join(t.TempDir(), "dsh-anywhere", CookieFile)
	if err := PersistCookies(path); err != nil {
		t.Fatalf("PersistCookies() error = %v", err)
	}
}

func TestPrepareRestrictsPermissions(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix 权限位不适用于 Windows")
	}
	dir := filepath.Join(t.TempDir(), "dsh-anywhere")
	path := filepath.Join(dir, CookieFile)
	// 已存在的宽松权限也要收紧。
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, nil, 0o644); err != nil {
		t.Fatal(err)
	}

	if err := prepare(path); err != nil {
		t.Fatalf("prepare() error = %v", err)
	}
	for p, want := range map[string]os.FileMode{dir: 0o700, path: 0o600} {
		fi, err := os.Stat(p)
		if err != nil {
			t.Fatal(err)
		}
		if got := fi.Mode().Perm(); got != want {
			t.Errorf("%s mode = %o, want %o", p, got, want)
		}
	}
}

func TestPrepareCreatesMissingFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "a", "b", CookieFile)
	if err := prepare(path); err != nil {
		t.Fatalf("prepare() error = %v", err)
	}
	if _, err := os.Stat(path); err != nil {
		t.Errorf("cookie file not created: %v", err)
	}
}

func TestPrepareFailsWhenDirIsAFile(t *testing.T) {
	blocker := filepath.Join(t.TempDir(), "dsh-anywhere")
	if err := os.WriteFile(blocker, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := prepare(filepath.Join(blocker, CookieFile)); err == nil {
		t.Error("prepare() error = nil, want failure when the directory is a file")
	}
	if err := PersistCookies(filepath.Join(blocker, CookieFile)); err == nil && runtime.GOOS == "linux" {
		t.Error("PersistCookies() error = nil, want failure when the directory is a file")
	}
}
