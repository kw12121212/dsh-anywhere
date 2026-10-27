//go:build windows

package probe

import "syscall"

// refusedErrnos 是表示连接被拒绝的系统错误码。Windows 上 ConnectEx 返回 WSAECONNREFUSED（10061），
// 它与 Go 在 Windows 上定义的 syscall.ECONNREFUSED 不相等。
var refusedErrnos = []error{syscall.ECONNREFUSED, syscall.Errno(10061)}
