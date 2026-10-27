package singleinstance

import (
	"sync"
	"testing"
)

// recorder 记录注入窗口操作的调用顺序。
type recorder struct {
	mu        sync.Mutex
	minimised bool
	calls     []string
}

func (r *recorder) window() Window {
	return Window{
		IsMinimised: func() bool {
			r.mu.Lock()
			defer r.mu.Unlock()
			return r.minimised
		},
		UnMinimise: func() { r.record("unminimise") },
		Show:       func() { r.record("show") },
		Focus:      func() { r.record("focus") },
	}
}

func (r *recorder) record(call string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.calls = append(r.calls, call)
}

func (r *recorder) recorded() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.calls...)
}

func assertCalls(t *testing.T, rec *recorder, want ...string) {
	t.Helper()

	got := rec.recorded()
	if len(got) != len(want) {
		t.Fatalf("calls = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("calls = %v, want %v", got, want)
		}
	}
}

func TestRequestOnVisibleWindowShowsAndFocuses(t *testing.T) {
	rec := &recorder{}
	activator := New()
	activator.Bind(rec.window())
	activator.Start()

	activator.Request()

	assertCalls(t, rec, "show", "focus")
}

func TestRequestOnMinimisedWindowRestoresBeforeShowing(t *testing.T) {
	rec := &recorder{minimised: true}
	activator := New()
	activator.Bind(rec.window())
	activator.Start()

	activator.Request()

	assertCalls(t, rec, "unminimise", "show", "focus")
}

func TestRequestBeforeStartIsDeferredUntilWindowReady(t *testing.T) {
	rec := &recorder{minimised: true}
	activator := New()
	activator.Bind(rec.window())

	activator.Request()
	activator.Request()
	assertCalls(t, rec)

	activator.Start()
	assertCalls(t, rec, "unminimise", "show", "focus")

	// 窗口只就绪一次；再次 Start 不应重复激活。
	activator.Start()
	assertCalls(t, rec, "unminimise", "show", "focus")
}

func TestRequestBeforeBindIsDeferredUntilWindowReady(t *testing.T) {
	rec := &recorder{}
	activator := New()

	activator.Request()
	activator.Bind(rec.window())
	activator.Start()

	assertCalls(t, rec, "show", "focus")
}

func TestRequestAfterStartActivatesEachTime(t *testing.T) {
	rec := &recorder{}
	activator := New()
	activator.Bind(rec.window())
	activator.Start()

	activator.Request()
	activator.Request()

	assertCalls(t, rec, "show", "focus", "show", "focus")
}

func TestConcurrentRequestAndStartActivatesAtLeastOnce(t *testing.T) {
	rec := &recorder{minimised: true}
	activator := New()
	activator.Bind(rec.window())

	const requests = 8
	var wg sync.WaitGroup
	wg.Add(requests + 1)
	go func() {
		defer wg.Done()
		activator.Start()
	}()
	for i := 0; i < requests; i++ {
		go func() {
			defer wg.Done()
			activator.Request()
		}()
	}
	wg.Wait()

	got := rec.recorded()
	if len(got) == 0 {
		t.Fatal("no activation recorded")
	}
	if len(got)%3 != 0 {
		t.Fatalf("calls = %v, want complete activation sequences", got)
	}
	for i := 0; i < len(got); i += 3 {
		want := []string{"unminimise", "show", "focus"}
		for j := range want {
			if got[i+j] != want[j] {
				t.Fatalf("activation %d calls = %v, want %v", i/3, got, want)
			}
		}
	}
}

func TestConcurrentRequestsAfterStartActivateOncePerRequest(t *testing.T) {
	rec := &recorder{minimised: true}
	activator := New()
	activator.Bind(rec.window())
	activator.Start()

	const requests = 8
	var wg sync.WaitGroup
	wg.Add(requests)
	for i := 0; i < requests; i++ {
		go func() {
			defer wg.Done()
			activator.Request()
		}()
	}
	wg.Wait()

	got := rec.recorded()
	if want := requests * 3; len(got) != want {
		t.Fatalf("calls = %v, want exactly %d calls (%d activations)", got, want, requests)
	}
	for i := 0; i < len(got); i += 3 {
		want := []string{"unminimise", "show", "focus"}
		for j := range want {
			if got[i+j] != want[j] {
				t.Fatalf("activation %d calls = %v, want %v", i/3, got, want)
			}
		}
	}
}
