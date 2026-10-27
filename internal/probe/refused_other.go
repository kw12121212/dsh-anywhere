//go:build !windows

package probe

import "syscall"

// refusedErrnos 是表示连接被拒绝的系统错误码。
var refusedErrnos = []error{syscall.ECONNREFUSED}
