//go:build android || darwin

package tstunnel

import "tailscale.com/net/netmon"

// SetDefaultRouteIfname 注入默认路由接口名（Android ConnectivityManager 侧的
// 观测；对应官方 App 调 netmon.UpdateLastKnownDefaultRouteInterface 的集成点。
// 传空串表示网络已丢失）。
//
// 上游符号只存在于 android 与 darwin 构建（netmon 的 GOOS 文件拆分）；gomobile
// bind 的产物是 GOOS=android，始终命中本实现。
func SetDefaultRouteIfname(name string) {
	netmon.UpdateLastKnownDefaultRouteInterface(name)
}
