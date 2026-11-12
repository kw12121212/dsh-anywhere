# DS-Bot API spike 结论（dsbot-api-spike）

对真实 dsh（WSL `dsh-web` systemd 服务，`https://gamepc.tail89569c.ts.net:13080`，tailscale serve）的实测结论。
基准：DS-Bot **0.3.2**（`~/.dsh/profiles/web/node_modules/ds-bot`）、dsh **0.2.2-rc.1**（nvm 全局运行时）。
验证工具：`android/core` 的 spike source set（`gradlew -p android/core runSpike`），复用 core 的
Socks5Dialer/TailnetRouting/HarnessUrl/SessionAuth；凭据经 `DSH_SPIKE_*` 环境变量传入，不打印不入库。
全部检查 PASS 的原始脱敏 dump 见 live 运行输出（临时目录，不提交）；脱敏夹具已固化到
`android/core/src/test/resources/dsbot/`。

## 1. 会话建立（token → dsh-auth cookie）

- `GET <base>/?token=<launch-token>`（带 token 的 dsh-url 输出，只用一次）→ **303 重定向** + `Set-Cookie: dsh-auth-<随机名>=<v1 JWT 形态值>`。
- cookie 属性实测：`Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict`（30 天）。
- cookie **值内嵌颁发 authority**（`"authority":"gamepc.tail89569c.ts.net:13080"`，base64 JWT 载荷）——即 cookie 绑定 host:port，换地址需重新认证。
- 重定向跟随需带已收 cookie（首次 401 即 cookie 未随行）。
- 之后所有请求带 `Cookie: dsh-auth-…=…`；token 不再出现。

## 2. POST /api/bot（DS-Bot 团队接口，全部 HTTP）

- 请求：`POST /api/bot`，`Content-Type: application/json`，body `{"endpoint":"<名>","payload":{…}}`，带 cookie。
- 响应：`{"ok":true,"value":…}` 或 `{"ok":false,"error":{"code":"bot/*","message":…,"details":{}}}`。
- **无需 Origin 头**：原生请求不带 Origin 完全放行（带跨站 Origin + 有效 cookie → 403，被拦截）。
- Host 必须与 trusted-host 一致：不一致（单头替换）→ `403 forbidden`；重复 Host 头按 `400 Bad Request`（纯文本）拒绝。
- 无 cookie → `401`，正文为固定失效标记（`SessionAuth.EXPIRED_MARKER` 匹配，与桌面/App 现判定同源）。
- Host 与 trusted-host 不一致（单个 Host 头替换实测）→ **403 forbidden**（admission fence 拒绝）；若请求带**重复** Host 头则先被按 400 Bad Request 拒绝——取证时必须用单头替换，双头污染归因（本节初版曾把 400 误记为「错 Host」的响应）。
- **路径必须精确以 `/api/…` 开头**：URL 拼接出现 `//api/bot`（根路径尾斜杠拼接）不会报路由错误，而是**静默落到 dsh SPA 静态兜底**，POST → `405 Allow: GET, HEAD`。这是本次 spike 踩过的最大坑：错配无任何 4xx/404 提示，务必在客户端固定「去尾斜杠再拼路径」。

实测通过的端点（0.3.2，channel.js 全量 ~130 个端点按需取用）：

| endpoint | payload | 返回 value 形态 |
|---|---|---|
| `state` | `{locale?}` | 全量视图：`revision, workspaceId, mainBotId(s), bots[], rooms[], privateArchives, exchangeTurns, questions, views, closedCards/Views, btwCards, sideChats, agents, sections, secrets, capabilities, secretRequests, connectorRequests, mailRequests, colors, models, defaultModel, permissions, modelsKey, prefs, browser, memory, readOnly, im, computer` + live 字段（`liveKey, activity, work{bots,rooms,approvals,waiting,turns,steered}, memoryNews, memoryReviews, learning, learningSettled, connectors, capabilityStatuses, imStatus, imVia, computerLive`） |
| `state`（增量） | `{since: <revision>}` | `{revision, unchanged: true, liveKey, …live 字段}` |
| `state`（增量全命中） | `{since, live: <liveKey>}` | 仅 `{revision, unchanged, liveKey}`（最小响应，无 live 字段） |
| `chat` | `{botId, cursor?}` | `{sessionId, items[]}`（item：`{kind: user|bot|event|answer|closed…, text, time, seq, turn, …}`；分页用 cursor） |
| `earlier` | `{sessionId, cursor?}` | Bot 早前 part 的历史页（HISTORY_PAGE=200/页，最多 50 页） |
| `send` | `{sessionId, text}` | `true`（**只收纯文本**，空文本/无 sessionId → `bot/invalid`） |
| `update-check` | `{fresh?}` | `{current: DS-Bot 版本, latest, …, dsh: {current: dsh 版本, …}}`（实测 current=0.3.2、dsh.current=0.2.2-rc.1） |

