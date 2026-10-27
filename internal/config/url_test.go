package config

import (
	"errors"
	"strings"
	"testing"
)

func TestNormalizeAcceptsHTTPAndHTTPS(t *testing.T) {
	tests := []struct {
		name, raw, want string
	}{
		{"https with port and surrounding spaces", "  https://dsh.example.test:13080/  ", "https://dsh.example.test:13080/"},
		{"http loopback", "http://127.0.0.1:13080/", "http://127.0.0.1:13080/"},
		{"no trailing slash", "https://dsh.example.test", "https://dsh.example.test"},
		{"uppercase scheme", "HTTPS://dsh.example.test/", "https://dsh.example.test/"},
		{"tabs and newlines", "\thttps://dsh.example.test/\n", "https://dsh.example.test/"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := Normalize(tt.raw)
			if err != nil {
				t.Fatalf("Normalize(%q) error = %v", tt.raw, err)
			}
			if got != tt.want {
				t.Errorf("Normalize(%q) = %q, want %q", tt.raw, got, tt.want)
			}
		})
	}
}

func TestNormalizeRejectsInvalidInput(t *testing.T) {
	tests := []struct {
		name, raw string
		want      error
	}{
		{"empty", "", ErrEmpty},
		{"blank", "   \t ", ErrEmpty},
		{"relative path", "/chat", ErrNotAbsolute},
		{"bare host", "dsh.example.test", ErrNotAbsolute},
		{"scheme without host", "https://", ErrNotAbsolute},
		{"opaque http", "http:dsh.example.test", ErrNotAbsolute},
		{"ftp", "ftp://dsh.example.test/", ErrUnsupportedScheme},
		{"file", "file:///tmp/x", ErrUnsupportedScheme},
		{"javascript", "javascript:alert(1)", ErrUnsupportedScheme},
		{"bad escape", "http://dsh.example.test/%zz", ErrMalformed},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := Normalize(tt.raw)
			if !errors.Is(err, tt.want) {
				t.Fatalf("Normalize(%q) error = %v, want %v", tt.raw, err, tt.want)
			}
			if got != "" {
				t.Errorf("Normalize(%q) = %q, want empty on error", tt.raw, got)
			}
		})
	}
}

func TestNormalizeStripsToken(t *testing.T) {
	tests := []struct {
		name, raw, want string
	}{
		{"single token", "http://127.0.0.1:13080/?token=abc123", "http://127.0.0.1:13080/"},
		{"token with other params, path prefix and fragment", "https://dsh.example.test/base/?lang=zh&token=abc123&token=def456#top", "https://dsh.example.test/base/?lang=zh#top"},
		{"token first keeps order of the rest", "https://dsh.example.test/?token=abc123&b=2&a=1", "https://dsh.example.test/?b=2&a=1"},
		{"escaped token key", "https://dsh.example.test/?%74oken=abc123&x=1", "https://dsh.example.test/?x=1"},
		{"empty token value", "https://dsh.example.test/?token=", "https://dsh.example.test/"},
		{"no token unchanged", "https://dsh.example.test/base/?lang=zh", "https://dsh.example.test/base/?lang=zh"},
		{"empty segments dropped", "https://dsh.example.test/?a=1&&token=x&", "https://dsh.example.test/?a=1"},
		{"uppercase key is not the token parameter", "https://dsh.example.test/?TOKEN=1", "https://dsh.example.test/?TOKEN=1"},
		{"similar names kept", "https://dsh.example.test/?tokens=1&xtoken=2", "https://dsh.example.test/?tokens=1&xtoken=2"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := Normalize(tt.raw)
			if err != nil {
				t.Fatalf("Normalize(%q) error = %v", tt.raw, err)
			}
			if got != tt.want {
				t.Errorf("Normalize(%q) = %q, want %q", tt.raw, got, tt.want)
			}
		})
	}
}

func TestNormalizeErrorNeverContainsToken(t *testing.T) {
	inputs := []string{
		"http://dsh.example.test/%zz?token=abc123",
		"ftp://dsh.example.test/?token=abc123",
		"/chat?token=abc123",
	}
	for _, raw := range inputs {
		_, err := Normalize(raw)
		if err == nil {
			t.Fatalf("Normalize(%q) error = nil, want rejection", raw)
		}
		if strings.Contains(err.Error(), "abc123") {
			t.Errorf("Normalize(%q) error %q leaks token", raw, err)
		}
	}
}

func TestRedact(t *testing.T) {
	tests := []struct {
		name, raw, want string
	}{
		{"strips token", "https://dsh.example.test/?token=abc123", "https://dsh.example.test/"},
		{"keeps other params", "https://dsh.example.test/?a=1&token=abc123", "https://dsh.example.test/?a=1"},
		{"hides password", "https://user:secret@dsh.example.test/", "https://user:xxxxx@dsh.example.test/"},
		{"unparseable", "http://dsh.example.test/%zz?token=abc123", "<无法解析的地址>"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := Redact(tt.raw); got != tt.want {
				t.Errorf("Redact(%q) = %q, want %q", tt.raw, got, tt.want)
			}
		})
	}
}
