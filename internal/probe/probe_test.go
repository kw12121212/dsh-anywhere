package probe

import (
	"crypto/tls"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"syscall"
	"testing"
	"time"
)

func TestCheckReachable(t *testing.T) {
	tests := map[string]http.HandlerFunc{
		"ok": func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) },
		"dsh 401": func(w http.ResponseWriter, _ *http.Request) {
			http.Error(w, "dsh web authentication required", http.StatusUnauthorized)
		},
		"server error": func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusBadGateway) },
		"redirect": func(w http.ResponseWriter, r *http.Request) {
			http.Redirect(w, r, "https://elsewhere.invalid/", http.StatusFound)
		},
	}
	for name, handler := range tests {
		t.Run(name, func(t *testing.T) {
			srv := httptest.NewServer(handler)
			defer srv.Close()
			if got := New(time.Second).Check(srv.URL + "/"); got != Reachable {
				t.Errorf("Check() = %q, want reachable", got)
			}
		})
	}
}

func TestCheckRefused(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := l.Addr().String()
	l.Close()

	if got := New(time.Second).Check("http://" + addr + "/"); got != Refused {
		t.Errorf("Check() = %q, want %q", got, Refused)
	}
}

func TestCheckTimeout(t *testing.T) {
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { <-release }))
	defer srv.Close()
	defer close(release)

	if got := New(100 * time.Millisecond).Check(srv.URL + "/"); got != Timeout {
		t.Errorf("Check() = %q, want %q", got, Timeout)
	}
}

func TestCheckTLSFailure(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {}))
	defer srv.Close()

	if got := New(time.Second).Check(srv.URL + "/"); got != TLS {
		t.Errorf("Check() = %q, want %q", got, TLS)
	}
}

func TestCheckPlainHTTPOverHTTPS(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {}))
	defer srv.Close()

	// 把只提供 http 的地址填成 https：TLS 握手收到明文 HTTP。
	if got := New(time.Second).Check("https://" + srv.Listener.Addr().String() + "/"); got != TLS {
		t.Errorf("Check() = %q, want %q", got, TLS)
	}
}

func TestClassifyTLSAndRefusedErrors(t *testing.T) {
	tests := map[string]struct {
		err  error
		want Reason
	}{
		"tls alert":         {&net.OpError{Op: "remote error", Err: tls.AlertError(40)}, TLS},
		"tls record header": {tls.RecordHeaderError{Msg: "first record does not look like a TLS handshake"}, TLS},
		"refused":           {&net.OpError{Op: "dial", Err: os.NewSyscallError("connect", syscall.ECONNREFUSED)}, Refused},
	}
	for name, tt := range tests {
		if got := classify(tt.err); got != tt.want {
			t.Errorf("%s: classify() = %q, want %q", name, got, tt.want)
		}
	}
}

func TestCheckDNSFailure(t *testing.T) {
	if got := New(5 * time.Second).Check("http://dsh-anywhere-probe.invalid/"); got != DNS {
		t.Errorf("Check() = %q, want %q", got, DNS)
	}
}

func TestCheckInvalidURL(t *testing.T) {
	if got := New(time.Second).Check("http://[::1"); got != Other {
		t.Errorf("Check() = %q, want %q", got, Other)
	}
}

func TestClassifyOther(t *testing.T) {
	if got := classify(errors.New("boom")); got != Other {
		t.Errorf("classify() = %q, want %q", got, Other)
	}
}
