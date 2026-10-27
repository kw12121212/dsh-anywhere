package guard

import (
	"bytes"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

const callBody = `{"object":0,"method":0,"args":{"call-id":"1","methodID":3765451224,"args":["http://evil.example.test/"]}}`

// newLocalCall 构造与本地 @wailsio/runtime 相同形态的绑定调用请求。
func newLocalCall(windowID string) *http.Request {
	r := httptest.NewRequest(http.MethodPost, "wails://localhost/wails/runtime", strings.NewReader(callBody))
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set(clientIDHeader, "client-1")
	if windowID != "" {
		r.Header.Set(windowIDHeader, windowID)
	}
	return r
}

// serve 让请求经过 guard，返回状态码以及下游是否被调用。
func serve(t *testing.T, g *Guard, r *http.Request) (int, bool) {
	t.Helper()
	called := false
	next := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		called = true
		w.WriteHeader(http.StatusOK)
	})
	rec := httptest.NewRecorder()
	g.Middleware(next).ServeHTTP(rec, r)
	return rec.Code, called
}

func TestAllowsLocalRuntimeCall(t *testing.T) {
	g := New()
	g.SetRemote(2, true) // 其他窗口远程不影响本窗口

	for _, windowID := range []string{"1", ""} {
		code, called := serve(t, g, newLocalCall(windowID))
		if code != http.StatusOK || !called {
			t.Errorf("window %q: code = %d, called = %v, want 200 and passed through", windowID, code, called)
		}
	}
}

func TestAllowsChunkedLocalRuntimeCall(t *testing.T) {
	r := newLocalCall("1")
	r.Header.Set("x-wails-chunk-id", "c1")
	r.Header.Set("Content-Type", "application/json; charset=utf-8")

	if code, called := serve(t, New(), r); code != http.StatusOK || !called {
		t.Errorf("code = %d, called = %v, want 200 and passed through", code, called)
	}
}

func TestAllowsNonWailsAssets(t *testing.T) {
	g := New()
	g.SetRemote(1, true)
	for _, path := range []string{"/", "/index.html", "/assets/index.js", "/wailsfoo"} {
		r := httptest.NewRequest(http.MethodGet, "wails://localhost"+path, nil)
		r.Header.Set(windowIDHeader, "1")
		if code, called := serve(t, g, r); code != http.StatusOK || !called {
			t.Errorf("GET %s: code = %d, called = %v, want 200 and passed through", path, code, called)
		}
	}
}

func TestBlocksEveryWailsRequestFromRemoteWindow(t *testing.T) {
	g := New()
	g.SetRemote(1, true)

	requests := map[string]*http.Request{
		"runtime call":    newLocalCall("1"),
		"runtime script":  httptest.NewRequest(http.MethodGet, "wails://localhost/wails/runtime.js", nil),
		"event payload":   httptest.NewRequest(http.MethodGet, "wails://localhost/wails/eventpayload/0123456789abcdef0123456789abcdef", nil),
		"stream endpoint": httptest.NewRequest(http.MethodGet, "wails://localhost/wails/stream", nil),
	}
	for name, r := range requests {
		r.Header.Set(windowIDHeader, "1")
		if code, called := serve(t, g, r); code != http.StatusForbidden || called {
			t.Errorf("%s: code = %d, called = %v, want 403 and blocked", name, code, called)
		}
	}
}

func TestBlocksMalformedRuntimeCalls(t *testing.T) {
	tests := map[string]func() *http.Request{
		"GET with query": func() *http.Request {
			return httptest.NewRequest(http.MethodGet, "wails://localhost/wails/runtime?object=0&method=0&args=%7B%7D", nil)
		},
		"OPTIONS preflight": func() *http.Request {
			r := httptest.NewRequest(http.MethodOptions, "wails://localhost/wails/runtime", nil)
			r.Header.Set("Access-Control-Request-Method", "POST")
			return r
		},
		"no-cors text/plain POST": func() *http.Request {
			r := httptest.NewRequest(http.MethodPost, "wails://localhost/wails/runtime", strings.NewReader(callBody))
			r.Header.Set("Content-Type", "text/plain;charset=UTF-8")
			return r
		},
		"JSON without client id": func() *http.Request {
			r := newLocalCall("1")
			r.Header.Del(clientIDHeader)
			return r
		},
		"form POST": func() *http.Request {
			r := newLocalCall("1")
			r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
			return r
		},
	}
	for name, build := range tests {
		if code, called := serve(t, New(), build()); code != http.StatusForbidden || called {
			t.Errorf("%s: code = %d, called = %v, want 403 and blocked", name, code, called)
		}
	}
}

func TestSetRemoteFalseRestoresAccess(t *testing.T) {
	g := New()
	g.SetRemote(1, true)
	if !g.IsRemote(1) {
		t.Fatal("IsRemote(1) = false after SetRemote(1, true)")
	}
	g.SetRemote(1, false)
	if g.IsRemote(1) {
		t.Fatal("IsRemote(1) = true after SetRemote(1, false)")
	}
	if code, called := serve(t, g, newLocalCall("1")); code != http.StatusOK || !called {
		t.Errorf("code = %d, called = %v, want 200 after leaving remote mode", code, called)
	}
}

func TestZeroValueGuardIsUsable(t *testing.T) {
	var g Guard
	if g.IsRemote(1) {
		t.Error("zero Guard reports remote window")
	}
	g.SetRemote(1, false) // 对空集合取消标记不应出错
	g.SetRemote(1, true)
	if !g.IsRemote(1) {
		t.Error("IsRemote(1) = false after SetRemote on zero Guard")
	}
}

func TestBlockedRequestLogOmitsQuery(t *testing.T) {
	var buf bytes.Buffer
	orig := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(&buf, nil)))
	t.Cleanup(func() { slog.SetDefault(orig) })

	r := httptest.NewRequest(http.MethodGet, "wails://localhost/wails/runtime?args=secret-abc123", nil)
	if code, _ := serve(t, New(), r); code != http.StatusForbidden {
		t.Fatalf("code = %d, want 403", code)
	}
	if !strings.Contains(buf.String(), "blocked wails request") {
		t.Errorf("log %q does not record the blocked request", buf.String())
	}
	if strings.Contains(buf.String(), "abc123") {
		t.Errorf("log %q contains query arguments", buf.String())
	}
}

func TestOriginCheck(t *testing.T) {
	tests := []struct {
		origin string
		want   int
	}{
		{"", http.StatusOK},
		{"wails://localhost", http.StatusOK},
		{"http://wails.localhost", http.StatusOK},
		{"http://127.0.0.1:18200", http.StatusForbidden},
		{"https://dsh.example.test", http.StatusForbidden},
		{"http://localhost", http.StatusForbidden},
		{"null", http.StatusForbidden},
	}
	for _, tt := range tests {
		t.Run(tt.origin, func(t *testing.T) {
			r := newLocalCall("1") // 本窗口未被标记为远程，只靠 Origin 判定
			if tt.origin != "" {
				r.Header.Set("Origin", tt.origin)
			}
			code, called := serve(t, New(), r)
			if code != tt.want || called != (tt.want == http.StatusOK) {
				t.Errorf("Origin %q: code = %d, called = %v, want %d", tt.origin, code, called, tt.want)
			}
		})
	}
}

func TestOriginCheckCoversOtherWailsPaths(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "wails://localhost/wails/runtime.js", nil)
	r.Header.Set("Origin", "http://127.0.0.1:18200")
	if code, called := serve(t, New(), r); code != http.StatusForbidden || called {
		t.Errorf("code = %d, called = %v, want 403 and blocked", code, called)
	}
}
