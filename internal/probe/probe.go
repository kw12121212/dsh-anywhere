// Package probe 在导航前对 harness 地址做带超时的 HTTP 可达性探测。
//
// 探测不携带 webview 的 cookie，因此只判断网络层面能否到达：任何 HTTP 响应（包括 401 等
// 4xx/5xx）都算可达，交给远程页面自己展示；dsh 的 401 由会话失效识别处理。
package probe

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net"
	"net/http"
	"strings"
	"time"
)

// DefaultTimeout 是一次探测的默认超时。
const DefaultTimeout = 10 * time.Second

// Reason 是不可达的原因分类，用于本地错误页的展示。
type Reason string

const (
	// Reachable 表示收到了 HTTP 响应。
	Reachable Reason = ""
	DNS       Reason = "dns"
	Refused   Reason = "refused"
	Timeout   Reason = "timeout"
	TLS       Reason = "tls"
	Other     Reason = "other"
)

// Prober 探测地址是否可达。
type Prober struct {
	client *http.Client
}

// New 返回超时为 timeout 的 Prober；探测不跟随重定向，重定向响应本身即算可达。
func New(timeout time.Duration) *Prober {
	return &Prober{client: &http.Client{
		Timeout:       timeout,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}}
}

// Check 对 url 发 GET 请求，返回 Reachable 或不可达原因。url 必须是不带 token 的干净地址。
func (p *Prober) Check(url string) Reason {
	req, err := http.NewRequestWithContext(context.Background(), http.MethodGet, url, nil)
	if err != nil {
		return Other
	}
	resp, err := p.client.Do(req)
	if err != nil {
		return classify(err)
	}
	_ = resp.Body.Close() // 只关心是否收到响应，不读正文：流式响应会让探测一直等到超时
	return Reachable
}

// plainHTTPOnTLS 是 net/http 在 https 请求得到明文 HTTP 响应时返回的错误文本；
// 它替换了底层的 tls.RecordHeaderError，因此只能按文本识别。
const plainHTTPOnTLS = "server gave HTTP response to HTTPS client"

func classify(err error) Reason {
	var dnsErr *net.DNSError
	var netErr net.Error
	var unknownAuthority x509.UnknownAuthorityError
	var hostname x509.HostnameError
	var invalid x509.CertificateInvalidError
	var verification *tls.CertificateVerificationError
	var recordHeader tls.RecordHeaderError
	var alert tls.AlertError
	switch {
	case errors.As(err, &dnsErr):
		return DNS
	case isRefused(err):
		return Refused
	case errors.As(err, &unknownAuthority), errors.As(err, &hostname), errors.As(err, &invalid),
		errors.As(err, &verification), errors.As(err, &recordHeader), errors.As(err, &alert),
		strings.Contains(err.Error(), plainHTTPOnTLS):
		return TLS
	case errors.Is(err, context.DeadlineExceeded), errors.As(err, &netErr) && netErr.Timeout():
		return Timeout
	default:
		return Other
	}
}

func isRefused(err error) bool {
	for _, errno := range refusedErrnos {
		if errors.Is(err, errno) {
			return true
		}
	}
	return false
}
