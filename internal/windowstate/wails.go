package windowstate

import "github.com/wailsapp/wails/v3/pkg/application"

// FromWails 把 Wails 的显示器列表适配为 Resolve 使用的 Screen 列表。这里只做数据
// 转换，不触碰窗口，因此可以在单元测试中直接构造 application.Screen 断言映射结果。
func FromWails(screens []*application.Screen) []Screen {
	converted := make([]Screen, 0, len(screens))
	for _, screen := range screens {
		if screen == nil {
			continue
		}
		converted = append(converted, Screen{
			Bounds:    Rect{X: screen.Bounds.X, Y: screen.Bounds.Y, Width: screen.Bounds.Width, Height: screen.Bounds.Height},
			WorkArea:  Rect{X: screen.WorkArea.X, Y: screen.WorkArea.Y, Width: screen.WorkArea.Width, Height: screen.WorkArea.Height},
			IsPrimary: screen.IsPrimary,
		})
	}
	return converted
}
