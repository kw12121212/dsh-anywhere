package windowstate

import (
	"testing"

	"github.com/wailsapp/wails/v3/pkg/application"
)

func TestFromWailsMapsDisplayData(t *testing.T) {
	screens := []*application.Screen{
		{
			Bounds:   application.Rect{X: -1920, Y: 0, Width: 1920, Height: 1080},
			WorkArea: application.Rect{X: -1920, Y: 0, Width: 1920, Height: 1040},
		},
		{
			Bounds:    application.Rect{X: 0, Y: 0, Width: 2560, Height: 1440},
			WorkArea:  application.Rect{X: 0, Y: 0, Width: 2560, Height: 1400},
			IsPrimary: true,
		},
	}

	got := FromWails(screens)
	want := []Screen{
		{
			Bounds:   Rect{X: -1920, Y: 0, Width: 1920, Height: 1080},
			WorkArea: Rect{X: -1920, Y: 0, Width: 1920, Height: 1040},
		},
		{
			Bounds:    Rect{X: 0, Y: 0, Width: 2560, Height: 1440},
			WorkArea:  Rect{X: 0, Y: 0, Width: 2560, Height: 1400},
			IsPrimary: true,
		},
	}
	if len(got) != len(want) {
		t.Fatalf("FromWails() = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("FromWails()[%d] = %+v, want %+v", i, got[i], want[i])
		}
	}
}

func TestFromWailsSkipsNilScreens(t *testing.T) {
	got := FromWails([]*application.Screen{nil, {Bounds: application.Rect{Width: 800, Height: 600}}})
	if len(got) != 1 {
		t.Fatalf("FromWails() = %v, want one screen", got)
	}
	if got := FromWails(nil); len(got) != 0 {
		t.Fatalf("FromWails(nil) = %v, want empty", got)
	}
}

func TestFromWailsFeedsResolve(t *testing.T) {
	got := Resolve(nil, FromWails([]*application.Screen{{
		Bounds:    application.Rect{X: 0, Y: 0, Width: 1920, Height: 1080},
		WorkArea:  application.Rect{X: 0, Y: 0, Width: 1920, Height: 1040},
		IsPrimary: true,
	}}))
	if got != centeredDefaults {
		t.Fatalf("Resolve(FromWails(...)) = %+v, want %+v", got, centeredDefaults)
	}
}
