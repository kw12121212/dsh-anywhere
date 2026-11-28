import DshCore
import SwiftUI
import WebKit

/// 沙箱 HTML 卡（macos-cards-dock）：独立 WKWebView——非持久化数据存储、无任何脚本桥接
///（不注册 WKScriptMessageHandler）、导航决定策略全部取消（about:blank 主框架除外）、
/// WKContentRuleList 阻断全部网络请求、不授权本地文件读取。加载卡片自带的 HTML 字符串；
/// 规则编译完成前不加载内容（fail-closed）。
struct SandboxHtmlView: NSViewRepresentable {
    let html: String
    /// 卡片声明的显示高度（正数）；缺省或越界取默认。
    var declaredHeight: Double?

    /// 渲染高度：卡片声明值夹在 80–600，缺省 240。
    var height: CGFloat {
        let h = declaredHeight ?? 240
        return CGFloat(min(max(h, 80), 600))
    }

    func makeNSView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.suppressesIncrementalRendering = true
        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.setValue(false, forKey: "drawsBackground")
        context.coordinator.pendingHtml = html
        // 先编译阻断规则再加载内容；编译失败按 fail-closed 重试，不在无规则状态下加载。
        compileRuleThenLoad(config: config, web: web, coordinator: context.coordinator)
        return web
    }

    func updateNSView(_ web: WKWebView, context: Context) {
        // 内容随卡变化时排队最新值；实际加载由协调器在规则就绪后统一执行。
        context.coordinator.pendingHtml = html
        context.coordinator.flush(web: web)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    private func compileRuleThenLoad(config: WKWebViewConfiguration, web: WKWebView, coordinator: Coordinator) {
        WKContentRuleListStore.default().compileContentRuleList(
            forIdentifier: "dsh-sandbox-block-network",
            encodedContentRuleList: SandboxHtmlView.blockRuleJson
        ) { list, _ in
            if let list {
                config.userContentController.add(list)
                coordinator.ruleReady = true
                coordinator.flush(web: web)
            } else {
                // 规则编译失败：fail-closed，重试编译（内容保持不加载）。
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    compileRuleThenLoad(config: config, web: web, coordinator: coordinator)
                }
            }
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var pendingHtml: String?
        var ruleReady = false
        var loadedHtml: String?

        /// 规则就绪后加载最新内容（同卡不重复加载）。
        func flush(web: WKWebView) {
            guard ruleReady, let html = pendingHtml, html != loadedHtml else { return }
            loadedHtml = html
            web.loadHTMLString(html, baseURL: nil)
        }

        /// 导航全拦截：只放行 loadHTMLString 的 about:blank 主框架，其余（链接跳转、
        /// 表单提交、window.open、file: 等）一律取消——沙箱内零导航。
        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            if navigationAction.targetFrame?.isMainFrame == true,
               navigationAction.request.url?.scheme?.lowercased() == "about" {
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
            }
        }
    }

    /// 阻断一切网络请求。不带 resource-type（合法值集合里没有 xhr/websocket/other，
    /// 写错会导致整条规则编译失败、零阻断），省略即匹配全部资源类型。
    private static let blockRuleJson = #"{"trigger":{"url-filter":".*"},"action":{"type":"block"}}"#
}
