package session

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

const (
	mainID = 1
	nonce  = "0123456789abcdef0123456789abcdef"
)

func TestHandleMessage(t *testing.T) {
	tests := []struct {
		name       string
		windowID   uint
		remote     bool
		retry      bool
		msg        string
		wantRetry  bool
		wantReauth bool
	}{
		{"expired session", mainID, true, false, ExpiredMessage(nonce), true, true},
		{"right after token navigation", mainID, true, true, ExpiredMessage(nonce), true, false},
		{"main window local", mainID, false, true, ExpiredMessage(nonce), false, false},
		{"other window", 2, true, true, ExpiredMessage(nonce), false, false},
		{"message with suffix", mainID, true, true, ExpiredMessage(nonce) + "x", false, false},
		{"wrong nonce from child frame", mainID, true, true, ExpiredMessage("guess"), false, false},
		{"no nonce", mainID, true, true, expiredPrefix, false, false},
		{"other message", mainID, true, true, "dsh-anywhere:open-external:https://example.org/", false, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var opened []string
			retried := false
			h := NewHandler(nonce, func() uint { return mainID },
				func(id uint) bool { return id == mainID && tt.remote },
				func() bool { retried = true; return tt.retry },
				func(url string) { opened = append(opened, url) })

			h.HandleMessage(tt.windowID, tt.msg)

			if retried != tt.wantRetry {
				t.Errorf("retry asked = %v, want %v", retried, tt.wantRetry)
			}
			if tt.wantReauth != (len(opened) == 1 && opened[0] == ReauthURL) || len(opened) > 1 {
				t.Errorf("opened %q, want reauth page opened = %v", opened, tt.wantReauth)
			}
		})
	}
}

func TestScriptBehavior(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not installed")
	}
	path := filepath.Join(t.TempDir(), "script.js")
	if err := os.WriteFile(path, []byte(Script(nonce)), 0o600); err != nil {
		t.Fatal(err)
	}
	out, err := exec.Command(node, filepath.Join("testdata", "harness.js"), path).Output()
	if err != nil {
		t.Fatalf("node harness failed: %v", err)
	}
	var results map[string][]string
	if err := json.Unmarshal(out, &results); err != nil {
		t.Fatalf("decode %q: %v", out, err)
	}
	want := map[string]int{
		"dsh 401 text":          1,
		"dsh 401 with newline":  1,
		"webkit handler only":   1,
		"html page with marker": 0,
		"other text":            0,
		"empty body":            0,
		"no body":               0,
	}
	for name, n := range want {
		msgs := results[name]
		if len(msgs) != n || (n == 1 && msgs[0] != ExpiredMessage(nonce)) {
			t.Errorf("%s: messages %q, want %d × %q", name, msgs, n, ExpiredMessage(nonce))
		}
	}
	if len(results) != len(want) {
		t.Errorf("harness ran %d scenarios, test checks %d", len(results), len(want))
	}
}

func TestNewNonceIsRandomHex(t *testing.T) {
	a, b := NewNonce(), NewNonce()
	if len(a) != 32 || a == b {
		t.Errorf("NewNonce() = %q, %q, want two distinct 32-char values", a, b)
	}
	if strings.Trim(a, "0123456789abcdef") != "" {
		t.Errorf("NewNonce() = %q, want hex", a)
	}
	if strings.Contains(Script(a), noncePlaceholder) || !strings.Contains(Script(a), a) {
		t.Error("Script() did not substitute the nonce")
	}
}
