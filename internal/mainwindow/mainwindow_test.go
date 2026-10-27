package mainwindow

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"dsh-anywhere/internal/config"
	"dsh-anywhere/internal/guard"
	"dsh-anywhere/internal/probe"
	"dsh-anywhere/internal/setup"
)

const windowID = 7

// navigation 记录一次 SetURL 调用以及调用时窗口是否已被标记为远程。
type navigation struct {
	url    string
	remote bool
}

type fakeWindow struct {
	guard *guard.Guard
	calls []navigation
	// down 记录不可达的干净地址及原因；probed 记录探测过的地址。
	down   map[string]probe.Reason
	probed []string
	// deferred 不为 nil 时后台任务被暂存而不立即执行，用于测试代次。
	deferred *[]func()
}

func (f *fakeWindow) check(url string) probe.Reason {
	f.probed = append(f.probed, url)
	return f.down[url]
}

func (f *fakeWindow) run(task func()) {
	if f.deferred != nil {
		*f.deferred = append(*f.deferred, task)
		return
	}
	task()
}

func (f *fakeWindow) window() Window {
	return Window{
		ID: func() uint { return windowID },
		SetURL: func(url string) {
			f.calls = append(f.calls, navigation{url: url, remote: f.guard.IsRemote(windowID)})
		},
	}
}

func newController(t *testing.T, savedURL string) (*Controller, *fakeWindow, *guard.Guard, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "dsh-anywhere", config.FileName)
	store := config.NewStore(path)
	if savedURL != "" {
		if _, err := store.Save(savedURL); err != nil {
			t.Fatal(err)
		}
	}
	g := guard.New()
	fw := &fakeWindow{guard: g, down: map[string]probe.Reason{}}
	return New(g, store, fw.window(), fw.check, fw.run), fw, g, path
}

func assertCalls(t *testing.T, got []navigation, want ...navigation) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("SetURL calls = %+v, want %+v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("SetURL call %d = %+v, want %+v", i, got[i], want[i])
		}
	}
}

func TestOpenRemoteMarksBeforeNavigating(t *testing.T) {
	c, fw, g, _ := newController(t, "")

	c.OpenRemote("http://127.0.0.1:13080/?token=abc123")

	assertCalls(t, fw.calls, navigation{url: "http://127.0.0.1:13080/?token=abc123", remote: true})
	if !g.IsRemote(windowID) {
		t.Error("window not marked remote after OpenRemote")
	}
}

func TestOpenLocalUnmarksAfterNavigating(t *testing.T) {
	c, fw, g, _ := newController(t, "")
	g.SetRemote(windowID, true)

	c.OpenLocal()

	assertCalls(t, fw.calls, navigation{url: setup.LocalURL, remote: true})
	if g.IsRemote(windowID) {
		t.Error("window still marked remote after OpenLocal")
	}
}

func TestReloadRemoteReopensSavedHarnessURL(t *testing.T) {
	c, fw, g, _ := newController(t, "https://dsh.example.test/")
	g.SetRemote(windowID, true)

	c.Reload()

	assertCalls(t, fw.calls, navigation{url: "https://dsh.example.test/", remote: true})
	if !g.IsRemote(windowID) {
		t.Error("window not marked remote after Reload")
	}
}

func TestReloadRemoteWithoutUsableConfigReturnsLocal(t *testing.T) {
	c, fw, g, path := newController(t, "https://dsh.example.test/")
	g.SetRemote(windowID, true)
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}

	c.Reload()

	assertCalls(t, fw.calls, navigation{url: setup.LocalURL, remote: true})
	if g.IsRemote(windowID) {
		t.Error("window still marked remote after falling back to local page")
	}
}

func TestReloadFromSetupPageWithConfigOpensHarness(t *testing.T) {
	// 例如初始化刚完成、探测尚未结束时按下 Ctrl+R：不应卡在初始化页。
	c, fw, g, _ := newController(t, "https://dsh.example.test/")

	c.Reload()

	assertCalls(t, fw.calls, navigation{url: "https://dsh.example.test/", remote: true})
	if !g.IsRemote(windowID) {
		t.Error("window not marked remote")
	}
}

