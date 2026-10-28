# dsh-anywhere

## 项目概述

dsh-anywhere 是一个多平台（Windows / macOS / Linux）桌面客户端，用于接入用户指定的远程 DeepSeek harness URL，并在本地提供交互界面。

## 技术栈

- Electron + TypeScript（Node.js 22+）
  - 主进程 / preload / 本地页面位于 `src/main`、`src/preload`、`src/renderer`，由 electron-vite 构建到 `out/`
  - electron-builder 打包三平台安装包（配置在 `electron-builder.yml`，入口为 npm `dist:*` script，由 `scripts/dist.mts` 注入版本）
  - 单元测试用 Vitest（`*.test.ts` 与源码同目录），端到端用 Playwright 的 Electron 支持（`e2e/`）
  - 打包等构建期脚本放在 `scripts/`（`.mts`）

## 约定与约束

- 分支模型：主力开发在 `dev` 分支进行；`main` 为生产分支，仅通过从 `dev` 合入变更，禁止直接在 `main` 上开发提交。
- 远程 DeepSeek harness URL 与凭据只能来自用户配置，禁止硬编码、提交到仓库或输出到日志。
- 需求与变更走 strict spec 流程，规范位于 `.strict-spec-driven/`；通过 `strict-spec-cli` 修改其中的 YAML，不要手工编辑。
- 对 `.github/workflows/` 的每次变更需通过 actionlint 本地校验；工作流结构由 `scripts/workflows.test.mts` 断言锁定。

## 常用命令

```bash
npm install        # 安装依赖
npm run dev        # 开发模式（electron-vite，热重载）
npm run build      # 生产构建（输出 out/）
npm run typecheck  # TypeScript 类型检查（主进程 / 渲染 / e2e 三份 tsconfig）
npm run test       # Vitest 单元测试
npm run test:coverage   # 单元测试 + 行覆盖率（门槛 80%）
npm run test:e2e   # Playwright 端到端（Electron）
npm run dist:win   # 打包 Windows NSIS 安装包（dist:mac / dist:linux 同理，输出 dist/）
```

完整回归（安装依赖 + 类型检查 + 构建 + 覆盖率门槛 80% + 端到端）在本地执行，以 `.strict-spec-driven/config.yaml` 中的 `review_evidence.project_regression.command` 为准：

```bash
npm ci && npm run typecheck && npm run build && npm run test:coverage && npm run test:e2e
```

回归测试不上 GitHub Actions；CI 只做打包：push 到 `main` 触发 Package 工作流（`package.yml`，三平台打包并上传产物），打 `v*` tag 触发 Release 工作流（`release.yml`，复用打包并发布）。Linux 上本地跑端到端需要 Xvfb 显示服务器与可用的 Electron 沙箱。
