//go:build server

package guard

// server 模式下 Wails 自身已按同源策略校验 /wails/runtime（transport_http_server.go），
// 页面 origin 是 HTTP 服务器地址而不是 wails 本地 origin，因此这里不再重复限制。
func originAllowed(string) bool {
	return true
}
