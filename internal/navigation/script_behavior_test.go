package navigation

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"testing"
)

type scenarioResult struct {
	Messages   []string `json:"messages"`
	Assigned   []string `json:"assigned"`
	Prevented  bool     `json:"prevented"`
	OpenReturn any      `json:"openReturn"`
}

// runScript 在 node vm 中执行 Script，并用 testdata/harness.js 中的伪造 DOM 驱动各场景。
func runScript(t *testing.T) map[string]json.RawMessage {
	t.Helper()
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not installed")
	}
	path := filepath.Join(t.TempDir(), "script.js")
	if err := os.WriteFile(path, []byte(Script), 0o600); err != nil {
		t.Fatal(err)
	}
	out, err := exec.Command(node, filepath.Join("testdata", "harness.js"), path).Output()
	if err != nil {
		t.Fatalf("node harness failed: %v", err)
	}
	var results map[string]json.RawMessage
	if err := json.Unmarshal(out, &results); err != nil {
		t.Fatalf("decode harness output %q: %v", out, err)
	}
	return results
}

func TestScriptBehavior(t *testing.T) {
	results := runScript(t)
	ext := func(u string) []string { return []string{OpenExternalPrefix + u} }
	tests := map[string]scenarioResult{
		"external link":                {Messages: ext("https://example.org/docs"), Prevented: true},
		"same-origin link":             {},
		"same-origin blank":            {Assigned: []string{"https://dsh.example.test/sessions/2"}, Prevented: true},
		"same-origin named target":     {Assigned: []string{"https://dsh.example.test/sessions/3"}, Prevented: true},
		"same-origin _top":             {},
		"base target blank":            {Assigned: []string{"https://dsh.example.test/sessions/5"}, Prevented: true},
		"ctrl click same-origin":       {Assigned: []string{"https://dsh.example.test/sessions/6"}, Prevented: true},
		"middle click external":        {Messages: ext("https://example.org/middle"), Prevented: true},
		"right auxclick ignored":       {},
		"svg xlink href":               {Messages: ext("https://example.org/svg"), Prevented: true},
		"svg href":                     {Messages: ext("https://example.org/svg2"), Prevented: true},
		"area":                         {Messages: ext("https://example.org/area"), Prevented: true},
		"other port":                   {Messages: ext("https://dsh.example.test:8443/"), Prevented: true},
		"mailto ignored":               {},
		"no link":                      {},
		"already prevented":            {Prevented: true},
		"page takes over click":        {Prevented: true},
		"page reopens via window.open": {Messages: ext("https://example.org/dbl"), Prevented: true},
		"open external":                {Messages: ext("https://example.org/popup")},
		"open same-origin":             {Assigned: []string{"https://dsh.example.test/sessions/7"}},
		"open no args":                 {},
		"open empty":                   {},
		"open about blank":             {},
		"open file":                    {},
		"open javascript":              {},
	}
	for name, want := range tests {
		t.Run(name, func(t *testing.T) {
			raw, ok := results[name]
			if !ok {
				t.Fatalf("harness has no scenario %q", name)
			}
			var got scenarioResult
			if err := json.Unmarshal(raw, &got); err != nil {
				t.Fatal(err)
			}
			if len(got.Messages) == 0 {
				got.Messages = nil
			}
			if len(got.Assigned) == 0 {
				got.Assigned = nil
			}
			got.OpenReturn = nil // window.open 总是返回 null，JSON 中与 undefined 无法区分
			if !reflect.DeepEqual(got, want) {
				t.Errorf("got %+v, want %+v", got, want)
			}
		})
	}
	if len(results) != len(tests)+1 {
		t.Errorf("harness ran %d scenarios, test checks %d", len(results)-1, len(tests))
	}
}

func TestScriptInstallsListenersOnce(t *testing.T) {
	var got struct {
		Count int `json:"count"`
	}
	if err := json.Unmarshal(runScript(t)["listeners"], &got); err != nil {
		t.Fatal(err)
	}
	if got.Count != 2 {
		t.Errorf("listeners installed = %d, want 2 (click and auxclick)", got.Count)
	}
}