群聊/卡片/审批等写端点（`create-room`, `update-room`, `post_to_group` 由 send 承担, `answer-question`, `view-reply`, `approval-answer`, `stop-member-work`, `stop-room-work`, `goal-*`, `side-*`, `btw-delete`, `secret-*` 等）与 client.js 的 actions 表一一对应，未逐一实测，形态同上（`{endpoint, payload}` → `{ok, value|error}`）。

## 3. GET /api/bot/feed（SSE 实时推送）

- `GET /api/bot/feed`，`Accept: text/event-stream`，带 cookie；响应 `content-type: text/event-stream; charset=utf-8`，**chunked** 传输。
- 帧格式：`event: <名>\ndata: <JSON>\n\n`；注释行 `: keepalive` 每 20s 一条（跳过）。
- 事件（feed.js，全部实测收到）：
  - `hello` `{revision}`（订阅即发）
  - `revision` `{revision}`（团队保存过：重新取 state）
  - `live` `{}`（工作状态变化无新 revision：重新取 state——实际聚合在 150ms 内）
  - `message` `{botId, sessionId, item}`（某 Bot 自有聊天的**新条目**，item 与 chat 端点同构，含用户消息与 Bot 回复）
  - `stream` `{botId, sessionId, attemptId, phase: "start"|"end"}` / `{…, text}`（流式回复：start → 若干 text 增量 → end）
- 同步策略建议（与里程碑 details 一致）：进前台取全量 state 记下 revision+liveKey → 订阅 feed → `revision`/`live` 事件后 `state?since&live` 增量补齐（unchanged 则零负载）→ `message`/`stream` 增量更新当前聊天 → 退后台断开、回前台补齐。注意 stream 事件在隐藏轮次（Bot 间中转）不发送，纯靠 message 补。

## 4. 附件路径（回答里程碑 attachment-path 问题）

| 环节 | 通道 | 实测/证据 |
|---|---|---|
| 附件上传 | `POST /api/session/uploadFileBinary?sessionId=<sid>&name=<名>[&mediaType=<图片类型>]`，`Content-Type: application/octet-stream`，body 为原始字节流 | **实测 200**：`{ok, value: {receiptId, file: {attachmentId: "sha256:…", name, bytes}}}`（图片变体带 width/height/mediaType） |
| 带附件的消息提交 | dsh 会话 prompt（content blocks：text + image/file receipt 引用） | 网页端经 **`/connect` WebSocket JSON-RPC**（dsh-client-connection `openConnectionWebSocket`）；HTTP envelope 网关（POST /api/<method> 的 rpcId/method/payload）在 web serve **未注册**（实测 404 "not found"），无 HTTP 直发路径 |
| 聊天图片/附件显示 | 会话 RPC `readAttachment(attachment)` → 字节 → blob/data URL | dsh-client-ui-attachment/conversation（客户端源码确认）；非 HTTP 端点 |
| Bot 对附件消息的回复呈现 | 与提交方式无关：`/api/bot/feed` 的 `message`/`stream` 事件照常推送 | feed.js 按sessionId 归属发布 |

