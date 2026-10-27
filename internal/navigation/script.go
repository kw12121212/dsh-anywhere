package navigation

import _ "embed"

// Script 是注入主窗口页面的导航拦截脚本，通过 WebviewWindowOptions.JS 设置。
// Wails v3.0.0 在 Linux 与 macOS 上每次页面加载完成后执行它；Windows 上远程页面不执行。
//
//go:embed script.js
var Script string
