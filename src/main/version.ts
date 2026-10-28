// 构建期 electron-vite 用 define 把 __APP_VERSION__ 替换为注入的版本字符串；
// 注入规则（v 前缀标签去掉前缀，未用标签构建为 dev）见设计决策 version-injection，
// 流水线接线由 electron-release 计划变更完成。
declare const __APP_VERSION__: string | undefined

// 应用版本号：未注入时为 dev（与 Go 版 internal/appinfo.Version 同语义）。
export const APP_VERSION: string = typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : 'dev'
