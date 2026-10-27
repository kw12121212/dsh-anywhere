package windowstate

import "testing"

var (
	primaryScreen = Screen{
		Bounds:    Rect{X: 0, Y: 0, Width: 1920, Height: 1080},
		WorkArea:  Rect{X: 0, Y: 0, Width: 1920, Height: 1040},
		IsPrimary: true,
	}
	// centeredDefaults 是默认尺寸在 primaryScreen 工作区居中的落点。
	centeredDefaults = Placement{X: 460, Y: 211, Width: DefaultWidth, Height: DefaultHeight, Move: true}
)

func TestResolveKeepsVisibleSavedGeometry(t *testing.T) {
	saved := &State{X: 700, Y: 150, Width: 1150, Height: 700}
	got := Resolve(saved, []Screen{primaryScreen})
	want := Placement{X: 700, Y: 150, Width: 1150, Height: 700, Move: true}
	if got != want {
		t.Fatalf("Resolve() = %+v, want %+v", got, want)
	}
}

func TestResolveKeepsPartiallyVisibleGeometry(t *testing.T) {
	// 只有左上角在屏幕内也算可见：回退只发生在完全不相交时。
	saved := &State{X: -200, Y: 100, Width: 1200, Height: 700}
	got := Resolve(saved, []Screen{primaryScreen})
	want := Placement{X: -200, Y: 100, Width: 1200, Height: 700, Move: true}
	if got != want {
		t.Fatalf("Resolve() = %+v, want %+v", got, want)
	}
}

func TestResolveRecentersOffscreenGeometry(t *testing.T) {
	saved := &State{X: 5000, Y: 3000, Width: 1200, Height: 800}
	got := Resolve(saved, []Screen{primaryScreen})
	if got != centeredDefaults {
		t.Fatalf("Resolve() = %+v, want %+v", got, centeredDefaults)
	}
}

func TestResolveRecentersGeometryOnDisconnectedDisplay(t *testing.T) {
	// 保存时同时有左侧外接屏；拔掉后矩形落在负坐标区域之外。
	secondary := Screen{
		Bounds:   Rect{X: -1920, Y: 0, Width: 1920, Height: 1080},
		WorkArea: Rect{X: -1920, Y: 0, Width: 1920, Height: 1040},
	}
	saved := &State{X: -1500, Y: 100, Width: 800, Height: 600}
	if got, want := Resolve(saved, []Screen{secondary, primaryScreen}), (Placement{X: -1500, Y: 100, Width: 800, Height: 600, Move: true}); got != want {
		t.Fatalf("Resolve() with connected secondary = %+v, want %+v", got, want)
	}
	if got := Resolve(saved, []Screen{primaryScreen}); got != centeredDefaults {
		t.Fatalf("Resolve() after disconnect = %+v, want %+v", got, centeredDefaults)
	}
}

func TestResolveWithoutSavedStateUsesCenteredDefaults(t *testing.T) {
	if got := Resolve(nil, []Screen{primaryScreen}); got != centeredDefaults {
		t.Fatalf("Resolve() = %+v, want %+v", got, centeredDefaults)
	}
}

func TestResolvePreservesMaximisedFlag(t *testing.T) {
	saved := &State{X: 700, Y: 150, Width: 1150, Height: 700, Maximised: true}
	if got := Resolve(saved, []Screen{primaryScreen}); !got.Maximised {
		t.Fatalf("Resolve() maximised = false, want true (%+v)", got)
	}

	offscreen := &State{X: 5000, Y: 3000, Width: 1150, Height: 700, Maximised: true}
	got := Resolve(offscreen, []Screen{primaryScreen})
	want := centeredDefaults
	want.Maximised = true
	if got != want {
		t.Fatalf("Resolve() offscreen maximised = %+v, want %+v", got, want)
	}
}

