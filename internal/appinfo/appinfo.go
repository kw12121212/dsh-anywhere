// Package appinfo 提供应用标识以及应用在用户配置目录中的位置。
package appinfo

import (
	"fmt"
	"os"
	"path/filepath"
)

// Name 是应用的内部名称，同时用作用户配置目录下的子目录名与 Wails 应用名。
// 它决定配置目录与 dsh 会话 cookie 的位置，改名会让已有用户配置失效，因此不随显示名称变化。
const Name = "dsh-anywhere"

// DisplayName 是应用对用户显示的正式名称，用于窗口标题、菜单与关于信息。
const DisplayName = "DSH Anywhere"

// Identifier 是应用的唯一标识，用于单实例锁等需要稳定标识的场合。
// 与 build/config.yml 的 productIdentifier 保持同源，由单元测试核对。
const Identifier = "work.keepwriting.dsh-anywhere"

// Version 是应用版本号，未注入时为 dev。
// 发布构建通过 -ldflags -X dsh-anywhere/internal/appinfo.Version=<标签去掉 v 前缀> 注入。
var Version = "dev"

// AboutTitle 是“关于”菜单项与对话框的标题。
func AboutTitle() string {
	return "关于 " + DisplayName
}

// AboutMessage 是“关于”对话框的正文，显示应用名称与版本号。
func AboutMessage() string {
	return DisplayName + "\n版本 " + Version
}

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
