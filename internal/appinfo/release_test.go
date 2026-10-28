package appinfo

import (
	"strings"
	"testing"
)

// 发布工作流的结构断言：锁定 tag 触发、复用打包工作流、产物校验、
// 草稿→全量上传→发布、失败清理与发布说明绕过步骤的存在。

func TestReleaseWorkflowReusesPackageWorkflow(t *testing.T) {
	workflow := repoFile(t, ".github/workflows/release.yml")
	for _, fragment := range []string{
		"tags: ['v*']",
		"uses: ./.github/workflows/package.yml",
		"needs: package",
		"merge-multiple: true",
	} {
		if !strings.Contains(workflow, fragment) {
			t.Errorf(".github/workflows/release.yml does not contain %q", fragment)
		}
	}
	// 发布工作流不得复制打包命令。
	for _, forbidden := range []string{"wails3 task", "go build"} {
		if strings.Contains(workflow, forbidden) {
			t.Errorf(".github/workflows/release.yml duplicates packaging logic (%q) instead of reusing package.yml", forbidden)
		}
	}
	// 复用的前提是 package.yml 保留 workflow_call 触发器，上游删掉它则发布断链。
	packageWorkflow := repoFile(t, ".github/workflows/package.yml")
	if !strings.Contains(packageWorkflow, "workflow_call") {
		t.Error(".github/workflows/package.yml no longer exposes the workflow_call trigger that release.yml depends on")
	}
	// 唯一允许的外部 action 是官方 download-artifact；发布步骤只用 gh CLI。
	for _, line := range strings.Split(workflow, "\n") {
		if strings.Contains(line, "uses:") &&
			!strings.Contains(line, "actions/download-artifact") &&
			!strings.Contains(line, "uses: ./.github/workflows/package.yml") {
			t.Errorf(".github/workflows/release.yml uses a third-party action %q instead of gh CLI", strings.TrimSpace(line))
		}
	}
}

func TestReleaseWorkflowDraftsVerifiesThenPublishes(t *testing.T) {
	workflow := repoFile(t, ".github/workflows/release.yml")
	for _, fragment := range []string{
		// release job 需要写权限创建/编辑 Release。
		"contents: write",
		// 四类产物按 tag 版本逐一校验，防止 dev 版产物被发布。
		"dsh-anywhere_${VERSION}_windows_amd64_installer.exe",
		"dsh-anywhere_${VERSION}_macos_universal.dmg",
		"dsh-anywhere_${VERSION}_linux_amd64.AppImage",
		"dsh-anywhere_${VERSION}_linux_amd64.deb",
		"shasum -a 256",
		"SHA256SUMS",
		// 先草稿后发布，失败（或取消）只删除草稿，不误删已发布版本。
		"gh release create \"${GITHUB_REF_NAME}\" --draft --verify-tag",
		"gh release edit \"${GITHUB_REF_NAME}\" --draft=false",
		"if: failure() || cancelled()",
		"--json isDraft --jq .isDraft",
		"gh release delete \"${GITHUB_REF_NAME}\" --yes",
	} {
		if !strings.Contains(workflow, fragment) {
			t.Errorf(".github/workflows/release.yml does not contain %q", fragment)
		}
	}
}

func TestReleaseNotesDocumentBypassSteps(t *testing.T) {
	workflow := repoFile(t, ".github/workflows/release.yml")
	for _, fragment := range []string{
		"Gatekeeper",
		"SmartScreen",
		"仍要打开",
		"仍要运行",
	} {
		if !strings.Contains(workflow, fragment) {
			t.Errorf(".github/workflows/release.yml release notes do not document %q", fragment)
		}
	}
}
