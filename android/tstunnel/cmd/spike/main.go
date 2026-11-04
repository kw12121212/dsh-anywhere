// spike 是 android-tstunnel 变更的验证 spike（milestone android-app 的前置）：
// 用 tag:pca-client 的 tagged auth key 起 userspace tailnet 节点，经节点的
// SOCKS5 回环代理访问 dsh web（gamepc:13080，MagicDNS 名称），回答一个问题——
// ACL 是否放行 tag:pca-client 访问 gamepc:13080。
//
// dsh web 对无会话请求返回 401（固定正文），任何 HTTP 响应码都证明连通；
// 连不通（dial 超时/拒绝）即 ACL 未放行，需用户在 Tailscale 管理后台调整。
//
// auth key 一律经环境变量 DSH_SPIKE_AUTH_KEY 传入（命令行参数会出现在进程表）；
// key 不落盘、不回显。期望 tag 经 DSH_SPIKE_TAG（默认 tag:pca-client）。
//
// 退出码：0=PASS（收到 HTTP 响应且自身 tags 含期望 tag）；1=FAIL；2=用法/环境错误。
package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"golang.org/x/net/proxy"

	"work.keepwriting/tstunnel"
)

var cleanup = func() {}

func main() {
	hostname := flag.String("hostname", "dsh-spike", "节点主机名（tailnet 管理台里可识别）")
	dir := flag.String("dir", "", "节点状态目录；空=临时目录（一次性 spike 不复用状态）")
	target := flag.String("target", os.Getenv("DSH_SPIKE_TARGET"), "dsh web 的 tailnet 内地址，如 http://gamepc:13080/")
	upTimeout := flag.Duration("up-timeout", 5*time.Minute, "Up 阻塞上限（tailnet 开设备审批时是等待窗口）")
	verbose := flag.Bool("verbose", false, "把 tsnet 后端日志打到 stderr（排障）")
	flag.Parse()

	authKey := os.Getenv("DSH_SPIKE_AUTH_KEY")
	expectTag := os.Getenv("DSH_SPIKE_TAG")
	if expectTag == "" {
		expectTag = "tag:pca-client"
	}
	if authKey == "" {
		fmt.Fprintln(os.Stderr, "缺少 DSH_SPIKE_AUTH_KEY：经环境变量传入（不落盘）")
		os.Exit(2)
	}
	if *target == "" {
		fmt.Fprintln(os.Stderr, "缺少 -target（或 DSH_SPIKE_TARGET）：dsh web 的 tailnet 内地址")
		os.Exit(2)
	}
	if *dir == "" {
		tmp, err := os.MkdirTemp("", "dsh-spike-")
		if err != nil {
			fmt.Fprintf(os.Stderr, "FATAL 临时目录: %v\n", err)
			os.Exit(1)
		}
		cleanup = func() { os.RemoveAll(tmp) }
		*dir = filepath.Join(tmp, "state")
	}
	defer cleanup()

	fmt.Printf("spike.start hostname=%s target=%s tag=%s\n", *hostname, *target, expectTag)

	n := tstunnel.NewNode(*hostname, *dir, authKey)
	if *verbose {
		n.EnableStderrLogs()
	}
	t0 := time.Now()
	if err := n.Start(); err != nil {
		result(1, fmt.Sprintf("start: %v (errmsg=%s)", err, n.Errmsg()))
	}
	if err := upWithProgress(n, *upTimeout, t0); err != nil {
		result(1, fmt.Sprintf("up: %v (errmsg=%s)", err, n.Errmsg()))
	}
	fmt.Printf("spike.up ok +%v\n", time.Since(t0).Round(time.Millisecond))

	login, tags, err := selfIdentity(n)
	if err != nil {
		fmt.Printf("spike.identity err=%v\n", err)
	} else {
		fmt.Printf("spike.identity login=%s tags=%s\n", login, strings.Join(tags, ","))
	}

	addr, err := n.Loopback()
	if err != nil {
		result(1, fmt.Sprintf("loopback: %v", err))
	}
	cred, err := n.ProxyCredential()
	if err != nil {
		result(1, fmt.Sprintf("proxyCredential: %v", err))
	}

	// target 支持完整 URL（带 token 的地址直接请求，验证会话交换）。
	code, err := request(dialer(addr, cred), *target)
	if err != nil {
		result(1, fmt.Sprintf("request: %v（读法：dial 超时=ACL 未放行 tag:pca-client 到 gamepc:13080，需在 Tailscale 管理后台调整）", err))
	}
	fmt.Printf("spike.request http=%d（任何响应码都证明连通；401=dsh web 的无会话响应）\n", code)

	if !containsTag(tags, expectTag) {
		result(1, fmt.Sprintf("节点 tags=%v 不含 %s（key 不是带该 tag 生成的？）", tags, expectTag))
	}
	result(0, "tagged 节点经 SOCKS5 访问 dsh web 收到 HTTP 响应，ACL 放行 tag:pca-client")
}

