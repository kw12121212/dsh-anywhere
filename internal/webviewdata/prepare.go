package webviewdata

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
)

// CookieFile 是 cookie 数据库在应用数据目录中的文件名。
const CookieFile = "cookies.sqlite"

// prepare 以仅当前用户可访问的权限准备 cookie 数据库：目录 0700，文件 0600。
// cookie 就是 dsh 会话凭据；SQLite 的日志文件随后创建在同一目录中，同样受目录权限保护。
func prepare(path string) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	if err := os.Chmod(dir, 0o700); err != nil {
		return err
	}
	f, err := os.OpenFile(path, os.O_RDONLY|os.O_CREATE, 0o600)
	if err != nil {
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	if err := os.Chmod(path, 0o600); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	return nil
}