func TestReloadWithoutConfigOpensSetup(t *testing.T) {
	c, fw, g, _ := newController(t, "")

	c.Reload()

	assertCalls(t, fw.calls, navigation{url: setup.LocalURL, remote: false})
	if g.IsRemote(windowID) {
		t.Error("window marked remote without config")
	}
}

func TestOpenLocalURLUnmarksAfterNavigating(t *testing.T) {
	c, fw, g, _ := newController(t, "")
	g.SetRemote(windowID, true)

	c.OpenLocalURL("/?view=reauth")

	assertCalls(t, fw.calls, navigation{url: "/?view=reauth", remote: true})
	if g.IsRemote(windowID) {
		t.Error("window still marked remote after OpenLocalURL")
	}
}

func TestRetrySessionAfterTokenNavigation(t *testing.T) {
	c, fw, g, _ := newController(t, "http://127.0.0.1:13080/")

	c.OpenRemote("http://127.0.0.1:13080/?token=abc123")
	if !c.RetrySession() {
		t.Fatal("RetrySession() = false after token navigation, want true")
	}
	if c.RetrySession() {
		t.Error("RetrySession() = true twice, want a single retry")
	}

	assertCalls(t, fw.calls,
		navigation{url: "http://127.0.0.1:13080/?token=abc123", remote: true},
		navigation{url: "http://127.0.0.1:13080/", remote: true})
	if !g.IsRemote(windowID) {
		t.Error("window not marked remote after retry")
	}
}

func TestRetrySessionOnceAfterCleanNavigation(t *testing.T) {
	c, fw, _, _ := newController(t, "https://dsh.example.test/")

	c.OpenRemote("https://dsh.example.test/") // 例如从本地提示页重新尝试 harness

	if !c.RetrySession() {
		t.Error("RetrySession() = false after clean navigation, want one retry")
	}
	if c.RetrySession() {
		t.Error("RetrySession() = true twice, want a single retry")
	}
	assertCalls(t, fw.calls,
		navigation{url: "https://dsh.example.test/", remote: true},
		navigation{url: "https://dsh.example.test/", remote: true})
}

func TestRetrySessionNotBeforeAnyNavigation(t *testing.T) {
	c, fw, _, _ := newController(t, "https://dsh.example.test/")
	if c.RetrySession() {
		t.Error("RetrySession() = true before any navigation, want false")
	}
	assertCalls(t, fw.calls)
}

func TestRetrySessionClearedByLaterNavigation(t *testing.T) {
	tests := map[string]func(c *Controller){
		"open local": func(c *Controller) { c.OpenLocalURL("/?view=reauth") },
	}
	for name, later := range tests {
		t.Run(name, func(t *testing.T) {
			c, _, _, _ := newController(t, "http://127.0.0.1:13080/")
			c.OpenRemote("http://127.0.0.1:13080/?token=abc123")
			later(c)
			if c.RetrySession() {
				t.Error("RetrySession() = true after a later navigation, want false")
			}
		})
	}
}

func TestRetrySessionWithoutUsableConfig(t *testing.T) {
	c, fw, _, path := newController(t, "http://127.0.0.1:13080/")
	c.OpenRemote("http://127.0.0.1:13080/?token=abc123")
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}

	if c.RetrySession() {
		t.Error("RetrySession() = true without usable config, want false")
	}
	if c.RetrySession() {
		t.Error("RetrySession() retried later, want the retry cleared")
	}
	assertCalls(t, fw.calls, navigation{url: "http://127.0.0.1:13080/?token=abc123", remote: true})
}

func TestReloadOnOtherLocalPageRetriesHarness(t *testing.T) {
	c, fw, g, _ := newController(t, "https://dsh.example.test/")
	g.SetRemote(windowID, true)
	c.OpenLocalURL("/?view=reauth")

	c.Reload()

	assertCalls(t, fw.calls,
		navigation{url: "/?view=reauth", remote: true},
		navigation{url: "https://dsh.example.test/", remote: true})
	if !g.IsRemote(windowID) {
		t.Error("window not marked remote after retrying harness")
	}
}