// upWithProgress 阻塞等待登录，期间轮询 BackendState 并打印转移：设备审批
// 窗口内 tsnet.Up 静默等待，「卡在等批准」必须可见。
func upWithProgress(n *tstunnel.Node, timeout time.Duration, t0 time.Time) error {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	type upRes struct{ err error }
	ch := make(chan upRes, 1)
	go func() { ch <- upRes{n.Up()} }()
	stateCh := make(chan struct{})
	go func() {
		last := ""
		tick := time.NewTicker(3 * time.Second)
		defer tick.Stop()
		for {
			select {
			case <-stateCh:
				return
			case <-tick.C:
				if st, err := n.StatusJSON(); err == nil {
					if s := backendState(st); s != "" && s != last {
						fmt.Printf("backend state=%s +%v\n", s, time.Since(t0).Round(time.Millisecond))
						last = s
					}
				}
			}
		}
	}()
	select {
	case res := <-ch:
		close(stateCh)
		return res.err
	case <-ctx.Done():
		close(stateCh)
		return ctx.Err()
	}
}

// selfIdentity 经进程内 LocalAPI 读自身节点身份（login 与 tags）。
func selfIdentity(n *tstunnel.Node) (string, []string, error) {
	st, err := n.StatusJSON()
	if err != nil {
		return "", nil, err
	}
	// ipnstate.Status 的 Self 节点字段（Userspace 子集）：LoginName 与 Tags。
	type selfWrap struct {
		Self struct {
			LoginName string   `json:"LoginName"`
			Tags      []string `json:"Tags"`
		} `json:"Self"`
	}
	var w selfWrap
	if err := jsonUnmarshal(st, &w); err != nil {
		return "", nil, err
	}
	return w.Self.LoginName, w.Self.Tags, nil
}

func backendState(st string) string {
	var w struct {
		BackendState string `json:"BackendState"`
	}
	_ = jsonUnmarshal(st, &w)
	return w.BackendState
}

func jsonUnmarshal(s string, v any) error {
	return json.Unmarshal([]byte(s), v)
}

func dialer(addr, cred string) *http.Client {
	auth := &proxy.Auth{User: tstunnel.TstunnelSocksUser, Password: cred}
	d, err := proxy.SOCKS5("tcp", addr, auth, proxy.Direct)
	if err != nil {
		result(1, fmt.Sprintf("socks5 dialer: %v", err))
	}
	return &http.Client{
		Transport: &http.Transport{
			DialContext:           d.(proxy.ContextDialer).DialContext,
			TLSClientConfig:       &tls.Config{InsecureSkipVerify: false},
			DisableKeepAlives:     true,
			ResponseHeaderTimeout: 30 * time.Second,
		},
		Timeout: 60 * time.Second,
	}
}

func request(client *http.Client, url string) (int, error) {
	resp, err := client.Get(url)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<16))
	return resp.StatusCode, nil
}

func containsTag(tags []string, want string) bool {
	for _, t := range tags {
		if t == want {
			return true
		}
	}
	return false
}

func result(code int, reason string) {
	fmt.Printf("spike.result %s reason=%s\n", map[int]string{0: "PASS", 1: "FAIL"}[code], reason)
	cleanup()
	os.Exit(code)
}
