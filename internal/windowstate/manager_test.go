package windowstate

import (
	"errors"
	"fmt"
	"sync"
	"testing"
)

// recorder 记录注入窗口操作的调用顺序。
type recorder struct {
	mu        sync.Mutex
	calls     []string
	position  [2]int
	size      [2]int
	maximised bool
}

func (r *recorder) ops() WindowOps {
	return WindowOps{
		Position: func() (int, int) {
			r.mu.Lock()
			defer r.mu.Unlock()
			return r.position[0], r.position[1]
		},
		Size: func() (int, int) {
			r.mu.Lock()
			defer r.mu.Unlock()
			return r.size[0], r.size[1]
		},
		IsMaximised: func() bool {
			r.mu.Lock()
			defer r.mu.Unlock()
			return r.maximised
		},
		SetPosition: func(x, y int) { r.record(fmt.Sprintf("setPosition:%d,%d", x, y)) },
		SetSize:     func(width, height int) { r.record(fmt.Sprintf("setSize:%d,%d", width, height)) },
		Maximise:    func() { r.record("maximise") },
		Show:        func() { r.record("show") },
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

// fakeStore 是 StateStore 的记录器实现。
type fakeStore struct {
	mu      sync.Mutex
	state   State
	loadErr error
	saveErr error
	saves   []State
}

func (s *fakeStore) Load() (State, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state, s.loadErr
}

func (s *fakeStore) Save(state State) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.saveErr != nil {
		return s.saveErr
	}
	s.saves = append(s.saves, state)
	return nil
}

func (s *fakeStore) savedStates() []State {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]State(nil), s.saves...)
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

func newManager(store StateStore, rec *recorder, screens []Screen) *Manager {
	return New(store, rec.ops(), func() []Screen { return screens })
}

func TestRestoreAppliesSavedGeometryBeforeShowing(t *testing.T) {
	rec := &recorder{}
	store := &fakeStore{state: State{X: 700, Y: 150, Width: 1150, Height: 700}}

	newManager(store, rec, []Screen{primaryScreen}).Restore()

	assertCalls(t, rec, "setSize:1150,700", "show", "setPosition:700,150")
}

func TestRestoreMaximisesAfterShowing(t *testing.T) {
	rec := &recorder{}
	store := &fakeStore{state: State{X: 700, Y: 150, Width: 1150, Height: 700, Maximised: true}}

	newManager(store, rec, []Screen{primaryScreen}).Restore()

	assertCalls(t, rec, "setSize:1150,700", "show", "setPosition:700,150", "maximise")
}

func TestRestoreRecentersOffscreenState(t *testing.T) {
	rec := &recorder{}
	store := &fakeStore{state: State{X: 5000, Y: 3000, Width: 1200, Height: 800}}

	newManager(store, rec, []Screen{primaryScreen}).Restore()

	assertCalls(t, rec, "setSize:1000,618", "show", "setPosition:460,211")
}

func TestRestoreWithoutSavedStateUsesDefaults(t *testing.T) {
	rec := &recorder{}
	store := &fakeStore{loadErr: ErrNoState}

	newManager(store, rec, []Screen{primaryScreen}).Restore()

	assertCalls(t, rec, "setSize:1000,618", "show", "setPosition:460,211")
}

func TestRestoreOffscreenMaximisedState(t *testing.T) {
	// 越界回退与最大化同时成立时：先回退到默认尺寸居中，再在主显示器上最大化。
	rec := &recorder{}
	store := &fakeStore{state: State{X: 5000, Y: 3000, Width: 1200, Height: 800, Maximised: true}}

	newManager(store, rec, []Screen{primaryScreen}).Restore()

	assertCalls(t, rec, "setSize:1000,618", "show", "setPosition:460,211", "maximise")
}

func TestRestoreWithoutScreensKeepsSizeOnly(t *testing.T) {
	rec := &recorder{}
	store := &fakeStore{state: State{X: 700, Y: 150, Width: 1150, Height: 700}}

	newManager(store, rec, nil).Restore()

	// 无法设置位置的会话（Wayland）下只恢复尺寸，不调用 SetPosition。
	assertCalls(t, rec, "setSize:1150,700", "show")
}

func TestRestoreRunsOnce(t *testing.T) {
	rec := &recorder{}
	store := &fakeStore{state: State{X: 700, Y: 150, Width: 1150, Height: 700}}

	calls := 0
	manager := New(store, rec.ops(), func() []Screen {
		calls++
		return []Screen{primaryScreen}
	})
	manager.Restore()
	manager.Restore()

	if calls != 1 {
		t.Fatalf("screens() calls = %d, want 1", calls)
	}
	assertCalls(t, rec, "setSize:1150,700", "show", "setPosition:700,150")
}

func TestSaveSkippedBeforeRestore(t *testing.T) {
	rec := &recorder{position: [2]int{300, 200}, size: [2]int{1100, 700}}
	store := &fakeStore{}

	if err := newManager(store, rec, []Screen{primaryScreen}).Save(); err != nil {
		t.Fatalf("Save() error = %v", err)
	}
	if saves := store.savedStates(); len(saves) != 0 {
		t.Fatalf("Save() wrote %v before Restore, want nothing", saves)
	}
	assertCalls(t, rec)
}

func TestSaveRecordsCurrentGeometry(t *testing.T) {
	rec := &recorder{position: [2]int{300, 200}, size: [2]int{1100, 700}, maximised: true}
	store := &fakeStore{}
	manager := newManager(store, rec, []Screen{primaryScreen})

	manager.Restore()
	if err := manager.Save(); err != nil {
		t.Fatalf("Save() error = %v", err)
	}

	saves := store.savedStates()
	want := State{X: 300, Y: 200, Width: 1100, Height: 700, Maximised: true}
	if len(saves) != 1 || saves[0] != want {
		t.Fatalf("Save() wrote %v, want [%+v]", saves, want)
	}
}

func TestSaveSkipsUnreadableGeometry(t *testing.T) {
	rec := &recorder{size: [2]int{0, 0}}
	store := &fakeStore{}
	manager := newManager(store, rec, []Screen{primaryScreen})

	manager.Restore()
	if err := manager.Save(); err != nil {
		t.Fatalf("Save() error = %v", err)
	}
	if saves := store.savedStates(); len(saves) != 0 {
		t.Fatalf("Save() wrote %v for destroyed window, want nothing", saves)
	}
}

func TestSavePropagatesStoreError(t *testing.T) {
	rec := &recorder{position: [2]int{300, 200}, size: [2]int{1100, 700}}
	saveErr := errors.New("disk full")
	store := &fakeStore{saveErr: saveErr}
	manager := newManager(store, rec, []Screen{primaryScreen})

	manager.Restore()
	if err := manager.Save(); !errors.Is(err, saveErr) {
		t.Fatalf("Save() error = %v, want %v", err, saveErr)
	}
}

func TestRestoreAndSaveUnderConcurrency(t *testing.T) {
	rec := &recorder{position: [2]int{300, 200}, size: [2]int{1100, 700}}
	store := &fakeStore{state: State{X: 700, Y: 150, Width: 1150, Height: 700}}
	manager := newManager(store, rec, []Screen{primaryScreen})

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		manager.Restore()
	}()
	go func() {
		defer wg.Done()
		if err := manager.Save(); err != nil {
			t.Errorf("Save() error = %v", err)
		}
	}()
	wg.Wait()

	// Restore 一定执行过，保存要么发生在恢复之前（跳过）要么之后（写入一次）。
	if saves := store.savedStates(); len(saves) > 1 {
		t.Fatalf("Save() wrote %d states, want at most 1", len(saves))
	}
}