func TestResolveWithoutScreensOnlyDecidesSize(t *testing.T) {
	saved := &State{X: 700, Y: 150, Width: 1150, Height: 700}
	got := Resolve(saved, nil)
	want := Placement{Width: 1150, Height: 700}
	if got != want {
		t.Fatalf("Resolve() without screens = %+v, want %+v", got, want)
	}

	empty := Resolve(nil, []Screen{})
	wantDefaults := Placement{Width: DefaultWidth, Height: DefaultHeight}
	if empty != wantDefaults {
		t.Fatalf("Resolve() without saved state or screens = %+v, want %+v", empty, wantDefaults)
	}
}

func TestResolveRejectsInvalidSavedSize(t *testing.T) {
	for name, saved := range map[string]*State{
		"zero width":     {X: 100, Y: 100, Width: 0, Height: 700},
		"negative width": {X: 100, Y: 100, Width: -10, Height: 700},
		"zero height":    {X: 100, Y: 100, Width: 1000, Height: 0},
		"oversized":      {X: 100, Y: 100, Width: maxDimension + 1, Height: 700},
	} {
		t.Run(name, func(t *testing.T) {
			if got := Resolve(saved, []Screen{primaryScreen}); got != centeredDefaults {
				t.Fatalf("Resolve() = %+v, want %+v", got, centeredDefaults)
			}
		})
	}
}

func TestResolveUsesPrimaryScreenForCentering(t *testing.T) {
	secondary := Screen{
		Bounds:   Rect{X: -2560, Y: 0, Width: 2560, Height: 1440},
		WorkArea: Rect{X: -2560, Y: 0, Width: 2560, Height: 1400},
	}
	got := Resolve(nil, []Screen{secondary, primaryScreen})
	if got != centeredDefaults {
		t.Fatalf("Resolve() = %+v, want %+v", got, centeredDefaults)
	}
}

func TestResolveFallsBackToFirstScreenWithoutPrimary(t *testing.T) {
	first := Screen{
		Bounds:   Rect{X: 0, Y: 0, Width: 1600, Height: 900},
		WorkArea: Rect{X: 0, Y: 0, Width: 1600, Height: 860},
	}
	got := Resolve(nil, []Screen{first})
	want := Placement{X: 300, Y: 121, Width: DefaultWidth, Height: DefaultHeight, Move: true}
	if got != want {
		t.Fatalf("Resolve() = %+v, want %+v", got, want)
	}
}

func TestResolveFallsBackToWorkAreaForBoundsAndWorkAreaForCentering(t *testing.T) {
	// 边界缺失时用工作区判定可见性。
	workAreaOnly := Screen{WorkArea: Rect{X: 0, Y: 0, Width: 1920, Height: 1080}}
	saved := &State{X: 10, Y: 10, Width: 800, Height: 600}
	if got, want := Resolve(saved, []Screen{workAreaOnly}), (Placement{X: 10, Y: 10, Width: 800, Height: 600, Move: true}); got != want {
		t.Fatalf("Resolve() with bounds fallback = %+v, want %+v", got, want)
	}

	// 工作区缺失时用边界居中。
	boundsOnly := Screen{Bounds: Rect{X: 0, Y: 0, Width: 1920, Height: 1080}}
	got := Resolve(nil, []Screen{boundsOnly})
	want := Placement{X: 460, Y: 231, Width: DefaultWidth, Height: DefaultHeight, Move: true}
	if got != want {
		t.Fatalf("Resolve() with work area fallback = %+v, want %+v", got, want)
	}
}

func TestResolveTreatsWindowUnderTaskbarAsVisible(t *testing.T) {
	// 可见性按显示器边界判定，因此落在工作区之外但在显示器之内的窗口不会被重新居中。
	saved := &State{X: 100, Y: 1050, Width: 800, Height: 600}
	got := Resolve(saved, []Screen{primaryScreen})
	want := Placement{X: 100, Y: 1050, Width: 800, Height: 600, Move: true}
	if got != want {
		t.Fatalf("Resolve() = %+v, want %+v", got, want)
	}
}
