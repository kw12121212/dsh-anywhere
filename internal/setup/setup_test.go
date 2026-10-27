package setup

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"dsh-anywhere/internal/config"
)

func newStore(t *testing.T) (*config.Store, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "dsh-anywhere", config.FileName)
	return config.NewStore(path), path
}

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestDecide(t *testing.T) {
	tests := []struct {
		name  string
		setup func(t *testing.T, path string)
		want  State
	}{
		{"missing config", func(*testing.T, string) {}, State{NeedsSetup: true}},
		{"corrupt config", func(t *testing.T, p string) { writeFile(t, p, "{not json") }, State{NeedsSetup: true, InvalidConfig: true}},
		{"invalid url", func(t *testing.T, p string) { writeFile(t, p, `{"harnessURL": "ftp://dsh.example.test/"}`) }, State{NeedsSetup: true, InvalidConfig: true}},
		{"unreadable config", func(t *testing.T, p string) {
			if err := os.MkdirAll(p, 0o700); err != nil { // 目录无法当作文件读取
				t.Fatal(err)
			}
		}, State{NeedsSetup: true, InvalidConfig: true}},
		{"valid config", func(t *testing.T, p string) { writeFile(t, p, `{"harnessURL": "https://dsh.example.test/"}`) }, State{}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			store, path := newStore(t)
			tt.setup(t, path)

			if got := Decide(store); got != tt.want {
				t.Errorf("Decide() = %+v, want %+v", got, tt.want)
			}
		})
	}
}

// recorder 记录 navigate 回调收到的地址。
type recorder struct{ urls []string }

func (r *recorder) navigate(url string) { r.urls = append(r.urls, url) }

func TestCompleteSavesCleanURLAndNavigatesRawOnce(t *testing.T) {
	store, path := newStore(t)
	rec := &recorder{}
	svc := NewService(store, rec.navigate)

	clean, err := svc.Complete("  http://127.0.0.1:13080/?token=abc123  ")
	if err != nil {
		t.Fatalf("Complete() error = %v", err)
	}
	if clean != "http://127.0.0.1:13080/" {
		t.Errorf("Complete() = %q, want %q", clean, "http://127.0.0.1:13080/")
	}
	if want := []string{"http://127.0.0.1:13080/?token=abc123"}; len(rec.urls) != 1 || rec.urls[0] != want[0] {
		t.Errorf("navigate calls = %q, want %q", rec.urls, want)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "abc123") {
		t.Errorf("config file %q contains token", data)
	}
	if got := svc.State(); got != (State{}) {
		t.Errorf("State() after Complete = %+v, want configured", got)
	}
}

func TestCompleteWithoutTokenNavigatesSameURL(t *testing.T) {
	store, _ := newStore(t)
	rec := &recorder{}

	clean, err := NewService(store, rec.navigate).Complete("https://dsh.example.test/")
	if err != nil {
		t.Fatalf("Complete() error = %v", err)
	}
	if clean != "https://dsh.example.test/" || len(rec.urls) != 1 || rec.urls[0] != "https://dsh.example.test/" {
		t.Errorf("Complete() = %q, navigate calls = %q, want both https://dsh.example.test/", clean, rec.urls)
	}
}

func TestCompleteRejectsInvalidWithoutSavingOrNavigating(t *testing.T) {
	for _, raw := range []string{"", "/chat", "ftp://dsh.example.test/", "ftp://dsh.example.test/?token=abc123"} {
		t.Run(raw, func(t *testing.T) {
			store, path := newStore(t)
			rec := &recorder{}
			svc := NewService(store, rec.navigate)

			_, err := svc.Complete(raw)
			if err == nil {
				t.Fatal("Complete() error = nil, want rejection")
			}
			if strings.Contains(err.Error(), "abc123") {
				t.Errorf("Complete() error %q leaks token", err)
			}
			if len(rec.urls) != 0 {
				t.Errorf("navigate calls = %q, want none", rec.urls)
			}
			if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
				t.Errorf("config file exists after rejected Complete: %v", err)
			}
			if got := svc.State(); got != (State{NeedsSetup: true}) {
				t.Errorf("State() = %+v, want still needing setup", got)
			}
		})
	}
}

func TestCompleteWriteFailureDoesNotNavigateOrLeakToken(t *testing.T) {
	parent := t.TempDir()
	blocker := filepath.Join(parent, "dsh-anywhere")
	if err := os.WriteFile(blocker, nil, 0o600); err != nil { // 配置目录位置被普通文件占用
		t.Fatal(err)
	}
	rec := &recorder{}
	svc := NewService(config.NewStore(filepath.Join(blocker, config.FileName)), rec.navigate)

	_, err := svc.Complete("http://127.0.0.1:13080/?token=abc123")
	if err == nil {
		t.Fatal("Complete() error = nil, want write error")
	}
	if strings.Contains(err.Error(), "abc123") {
		t.Errorf("Complete() error %q leaks token", err)
	}
	if len(rec.urls) != 0 {
		t.Errorf("navigate calls = %q, want none", rec.urls)
	}
}
