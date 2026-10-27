// 注入主窗口每个加载完成的顶层页面：页面是 dsh 的会话失效响应（401 固定文本）时通知 Go 侧。
// 消息带上每次启动随机生成的 nonce：Wails 只在主 frame 执行窗口 JS 选项，而 Linux 上原生消息
// 不区分 frame，子 iframe 不知道 nonce，无法伪造失效消息。
(function () {
    "use strict";
    var MARKER = "dsh web authentication required; reopen the URL printed by dsh web.";
    if (document.contentType !== "text/plain" || !document.body) return;
    if (document.body.textContent.trim() !== MARKER) return;
    var message = "dsh-anywhere:session-expired:__DSH_NONCE__";
    if (window._wails && typeof window._wails.invoke === "function") {
        window._wails.invoke(message);
    } else if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.external) {
        window.webkit.messageHandlers.external.postMessage(message);
    }
})();
