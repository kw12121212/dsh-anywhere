package settings

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"dsh-anywhere/internal/config"

	"github.com/wailsapp/wails/v3/pkg/application"
)

type recorder struct {
	opened    []string
	windows   int
	closed    int
	mainLocal bool // 主窗口正显示本地页面
}

func newService(t *testing.T, saved string) (*Service, *recorder, *config.Store) {
	t.Helper()
	store := config.NewStore(filepath.Join(t.TempDir(), "dsh-anywhere", config.FileName))
	if saved != "" {
		if _, err := store.Save(saved); err != nil {
			t.Fatal(err)
		}
	}
	rec := &recorder{}
	svc := NewService(store, func() bool { return !rec.mainLocal },
		func(url string) { rec.opened = append(rec.opened, url) },
		func() { rec.windows++ },
		func() { rec.closed++ })
	return svc, rec, store
}

func assertSaved(t *testing.T, store *config.Store, want string) {
	t.Helper()
	if got, err := store.Load(); err != nil || got != want {
		t.Errorf("saved = %q, %v, want %q", got, err, want)
	}
}

func TestSave(t *testing.T) {
	tests := []struct {
		name, saved, raw string
		wantClean        string
		wantOpened       []string
	}{
		{"new token url", "https://a.example.test/", "http://127.0.0.1:13080/?token=abc123",
			"http://127.0.0.1:13080/", []string{"http://127.0.0.1:13080/?token=abc123"}},
		{"unchanged", "https://dsh.example.test/", "https://dsh.example.test/",
			"https://dsh.example.test/", nil},
		{"unchanged with spaces", "https://dsh.example.test/", "  https://dsh.example.test/  ",
			"https://dsh.example.test/", nil},
		{"same url new token", "http://127.0.0.1:13080/", "http://127.0.0.1:13080/?token=def456",
			"http://127.0.0.1:13080/", []string{"http://127.0.0.1:13080/?token=def456"}},
		{"changed url", "https://a.example.test/", " https://b.example.test/ ",
			"https://b.example.test/", []string{"https://b.example.test/"}},
		{"nothing saved before", "", "https://dsh.example.test/",
			"https://dsh.example.test/", []string{"https://dsh.example.test/"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			svc, rec, store := newService(t, tt.saved)

			clean, err := svc.Save(tt.raw)
			if err != nil {
				t.Fatalf("Save() error = %v", err)
			}
			if clean != tt.wantClean {
				t.Errorf("Save() = %q, want %q", clean, tt.wantClean)
			}
			if strings.Join(rec.opened, " ") != strings.Join(tt.wantOpened, " ") {
				t.Errorf("opened %q, want %q", rec.opened, tt.wantOpened)
			}
			if rec.closed != 1 {
				t.Errorf("closeWindow called %d times, want 1", rec.closed)
			}
			assertSaved(t, store, tt.wantClean)
		})
	}
}

func TestSaveUnchangedFromLocalMainWindowNavigates(t *testing.T) {
	svc, rec, _ := newService(t, "https://dsh.example.test/")
	rec.mainLocal = true // 例如主窗口正显示重新认证提示页

	if _, err := svc.Save("https://dsh.example.test/"); err != nil {
		t.Fatal(err)
	}
	if len(rec.opened) != 1 || rec.opened[0] != "https://dsh.example.test/" || rec.closed != 1 {
		t.Errorf("opened %q, closed %d, want main window back on the harness", rec.opened, rec.closed)
	}
}

func TestSaveAfterInvalidConfigNavigates(t *testing.T) {
	path := filepath.Join(t.TempDir(), "dsh-anywhere", config.FileName)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	rec := &recorder{}
	svc := NewService(config.NewStore(path), func() bool { return true }, func(url string) { rec.opened = append(rec.opened, url) }, func() { rec.windows++ }, func() { rec.closed++ })

	if _, err := svc.Save("https://dsh.example.test/"); err != nil {
		t.Fatal(err)
	}
	if len(rec.opened) != 1 || rec.closed != 1 {
		t.Errorf("opened %q, closed %d, want one navigation and one close", rec.opened, rec.closed)
	}
}

func TestSaveRejectsInvalidWithoutClosingOrNavigating(t *testing.T) {
	for _, raw := range []string{"", "/chat", "ftp://dsh.example.test/?token=abc123"} {
		t.Run(raw, func(t *testing.T) {
			svc, rec, store := newService(t, "https://a.example.test/")

			_, err := svc.Save(raw)
			if err == nil {
				t.Fatal("Save() error = nil, want rejection")
			}
			if strings.Contains(err.Error(), "abc123") {
				t.Errorf("error %q leaks token", err)
			}
			if len(rec.opened) != 0 || rec.closed != 0 {
				t.Errorf("opened %q, closed %d, want neither", rec.opened, rec.closed)
			}
			assertSaved(t, store, "https://a.example.test/")
		})
	}
}