func TestReloadOnOtherLocalPageWithoutConfigOpensSetup(t *testing.T) {
	c, fw, g, path := newController(t, "https://dsh.example.test/")
	c.OpenLocalURL("/?view=reauth")
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}

	c.Reload()

	assertCalls(t, fw.calls,
		navigation{url: "/?view=reauth", remote: false},
		navigation{url: setup.LocalURL, remote: false})
	if g.IsRemote(windowID) {
		t.Error("window marked remote without usable config")
	}
}

func TestReloadGrantsFreshSingleRetry(t *testing.T) {
	c, _, _, _ := newController(t, "http://127.0.0.1:13080/")
	c.OpenRemote("http://127.0.0.1:13080/?token=abc123")
	if !c.RetrySession() {
		t.Fatal("RetrySession() = false after token navigation")
	}

	c.Reload() // 重新加载是一次新的导航，重新获得一次重试

	if !c.RetrySession() {
		t.Error("RetrySession() = false after Reload, want one retry")
	}
	if c.RetrySession() {
		t.Error("RetrySession() = true twice after Reload, want a single retry")
	}
}

func TestOpenRemoteProbesCleanURL(t *testing.T) {
	c, fw, _, _ := newController(t, "")

	c.OpenRemote("http://127.0.0.1:13080/?token=abc123")

	if len(fw.probed) != 1 || fw.probed[0] != "http://127.0.0.1:13080/" {
		t.Errorf("probed %q, want only the clean url", fw.probed)
	}
	assertCalls(t, fw.calls, navigation{url: "http://127.0.0.1:13080/?token=abc123", remote: true})
}

func TestOpenRemoteUnreachableShowsLoadFailedPage(t *testing.T) {
	c, fw, g, _ := newController(t, "")
	fw.down["http://127.0.0.1:13080/"] = probe.Refused

	c.OpenRemote("http://127.0.0.1:13080/?token=abc123")

	want := "/?view=load-failed&reason=refused&url=http%3A%2F%2F127.0.0.1%3A13080%2F"
	assertCalls(t, fw.calls, navigation{url: want, remote: false})
	if g.IsRemote(windowID) {
		t.Error("window marked remote after unreachable probe")
	}
	if c.RetrySession() {
		t.Error("RetrySession() = true after showing the load-failed page")
	}
}

func TestLoadFailedPageNeverContainsToken(t *testing.T) {
	c, fw, _, _ := newController(t, "")
	fw.down["https://dsh.example.test/a?b=1"] = probe.TLS

	c.OpenRemote("https://dsh.example.test/a?token=abc123&b=1")

	want := "/?view=load-failed&reason=tls&url=https%3A%2F%2Fdsh.example.test%2Fa%3Fb%3D1"
	assertCalls(t, fw.calls, navigation{url: want, remote: false})
}

func TestOpenRemoteUnparseableURLFailsSafely(t *testing.T) {
	c, fw, _, _ := newController(t, "")

	c.OpenRemote("http://dsh.example.test/%zz?token=abc123")

	if len(fw.probed) != 0 {
		t.Errorf("probed %q, want no probe for an unparseable url", fw.probed)
	}
	assertCalls(t, fw.calls, navigation{url: LoadFailedURL("", probe.Other), remote: false})
}

func TestLaterNavigationDiscardsPendingProbe(t *testing.T) {
	c, fw, _, _ := newController(t, "")
	var pending []func()
	fw.deferred = &pending

	c.OpenRemote("https://a.example.test/")
	c.OpenRemote("https://b.example.test/")
	for _, task := range pending {
		task()
	}

	assertCalls(t, fw.calls, navigation{url: "https://b.example.test/", remote: true})
}

func TestLocalNavigationDiscardsPendingProbe(t *testing.T) {
	c, fw, _, _ := newController(t, "")
	var pending []func()
	fw.deferred = &pending

	c.OpenRemote("https://a.example.test/")
	c.OpenLocalURL("/?view=reauth")
	for _, task := range pending {
		task()
	}

	assertCalls(t, fw.calls, navigation{url: "/?view=reauth", remote: false})
}

func TestRetrySessionDoesNotProbe(t *testing.T) {
	c, fw, _, _ := newController(t, "http://127.0.0.1:13080/")
	c.OpenRemote("http://127.0.0.1:13080/?token=abc123")
	fw.probed = nil

	if !c.RetrySession() {
		t.Fatal("RetrySession() = false")
	}
	if len(fw.probed) != 0 {
		t.Errorf("RetrySession probed %q, want no probe", fw.probed)
	}
}

