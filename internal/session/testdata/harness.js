// 在 node vm 中执行会话脚本，用伪造的 document 驱动各场景，输出每个场景发送的消息。
const vm = require("vm");
const fs = require("fs");
const script = fs.readFileSync(process.argv[2], "utf8");
const MARKER = "dsh web authentication required; reopen the URL printed by dsh web.";

function run(contentType, bodyText, viaWebkit) {
    const messages = [];
    const document = {contentType, body: bodyText === null ? null : {textContent: bodyText}};
    const window = viaWebkit
        ? {webkit: {messageHandlers: {external: {postMessage: (m) => messages.push(m)}}}}
        : {_wails: {invoke: (m) => messages.push(m)}};
    vm.runInContext(script, vm.createContext({window, document}));
    return messages;
}

process.stdout.write(JSON.stringify({
    "dsh 401 text": run("text/plain", MARKER),
    "dsh 401 with newline": run("text/plain", MARKER + "\n"),
    "webkit handler only": run("text/plain", MARKER, true),
    "html page with marker": run("text/html", MARKER),
    "other text": run("text/plain", "Unauthorized"),
    "empty body": run("text/plain", ""),
    "no body": run("text/plain", null),
}));
