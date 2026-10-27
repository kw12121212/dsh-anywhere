//go:build !server

package guard

// localOrigins 是各平台本地资源服务器的 origin（Linux/macOS 为 wails://localhost，
// Windows 为 http://wails.localhost）。本地页面的同源请求要么不带 Origin，要么带其中之一。
var localOrigins = map[string]bool{
	"wails://localhost":      true,
	"http://wails.localhost": true,
}

// originAllowed 报告带 Origin 头的请求是否来自本地页面。
// WebKitGTK 2.52.6 对自定义 scheme 的跨源 JSON POST 不做预检，远程页面的请求会带上它自己的 Origin。
func originAllowed(origin string) bool {
	return localOrigins[origin]
}
