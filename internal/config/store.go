package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"

	"dsh-anywhere/internal/appinfo"
)

// FileName 是配置文件在应用配置目录中的文件名。
const FileName = "config.json"

var (
	// ErrNotFound 表示从未保存过配置。
	ErrNotFound = errors.New("尚未配置 harness 地址")
	// ErrInvalidConfig 表示已有配置损坏或其中的地址无效。
	ErrInvalidConfig = errors.New("已有配置无效")
)

// rename 可在测试中替换，用于模拟写入最后一步失败。
var rename = os.Rename

type fileData struct {
	HarnessURL string `json:"harnessURL"`
}

// Store 读写单个配置文件。
type Store struct {
	path string
}

// NewStore 返回读写 path 的 Store。
func NewStore(path string) *Store {
	return &Store{path: path}
}

// DefaultStore 返回位于应用配置目录下的 Store。
func DefaultStore() (*Store, error) {
	dir, err := appinfo.ConfigDir()
	if err != nil {
		return nil, err
	}
	return NewStore(filepath.Join(dir, FileName)), nil
}

// Load 返回已保存的干净地址。从未保存过时返回 ErrNotFound；
// 内容损坏或地址无效时返回包装了原因的 ErrInvalidConfig。
func (s *Store) Load() (string, error) {
	data, err := os.ReadFile(s.path)
	if errors.Is(err, fs.ErrNotExist) {
		return "", ErrNotFound
	}
	if err != nil {
		return "", fmt.Errorf("读取配置失败：%w", err)
	}
	var fd fileData
	if err := json.Unmarshal(data, &fd); err != nil {
		return "", fmt.Errorf("%w：内容无法解析", ErrInvalidConfig)
	}
	clean, err := Normalize(fd.HarnessURL)
	if err != nil {
		return "", fmt.Errorf("%w：%w", ErrInvalidConfig, err)
	}
	return clean, nil
}

// Save 校验 raw，原子地保存去掉 token 的干净地址并返回它。
// 校验失败时不修改已有配置。
func (s *Store) Save(raw string) (string, error) {
	clean, err := Normalize(raw)
	if err != nil {
		return "", err
	}
	data, err := json.MarshalIndent(fileData{HarnessURL: clean}, "", "  ")
	if err != nil {
		return "", fmt.Errorf("保存配置失败：%w", err)
	}
	if err := writeFileAtomic(s.path, append(data, '\n')); err != nil {
		return "", fmt.Errorf("保存配置失败：%w", err)
	}
	return clean, nil
}

// writeFileAtomic 在目标目录中写临时文件并重命名覆盖，写入中断不会留下半截文件。
func writeFileAtomic(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+".*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName) // 重命名成功后为空操作

	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return rename(tmpName, path)
}
