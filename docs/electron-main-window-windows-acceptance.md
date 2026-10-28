# electron-main-window：Windows 真机手工验收

本文档对应 strict 变更 `electron-main-window` 的验收任务 `verify-windows-manual`。

在 Linux 上，端到端测试已经覆盖导航范围与会话失效；Windows 上的行为只能在真机上确认。里程碑 `electron-migration` 约定：由用户在 WSL 的 Windows 宿主 **GAMEPC** 的桌面上，按 delta spec 的场景逐条验收。

- 对应规格：`navigation-scope`（`same-origin-stays`、`external-to-browser`、`only-web-urls-external`）、`session-auth`（`session-expired-page`）
- 预计耗时：约 5 分钟

## 已准备好的环境

| 位置 | 内容 |
|---|---|
| `C:\Users\wx766\dsh-anywhere-win\app` | 当前 Electron 版源码副本，已在 Windows 上执行 `npm ci` 与 `electron-vite build`，Electron 二进制已下载 |
| `C:\Users\wx766\dsh-anywhere-win\manual-dsh.mts` | 手工验收用的模拟 dsh（`http://127.0.0.1:18080/`），启动时会话为失效状态 |
| `C:\Users\wx766\dsh-anywhere-win\userdata` | 独立的应用数据目录，其中 `config.json` 指向模拟 dsh；不读写真实的 `%APPDATA%\dsh-anywhere` |

副本已同步到代码审查修复之后的源码。如果源码之后还有改动，需要重新同步并构建这份副本（见文末“重新准备环境”）。

## 步骤

### 1. 启动模拟 dsh

打开一个 cmd 窗口：

```bat
cd C:\Users\wx766\dsh-anywhere-win
node manual-dsh.mts
```

看到“模拟 dsh 已在 http://127.0.0.1:18080/ 运行，当前会话失效”即可。**这个窗口在整个验收过程中保持打开**，它会打印收到的每个请求。

### 2. 启动应用，检查会话失效页

再打开一个 cmd 窗口：

```bat
cd C:\Users\wx766\dsh-anywhere-win\app
node_modules\electron\dist\electron.exe . --user-data-dir=C:\Users\wx766\dsh-anywhere-win\userdata
```

- [ ] 窗口标题为 **DSH Anywhere**
- [ ] 主窗口显示本地的“**需要重新认证**”页：说明需要在 DSH 主机上运行 `dsh-url`，并有“打开设置”按钮
- [ ] 页面中不出现 dsh 返回的英文 401 原文，也不出现任何 token

> “打开设置”按钮在本变更中只渲染，点击没有反应属于预期：打开设置窗口的动作由后续变更 `electron-setup-settings` 接通。

### 3. 让会话有效，检查导航范围

在模拟 dsh 的窗口输入 `ok` 并回车，然后回到应用按 **Ctrl+R**（或菜单“视图 → 重新加载”）。

- [ ] 主窗口显示“模拟 harness：/”页面，上面列了 7 项
- [ ] 先在 harness 页面空白处点一下（让远程页面获得焦点），再按 **Ctrl+R**：模拟 dsh 窗口打印出新的请求，主窗口仍显示 harness 页面（快捷键在远程页面有焦点时也有效）

逐项操作。第 1～3 项跳转后，按 **Ctrl+R** 回到首页再测下一项：

| # | 操作 | 期望结果 | 结果 |
|---|---|---|---|
| 1 | 点“同源链接 /sessions/1” | 主窗口打开 `/sessions/1`，系统浏览器没有被打开 | ☐ |
| 2 | 点“同源 target=_blank 链接 /sessions/3” | 主窗口打开 `/sessions/3`，没有弹出新窗口 | ☐ |
| 3 | 点按钮 `window.open('/sessions/2')` | 主窗口打开 `/sessions/2`，没有弹出新窗口 | ☐ |
| 4a | **左键**点外部链接 `https://example.org/docs` | 系统默认浏览器打开该地址，主窗口仍显示原页面 | ☐ |
| 4b | **中键**点同一个外部链接 | 系统默认浏览器打开该地址，主窗口仍显示原页面 | ☐ |
| 5 | 点按钮 `window.open('https://example.org/popup')` | 系统默认浏览器打开该地址，主窗口不变，没有弹出新窗口 | ☐ |
| 6 | 点“不同端口 http://127.0.0.1:18081/” | 交给系统默认浏览器（浏览器里打不开没关系），主窗口不变 | ☐ |
| 7 | 点 `file:///C:/Windows/win.ini` | 什么都不发生：系统浏览器没有被打开，主窗口不变 | ☐ |

### 4. 让会话再次失效

在模拟 dsh 的窗口输入 `expire` 并回车，回到应用按 **Ctrl+R**。

- [ ] 主窗口回到“需要重新认证”页

### 5. 结束

关闭应用窗口，在模拟 dsh 的窗口按 Ctrl+C 退出。

## 反馈结果

把结果告诉 Claude 即可，例如“全部通过”，或“第 4b 项中键没有打开浏览器”。如有截图更好。

收到结果后，Claude 会把结论记入 `verify-windows-manual` 任务，然后运行 `strict-spec-cli verify electron-main-window` 并归档该变更。如有失败项，会先修复，再请你复验对应的条目。

## 重新准备环境（仅在源码改动后需要）

在 WSL 的仓库根目录执行：

```bash
W=/mnt/c/Users/wx766/dsh-anywhere-win
rsync -a --delete --exclude node_modules --exclude out \
  package.json package-lock.json electron.vite.config.ts tsconfig.json src "$W/app/"
cd "$W/app" && /mnt/c/Windows/System32/cmd.exe /c "npm ci --no-audit --no-fund && npx electron-vite build"
```

`userdata\config.json` 不需要改动，内容为：

```json
{
  "harnessURL": "http://127.0.0.1:18080/"
}
```