func TestInitialURLAndStart(t *testing.T) {
	t.Run("configured", func(t *testing.T) {
		c, fw, _, _ := newController(t, "https://dsh.example.test/")
		if got := c.InitialURL(); got != ConnectingURL {
			t.Errorf("InitialURL() = %q, want %q", got, ConnectingURL)
		}
		c.Start()
		c.Start() // 之后的 WindowRuntimeReady 事件不再触发导航
		assertCalls(t, fw.calls, navigation{url: "https://dsh.example.test/", remote: true})
	})
	t.Run("unconfigured", func(t *testing.T) {
		c, fw, _, _ := newController(t, "")
		if got := c.InitialURL(); got != setup.LocalURL {
			t.Errorf("InitialURL() = %q, want %q", got, setup.LocalURL)
		}
		c.Start()
		assertCalls(t, fw.calls)
	})
	t.Run("configured but unreachable", func(t *testing.T) {
		c, fw, _, _ := newController(t, "https://dsh.example.invalid/")
		fw.down["https://dsh.example.invalid/"] = probe.DNS
		c.InitialURL()
		c.Start()
		assertCalls(t, fw.calls, navigation{url: LoadFailedURL("https://dsh.example.invalid/", probe.DNS), remote: false})
	})
}

func TestReloadFromConnectingOrFailedPageRetriesHarness(t *testing.T) {
	c, fw, _, _ := newController(t, "https://dsh.example.test/")
	fw.down["https://dsh.example.test/"] = probe.Timeout
	c.InitialURL()
	c.Start()
	delete(fw.down, "https://dsh.example.test/") // 服务恢复

	NewService(c).Retry()

	assertCalls(t, fw.calls,
		navigation{url: LoadFailedURL("https://dsh.example.test/", probe.Timeout), remote: false},
		navigation{url: "https://dsh.example.test/", remote: true})
}

func TestRetryWhileStillDownShowsErrorAgain(t *testing.T) {
	c, fw, _, _ := newController(t, "https://dsh.example.test/")
	fw.down["https://dsh.example.test/"] = probe.Refused
	c.Start()

	NewService(c).Retry()

	failed := navigation{url: LoadFailedURL("https://dsh.example.test/", probe.Refused), remote: false}
	assertCalls(t, fw.calls, failed, failed)
}

// TestConcurrentNavigationsKeepMarkConsistent 用真正的 goroutine 并发发起导航，
// 断言最后一次 SetURL 与远程标记一致（远程页面绝不会在未标记时显示）。
func TestConcurrentNavigationsKeepMarkConsistent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "dsh-anywhere", config.FileName)
	store := config.NewStore(path)
	if _, err := store.Save("https://dsh.example.test/"); err != nil {
		t.Fatal(err)
	}
	g := guard.New()
	var mu sync.Mutex
	var last navigation
	window := Window{
		ID: func() uint { return windowID },
		SetURL: func(url string) {
			mu.Lock()
			last = navigation{url: url, remote: g.IsRemote(windowID)}
			mu.Unlock()
		},
	}
	var wg sync.WaitGroup
	check := func(url string) probe.Reason {
		time.Sleep(time.Millisecond)
		if url == "https://down.example.test/" {
			return probe.Refused
		}
		return probe.Reachable
	}
	run := func(task func()) { wg.Add(1); go func() { defer wg.Done(); task() }() }
	c := New(g, store, window, check, run)

	var callers sync.WaitGroup
	for i := 0; i < 50; i++ {
		callers.Add(3)
		go func() { defer callers.Done(); c.OpenRemote("https://dsh.example.test/") }()
		go func() { defer callers.Done(); c.OpenRemote("https://down.example.test/") }()
		go func() { defer callers.Done(); c.OpenLocalURL("/?view=reauth") }()
	}
	callers.Wait()
	wg.Wait()

	mu.Lock()
	defer mu.Unlock()
	if remote := g.IsRemote(windowID); strings.HasPrefix(last.url, "https://") != remote {
		t.Errorf("last SetURL %q but window remote = %v", last.url, remote)
	}
}
