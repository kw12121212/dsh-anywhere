//go:build !android && !darwin

package tstunnel

// SetDefaultRouteIfname 在桌面构建（linux 等）上是 no-op：netmon 自己读路由表，
// 不需要注入；保留同形 API 让桌面 spike（cmd/taggedwhois）与 AAR 共用一份源码。
func SetDefaultRouteIfname(name string) {}
