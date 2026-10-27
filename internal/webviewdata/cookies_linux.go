//go:build linux && cgo && !gtk3 && !server && !android

package webviewdata

/*
#cgo pkg-config: webkitgtk-6.0
#include <webkit/webkit.h>
#include <stdlib.h>

// 为 Wails 使用的默认 network session 开启 SQLite 持久化 cookie。返回 0 表示成功。
static int dsh_persist_cookies(const char *path) {
	WebKitNetworkSession *session = webkit_network_session_get_default();
	if (session == NULL) return 1;
	webkit_cookie_manager_set_persistent_storage(webkit_network_session_get_cookie_manager(session),
		path, WEBKIT_COOKIE_PERSISTENT_STORAGE_SQLITE);
	return 0;
}
*/
import "C"

import (
	"fmt"
	"unsafe"
)

// PersistCookies 让 webview 的 cookie 跨应用重启保留，数据库放在 path（应用专属目录）。
// 必须在 app.Run 之前、任何页面加载之前调用。Wails v3.0.0 在 Linux 上使用 WebKitGTK 默认
// network session，但不为其开启持久化 cookie 存储。
func PersistCookies(path string) error {
	if err := prepare(path); err != nil {
		return fmt.Errorf("prepare webview cookie storage: %w", err)
	}
	cpath := C.CString(path)
	defer C.free(unsafe.Pointer(cpath))
	if rc := C.dsh_persist_cookies(cpath); rc != 0 {
		return fmt.Errorf("enable persistent webview cookies: code %d", int(rc))
	}
	return nil
}
