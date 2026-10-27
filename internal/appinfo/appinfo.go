// Package appinfo 提供应用标识以及应用在用户配置目录中的位置。
package appinfo

import (
	"fmt"
	"os"
	"path/filepath"
)

// Name 是应用名称，同时用作用户配置目录下的子目录名。
const Name = "dsh-anywhere"

// Identifier 是应用的唯一标识，用于单实例锁等需要稳定标识的场合。
// app-identity 落地后与 build/config.yml 的 productIdentifier 保持同源。
const Identifier = "work.keepwriting.dsh-anywhere"

// userConfigDir 可在测试中替换，用于模拟系统配置目录不可用。
var userConfigDir = os.UserConfigDir

// ConfigDir 返回应用的配置目录 <用户配置目录>/dsh-anywhere，不会创建该目录。
func ConfigDir() (string, error) {
	base, err := userConfigDir()
	if err != nil {
		return "", fmt.Errorf("locate user config dir: %w", err)
	}
	return filepath.Join(base, Name), nil
}

// userHomeDir 可在测试中替换，用于模拟主目录不可用。
var userHomeDir = os.UserHomeDir

// DataDir 返回应用在 XDG 数据目录中的目录 <XDG_DATA_HOME 或 ~/.local/share>/dsh-anywhere，不会创建该目录。
// 只用于 Linux 上的 webview 持久化数据；其他平台的 webview 自行管理数据目录。
func DataDir() (string, error) {
	if base := os.Getenv("XDG_DATA_HOME"); filepath.IsAbs(base) {
		return filepath.Join(base, Name), nil
	}
	home, err := userHomeDir()
	if err != nil {
		return "", fmt.Errorf("locate user data dir: %w", err)
	}
	return filepath.Join(home, ".local", "share", Name), nil
}
