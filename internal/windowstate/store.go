package windowstate

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"dsh-anywhere/internal/appinfo"
)

// FileName 是窗口状态在应用配置目录中的文件名。
const FileName = "window-state.json"

// ErrNoState 表示没有可用的窗口状态：文件不存在、内容无法解析或数值无效。
var ErrNoState = errors.New("没有可用的窗口状态")

// rename 可在测试中替换，用于模拟写入最后一步失败。
var rename = os.Rename

type fileData struct {
	X         int  `json:"x"`
	Y         int  `json:"y"`
	Width     int  `json:"width"`
	Height    int  `json:"height"`
	Maximised bool `json:"maximised"`
}

// Store 读写单个窗口状态文件。
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

// Load 读取窗口状态。文件不存在、无法读取、内容损坏或尺寸无效时返回 ErrNoState，
// 使这些情况在调用方统一退化为“没有保存状态”。
func (s *Store) Load() (State, error) {
	data, err := os.ReadFile(s.path)
	if err != nil {
		return State{}, fmt.Errorf("%w：%w", ErrNoState, err)
	}
	var fd fileData
	if err := json.Unmarshal(data, &fd); err != nil {
		return State{}, fmt.Errorf("%w：内容无法解析", ErrNoState)
	}
	state := State{X: fd.X, Y: fd.Y, Width: fd.Width, Height: fd.Height, Maximised: fd.Maximised}
	if !state.valid() {
		return State{}, fmt.Errorf("%w：尺寸 %dx%d 无效", ErrNoState, fd.Width, fd.Height)
	}
	return state, nil
}

// Save 原子地保存 state，覆盖上一次保存的状态。
func (s *Store) Save(state State) error {
	if !state.valid() {
		return fmt.Errorf("窗口状态无效：尺寸 %dx%d", state.Width, state.Height)
	}
	data, err := json.MarshalIndent(fileData{
		X:         state.X,
		Y:         state.Y,
		Width:     state.Width,
		Height:    state.Height,
		Maximised: state.Maximised,
	}, "", "  ")
	if err != nil {
		return fmt.Errorf("保存窗口状态失败：%w", err)
	}
	if err := writeFileAtomic(s.path, append(data, '\n')); err != nil {
		return fmt.Errorf("保存窗口状态失败：%w", err)
	}
	return nil
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
