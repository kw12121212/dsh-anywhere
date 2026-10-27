// 注入主窗口每个加载完成的顶层页面：同源导航留在主窗口，非同源的 http/https 目标交给 Go 侧用系统浏览器打开。
// 页面可以预先设置 __dshNavigation 来跳过安装；这只影响页面自身的链接，不是安全边界（本地服务由 guard 保护）。
(function () {
    "use strict";
    if (window.__dshNavigation) return;
    window.__dshNavigation = true;

    var PREFIX = "dsh-anywhere:open-external:";
    var XLINK = "http://www.w3.org/1999/xlink";
    var SAME_WINDOW_TARGETS = {"": true, "_self": true, "_top": true, "_parent": true};

    function post(message) {
        if (window._wails && typeof window._wails.invoke === "function") {
            window._wails.invoke(message);
        } else if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.external) {
            window.webkit.messageHandlers.external.postMessage(message);
        }
    }

    // 同一任务内对同一地址只发送一次：页面自己的点击处理可能在拦截之后再调用 window.open(href)。
    var sent = {};
    function openExternal(href) {
        if (sent[href]) return;
        sent[href] = true;
        setTimeout(function () { delete sent[href]; }, 0);
        post(PREFIX + href);
    }

    function resolve(raw) {
        try {
            var url = new URL(String(raw), document.baseURI);
            return url.protocol === "http:" || url.protocol === "https:" ? url : null;
        } catch (_) {
            return null;
        }
    }

    function linkHref(link) {
        return link.getAttribute("href") || link.getAttributeNS(XLINK, "href");
    }

    function linkTarget(link) {
        var target = link.getAttribute("target");
        if (target === null) {
            var base = document.querySelector("base[target]");
            target = base ? base.getAttribute("target") : "";
        }
        return target.toLowerCase();
    }

    function onClick(event) {
        if (event.defaultPrevented || (event.type === "auxclick" && event.button !== 1)) return;
        var link = event.target && event.target.closest ? event.target.closest("a[href], area[href], a[*|href]") : null;
        if (!link) return;
        var raw = linkHref(link);
        var url = raw ? resolve(raw) : null;
        if (!url) return;
        var external = url.origin !== location.origin;
        var newWindow = event.type === "auxclick" || !SAME_WINDOW_TARGETS[linkTarget(link)] ||
            event.ctrlKey || event.metaKey || event.shiftKey;
        if (!external && !newWindow) return; // 同源的普通导航按默认行为留在主窗口

        // 立即阻止默认行为，主窗口不会离开；页面自己的处理器稍后也可能阻止默认行为，
        // 这时说明页面接管了点击（例如改为复制链接），本次不再打开或导航。
        var pagePrevented = false;
        var preventDefault = event.preventDefault;
        preventDefault.call(event);
        event.preventDefault = function () {
            pagePrevented = true;
            preventDefault.call(event);
        };
        setTimeout(function () {
            if (pagePrevented) return;
            if (external) openExternal(url.href);
            else location.assign(url.href);
        }, 0);
    }

    document.addEventListener("click", onClick, true);
    document.addEventListener("auxclick", onClick, true);

    window.open = function (raw) {
        // 无参数、空字符串与 about:blank 按规范打开空白窗口；主窗口内没有对应行为，忽略。
        if (raw === undefined || raw === null || String(raw) === "" || String(raw) === "about:blank") return null;
        var url = resolve(raw);
        if (!url) return null;
        if (url.origin !== location.origin) openExternal(url.href);
        else location.assign(url.href);
        return null;
    };
})();
