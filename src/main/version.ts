// 构建期 electron-vite 用 define 把 __APP_VERSION__ 替换为注入的版本字符串；
// 唯一登记处是 package.json 的 version（技术标准 packaging/package-version-single-source），
// 由 electron.vite.config.ts 直接读取注入，关于页、安装包元数据与文件名同源。
declare const __APP_VERSION__: string | undefined

// 应用版本号：define 未注入时兜底为 dev。经 electron-vite 构建的产物总会注入
// package.json 的版本号，dev 只是防御路径（如单元测试环境）。
export const APP_VERSION: string = typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : 'dev'
