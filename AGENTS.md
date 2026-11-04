# dsh-anywhere

## 项目概述

dsh-anywhere 是一个多平台（Windows / macOS / Linux）桌面客户端，用于接入用户指定的远程 DeepSeek harness URL，并在本地提供交互界面。

## 技术栈

- Electron + TypeScript（Node.js 22+）
  - 主进程 / preload / 本地页面位于 `src/main`、`src/preload`、`src/renderer`，由 electron-vite 构建到 `out/`
  - electron-builder 打包三平台安装包（配置在 `electron-builder.yml`，入口为 npm `dist:*` script，由 `scripts/dist.mts` 注入版本）
  - 单元测试用 Vitest（`*.test.ts` 与源码同目录），端到端用 Playwright 的 Electron 支持（`e2e/`）
  - 打包等构建期脚本放在 `scripts/`（`.mts`）
- Android 客户端（`android/`，自成一体的 Gradle 多模块工程）
  - `:app` 为 Kotlin + Jetpack Compose Material3 单 Activity 应用（applicationId `work.keepwriting.dshanywhere`，显示名 DSH Anywhere）
  - `android/core` 是不依赖 `android.*` 的纯 JVM Kotlin 独立构建（includeBuild 引用，坐标 `work.keepwriting:core`），URL 校验、同源判定、分流等纯逻辑下沉于此
  - versionName 构建期读取仓库根 `package.json` 的 version，versionCode 按 `MAJOR*10000+MINOR*100+PATCH` 派生；Gradle 中无第二个版本号
  - debug 与 release 统一用 `~/.config/dsh-anywhere/keys/` 下不入库的专用 release keystore 签名（`scripts/android-keystore.mts` 生成；`DSH_KEYSTORE_PROPS` 可指向配置），出包任务缺 keystore 即失败并提示
  - Android 约定见 `.strict-spec-driven/tech/android-client.yaml`：core 的 JUnit 测试进完整回归命令（只需 JDK 17+）；`:app`/AAR 构建与设备验收不进回归命令也不上 GitHub Actions

## 约定与约束

- 分支模型：主力开发在 `dev` 分支进行；`main` 为生产分支，仅通过从 `dev` 合入变更，禁止直接在 `main` 上开发提交。
- 版本管理：版本号唯一登记处是 `package.json` 的 `version`（语义化版本，自 1.0.0 起步；不兼容变更升 MAJOR、新功能升 MINOR、修复升 PATCH），构建期同一版本注入关于页、安装包元数据与文件名。发布流程：在 `dev` 升版（`npm version <版本> --no-git-tag-version` 同步 lockfile）→ 合入 `main` → 打 `v` + 版本的 tag；Release 工作流在打包前校验 tag 与 `package.json` 一致，不得引入第二版本来源。Android APK 发布：tag 推送并等 Release 工作流创建对应 Release 后，在 tag 提交上运行 `node scripts/android-release.mts`（本地构建签名 APK 与 .sha256 并上传；前置校验不满足会拒绝，详见 packaging 技术标准）。
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
node scripts/android-keystore.mts   # 生成 Android 统一签名 keystore（一次性，不入库，请自行备份）
android/gradlew -p android/core test          # core 纯 JVM 模块 JUnit 测试（只需 JDK 17+）
android/gradlew -p android assembleDebug      # 构建 :app 调试 APK（需 Android SDK 与 keystore）
```

完整回归（安装依赖 + 类型检查 + 构建 + 覆盖率门槛 80% + 端到端 + Android core JVM 测试）在本地执行，以 `.strict-spec-driven/config.yaml` 中的 `review_evidence.project_regression.command` 为准：

```bash
npm ci && npm run typecheck && npm run build && npm run test:coverage && npm run test:e2e && android/gradlew -p android/core test
```

回归测试不上 GitHub Actions；CI 只做打包：push 到 `main` 触发 Package 工作流（`package.yml`，三平台打包并上传产物），打 `v*` tag 触发 Release 工作流（`release.yml`，复用打包并发布）。Linux 上本地跑端到端需要 Xvfb 显示服务器与可用的 Electron 沙箱。
