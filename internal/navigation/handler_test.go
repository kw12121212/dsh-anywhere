package navigation

import (
	"bytes"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"dsh-anywhere/internal/config"
)

const mainID = 1

type opener struct {
	urls []string
	err  error
}

func (o *opener) open(url string) error {
	o.urls = append(o.urls, url)
	return o.err
}

func newHandler(t *testing.T, savedURL string) (*Handler, *opener, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "dsh-anywhere", config.FileName)
	store := config.NewStore(path)
	if savedURL != "" {
		if _, err := store.Save(savedURL); err != nil {
			t.Fatal(err)
		}
	}
	o := &opener{}
	return NewHandler(func() uint { return mainID }, store, o.open), o, path
}

func TestHandleMessageOpensExternalURL(t *testing.T) {
	h, o, _ := newHandler(t, "https://dsh.example.test/")

	h.HandleMessage(mainID, OpenExternalPrefix+"https://example.org/docs")
	h.HandleMessage(mainID, OpenExternalPrefix+"https://dsh.example.test:8443/")
	h.HandleMessage(mainID, OpenExternalPrefix+"http://dsh.example.test/")

	want := []string{"https://example.org/docs", "https://dsh.example.test:8443/", "http://dsh.example.test/"}
	if strings.Join(o.urls, " ") != strings.Join(want, " ") {
		t.Errorf("opened %q, want %q", o.urls, want)
	}
}

func TestHandleMessageIgnores(t *testing.T) {
	tests := []struct {
		name     string
		windowID uint
		msg      string
	}{
		{"same origin", mainID, OpenExternalPrefix + "https://dsh.example.test/sessions/1"},
		{"file url", mainID, OpenExternalPrefix + "file:///etc/passwd"},
		{"javascript url", mainID, OpenExternalPrefix + "javascript:alert(1)"},
		{"relative url", mainID, OpenExternalPrefix + "/sessions/1"},
		{"unparseable", mainID, OpenExternalPrefix + "https://example.org/%zz"},
		{"empty target", mainID, OpenExternalPrefix},
		{"other window", 2, OpenExternalPrefix + "https://example.org/docs"},
		{"other prefix", mainID, "something-else:https://example.org/docs"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			h, o, _ := newHandler(t, "https://dsh.example.test/")
			h.HandleMessage(tt.windowID, tt.msg)
			if len(o.urls) != 0 {
				t.Errorf("opened %q, want nothing", o.urls)
			}
		})
	}
}

func TestHandleMessageWithoutUsableConfigTreatsTargetAsExternal(t *testing.T) {
	h, o, path := newHandler(t, "https://dsh.example.test/")
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}

	h.HandleMessage(mainID, OpenExternalPrefix+"https://dsh.example.test/a")

	if len(o.urls) != 1 || o.urls[0] != "https://dsh.example.test/a" {
		t.Errorf("opened %q, want the target once", o.urls)
	}
}

func TestHandleMessageOpenFailureIsLoggedWithoutURL(t *testing.T) {
	var buf bytes.Buffer
	orig := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(&buf, nil)))
	t.Cleanup(func() { slog.SetDefault(orig) })
	h, o, _ := newHandler(t, "https://dsh.example.test/")
	o.err = errors.New("no browser")

	h.HandleMessage(mainID, OpenExternalPrefix+"https://example.org/?token=abc123")

	if !strings.Contains(buf.String(), "open external url failed") {
		t.Errorf("log %q does not record the failure", buf.String())
	}
	if strings.Contains(buf.String(), "abc123") || strings.Contains(buf.String(), "example.org") {
		t.Errorf("log %q contains the url", buf.String())
	}
}
