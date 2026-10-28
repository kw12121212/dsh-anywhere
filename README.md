# DSH Anywhere

多平台（Windows / macOS / Linux）桌面客户端，接入用户指定的远程 DeepSeek harness URL，在本地提供交互界面。

- 技术栈：Electron + TypeScript（electron-vite 构建，electron-builder 打包三平台安装包）。
- 远程 harness URL 与凭据只来自用户配置，不内置任何地址或密钥。

## 安装

从 [Releases](https://github.com/kw12121212/dsh-anywhere/releases) 下载对应平台的安装包：

| 平台 | 安装包 |
| --- | --- |
| Windows | `dsh-anywhere_<版本>_windows_amd64_installer.exe`（NSIS 安装器） |
| macOS | `dsh-anywhere_<版本>_macos_universal.dmg`（arm64 + amd64 通用） |
| Linux | `dsh-anywhere_<版本>_linux_amd64.deb` 或 `dsh-anywhere_<版本>_linux_amd64.AppImage` |

## 首次启动：绕过未签名警告

安装包未做代码签名与公证，首次启动会触发系统警告，属预期行为：

### macOS（Gatekeeper）

打开 dmg 并拖入「应用程序」后，首次启动会提示「无法打开，因为无法验证开发者」。任选其一：

1. **右键打开**：在「应用程序」中对 DSH Anywhere 右键，选择「打开」，再在弹窗中确认「打开」；
2. **系统设置放行**：先双击启动一次（弹窗中点「完成」或关闭），然后打开「系统设置 → 隐私与安全性」，在底部找到关于 DSH Anywhere 的提示，点「仍要打开」。

命令行等价方式（可选）：`xattr -cr /Applications/DSH\ Anywhere.app`

### Windows（SmartScreen）

运行安装器或首次启动时，SmartScreen 可能提示「Windows 已保护你的电脑」。点击 **「更多信息」**，再点击 **「仍要运行」** 即可继续。

## 从源码构建

需要 Node.js 22+（`engines` 约束见 `package.json`）：

```bash
npm install    # 安装依赖
npm run dev    # 开发模式（electron-vite）
npm run build  # 生产构建（输出 out/）
npm run dist:win   # 打包当前版本 Windows NSIS 安装包（dist:mac / dist:linux 同理）
```

打包版本号来自 git tag 精确匹配（去 `v` 前缀，未打标签时为 `dev`），同一版本号注入应用内关于页、安装包元数据与文件名。完整回归与打包流程见 `.github/workflows/`。
