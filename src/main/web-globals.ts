// Electron 45 的 app:// 协议分发器在第一次处理请求时才懒加载 Node 的 Web 全局
// （Response/Headers/fetch 同属 undici 模块）。若这次懒加载与主进程启动末段赛跑，
// undici 会读到尚未就绪的 performance，抛 markResourceTiming 并使本地页以
// ERR_UNEXPECTED 失败（端到端表现为偶发 5 秒内等不到主窗口）。
// 在创建窗口前先构造一个 Response，让该模块在启动阶段完成初始化。
// Electron 上游修复该竞态后可删除本文件及调用。
export function warmUpWebGlobals(): void {
  void new Response(null, { status: 204 })
}
