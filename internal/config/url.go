// Package config 负责 harness URL 的校验、token 剥离与持久化。
package config

import (
	"errors"
	"net/url"
	"strings"
)

// tokenParam 是 dsh web 启动令牌的查询参数名。
const tokenParam = "token"

// 校验错误只含固定文本，不回显输入，避免 token 经由错误信息泄露。
var (
	ErrEmpty             = errors.New("地址不能为空")
	ErrMalformed         = errors.New("地址格式无效")
	ErrNotAbsolute       = errors.New("必须是包含协议和主机名的完整地址，例如 https://example.com")
	ErrUnsupportedScheme = errors.New("只支持 http 或 https 地址")
)

// Normalize 校验用户输入的 harness URL 并返回去掉 token 参数的干净地址。
// 输入的首尾空白会被忽略；只接受带主机名的 http/https 绝对地址。
func Normalize(raw string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", ErrEmpty
	}
	u, err := url.Parse(trimmed)
	if err != nil {
		return "", ErrMalformed
	}
	if u.Scheme == "" {
		return "", ErrNotAbsolute
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", ErrUnsupportedScheme
	}
	if u.Hostname() == "" {
		return "", ErrNotAbsolute
	}
	stripToken(u)
	return u.String(), nil
}

// Redact 返回可写入日志的地址文本：去掉 token 参数并隐藏密码；无法解析时不回显原文。
func Redact(raw string) string {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return "<无法解析的地址>"
	}
	stripToken(u)
	return u.Redacted()
}

// stripToken 删除所有名为 token（区分大小写）的查询参数，其余参数保持原有顺序与编码，
// 空段一并去掉。只按 & 分隔，与 dsh web 生成的地址一致；片段中的内容不处理。
func stripToken(u *url.URL) {
	if u.RawQuery == "" {
		return
	}
	parts := strings.Split(u.RawQuery, "&")
	kept := parts[:0]
	for _, part := range parts {
		if part == "" {
			continue
		}
		key, _, _ := strings.Cut(part, "=")
		if name, err := url.QueryUnescape(key); err == nil && name == tokenParam {
			continue
		}
		kept = append(kept, part)
	}
	u.RawQuery = strings.Join(kept, "&")
	u.ForceQuery = false
}
