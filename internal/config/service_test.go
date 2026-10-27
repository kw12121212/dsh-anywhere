package config

import (
	"errors"
	"testing"
)

func TestServiceCurrentURL(t *testing.T) {
	store, path := newTestStore(t)
	svc := NewService(store)

	if got := svc.CurrentURL(); got != "" {
		t.Errorf("CurrentURL() without config = %q, want empty", got)
	}
	writeConfigFile(t, path, "{not json")
	if got := svc.CurrentURL(); got != "" {
		t.Errorf("CurrentURL() with corrupt config = %q, want empty", got)
	}
	if _, err := store.Save("https://dsh.example.test/"); err != nil {
		t.Fatal(err)
	}
	if got := svc.CurrentURL(); got != "https://dsh.example.test/" {
		t.Errorf("CurrentURL() = %q, want %q", got, "https://dsh.example.test/")
	}
}

func TestServiceValidateURLDoesNotSave(t *testing.T) {
	store, _ := newTestStore(t)
	svc := NewService(store)

	got, err := svc.ValidateURL("http://127.0.0.1:13080/?token=abc123")
	if err != nil {
		t.Fatalf("ValidateURL() error = %v", err)
	}
	if got != "http://127.0.0.1:13080/" {
		t.Errorf("ValidateURL() = %q, want %q", got, "http://127.0.0.1:13080/")
	}
	if _, err := svc.ValidateURL("ftp://dsh.example.test/"); !errors.Is(err, ErrUnsupportedScheme) {
		t.Errorf("ValidateURL(ftp) error = %v, want ErrUnsupportedScheme", err)
	}
	if _, err := store.Load(); !errors.Is(err, ErrNotFound) {
		t.Errorf("Load() after ValidateURL error = %v, want ErrNotFound", err)
	}
}

func TestServiceSaveURL(t *testing.T) {
	store, _ := newTestStore(t)
	svc := NewService(store)

	got, err := svc.SaveURL("http://127.0.0.1:13080/?token=abc123")
	if err != nil {
		t.Fatalf("SaveURL() error = %v", err)
	}
	if got != "http://127.0.0.1:13080/" {
		t.Errorf("SaveURL() = %q, want %q", got, "http://127.0.0.1:13080/")
	}
	if cur := svc.CurrentURL(); cur != "http://127.0.0.1:13080/" {
		t.Errorf("CurrentURL() after save = %q, want %q", cur, "http://127.0.0.1:13080/")
	}
	if _, err := svc.SaveURL(""); !errors.Is(err, ErrEmpty) {
		t.Errorf("SaveURL(\"\") error = %v, want ErrEmpty", err)
	}
	if cur := svc.CurrentURL(); cur != "http://127.0.0.1:13080/" {
		t.Errorf("CurrentURL() after rejected save = %q, want unchanged", cur)
	}
}
