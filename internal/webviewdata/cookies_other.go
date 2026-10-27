//go:build !(linux && cgo && !gtk3 && !server && !android)

package webviewdata

// PersistCookies 在其他平台与构建配置下不做处理：macOS 的 WKWebView 默认数据存储与
// Windows 的 WebView2 用户数据目录本身就会持久化 cookie。Linux 的 gtk3 构建（WebKitGTK 4.1）
// 不在支持范围内，那里的 cookie 不会跨重启保留。
func PersistCookies(string) error {
	return nil
}