func TestSaveWriteFailure(t *testing.T) {
	parent := t.TempDir()
	blocker := filepath.Join(parent, "dsh-anywhere")
	if err := os.WriteFile(blocker, nil, 0o600); err != nil { // 配置目录位置被普通文件占用
		t.Fatal(err)
	}
	rec := &recorder{}
	svc := NewService(config.NewStore(filepath.Join(blocker, config.FileName)), func() bool { return true },
		func(url string) { rec.opened = append(rec.opened, url) }, func() { rec.windows++ }, func() { rec.closed++ })

	_, err := svc.Save("http://127.0.0.1:13080/?token=abc123")
	if err == nil || strings.Contains(err.Error(), "abc123") {
		t.Fatalf("Save() error = %v, want a write error without the token", err)
	}
	if len(rec.opened) != 0 || rec.closed != 0 {
		t.Errorf("opened %q, closed %d, want neither", rec.opened, rec.closed)
	}
}

func TestOpenServiceOpensWindowOnly(t *testing.T) {
	svc, rec, store := newService(t, "https://a.example.test/")

	svc.Open()

	if rec.windows != 1 || rec.closed != 0 || len(rec.opened) != 0 {
		t.Errorf("windows %d, closed %d, opened %q, want one window opened only", rec.windows, rec.closed, rec.opened)
	}
	assertSaved(t, store, "https://a.example.test/")
}

func TestCancelOnlyClosesWindow(t *testing.T) {
	svc, rec, store := newService(t, "https://a.example.test/")

	svc.Cancel()

	if rec.closed != 1 || len(rec.opened) != 0 {
		t.Errorf("closed %d, opened %q, want one close and no navigation", rec.closed, rec.opened)
	}
	assertSaved(t, store, "https://a.example.test/")
}

// fakeWindow 记录设置窗口上的操作。
type fakeWindow struct{ calls []string }

func (f *fakeWindow) Show() application.Window { f.calls = append(f.calls, "show"); return nil }
func (f *fakeWindow) Focus()                   { f.calls = append(f.calls, "focus") }
func (f *fakeWindow) Close()                   { f.calls = append(f.calls, "close") }

func lookupOf(w *fakeWindow) Lookup {
	return func() (Window, bool) {
		if w == nil {
			return nil, false
		}
		return w, true
	}
}

func TestOpenFocusesExistingWindow(t *testing.T) {
	w := &fakeWindow{}
	created := 0

	Open(lookupOf(w), func() { created++ })

	if created != 0 {
		t.Errorf("Open() created %d windows while one exists", created)
	}
	if strings.Join(w.calls, ",") != "show,focus" {
		t.Errorf("window calls = %q, want show,focus", w.calls)
	}
}

func TestOpenCreatesMissingWindow(t *testing.T) {
	created := 0
	Open(lookupOf(nil), func() { created++ })
	if created != 1 {
		t.Errorf("Open() created %d windows, want 1 when none exists", created)
	}
}

func TestClose(t *testing.T) {
	w := &fakeWindow{}
	Close(lookupOf(w))
	if strings.Join(w.calls, ",") != "close" {
		t.Errorf("window calls = %q, want close", w.calls)
	}
	Close(lookupOf(nil)) // 窗口不存在时不应出错
}

// appWindow 用 fakeWindow 实现 application.Window 中设置窗口用到的方法，其余方法不会被调用。
type appWindow struct {
	application.Window
	fake *fakeWindow
}

func (a appWindow) Show() application.Window { return a.fake.Show() }
func (a appWindow) Focus()                   { a.fake.Focus() }
func (a appWindow) Close()                   { a.fake.Close() }

func TestByName(t *testing.T) {
	fake := &fakeWindow{}
	var asked []string
	lookup := ByName(func(name string) (application.Window, bool) {
		asked = append(asked, name)
		return appWindow{fake: fake}, true
	})
	Close(lookup)
	if strings.Join(asked, ",") != WindowName || strings.Join(fake.calls, ",") != "close" {
		t.Errorf("asked %q, calls %q, want lookup of %q and close", asked, fake.calls, WindowName)
	}

	missing := ByName(func(string) (application.Window, bool) { return nil, false })
	if w, ok := missing(); ok || w != nil {
		t.Errorf("missing lookup = %v, %v, want nil, false", w, ok)
	}
}
