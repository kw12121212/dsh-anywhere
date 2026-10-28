package appinfo

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// repoFile 读取仓库中的文件；测试的工作目录是 internal/appinfo。
func repoFile(t *testing.T, rel string) string {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("..", "..", rel))
	if err != nil {
		t.Fatalf("read %s: %v", rel, err)
	}
	return string(data)
}

// configValue 取出 build/config.yml 中 info 段的 `key: "value"` 值。
func configValue(t *testing.T, config, key string) string {
	t.Helper()
	prefix := key + `: "`
	for _, line := range strings.Split(config, "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, prefix) {
			continue
		}
		rest := strings.TrimPrefix(line, prefix)
		if end := strings.Index(rest, `"`); end >= 0 {
			return rest[:end]
		}
	}
	t.Fatalf("build/config.yml has no %q in info", key)
	return ""
}

// generatedAssets 是 wails3 update build-assets 生成的、随仓库提交的平台资源。
// build/linux/dsh-anywhere.desktop 由构建任务生成且被 .gitignore 忽略，因此不在其中。
var generatedAssets = []string{
	"build/darwin/Info.plist",
	"build/darwin/Info.dev.plist",
	"build/windows/info.json",
	"build/windows/nsis/wails_tools.nsh",
	"build/linux/desktop",
	"build/linux/nfpm/nfpm.yaml",
}

func TestBuildConfigUsesProductIdentity(t *testing.T) {
	config := repoFile(t, "build/config.yml")

	if got := configValue(t, config, "productName"); got != DisplayName {
		t.Errorf("build/config.yml productName = %q, want %q", got, DisplayName)
	}
	if got := configValue(t, config, "productIdentifier"); got != Identifier {
		t.Errorf("build/config.yml productIdentifier = %q, want %q", got, Identifier)
	}
	for _, key := range []string{"companyName", "copyright", "description", "comments"} {
		value := configValue(t, config, key)
		if value == "" {
			t.Errorf("build/config.yml %s is empty", key)
		}
		if strings.Contains(value, "My Company") || strings.Contains(value, "A program that does X") {
			t.Errorf("build/config.yml %s still holds scaffolding text %q", key, value)
		}
	}
}

func TestGeneratedAssetsMatchBuildConfig(t *testing.T) {
	config := repoFile(t, "build/config.yml")
	product := configValue(t, config, "productName")
	identifier := configValue(t, config, "productIdentifier")
	company := configValue(t, config, "companyName")
	copyright := configValue(t, config, "copyright")
	description := configValue(t, config, "description")

	want := map[string][]string{
		"build/darwin/Info.plist": {
			"<string>" + product + "</string>",
			"<string>" + identifier + "</string>",
			"<string>" + copyright + "</string>",
			"<string>" + description + "</string>",
		},
		"build/darwin/Info.dev.plist": {
			"<string>" + product + "</string>",
			"<string>" + identifier + "</string>",
			"<string>" + copyright + "</string>",
			// dev plist 的 NSAppTransportSecurity 是模板外的自定义键，重新生成时必须保留。
			"NSAllowsLocalNetworking",
		},
		"build/windows/info.json": {
			`"ProductName": "` + product + `"`,
			`"CompanyName": "` + company + `"`,
			`"LegalCopyright": "` + copyright + `"`,
			`"FileDescription": "` + description + `"`,
		},
		"build/windows/nsis/wails_tools.nsh": {
			`!define INFO_PRODUCTNAME "` + product + `"`,
			`!define INFO_COMPANYNAME "` + company + `"`,
			`!define INFO_COPYRIGHT "` + copyright + `"`,
		},
		"build/linux/nfpm/nfpm.yaml": {
			`description: "` + description + `"`,
			`vendor: "` + company + `"`,
		},
		"build/linux/desktop": {
			"Name=" + product,
			"Comment=" + description,
		},
	}
	for relative, fragments := range want {
		content := repoFile(t, relative)
		for _, fragment := range fragments {
			if !strings.Contains(content, fragment) {
				t.Errorf("%s does not contain %q", relative, fragment)
			}
		}
	}
}

func TestGeneratedAssetsHaveNoScaffoldingIdentity(t *testing.T) {
	placeholders := []string{
		"My Product", "My Company", "com.example.dshanywhere", "com.mycompany.myproduct",
		"A program that does X", "Some Product Comments", "(c) 2025",
	}
	for _, relative := range generatedAssets {
		content := repoFile(t, relative)
		for _, placeholder := range placeholders {
			if strings.Contains(content, placeholder) {
				t.Errorf("%s still contains scaffolding value %q", relative, placeholder)
			}
		}
	}
}

func TestLinuxDesktopEntryShowsDisplayName(t *testing.T) {
	content := repoFile(t, "build/linux/desktop")
	if strings.Contains(content, "Name="+Name+"\n") {
		t.Errorf("build/linux/desktop shows the internal name %q instead of %q", Name, DisplayName)
	}
	if !strings.Contains(content, "Name="+DisplayName) {
		t.Errorf("build/linux/desktop does not show display name %q", DisplayName)
	}

	// 实际打包用的 dsh-anywhere.desktop 由构建任务生成并被 .gitignore 忽略，
	// 因此测试核对任务参数：显示名取自根 Taskfile 的 APP_DISPLAY_NAME，Exec 指向二进制。
	taskfile := repoFile(t, "build/linux/Taskfile.yml")
	for _, fragment := range []string{
		`-name "{{.DISPLAY_NAME}}"`,
		`DISPLAY_NAME: '{{.APP_DISPLAY_NAME}}'`,
		`-exec "{{.EXEC}}"`,
	} {
		if !strings.Contains(taskfile, fragment) {
			t.Errorf("build/linux/Taskfile.yml does not contain %q", fragment)
		}
	}
}

func TestReleaseBuildsInjectVersion(t *testing.T) {
	injection := "-X dsh-anywhere/internal/appinfo.Version={{.VERSION}}"
	for _, relative := range []string{
		"build/Taskfile.yml",
		"build/linux/Taskfile.yml",
		"build/darwin/Taskfile.yml",
		"build/windows/Taskfile.yml",
	} {
		if content := repoFile(t, relative); !strings.Contains(content, injection) {
			t.Errorf("%s does not inject the release version with %q", relative, injection)
		}
	}

	// 交叉编译走 Docker 镜像，版本号由宿主任务传入并在镜像内追加到 ldflags。
	if content := repoFile(t, "build/docker/Dockerfile.cross"); !strings.Contains(content, "-X dsh-anywhere/internal/appinfo.Version=${VERSION}") {
		t.Error("build/docker/Dockerfile.cross does not inject VERSION into cross builds")
	}
	for _, relative := range []string{
		"build/linux/Taskfile.yml",
		"build/darwin/Taskfile.yml",
		"build/windows/Taskfile.yml",
	} {
		if content := repoFile(t, relative); !strings.Contains(content, `-e VERSION="{{.VERSION}}"`) {
			t.Errorf("%s does not pass VERSION to the cross-build container", relative)
		}
	}

	root := repoFile(t, "Taskfile.yml")
	for _, fragment := range []string{
		`APP_DISPLAY_NAME: "` + DisplayName + `"`,
		"git describe --tags",
		"VERSION:",
	} {
		if !strings.Contains(root, fragment) {
			t.Errorf("Taskfile.yml does not contain %q", fragment)
		}
	}
}
