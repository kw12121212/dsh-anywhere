# dsh-anywhere

## 项目概述

dsh-anywhere 是一个多平台（Windows / macOS / Linux）桌面客户端，用于接入用户指定的远程 DeepSeek harness URL，并在本地提供交互界面。

## 技术栈

- Go 1.27
- Wails 3.0（`github.com/wailsapp/wails/v3`）
  - Go 后端服务通过 Wails bindings 暴露给前端
  - 前端为 npm 构建的 Web UI，产物 `frontend/dist` 通过 `go:embed` 嵌入单一可执行文件
  - 构建由 `wails3` CLI 与 `Taskfile.yml` 驱动

## 约定与约束

- 远程 DeepSeek harness URL 与凭据只能来自用户配置，禁止硬编码、提交到仓库或输出到日志。
- `frontend/bindings/` 为自动生成代码，不得手改；修改 Go 服务后用 `wails3 generate bindings` 重新生成。
- 平台相关代码放在对应构建标签 / `build/<platform>/` 下，保持三平台均可构建。
- 需求与变更走 strict spec 流程，规范位于 `.strict-spec-driven/`；通过 `strict-spec-cli` 修改其中的 YAML，不要手工编辑。

## 常用命令

```bash
go install github.com/wailsapp/wails/v3/cmd/wails3@v3.0.0   # 安装与 go.mod 一致的 wails3 CLI（装到 $(go env GOPATH)/bin）
wails3 dev                     # 开发模式（热重载）
wails3 build                   # 生产构建，输出 bin/dsh-anywhere
wails3 generate bindings -clean=true -ts -i   # 修改 Go 服务后重新生成 frontend/bindings（与 Taskfile 参数一致，wails3 build 也会自动执行）
wails3 task --list             # 列出 Taskfile.yml 中的任务（打包、签名等）
go test -race ./internal/...   # 只跑 internal 包的单元测试
```

完整回归（构建 + 全量测试 + 覆盖率门槛 80%，不含 `main.go`），以 `.strict-spec-driven/config.yaml` 中的 `review_evidence.project_regression.command` 为准：

```bash
(cd frontend && npm ci && npm run build) && go vet ./... && go build ./... \
  && go test -race -covermode=atomic -coverprofile=coverage.out ./... \
  && grep -v -E '^[^:]*/main\.go:' coverage.out > coverage.filtered.out \
  && go tool cover -func=coverage.filtered.out   # 由 config 中的 awk 检查 total ≥ 80%
```

Linux 上构建需要安装 GTK4 / WebKitGTK 6.0 开发库（Ubuntu：`libgtk-4-dev libwebkitgtk-6.0-dev`，Wails v3.0.0 默认；GTK3 需加 `-tags gtk3`，回归命令不带该标签）；`-race` 需要启用 cgo。
