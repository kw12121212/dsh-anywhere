package navigation

import "testing"

func TestSameOrigin(t *testing.T) {
	const base = "https://dsh.example.test/"
	tests := []struct {
		name, target string
		want         bool
	}{
		{"same root", "https://dsh.example.test/", true},
		{"sub path and query", "https://dsh.example.test/sessions/1?x=1#top", true},
		{"explicit default port", "https://dsh.example.test:443/a", true},
		{"uppercase host and scheme", "HTTPS://DSH.Example.Test/a", true},
		{"different port", "https://dsh.example.test:8443/", false},
		{"different scheme", "http://dsh.example.test/", false},
		{"different host", "https://example.org/docs", false},
		{"subdomain", "https://a.dsh.example.test/", false},
		{"relative", "/sessions/1", false},
		{"unparseable", "https://dsh.example.test/%zz", false},
		{"file", "file:///etc/passwd", false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := SameOrigin(base, tt.target); got != tt.want {
				t.Errorf("SameOrigin(%q, %q) = %v, want %v", base, tt.target, got, tt.want)
			}
		})
	}
}

func TestSameOriginHTTPDefaultPortAndInvalidBase(t *testing.T) {
	if !SameOrigin("http://127.0.0.1:80/", "http://127.0.0.1/x") {
		t.Error("http default port not normalized")
	}
	if !SameOrigin("http://127.0.0.1:13080/", "http://127.0.0.1:13080/chat") {
		t.Error("same explicit port reported as different origin")
	}
	if SameOrigin("", "https://dsh.example.test/") || SameOrigin("/chat", "/chat") {
		t.Error("invalid base reported as same origin")
	}
}
