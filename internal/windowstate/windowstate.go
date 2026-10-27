// Package windowstate 负责主窗口位置、尺寸与最大化状态的持久化与恢复。
//
// 恢复策略集中在本包：Resolve 是不依赖 Wails 的纯函数，Store 读写应用配置目录中的
// window-state.json，Manager 把两者与注入的窗口操作串起来。main.go 只做装配。
package windowstate

const (
	// DefaultWidth 与 DefaultHeight 是主窗口首次启动与越界回退时使用的尺寸。
	DefaultWidth  = 1000
	DefaultHeight = 618
)

// maxDimension 是接受的最大窗口尺寸，超过即视为状态无效，避免异常数值撑坏窗口。
const maxDimension = 32768

// State 是保存的窗口状态，位置与尺寸均为显示坐标（DIP）。
type State struct {
	X         int
	Y         int
	Width     int
	Height    int
	Maximised bool
}

// valid 报告状态中的尺寸能否用于恢复。
func (s State) valid() bool {
	return s.Width > 0 && s.Height > 0 && s.Width <= maxDimension && s.Height <= maxDimension
}

// Rect 是屏幕坐标系中的矩形。
type Rect struct {
	X      int
	Y      int
	Width  int
	Height int
}

// Screen 是恢复所需的显示器信息，由 main.go 从 Wails 的显示器列表适配而来。
type Screen struct {
	Bounds    Rect
	WorkArea  Rect
	IsPrimary bool
}

// Placement 是把保存状态换算成的一次恢复动作。
type Placement struct {
	X      int
	Y      int
	Width  int
	Height int
	// Move 为 false 时不设置窗口位置：没有显示器信息，位置由系统或合成器决定。
	Move      bool
	Maximised bool
}

// Resolve 返回 saved 在当前显示器布局下的落点。saved 为 nil、尺寸无效或与任何显示器
// 都不相交时，改用默认尺寸在主显示器工作区居中；显示器列表为空时只决定尺寸、不移动
// 窗口，因此在无法设置位置的会话（Wayland）下退化为只恢复尺寸与最大化状态。
func Resolve(saved *State, screens []Screen) Placement {
	placement := Placement{Width: DefaultWidth, Height: DefaultHeight}
	if saved != nil && saved.valid() {
		placement = Placement{
			X:         saved.X,
			Y:         saved.Y,
			Width:     saved.Width,
			Height:    saved.Height,
			Maximised: saved.Maximised,
		}
	}
	if len(screens) == 0 {
		// 没有显示器信息时不移动窗口，位置字段因此不适用。
		placement.X = 0
		placement.Y = 0
		return placement
	}
	if saved != nil && saved.valid() && onScreen(Rect{X: saved.X, Y: saved.Y, Width: saved.Width, Height: saved.Height}, screens) {
		placement.Move = true
		return placement
	}

	area := workArea(primary(screens))
	placement.X = area.X + (area.Width-DefaultWidth)/2
	placement.Y = area.Y + (area.Height-DefaultHeight)/2
	placement.Width = DefaultWidth
	placement.Height = DefaultHeight
	placement.Move = true
	return placement
}

// primary 返回主显示器；没有任何显示器标记为主屏时使用第一个。
func primary(screens []Screen) Screen {
	for _, screen := range screens {
		if screen.IsPrimary {
			return screen
		}
	}
	return screens[0]
}

// workArea 返回显示器的工作区；工作区缺失时退回显示器边界。
func workArea(screen Screen) Rect {
	if screen.WorkArea.Width > 0 && screen.WorkArea.Height > 0 {
		return screen.WorkArea
	}
	return screen.Bounds
}

// onScreen 报告 rect 是否与任一显示器有正面积重叠。
func onScreen(rect Rect, screens []Screen) bool {
	for _, screen := range screens {
		if overlaps(rect, screenBounds(screen)) {
			return true
		}
	}
	return false
}

// screenBounds 返回显示器的边界；边界缺失时退回工作区。
func screenBounds(screen Screen) Rect {
	if screen.Bounds.Width > 0 && screen.Bounds.Height > 0 {
		return screen.Bounds
	}
	return screen.WorkArea
}

func overlaps(a, b Rect) bool {
	return a.X < b.X+b.Width && b.X < a.X+a.Width && a.Y < b.Y+b.Height && b.Y < a.Y+a.Height
}
