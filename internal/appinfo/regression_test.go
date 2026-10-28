package appinfo

import (
	"strings"
	"testing"
)

// 回归工作流的结构断言：锁定三平台逐字执行 config.yaml 的完整回归命令（含 Electron 部分），
// 以及 Linux 在回归步骤之前准备显示服务器与 Electron 沙箱，而不是改写命令。

func regressionCommand(t *testing.T) string {
	t.Helper()
	for _, line := range strings.Split(repoFile(t, ".strict-spec-driven/config.yaml"), "\n") {
		if command, ok := strings.CutPrefix(strings.TrimRight(line, "\r"), "    command: "); ok {
			return command
		}
	}
	t.Fatal(".strict-spec-driven/config.yaml has no review_evidence.project_regression.command")
	return ""
}

func TestRegressionWorkflowRunsConfiguredCommandVerbatim(t *testing.T) {
	// Windows 检出可能把换行转成 CRLF，按行比较前统一换行符。
	workflow := strings.ReplaceAll(repoFile(t, ".github/workflows/regression.yml"), "\r\n", "\n")
	command := regressionCommand(t)
	if !strings.Contains(workflow, "\n          "+command+"\n") {
		t.Error(".github/workflows/regression.yml does not run the configured regression command verbatim")
	}
	for _, fragment := range []string{
		"npm run typecheck", "npm run build", "npm run test:coverage", "npm run test:e2e",
	} {
		if !strings.Contains(command, fragment) {
			t.Errorf("configured regression command does not include the Electron step %q", fragment)
		}
	}
	for _, fragment := range []string{
		"- ubuntu-latest", "- macos-latest", "- windows-latest",
		"shell: bash",
		"frontend/package-lock.json",
		"            package-lock.json",
	} {
		if !strings.Contains(workflow, fragment) {
			t.Errorf(".github/workflows/regression.yml does not contain %q", fragment)
		}
	}
}

func TestRegressionWorkflowPreparesLinuxDisplayBeforeRegression(t *testing.T) {
	workflow := repoFile(t, ".github/workflows/regression.yml")
	regression := strings.Index(workflow, "- name: Full project regression")
	if regression < 0 {
		t.Fatal(".github/workflows/regression.yml has no Full project regression step")
	}
	for _, fragment := range []string{
		"Xvfb :99",
		`echo "DISPLAY=:99" >> "$GITHUB_ENV"`,
		"kernel.apparmor_restrict_unprivileged_userns=0",
	} {
		at := strings.Index(workflow, fragment)
		if at < 0 {
			t.Errorf(".github/workflows/regression.yml does not contain %q", fragment)
		} else if at > regression {
			t.Errorf("%q must be prepared before the Full project regression step", fragment)
		}
	}
	// 显示服务器与沙箱由前置步骤提供：命令保持三平台一致，也不以关闭沙箱绕过安全基线。
	for _, forbidden := range []string{"xvfb-run", "--no-sandbox"} {
		if strings.Contains(workflow, forbidden) {
			t.Errorf(".github/workflows/regression.yml must not use %q", forbidden)
		}
	}
}
