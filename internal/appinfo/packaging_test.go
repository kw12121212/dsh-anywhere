package appinfo

import (
	"strings"
	"testing"
)

// 打包链路的配置断言：与 buildassets_test.go 相同的仓库文件断言模式，
// 锁定 package:universal:dmg、deb 版本注入、CI 打包工作流与 README 绕过说明的存在。

func TestDarwinUniversalDmgTask(t *testing.T) {
	taskfile := repoFile(t, "build/darwin/Taskfile.yml")
	for _, fragment := range []string{
		"package:universal:dmg:",
		"- task: package:universal",
		"- task: create:dmg",
	} {
		if !strings.Contains(taskfile, fragment) {
			t.Errorf("build/darwin/Taskfile.yml does not contain %q", fragment)
		}
	}
}

func TestNfpmVersionInjectedFromEnv(t *testing.T) {
	nfpm := repoFile(t, "build/linux/nfpm/nfpm.yaml")
	if !strings.Contains(nfpm, `version: "${VERSION}"`) {
		t.Error(`build/linux/nfpm/nfpm.yaml does not take version from the ${VERSION} environment variable`)
	}
	// deb/rpm/aur 生成任务必须把 Taskfile 的 VERSION 变量注入环境，且 maintainer 有兜底值，
	// 否则 CI 上无 git committer 环境变量时 nfpm 会失败。
	linuxTasks := repoFile(t, "build/linux/Taskfile.yml")
	for _, fragment := range []string{
		`VERSION: '{{.VERSION}}'`,
		`GIT_COMMITTER_NAME: '{{.GIT_COMMITTER_NAME | default "Keepwriting"}}'`,
		`GIT_COMMITTER_EMAIL: '{{.GIT_COMMITTER_EMAIL | default "keepwriting@users.noreply.github.com"}}'`,
	} {
		if !strings.Contains(linuxTasks, fragment) {
			t.Errorf("build/linux/Taskfile.yml does not contain %q", fragment)
		}
	}
	if strings.Contains(nfpm, "homepage: \"https://wails.io\"") {
		t.Error("build/linux/nfpm/nfpm.yaml still points homepage at the wails.io scaffolding value")
	}
}

func TestPackageWorkflowCallsTaskfileTasks(t *testing.T) {
	workflow := repoFile(t, ".github/workflows/package.yml")
	for _, fragment := range []string{
		"wails3 task darwin:package:universal:dmg",
		"wails3 task linux:create:appimage",
		"wails3 task linux:create:deb",
		"wails3 task windows:package",
		"upload-artifact",
		"libgtk-4-dev",
		"libwebkitgtk-6.0-dev",
		"choco install nsis",
		"if-no-files-found: error",
		"workflow_call",
	} {
		if !strings.Contains(workflow, fragment) {
			t.Errorf(".github/workflows/package.yml does not contain %q", fragment)
		}
	}
	// CI 不得复制打包命令：工作流里不应直接出现 go build 打包逻辑。
	if strings.Contains(workflow, "go build") {
		t.Error(".github/workflows/package.yml builds directly instead of delegating to wails3 tasks")
	}
	// 版本派生公式必须与 Taskfile 的 VERSION 变量字符级一致，否则产物版本与二进制版本漂移。
	rootTasks := repoFile(t, "Taskfile.yml")
	formula := `v=$(git describe --tags --match "v*" --exact-match 2>/dev/null) && echo "${v#v}" || echo dev`
	if !strings.Contains(workflow, formula) {
		t.Errorf(".github/workflows/package.yml does not derive the version with the same formula as Taskfile.yml (%q)", formula)
	}
	if !strings.Contains(rootTasks, formula) {
		t.Errorf("Taskfile.yml no longer derives VERSION with the expected formula %q", formula)
	}
	// 依赖 git describe 的 CI 作业必须完整拉取历史与 tag，否则 tag 匹配静默失败、产物被命名为 dev。
	if !strings.Contains(workflow, "fetch-depth: 0") {
		t.Error(".github/workflows/package.yml does not fetch full history for tag-derived versions")
	}
}

func TestReadmeDocumentsUnsignedInstallBypass(t *testing.T) {
	readme := repoFile(t, "README.md")
	for _, fragment := range []string{
		"Gatekeeper",
		"SmartScreen",
		"仍要运行",
		"仍要打开",
	} {
		if !strings.Contains(readme, fragment) {
			t.Errorf("README.md does not document %q for unsigned-install bypass", fragment)
		}
	}
}