**结论**：纯文本对话 + 团队视图 + feed 全部走 HTTP（/api/bot + /api/bot/feed），已实测闭环；附件三环节中**上传是纯 HTTP**（uploadFileBinary），**提交带附件消息与读取附件字节需要最小 `/connect` WebSocket JSON-RPC 客户端**（握手要求：受信 Host + dsh-auth cookie，`GET /connect` Upgrade）。Android 侧建议混合方案：日常纯文本走 /api/bot send（简单、已验证），仅附件消息与附件查看时建立短时 WebSocket 会话；回复呈现统一由 feed 承担。**没有**完全避开 WebSocket 的纯 HTTP 附件方案（0.2.2-rc.1 实测与源码双重确认）。

## 5. 版本检测

- `POST /api/bot {endpoint:"update-check"}`（READS 白名单内，readOnly 也可用）→ `value.current`（DS-Bot 当前版本）+ `value.dsh.current`（dsh 当前版本）。
- 实测：DS-Bot `0.3.2`、dsh `0.2.2-rc.1`，与基准一致。版本读取路径就以此为准（比 milestone 备选的「update-check 的当前版本字段」更精确：`value.current` 与 `value.dsh.current` 两个字段）。

## 6. WebView CookieManager 迁移可行性

- 结论：**可行**。Chromium WebView 的 `android.webkit.CookieManager.getCookie(url)` 底层以 `include_http_only=true` 取 cookie（Chromium `AwCookieManager.getCookie` → native `GetCookies(url, /*include_http_only=*/true)`），HttpOnly 的 `dsh-auth-*` cookie 能被 App 原生读出。
- 依据：Chromium 源码 `android_webview/java/src/org/chromium/android_webview/AwCookieManager.java`（googlesource，2026-11 检索）与 framework 适配层 `CookieManagerAdapter.java`；API 语义与 `developer.android.com/reference/android/webkit/CookieManager` 的「返回可作 Cookie 请求头的完整串」一致。
- 注意：getCookie 返回的是 `name=value; …` 串，需解析出 `dsh-auth-*` 项迁移到原生 cookie 存储；读取后立即 `flush()` 已由 WebView 自身保证持久化。

## 7. 其他边界（实测）

- 错 Host（`Host: spike-invalid-host.example` 单头替换 + 有效 cookie）→ `403 forbidden`；重复 Host 头（初版 spike 误发）→ `400 Bad Request` 纯文本。Host 白名单在 dsh webserver/connection 层。
- 跨站 Origin（`Origin: https://evil.example` + 有效 cookie）→ `403`（Origin 校验在 /api admission）。
- 无 cookie → `401` + 固定失效标记文本（与桌面端 SessionAuth 判定同源）。
- tailnet peer（Mac 上的 pca desktop，loopback 抓包确认其 POST /api/bot 全通）——原生路径与访问来源无关，Android 内嵌节点路径成立。

## 取证方式说明

- 标注「实测」的结论来自 spike 运行（`gradlew -p android/core runSpike`，PASS 输出与脱敏 dump）或针对真实 dsh 的单发 curl（错 Host 403、附件上传 200、HTTP envelope 网关 404、双斜杠 405 均为单发 curl 复核）；loopback 抓包用 root 下的临时 python AF_PACKET 采集器（用后已删）。
- 标注「源码核对」的结论来自对运行时包的静态阅读：dsh 0.2.2-rc.1（nvm 全局 `@deepseek-ai/dsh-*`）的 client-connection（/api 分发、rpcFetchHandler、CONNECT_PATH）、dsh-client-file-upload（uploadFileBinary 路由）、dsh-api-session-controller（/api/file）、dsh-client-ui-attachment/conversation（readAttachment → blob）；ds-bot 0.3.2 的 channel.js/feed.js/constants.js/client.js。
- Chromium CookieManager 结论来自 android_webview `AwCookieManager` 源码检索（include_http_only=true）。

## 8. 后续变更的落点提示（不展开）

- `dsbot-api-client`（core）：请求封装、`{ok,value|error}` 解码、错误码映射（`bot/invalid|refused|stale|not-found|read-only|internal`、`bot/browser-off`）、SSE 解析、since/live 增量状态机、版本比对。
- `dsbot-native-shell`：升级迁移读 CookieManager.getCookie → 原生存储。
- `dsbot-attachments`：uploadFileBinary（HTTP）+ 最小 /connect WS 客户端（prompt with content blocks + readAttachment）。
