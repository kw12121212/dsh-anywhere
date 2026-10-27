package windowstate

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func tempStore(t *testing.T) (*Store, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "nested", FileName)
	return NewStore(path), path
}

func writeRaw(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestStoreSaveLoadRoundTrip(t *testing.T) {
	store, path := tempStore(t)
	want := State{X: -120, Y: 40, Width: 1150, Height: 700, Maximised: true}

	if err := store.Save(want); err != nil {
		t.Fatalf("Save() error = %v", err)
	}
	got, err := store.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if got != want {
		t.Fatalf("Load() = %+v, want %+v", got, want)
	}

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]any
	if err := json.Unmarshal(data, &fields); err != nil {
		t.Fatalf("state file is not JSON: %v", err)
	}
	for key, wantValue := range map[string]float64{"x": -120, "y": 40, "width": 1150, "height": 700} {
		if fields[key] != wantValue {
			t.Errorf("%s = %v, want %v", key, fields[key], wantValue)
		}
	}
	if fields["maximised"] != true {
		t.Errorf("maximised = %v, want true", fields["maximised"])
	}
}

func TestStoreLoadWithoutFileReportsNoState(t *testing.T) {
	store, _ := tempStore(t)
	if _, err := store.Load(); !errors.Is(err, ErrNoState) {
		t.Fatalf("Load() error = %v, want %v", err, ErrNoState)
	}
}

func TestStoreLoadRejectsUnusableFiles(t *testing.T) {
	cases := map[string]string{
		"corrupt json":     "{not json",
		"wrong shape":      `["x"]`,
		"zero width":       `{"x": 1, "y": 2, "width": 0, "height": 700}`,
		"negative height":  `{"x": 1, "y": 2, "width": 1000, "height": -1}`,
		"missing geometry": `{"x": 1, "y": 2}`,
		"oversized width":  `{"x": 1, "y": 2, "width": 40000, "height": 700}`,
	}
	for name, content := range cases {
		t.Run(name, func(t *testing.T) {
			store, path := tempStore(t)
			writeRaw(t, path, content)
			if _, err := store.Load(); !errors.Is(err, ErrNoState) {
				t.Fatalf("Load() error = %v, want %v", err, ErrNoState)
			}
		})
	}
}

func TestStoreLoadUnreadableFileReportsNoState(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 可以读取任意权限的文件")
	}
	store, path := tempStore(t)
	writeRaw(t, path, `{"x": 1, "y": 2, "width": 1000, "height": 700}`)
	if err := os.Chmod(path, 0o000); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Load(); !errors.Is(err, ErrNoState) {
		t.Fatalf("Load() error = %v, want %v", err, ErrNoState)
	}
}

func TestStoreSaveRejectsInvalidGeometry(t *testing.T) {
	store, path := tempStore(t)
	if err := store.Save(State{Width: 0, Height: 700}); err == nil {
		t.Fatal("Save() error = nil, want error")
	}
	if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("state file exists after rejected save: %v", err)
	}
}

func TestStoreSaveOverwritesPreviousState(t *testing.T) {
	store, path := tempStore(t)
	if err := store.Save(State{Width: 1200, Height: 800}); err != nil {
		t.Fatal(err)
	}
	if err := store.Save(State{Width: 900, Height: 600}); err != nil {
		t.Fatal(err)
	}

	got, err := store.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if got.Width != 900 || got.Height != 600 {
		t.Fatalf("Load() = %+v, want 900x600", got)
	}

	entries, err := os.ReadDir(filepath.Dir(path))
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != FileName {
		t.Fatalf("config dir entries = %v, want only %s", entries, FileName)
	}
}

func TestStoreSaveInterruptedKeepsPreviousState(t *testing.T) {
	store, path := tempStore(t)
	if err := store.Save(State{Width: 1200, Height: 800}); err != nil {
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

	if err := store.Save(State{Width: 900, Height: 600}); !errors.Is(err, interrupted) {
		t.Fatalf("Save() error = %v, want wrapping %v", err, interrupted)
	}

	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != string(before) {
		t.Fatalf("state file changed after failed save: %s", after)
	}
	entries, err := os.ReadDir(filepath.Dir(path))
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != FileName {
		t.Fatalf("config dir entries = %v, want only %s", entries, FileName)
	}
}

func TestDefaultStoreUsesConfigDir(t *testing.T) {
	home := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", "")
	t.Setenv("HOME", home)

	store, err := DefaultStore()
	if err != nil {
		t.Fatalf("DefaultStore() error = %v", err)
	}
	want := filepath.Join(home, ".config", "dsh-anywhere", FileName)
	if store.path != want {
		t.Fatalf("DefaultStore() path = %q, want %q", store.path, want)
	}
}
