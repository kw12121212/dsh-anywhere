// Package navigation 决定主窗口中的导航留在窗口内还是交给系统浏览器。
package navigation

import (
	"net/url"
	"strings"
)

// SameOrigin 报告 target 与 base 是否同源：协议、主机（不区分大小写）与端口（缺省端口按协议补全）都相同。
// 任一地址无法解析或不是带主机名的绝对地址时返回 false。
func SameOrigin(base, target string) bool {
	b, ok := origin(base)
	if !ok {
		return false
	}
	t, ok := origin(target)
	return ok && b == t
}

type originKey struct {
	scheme, host, port string
}

func origin(raw string) (originKey, bool) {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme == "" || u.Hostname() == "" {
		return originKey{}, false
	}
	scheme := strings.ToLower(u.Scheme)
	port := u.Port()
	if port == "" {
		port = defaultPorts[scheme]
	}
	return originKey{scheme: scheme, host: strings.ToLower(u.Hostname()), port: port}, true
}

var defaultPorts = map[string]string{"http": "80", "https": "443"}
