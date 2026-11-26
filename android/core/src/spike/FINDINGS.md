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

## 9. 0.3.6 复验（2026-11-13，服务器升级后）

服务器升级 DS-Bot 0.3.2 → **0.3.6**（dsh 仍 0.2.2-rc.1）后 `gradlew -p android/core runSpike`
（DSH_SPIKE_SEND=1）全项 PASS、FAIL=0：auth-token-session（303+Set-Cookie）、state
全量/since/since+live、chat 分页、update-check（value.current=0.3.6）、send+feed
（hello/revision/live/message/stream 事件形态不变）、401 固定失效标记、错 Host 403。
客户端依赖的响应形态与 0.3.2 一致；version-baseline-and-degrade 基准随本次复验提到
0.3.6（DsbotVersions.BASELINE_DS_BOT）。

## 10. /api/file 版本重定向形态（2026-11-13，源码核对 dsh-api-session-controller）

serveFile 对不带当前 `v` 参数的请求回 302，**Location 只给查询串**（`?path=…&v=文件版本`，
非绝对地址也非根相对路径）——1.6.1 只跟随 http/根相对两种形态导致真实服务器上「文件获取
失败（302）」。其余边界：>maxImageBytes（默认 50MB）回 413；不存在 404；非普通文件/越权
403；成功响应 Content-Type 按扩展名识别。已登记 tech/dsbot-api 的 media-reference-file，
客户端解析统一走 core HttpLocation（mock 也已对齐查询串形态）。

## 11. 0.2.2-rc.3 复验（2026-11-15，dsh 升级后）

WSL 服务器升级 dsh 0.2.2-rc.1 → **0.2.2-rc.3**（DS-Bot 仍 0.3.6）后 `gradlew -p android/core runSpike`
（DSH_SPIKE_SEND=1）全项 PASS、FAIL=0：auth-token-session（303+Set-Cookie）、state
全量/since/since+live、chat 分页、update-check（value.current=0.3.6、value.dsh.current=0.2.2-rc.3）、
send+feed（hello/live/message/stream 事件形态不变）、401 固定失效标记、错 Host 403。state 顶层键为
纯增量新增（`phone`、`browserHolds` 等，revision/bots/rooms 等客户端依赖键齐全），其余响应形态
与基准一致；version-baseline-and-degrade 基准随本次复验提到 dsh 0.2.2-rc.3
（DsbotVersions.BASELINE_DSH）。

## 12. DS-Bot 0.3.10 实测（2026-11-22，dsbot-baseline-0310）

服务器升级 DS-Bot 0.3.6 → **0.3.10**（dsh 仍 **0.2.2-rc.3**，update-check 的 value.current /
value.dsh.current 实测）后 `gradlew -p android/core runSpike`（新增 `Probes0310` 探针组，
DSH_SPIKE_SEND=1）全项 PASS、FAIL=0；既有检查（会话、state 全量/since/since+live、chat、
feed、401、错 Host 403）形态不变。新端点全部经本项目 HTTP 路径（POST /api/bot 信封与
GET /api/bot/feed SSE）可用——上游手机端的加密链路不是必需的。写端点只用不存在的 id
取错误形态，未改动真实团队。

运行备注：WSL 的 resolv.conf 不走 MagicDNS，tailnet 主机名需经
`JAVA_TOOL_OPTIONS=-Djdk.net.hosts.file=<临时 hosts>`（IP 取 `tailscale ip -4 gamepc`）。

**握手与提醒**（夹具 hello.json、hello-api-unsupported.json、notices.json、notices-reset.json、feed-stream-0310.sse）：

| 端点/事件 | 请求 | 应答（实测） |
|---|---|---|
| `hello` | `{api:[1]}` | `{api:1, apiMin:1, apiMax:1, plugin:{name,version:"0.3.10"}, capabilities[], boot, seq, revision, readOnly, memory, feed:"api/bot/feed", rpc:{call,feed}}` |
| `hello`（无共同版本） | `{api:[99]}` | 同上但 `api:null` → 客户端判不兼容 |
| capabilities 名单 | — | state, state-avatar-refs, avatar, chat, room-chat, agents, send, send-images, feed, feed-room, notices, questions, views, approvals, stop-member-work, memory, memory-edit, usage-summary, stop-chat, stop-agent, row-meta, chat-steps, link-feed, images, image-preview, file-download, markdown-image, file-download-chunks, write-state, rpc |
| `notices` | `{boot, since}` | `{boot, seq, reset:false, notices:[{seq,time,botId,name,kind,sessionId?,roomId?,title?,text}]}`；陌生 boot → `reset:true`（须重取整体状态）；kind ∈ message/group/done/question/view/approval，提醒只存内存（200 条环，重启换 boot） |
| feed `hello` | — | `{revision, api, boot, seq}`（0.3.6 只有 revision） |
| feed `notice` | — | 与 notices 条目同形（Bot 回复落定后推送，晚于 stream 结束） |
| feed `room` | — | `{roomId}`（群聊有活动时 150ms 合并推送；本次窗口内无群活动，形态为源码核对） |

**未知端点**：`{ok:false, error:{code:"bot/not-found", message:"Unknown endpoint …"}}`（夹具
error-unknown-endpoint.json）。0.3.6 对 `hello` 的应答**未实测**（服务器已升级），按 channel.js
未知端点分支推断为同一形态；客户端对任何非 ok 应答都判 LEGACY，错误码不同不影响行为。

**媒体与文件**（夹具 image-preview.json、avatar.json、markdown-image-forbidden.json、file-text-unavailable.json、file-download-invalid-offset.json）：

| 端点 | 请求 | 应答（实测） |
|---|---|---|
| `state` | `{avatars:"ref"}` | bots[].avatar 的 image 换成 `imageRef`（本服务器 Bot 头像均为 shape，未出现 imageRef） |
| `avatar` | `{botId}` | `{botId, ref, image}`（无照片头像时 ref/image 为 null） |
| 聊天条目图片引用 | — | 用户条目 `images:<数量>, imageRefs:[{attachmentId:"sha256:…", mime, name}]`（不是旧的 image 对象） |
| `image` | `{sessionId, attachmentId, preview:true}` | `{mime, data:base64}`（预览缩到 PREVIEW_EDGE 内；无此附件 → bot/not-found） |
| `file-download` | `{sessionId, attachmentId \| seq+index, offset}` | offset 非法 → bot/invalid；图片附件 id → bot/not-found（只认文件块与 deliverables/presented）。**成功分块样本未取得**：本服务器 13 个聊天首页无文件附件；分块形态（512 KiB、offset/version/nextOffset）为源码核对，由 dsbot-reader-files 实测后补夹具 |
| `file-text` | `{file:{attachmentId}}` | 读不了 → **bot/unavailable**（新错误码） |
| `markdown-image` | `{sessionId, path}` | 工作区外路径 → **bot/forbidden**（新错误码）；非 file 协议 → bot/invalid |

**写端点（错误形态）**：bot-stop-task → bot/not-found；computer-app-answer → `value:false`；
computer-stop `{botId}` → `value:true`（无此 Bot 时空操作）；learning-answer → bot/stale；
connector-request-cancel → bot/refused；agent-chat → bot/not-found；stop-agent → `value:false`；
memory-add / memory-edit（无此 Bot）→ bot/refused；schedule-pause → bot/invalid；
stage-image（非图片类型）→ bot/invalid；send `{images}`（无此会话）→ bot/stale；
usage-summary `{botId}` → `{botId, day, week, month, models, scanning}`；room-chat 形态不变。

新错误码 bot/forbidden、bot/unavailable 经 `DsbotApi.reasonOf` 的缺省分支归 REFUSED（拒绝类），
无需新增映射。

**401 形态备注**：无 cookie 的 `POST /api/bot` 回 401、正文 `unauthorized`、无 Content-Type
（dsh-client-connection 的 /api 准入分支）；固定失效标记（夹具 error-401-body.txt）只出现在
页面入口 GET / 的 401 上——DialProbe 判定走的正是页面入口，`DsbotApi.parseResponse` 对任何 401
都按会话失效处理，两条路径各自正确，不是回归。

**待首个消费变更实测补夹具（本次只做了源码核对）**：feed `room` 事件、file-download 成功分块、
file-text 与 markdown-image 成功应答、`state {avatars:"ref"}` 下的 `imageRef`。

**客户端落地**：core `DsbotHello`/`Capabilities`（未声明即不可用；无 hello → LEGACY；无共同
api → INCOMPATIBLE 并提示版本不一致；feed hello 的 boot 变化或不再带 boot → 重读），`DsbotVersions` 基准 0.3.10 / 0.2.2-rc.3；其余端点的
解码随首个消费它的计划变更以本节与夹具为准实现。

## 13. 0.3.10 聊天条字段（2026-11-23，dsbot-chat-bars-0310，源码核对）

后台任务、电脑操作、模型失败阻塞、群目标与工作详情不在 hello capabilities 名单内，客户端按
字段存在显隐（core `ChatBars`/`WorkPhrase`）。形态按本机已装 DS-Bot 0.3.10 源码核对——**未实测**：

| 字段 | 位置 | 形态（源码核对） |
|---|---|---|
| `bots[].tasks[]` | state（channel.js view → bot-tasks.js taskViewOf） | `{id,title,status,sessionId,roomId?,origin?,parts?,startedAt,updatedAt,endedAt?,workDir?,summary?,error?,running}`；活动态 running/waiting_work/waiting_approval（threads.js） |
| `bots[].blocked` | state（turns.js blockBot） | `{code,status?,requestId?,provider?,at}`；code ∈ BLOCKING_CODES；重试 `resume {botId}` |
| `computerLive` | state 应答合并 live（`{...view(), liveKey, ...live}`） | `{bots:{id→{state,app?,waitingFor?,lastShot?,steps}},leases[],cursors[],asks:{id→{id,botId,sessionId,appName,at,roomId?,icon?,callId?}},holds}`；作答 `computer-app-answer {id,answer:always\|once\|deny}`（无此请求 → value:false），停止 `computer-stop {botId}` |
| `rooms[].goal` | state（goals.js） | `{id,revision,status:drafting\|active\|paused\|complete\|stopped,title,stages[{id,title,acceptance,status:pending\|active\|passed,tasks[],reworks}],current,…}` |
| `work.bots` / `work.rooms` | live（turns.js workView/roomWorkView/doingOf） | bots `{id→{since,tool?,said?,note?,target?,roomId?}}`；rooms `{roomId→[{botId,since,tool?,said?,note?,target?,speaking?}]}` |

真实服务取样尝试（2026-11-23）：本机团队 12 个 Bot、2 个群当前无任务/阻塞/群目标；
`create-bot` 指定未配置服务商的模型被拒（bot/refused，未创建对象）；所有本地 Agent 已安装，
无零成本触发阻塞的途径；电脑操作 `computer.enabled=false` 且宿主为 Linux。因此单元测试样本放在
`android/core/src/test/resources/dsbot-source/`（明确标注为源码核对，非真实夹具），模拟器验收用
`node scripts/mock-dsh-server.mjs --chat-bars` 注入同一形态。**残余风险**：电脑操作请求与上述字段
的真实下发形态待服务器出现样本后实测，取到即改为 `dsbot/` 下的脱敏夹具。

## 14. 应用内提醒的字段语义（2026-11-23，dsbot-notices-banner）

实测（第 12 节夹具）只有 `kind=message`：`{seq,time,botId,name,kind:"message",sessionId,text}`。其余形态按
ds-bot 0.3.10 `src/host/mobile.js` 源码核对、**待实测**：

| kind | 来源 | 字段 |
|---|---|---|
| group | rt.roomLineAdded（群日志的成员消息） | roomId、**title = room.name（群名）**、text = 消息正文 |
| done | rt.roomLineAdded（群日志 kind=done） | roomId、title = 群名、text = ""（空） |
| approval | rt.approvalWaiting | sessionId、text = 工具名；群内审批另带 roomId、title = 群名 |
| question / view | rt.cardShown（私聊卡片） | sessionId、text；不带 title |
| message | session/event assistant/message | sessionId、text（实测） |

客户端（core `Notices`）：title 只作来源（「群名 · 名称」），摘要一律取 text；只显示
question/approval/message/group/done。群类提醒的真实样本需要群活动（会触发真实模型），取得后补
`dsbot/` 脱敏夹具。

## 15. 发送箱对账依据实测（2026-11-23，dsbot-outbox-retry，只读）

对真实 dsh（DS-Bot 0.3.10）只读调用 state / chat / room-chat 取条目形态（只看键与类型，不取内容）：

- 私聊 chat 的用户条目：`{kind:"user", text, time, seq, turn}`；带图片的用户条目另有 `images:<数量>, imageRefs:[…]`，**text 仍在、seq 仍在**。
- 群聊 room-chat 的用户条目：`{id:"<短字符串>", kind:"user", text, time}`——**没有 seq**；成员条目 `{id, kind:"bot", botId, name, text, time, duration?, sessionId}`。
- 群聊的 feed 只推 `room` 信号（第 12 节，源码核对），不推群内用户 message 事件。

客户端（core `Outbox`）据此：私聊以 seq 为顺序值对账，群聊以服务端 `time` 为顺序值（窗口取发送时群页最大 time，
与手机时钟无关），群消息发送成功后立即重读群页确认；对不上的本地行停在「已发送，等待确认」并可删除。

## 16. 停靠区数据（2026-11-23，dsbot-pending-dock，源码核对）

DS-Bot 0.3.10 的待处理项都在 state（含 live 字段）里；聊天条目中的 `kind:"answer"`（带 `answers`/`handoff`/`views`）
是**用户已经给出的回复记录**（parts.js REPLY_KIND），不是待答卡——真实夹具 chat-page.json 即此形态。

| 类型 | 来源 | 作答 |
|---|---|---|
| 提问 | `questions{会话id→{id, question, detail?, options[{label, description?}], multiSelect, allowCustom?}}` | `answer-question {sessionId, questionId, answer:{selected:[选项文字], custom?}}`；`dismiss-question {sessionId}` |
| 卡片 | `views{会话id→{id, title, view}}` | `view-reply {sessionId, viewId, action, values}`（富渲染承接）；`dismiss-view {sessionId}` |
| 工具审批 | live `work.approvals[{id, sessionId, botId, roomId?, taskTitle?, tool, reason, answerable}]` | `approval-answer {id, choice:allow\|reject}` |
| 密钥请求 | `secretRequests{botId→[{id, sessionId, name, purpose, kind:fill\|allow, status}]}` | `secret-set {requestId, value}` / `secret-allow {requestId}` / `secret-cancel {requestId}` |
| 连接器请求 | `connectorRequests{botId→[{id, sessionId, connectorId, name, purpose, status}]}` | `connector-request-cancel {requestId}` |
| 学习审批 | live `learning{botId→[skill{…previous?} \| agents{path, edits[{op, old, text, why}]}]}` | `learning-answer {id, approve}` |
| 浏览器交接 | live `browserHolds{botId→{by, reason, note, callId?}}` | `browser-give-back {botId, outcome:done\|skip}` |
| 插话删除 | `btwCards{botId→[…]}`（聊天内联） | `btw-delete {sessionId, cardId}` |

0.3.5 时期内联卡片使用的参数（审批 `{callId, approved}`、插话删除 `{id}` 等）在 0.3.10 上会被拒绝。本机真实服务当前
无上述待处理样本，成功形态待实测；客户端缺字段降级只显示标题。

## 17. 交付文件分块下载与全文（2026-11-23，dsbot-reader-files）

**实测（只读，DS-Bot 0.3.10 / dsh 0.2.2-rc.3）**：本机 12 个 Bot 的聊天里 49 条 bot 条目带 `files`，
形态 `files:[{name, sessionId, seq, index}]`——Bot 交付的文件（deliverables/presented）；`seq` 是交付
事件所在会话的序号（早于消息自己的 seq），`file-download` 必须用这组定位符，用消息的 seq 回 bot/not-found。

| 请求 | 应答（实测） |
|---|---|
| `file-download {sessionId, seq, index, offset:0}` | `{name, mime, bytes:base64, size, version, nextOffset, done}`；name 与 files[] 列出的一致；mime 按扩展名（md→text/markdown、pdf、zip、mp4、png；html/docx/xlsx→application/octet-stream） |
| 续块 `{…, offset:nextOffset, version}` | 每块 524288 字节（512 KiB），version 不变；1.6 MB PDF、101 MB 视频均可逐块取回 |
| 版本不符 `{…, version:"stale"}` | `bot/not-found`「This file is no longer available」（文件变更或已删除同此） |
| 未知会话 | `bot/not-found`「Unknown session」 |

夹具（脱敏：名称、会话、version 数字与内容均为合成，形态与数值格式为实测）：
`file-download-chunk-small.json`（单块完结）、`file-download-chunk-large-first.json`（首块 nextOffset=524288、
done=false，bytes 为占位）、`file-download-version-changed.json`、`chat-item-files.json`。

**待实测（源码核对，tech/dsbot-api endpoint-inventory-0310 例外）**：`file-text {file:{attachmentId}}` 的成功应答
`{text, whole}` 与 room-chat bot 条目的 `whole:{length, file}`——本机无带 attachmentId 的文件附件或被截断的长群帖，
取样需用户上传附件或 Bot 在群里发超长帖（触发真实模型运行）。客户端只在条目带 whole.file（含 attachmentId）时显示
「阅读全文」，失败给可读提示。交付文件本身没有 attachmentId，其「阅读全文」走上面实测的分块下载后按 UTF-8 解码。

## 18. show_view 蓝图与 Markdown 本地图片（2026-11-23，dsbot-rich-render）

**实测（只读）**：

| 请求 / 字段 | 应答（实测） |
|---|---|
| `markdown-image {sessionId, path}`（工作区内） | `{mime, bytes:base64, size, rev}`，rev 形如 `<字节数>:<16 位十六进制>` |
| 同上带 `knownRev`（未变） | `{mime, unchanged:true, size, rev}`（不再回传 bytes） |
| 工作区外路径 | `bot/forbidden`「Image outside workspace」（本机 45 处 Markdown 图片引用中 13 处属此类，`/tmp/…` 截图常见） |
| Markdown 图片写法 | `![alt](/abs/path.png)` 与 `![alt](</path with space.png>)` 两种都出现 |
| 聊天 answer 条目的 `views[]` | `{id, title, view}`，view 为 deep-view 组件树；本机唯一样本为 stack/text/choice×4/submit |

夹具（脱敏：图片内容换成 1×1 PNG、rev 合成，卡片文字与选项值合成）：`markdown-image-ok.json`、
`markdown-image-unchanged.json`、`view-blueprint-choice.json`。

**源码核对（deep-view 0.1.2 core/views.js、formula.js、reply.js；tech/dsbot-api endpoint-inventory-0310 例外）**：
组件 stack/row（let 只在最外层）、text/metric/table/chart（bar/hbar/line/area/donut）/progress/callout/sources/html、
slider/number/choice/toggle、options/confirm/form/submit；上限 8 层、64 组件、16 子节点、文字 2000、单元格 200、
8 列 50 行、8 选项、6 字段、蓝图 8192 字节；公式 `{"=": "…"}` 的语法与函数集；作答 `view-reply {sessionId, viewId,
action, values}`，values 为 `{inputs?}` 加 options→`selected[]`、confirm→`confirmed`、form→`fields{}`/`canceled:true`，
submit 只带 inputs；服务端以 validateViewAnswer 校验，视图已关闭回 bot/stale。客户端（core ViewCard）逐条移植上述校验，
另加序列 ≤ 8、点数 ≤ 50 的绘制上限，不通过即降级只读摘要。

## 19. Bot 管理端点（2026-11-23，dsbot-bot-manage）

**实测（只读，DS-Bot 0.3.10）**：

| 请求 / 字段 | 应答（实测） |
|---|---|
| `efforts {ref}`（有级别的模型） | `{efforts:[{id:"low",name:"Low"},{id:"high",name:"High"},{id:"max",name:"Max"}]}`，本机无 `defaultEffort` |
| `efforts {ref}`（无级别或未知 ref） | `{efforts:[]}` |
| state `bots[]` 管理字段 | `model`、`appliedModel`、`effort`、`appliedEffort`、`permission`（仅 1 个 Bot 设过）、`color`、`avatar{shape}`、`workDir`；`private/pinned/hidden/sectionId` 本机无样本 |
| state `models[]` | `{ref,provider,providerName,id,name,input[]}`，Bot 在用的模型另带 `reasoning{efforts[]}` |
| state `permissions` | `{options:[{value,name}], defaultPreset}`（夹具 state-full.json） |
| state `privateArchives` | `[]`（本机无已删除私有 Bot） |

夹具：`efforts.json`、`efforts-empty.json`。

**源码核对、未对真实团队写入**（真实服务是用户在用的团队：新建会触发真实模型问候，删除不可逆）：
`create-bot {name, role?, instructions?, color?, avatar?{shape?|image?}, model?, effort?, workDir?, sectionId?}` → 新 Bot 对象；
`update-bot {id, …patch}`（name/role/instructions/color(null 清除)/avatar(null 清除)/model/effort(null=模型默认)/permission/
private/workDir；运行引擎（本地 Agent 与模型之间）变化须 `changeEngine:true`）→ 更新后的 Bot；`duplicate-bot {id}` → 副本 Bot
（私有 Bot 拒绝）；`delete-bot {id}`（Main Bot 拒绝）、`private-release {id}`、`set-main {id, main, primary?, replace?}`
（私有 Bot 拒绝、唯一 Main 不可取消）→ 整份 state 视图；`set-flags {id, pinned?, hidden?, sectionId?}` → 该条目。拒绝为
`{ok:false, error:{code:"bot/refused", message}}`，message 是具体原因（channel.js handle 的 catch 经 errorCode：普通 Error →
bot/refused，内部异常 → bot/internal，客户端对 bot/internal 不显示原文）。换模型时上游保留 effort，新模型不支持该级别时按模型默认。
**与网页端的差异**：网页端切换运行引擎时附带 `avatarAfter`（换掉本地 Agent 徽标照片），手机拿不到徽标图，只发
`{model, changeEngine:true}`，徽标照片的处理留给 dsbot-avatars。
位置：channel.js、roster.js、privacy.js、permissions.js、models.js reasoningOf、shared/mentions.js nameProblem。
模拟器验收用 `node scripts/mock-dsh-server.mjs --manage` 注入同一形态。

## 20. 头像库（2026-11-24，dsbot-avatars）

**实测（只读）**：`avatar-library {}` → `{items:[], total:0, next:0}`；`{offset:5, limit:2}` → `{items:[], total:0, next:5}`
（本机头像库为空；夹具 `avatar-library-empty.json`）。

**源码核对、未写入用户真实头像库**（ds-bot 0.3.10 avatar-library.js、avatar.js、constants.js）：条目
`{id(字节 sha256), name(≤120), mime, bytes, createdAt, image(data URL)}`，最新在前、每页至多 48、库上限 500；
`avatar-import {items[{name, image}]}` 逐张处理 → `{imported[条目], duplicates[{id,name}], failed[{name,message}]}`；
`avatar-delete {id}` → `true`/`false`（使用该照片的 Bot 保留自己的副本）。单张：PNG/JPEG/WebP/GIF（按魔数）、
字节 ≤ 119982（`(160000-23)/4*3`）、data URL ≤ 160000；上游提示 256×256 方形。设为 Bot 头像为
`update-bot {id, avatar:{shape?, image}}`（网页端 `{...bot.avatar, image}`），移除为 `avatar:{shape}` 或 `null`。
App 不以 `avatars:'ref'` 取 state，照片 data URL 内嵌在 `bots[].avatar.image`。模拟器验收用 `--manage` 的同形态实现。
SVG 头像（上游 update-bot 的 cleanAvatar 允许 `image/svg+xml`，头像库不接受）在 App 中无法解码：详情页显示「已设照片头像」但不显示缩略图，页头退回颜色点。

## 21. 工作文件夹（2026-11-24，dsbot-folders）

**实测（只读）**：`folders {}` → `{team, picker:"browse", folders[{path,title}]}`（本机 17 个最近文件夹；夹具 `folders.json`
的路径已合成化）；`folder-list {}` 从 home 列出，`{path:"/usr"}` → `{path, home, crumbs[{name,path,hidden}], entries[{name,path,hidden}],
truncated}`（夹具 `folder-list-usr.json`）；不存在 → `bot/refused`「cannot list /nope/zzz: ENOENT…」（夹具 `folder-list-missing.json`），
是文件 → `bot/refused`「…ENOTDIR…」。

**源码核对、未改用户真实团队**（ds-bot 0.3.10 folders.js、roster.js）：`folder-list` 只在 picker=browse 时可用；`folder-pick`
在电脑桌面弹对话框，手机端不调用；`team-workspace {path}` → `{team}`（数据目录及其上级、私有 Bot 文件夹附近拒绝，不存在时创建）；
`update-bot {id, workDir}`（空串=团队工作区）、`update-room {id, workDir}`（空串=各 Bot 用自己的文件夹）经 cleanWorkDir：
完整路径、存在、是文件夹、不在数据目录（团队工作区在数据目录内是例外），等于团队工作区时视为未设。用户经这些端点修改不经审批；
approveFolder 只在 Bot 经工具改文件夹时触发。

## 22. 群管理（2026-11-24，dsbot-group-manage）

**实测（state-full 夹具）**：rooms[] 带 `id, name, named, members[], admin, mode:"admin", notice, createdAt, createdBy, ownParts, parts,
preview, seq, sessionId`；本机两个群都没有 `permission/workDir/pinned/hidden`。

**源码核对、未改用户真实团队**（ds-bot 0.3.10 channel.js、roster.js、permissions.js、constants.js、groups.js、tools.js）：
`create-room` 只转发 `{members[], name?, admin?（仅字符串）, section?, sectionId?, greet?}`——mode 与 notice 不转发（新群为 admin 模式、
空公告），admin 非字符串时取成员中的 Main Bot；网页端新建群带 `greet:true`，由管理员打招呼。回复模式只有 `mode==="admin"` 且管理员
仍是团队 Bot 时才生效为管理员优先，否则全员轮流（网页端 shownMode 同此；把管理员设为无或移出群时上游不改 mode）。自动群名为
`["You", ...成员名].join(", ")`。`update-room {id, …}` 先整体校验再写入（planRoomChange）：成员 2–12、不含私有 Bot、管理员须为成员（管理员被移出时清空）、
mode ∈ admin/everyone、公告 ≤2000（trim 后，超长拒绝）、群名超过 60 字截断且不与其他群重名（空串=按成员自动命名）、permission 为预设名或 null
（只有用户能改）→ `{room, changes[]}`；`delete-room {id}` → 整份 state；`set-flags` 对群同样适用。拒绝为 `bot/refused` + 具体 message。
用户经这些端点修改不经审批。

## 23. 团队的 DSH Agent（2026-11-24，dsbot-member-agents）

**实测（只读）**：本机 `state.agents` 为 `[]`（团队没有 DSH Agent）；`agent-chat {agentId:"nope"}` → `bot/not-found`「Unknown DSH Agent」；
hello capabilities 含 `agents`、`stop-agent`。

**源码核对、待实测**（取得成功样本需在用户在用的团队里新建 Agent，项目约定不得改动真实数据；tech/dsbot-api endpoint-inventory-0310 例外）：
DSH Agent 是团队级、至多一个的普通 dsh 入口（agents.js），`state.agents[{id, name:"DSH Agent", sessions[], createdAt}]`，最新会话为
sessions 末尾；live `work.agents{agentId→{since,…}}` 表示工作中。`add-agent {}`（已有时拒绝）、`agent-chat {agentId, cursor?}`（parts.js
agentPage：最新会话、与 chat 同由 partPage 分页；全部归档时 items 为空）、`agent-session {agentId}` → `{sessionId}`、`agent-archive
{agentId, sessionId}`、`agent-rename {agentId, sessionId, title}`（trim 后非空、截断 80）、`remove-agent {agentId}`（归档全部会话）、
`stop-agent {agentId}` → 是否停止了运行中的回合。发送为 `send {sessionId, text}`。feed.js 的 message/stream 只为 chatOwners 中的
Bot 会话发布，Agent 会话只在 agent/status 时触发 live，客户端据 live/轮询重读 agent-chat。`earlier` 对 Agent 会话返回空，更早内容
用 agent-chat 带 cursor。`agent-adopt` 需要已有 dsh 根会话 id，手机端不使用。

## 24. 记忆面板（2026-11-24，dsbot-memory）

**实测（只读，不带 summarize，不触发模型）**：`memory-view {botId}` → `{botId, entries[{scope:own|team, topic|null, text, id, added,
by|null, source, until|null, pinned, expired}], sizes{own,team}, limits{entry:300, group:12000, bot:8000, team:4000, topic:16000, topics:40, own},
summary:null, fresh, summarizing, tidy{scope, batch, at, merged, moved, retired, items[{op,text}]}, keys{own:"bot:<id>", team:"team"},
places[{key, kind:bot|group, name}], error:null}`（主 Bot 50 条、10 个主题、0 条置顶）；`memory-view {roomId}` → `{roomId, entries:[],
sizes{group}, keys{group:"group:<id>"}, tidy:null, …}`；`memory-history {botId}` → `{rows[{id, time, op:add|replace, scope, text, by,
restored}], more:"<下一页 before 的 id>"}`（每页 50），群为 `{rows:[], more:null}`；`memory-restore` 未知 id → `bot/refused`「That change is not in
the change log of this memory.」；`memory-edit` 未知 Bot → `bot/refused`「Unknown Bot」。夹具 `memory-view-bot.json`、`memory-view-room-empty.json`、
`memory-history.json` 按实测形态脱敏（正文全部合成），`memory-restore-unknown.json` 为实测原文。

**源码核对、待实测**（改写真实记忆会改变 Bot 行为；tech/dsbot-api endpoint-inventory-0310 例外）：memory-ask {…, text≤2000} → {reply,
changes[{op, scope, text, before?, ok, error?}], view}（调用模型）；pin/forget/edit/add/move/layer/restore/tidy-undo 均返回最新视图；条目以
(scope, topic, text) 定位，条目已变回 NO_MATCH 类拒绝；memory-layer 只用于团队条目（always=true 移到主文件、false 放回 shared 主题）；
memory-move 的 to 为 places[].key 或 team。memory-ask 同步等模型写完才返回（单次模型调用上限 120 秒、失败重试并换默认模型，
本地 Agent 路线通常更久），客户端对它使用 5 分钟读超时，失败时重读视图并提示可能已在服务端完成。只读主机（另一处持有团队）的 READS
含 memory-view 但不含 memory-history，此时历史页显示「团队当前只读」。整理条目的 op 为 merged/moved/retired。条目 id 只在同一记忆范围内
唯一（无 meta.id 时为正文 sha256 前 4 位），同页并列多组时列表键须带范围。

## 25. 团队偏好（2026-11-24，dsbot-prefs-theme）

**实测（只读）**：`state.prefs` 为对象，本机团队为 `{theme:"paper", width:90, defaultBotModel:"<ref>"}`——缺省值不落盘（accent、layout、motion、
enterToSend 缺省时键不存在），网页端设过的消息宽度 90% 正是手机修改其他项时必须原样保留的值。

**源码核对、未对真实团队写入**（改偏好会改变用户网页端外观；set-prefs 不在 endpoint-inventory-0310 的清单内，形态登记于 tech/dsbot-api team-prefs-0310）：经 dsh 的 `POST /api/bot {endpoint:"set-prefs", payload}`、鉴权同其他管理端点（会话 cookie），不合法取值回 `bot/invalid`。`set-prefs` 按键逐项合并（channel.js setPrefs），
未出现的键不动，返回整份 `state.prefs`。theme 为 `^[a-z][\w-]{0,31}$`（未知 id 也接受，客户端按默认主题显示）；accent 为 `#rrggbb`，null/空串
删除（回到主题默认）；motion quiet|normal|lively（normal 删除键）；layout bubble|flat（bubble 删除键）；width 50–100 整数（80 删除键）；
defaultBotModel 经 resolveModel 解析、本地 Agent 拒绝「A local agent cannot be the default model…」、null/空串删除；enterToSend 只存 false。
手机每次只发改动的一项、从不发 width/flatWidth/effects/thinking。网页端改偏好后经 revision 刷新 state，App 随之换配色与聊天布局。

## 26. 团队侧栏分组（2026-11-24，dsbot-sidebar-sections）

**实测（只读，夹具 `state-full.json`）**：本机团队当前 `state.sections` 为 `[]`，`bots[]` 与 `rooms[]` 条目均未携带 `sectionId`。

**源码核对、未对真实团队写入**（ds-bot 0.3.10 `src/host/channel.js`、`roster.js`、`constants.js`、`store.js`、`text.js`）：
- **state 形态**：`state.sections` 为 `[{id, name, collapsed?, color?}]`（数组顺序即侧栏分组顺序）；条目归属记在 `bots`/`rooms`/`agents` 各自的 `sectionId`（不在分组对象上存 `itemIds`）；Main Bot（`mainBotIds[0]`）不可归组，保存或改主 Bot 时删除其 `sectionId`，指向不存在分组的 `sectionId` 在加载时清除。
- **`section-save {id?, name?, collapsed?, color?, items?, add?, remove?}`**：无 `id`（或空）时新建（`sec-<uuid8>`），空名时取 `freshSectionName`（「New section」及序号）；有 `id` 时按出现的键局部更新（`collapsed: false` 删除键）。名称经 `unquote`（去首尾空白与引号）、合并空白、截断 `SECTION_NAME_MAX = 32`；空名或与同级其他分组（`trim().toLowerCase()`）重名时拒绝（`bot/refused`）；`color` 只允许 `SECTION_COLORS`（`blue|orange|green|violet|rose`）或 null/空串。返回保存后的分组对象。
- **`section-order {ids}`**：按 `ids` 重排 `state.sections`（未列出的已有分组按原顺序接在后面），返回整份 `state` 视图。
- **`section-delete {id}`**：未知 `id` 拒绝 `Unknown section`；删除分组并清除所有条目上匹配的 `sectionId`，返回整份 `state` 视图。
- **`set-flags {id, pinned?, hidden?, sectionId?}`**：`sectionId` 为 `null`/空串或主 Bot 时删除归属；否则校验 `id` 存在于 `state.sections`（未知 `Unknown section`）后写入。客户端移动单个条目走 `set-flags`，避免 `section-save` 的 `items` 全量替换覆盖并发改动。
- **网页端顺序**：先渲染未分组条目，再按 `state.sections` 顺序渲染各分组。模拟器验收用 `node scripts/mock-dsh-server.mjs --manage` 注入同形态实现。

## 27. Bot 浏览器（2026-11-24，dsbot-bot-browser）

**实测（只读，本机 dsh Web 宿主 + DS-Bot 0.3.10，未改动真实团队）**：
- `state`：`browser: false`（dsh Web 宿主无桌面通道 `rt.browserChannel`），live `browserHolds: {}`。
- `browser-frame {botId}`（已启用 `browserPlugin`、该 Bot 当前未打开浏览器）：`{ok:false, error:{code:"bot/not-found", message:"This Bot has no open browser", details:{}}}`（证明 `rt.browserFrame` 存在且在 dsh Web 宿主上可用）。
- `browser-takeover {botId:"__nonexistent__"}`：`{ok:false, error:{code:"bot/browser-off", message:"Bot browsers are off on this host", details:{}}}`。
- `browser-give-back {botId:"__nonexistent__"}`：`{ok:false, error:{code:"bot/browser-off", message:"Bot browsers are off on this host", details:{}}}`。
- `browser-stop {botId:"__nonexistent__"}`：`{ok:false, error:{code:"bot/not-found", message:"Unknown Bot", details:{}}}`（不依赖桌面通道，dsh Web 宿主上可用）。

**源码核对（ds-bot 0.3.10 `src/host/channel.js`、`browser.js`、`browser-plugins.js`、`browser/mount.js`、`browser/session.js`）**：
- **`browser-frame {botId}`**（属 READS 白名单）：`rt.browserFrame` 与 `rt.browserChannel` 均不存在时回 `bot/browser-off`；Bot 无打开页面回 `bot/not-found`；成功返回 `{mime:"image/jpeg", data:base64, url, title}`（`session.js` `frame()`）。
- **live `browserHolds`**：`{botId → {by:"bot"|"user", reason, note, callId?, sessionId?}}`；无条目=Bot 使用中，`by="bot"`=等待用户交接，`by="user"` 且 `reason="stopped"`=已停止，其余 `by="user"`=用户已接管。
- **`browser-takeover {botId}`**：需要 `rt.browserChannel`，成功回 `true`（若原有交接带 `callId` 则保留 `reason/note/callId/sessionId` 并将 `by` 改为 `user`，否则写 `{by:"user", reason:"takeover", note}`）。
- **`browser-give-back {botId, callId?, outcome?}`**：需要 `rt.browserChannel`；面板交还不带 `outcome` 与 `callId`（只清 hold），停靠区交接卡带 `callId` 与 `outcome:"done"|"skip"`；未持有回 `bot/forbidden`，`callId` 过期回 `bot/stale`。
- **`browser-stop {botId}`**：不依赖 `rt.browserChannel`，取消私聊与群成员工作，若存在 `rt.browserChannel` 则写入 `{by:"user", reason:"stopped"}`；成功回 `true`。
- 手机端不调用 `browser-wait` / `browser-result`；快照仅存于内存，不写入日志、诊断上报与磁盘缓存。模拟器验收用 `node scripts/mock-dsh-server.mjs --manage` 的 `/mock/browser` 控制路由覆盖全部宿主模式。

## 28. 能力库（2026-11-24，dsbot-capabilities）

**实测（只读，本机 dsh Web 宿主 + DS-Bot 0.3.10，`runSpike` 的 `ProbesCapabilities` 探针组，未改动真实团队）**：
- `state` 顶层含 `capabilities`（数组）与 live `capabilityStatuses`（对象），hello `capabilities` 名单不含能力库相关能力名——客户端以 `state.capabilities` 字段存在门控入口。
- `state.capabilities=[36]`：`kind` 分布 skill=32、mcp=4（`transport` streamable-http=1、stdio=3）；3 个 stdio 均未批准；36 项 `createdBy` 均为 Bot id（Bot 提议）。三种键集：skill `{name, kind, description, content, createdBy, createdAt, updatedAt}`；HTTP MCP `{…, transport, url, headers, approved}`；stdio MCP `{…, transport, command, args, env, approved}`（与 `libraryView` 一致；本次无能力包样本）。
- `state.bots[]`：12 个 Bot 均带 `capabilities` 与 `packs` 数组；常驻挂载 33 处、按需 0 处。
- `capabilityStatuses`：12 个 botId 键，33 条状态全部 `{state:"ready"}`（条目只有 `state` 键；`error` 仅出错时出现）。
- 写端点错误形态（均 HTTP 200、`ok:false`、`error.code="bot/refused"`，message 为服务端原文）：`capability-approve {name:不存在}` → "No unapproved stdio MCP named …"；`capability-delete {name:不存在}` → "No capability named …"；`bot-capability {botId:不存在}` → "Unknown Bot"；`capability-save` 无效名称 → "Use a capability name with lowercase letters, digits, and single hyphens; connector names are reserved."；`capability-save` 能力包含不存在子项 → "Packs can include only saved MCP servers and skills. … is not one."（两者都在写入前拒绝）。

**源码核对（ds-bot 0.3.10 `src/host/capabilities.js`、`channel.js`、`store.js`，client `capability-settings.js`）**：
- `capability-save`（界面调用 ui:true）：名称 `^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$` 且 ≤32、github/mail 保留；已存在时不可改 kind；description trim 截 2000；stdio 需非空 command，args 为文本数组、env 为文本值对象，界面保存直接 `approved:true`；HTTP 需 http(s) URL，headers 为文本值对象；skill 存 trim 后的 content；pack 的 items 去重且只能是已保存的 mcp/skill。返回能力对象。
- `capability-delete {name}` → `true`，同时从所有能力包与 Bot 的 capabilities/packs 移除；`capability-approve {name}` → 能力对象（仅 stdio MCP）。
- `bot-capability {botId, name, attached, mode}`：先从两个列表移除再按 `mode`（always→capabilities，catalog→packs）加入；返回该 Bot 的 capabilities 数组。
- `capabilityStatuses`：只为常驻挂载（`bot.capabilities`）生成；单项为该 Bot 各会话中最严重者（error > needs-approval > connecting > ready），能力包取子项最严重者。
- 网页端 Shared capabilities 列表按名称排序、行内 Allow/Edit/Delete（再点一次删除）；表单参数每行一项、环境变量与请求头为 JSON 对象文本（占位 `{"API_KEY": "$DSH_SECRET_MY_KEY"}`）；Bot 能力卡为 Always on / In catalog / Off 三段、能力包在前，显示「经由某包常驻/按需」、挂载后才提示待批准、只对常驻项显示状态——均复刻进 core `CapabilityLibrary`（`CapabilityLibraryTest`）。
- 模拟器验收用 `node scripts/mock-dsh-server.mjs --manage`：内置能力包、出错的 HTTP MCP 与 Bot 提议的未批准 stdio MCP 样本；`/mock/capabilities?library=0|1` 切换 state 是否带能力库，返回能力库、挂载与调用记录。

## 29. 连接器与邮箱（2026-11-24，dsbot-connectors-mail）

**实测（只读，本机 dsh Web 宿主 + DS-Bot 0.3.10，`runSpike` 的 `ProbesConnectors` 探针组，未改动真实连接器、邮箱与规则）**：
- `state` 顶层含 live `connectors`（对象，键 `connectors`、`mail`）、`connectorRequests` 与 `mailRequests`（均为 `{botId → []}` 对象，本机当前为空）；hello `capabilities` 名单不含连接器能力名——客户端以 `state.connectors` 字段存在门控入口。
- `connectors.connectors=[38]`：全部 `state:"off"`；`auth.kind` oauth=31、none=6、key=1；类别 dev=6、docs=7、work=11、commerce=3、data=4、design=5、search=2；无自加连接器、无 `pending`；未连接行键集 `{id, name, category, about, auth, state, account, offFor, tools}`（`account` 为 null，`readOnly` 只在已连接的远程服务出现——与 `rowFor` 一致）。GitHub 行不在列表中（本机 `rt.config.github === false` 分支）。
- `connectors.mail` 键 `{accounts, google, allowedRecipients, noDraftFor}`：0 个账号、`google:false`（本机未配置 Google 客户端，手机端不显示「用 Google 登录」）、规则为空；`pending` 只在有 Google 登录进行中时出现。
- `connector-import-scan {}` → 数组 2 项（来源 cursor），项键 `{source, name, command, args, envNames, headersNames}`（只记数量与键，不记名称与命令）。
- 写端点错误形态（均 HTTP 200、`ok:false`、`error.code="bot/refused"`，均在任何写入前拒绝）：`connector-retry/sign-in-code/set/remove {id:不存在}` → "No such connector"；`connector-add {name:""}` → "Name the connector"；`connector-add` 非 https 地址 → "Connector addresses must start with https://"；`connector-request-cancel {requestId:不存在}` → "This request is gone"；`mail-account-remove {id:不存在}` → "No such account"；`mail-send-answer {requestId:不存在}` → "This card was already answered"；`mail-account-add` 地址无效 → "Enter an email address"；`mail-sign-in-code {input:""}`（无等待中的 Google 登录）→ "No Google sign-in is waiting"。`mail-rule-set` 即使空补丁也会保存，未探。

**源码核对（ds-bot 0.3.10 `src/host/connectors/index.js`、`catalog.js`、`oauth.js`、`mail/index.js`、`mail/presets.js`、`channel.js`，client.js `ConnectorRow`/`MailForm`/`MailSendCard`/`ConnectorsPage`）**：
- OAuth 浏览器登录的回调为 DS-Bot 所在电脑的 `http://127.0.0.1:<FIXED_CALLBACK_PORT 或随机端口>/callback`；手机上授权后浏览器停在不可达的 127.0.0.1 页面，`connector-sign-in-code {id, input}` 接受整段地址（校验本次 `state`）或纯授权码完成登录；GitHub 走设备码（`pending.kind:"device"`，`userCode`、`verificationUri`），后台轮询完成；Slack 等 `clientSetup` 服务先 `needs-client`，`connector-client {id, clientId, clientSecret?}` 后开始浏览器登录。
- `mail-account-add` 先 IMAP 登录验证（socketTimeout 60 秒）再存密钥与账号；`mail-send-answer` 同步 SMTP 发送（连接超时最长 120 秒），发送失败也回 `true` 并把卡标记 `failed`、告知 Bot。手机端把这些端点登记为长读超时（`ConnectorSettings.SLOW_ENDPOINTS`），停靠区的邮件作答也改在读线程发出。
- 写端点成功应答（视图、`{view, created}`、`true`）、登录流程、规则 `applyRules` 与发送卡字段为源码核对，由 `node scripts/mock-dsh-server.mjs --manage`（`scripts/mock-dsh-connectors.mjs`）按同一形态覆盖：内置设备码、浏览器登录（粘贴地址校验 state）、needs-client、key、直连、出错与自加连接器样本、QQ 邮箱账号、规则、待发邮件卡与连接器请求卡；`/mock/connectors?enabled=0|1&complete=<id>&google=0|1&mailCard=1&connectorCard=<id>&reset=1&resetCalls=1` 控制并返回调用记录（只记参数键）。

## 30. IM 接入（2026-11-24，dsbot-im-bridges）

**实测（只读，本机 dsh Web 宿主 + DS-Bot 0.3.10，`runSpike` 的 `ProbesIm` 探针组，未改动真实 IM 连接）**：
- `state` 顶层含 `im`（对象，键 `connections`、`platforms`）与 live `imStatus`（对象）；hello `capabilities` 名单不含 IM 能力名——客户端以 `state.im` 字段存在门控入口。
- `im.platforms=[telegram, weixin, feishu, dingtalk, wecom, qq, discord, slack]`（与 `src/shared/im-platforms.js` 目录顺序一致，即本机全部 8 个平台都有适配器）；`im.connections` 当前为空，`imStatus` 为空对象。
- 写端点错误形态（均 HTTP 200、`ok:false`、`error.code="bot/refused"`，均在写入前拒绝）：`im-update/remove/pair/unpair/chat/retry {id:不存在}` → "No such IM bot"；`im-add {platform:不存在}` → "This platform is not available yet"；`im-login-start/im-login-wait {platform:"telegram"}` → "This platform has no QR login"。
- 取证时 dsh-web 已改为打印 `https://127.0.0.1:13080/?token=…`（之前为 http）；runSpike 的目标改用该行替换为 tailnet 主机名（不回显 token）。

**源码核对（ds-bot 0.3.10 `src/host/im/index.js`、`src/shared/im-platforms.js`、`im/platforms/weixin.js`、`channel.js`，client.js `ImPage`/`AddView`/`QrLogin`/`DetailView`/`GUIDES`）**：
- 连接视图 `viewOf`：`{id, platform, account{id,name}|null, botId|null, settings{非密钥字段}, secrets[密钥字段名], enabled, paired[{id,name,at}], requests[{id,name,chatKey,chatKind,…}], chats{chatKey→{kind,title,target?,at}}, createdAt}`；`imStatusView`：`{connId→{state, error?, at}}`，网页端 `stateOf` 以停用优先、其余未知视为连接中。
- 平台目录只在客户端共享代码里（字段、选项、密钥标记、`login:'qr'`），state 只下发可用平台 id；手机端内置同一目录（core `ImBridges.PLATFORMS`）并按 `im.platforms` 过滤。
- `im-add` 先向平台校验账号（`checkAccount`）再保存，扫码平台拒绝；`im-login-start` 返回 `{loginId, qr, expiresAt, botId}`（`qr` 为编码进二维码的文本），`im-login-wait` 对微信 `get_qrcode_status` 长轮询（`POLL_TIMEOUT_MS=35_000`），返回 waiting/scanned/expired，确认后创建连接并返回 `{status:"done", id}`。手机端把 `im-add/update/retry/login-start/login-wait` 登记为慢端点。
- 写端点成功应答与扫码流程由 `node scripts/mock-dsh-server.mjs --manage`（`scripts/mock-dsh-im.mjs`）按同一形态覆盖：Telegram 已连接（待允许请求、已配对账号、私聊、已绑定与未绑定群聊）、飞书出错（重试成功）、微信扫码；`/mock/im?enabled=0|1&scan=1&confirm=1&expire=1&request=<名字>&reset=1&resetCalls=1` 控制并返回调用记录（只记参数键与字段名）。

## 31. 本地 Agent（2026-11-24，dsbot-local-agents）

**实测（只读，本机 dsh Web 宿主 + DS-Bot 0.3.10，`runSpike` 的 `ProbesLocalAgents` 探针组，未改动真实本地 Agent，未调用会启动 CLI 的 local-agent-models）**：
- `local-agents` 对团队数据只读，但不是无副作用：上游先 `scan()` 并 `applyRoutes()`（可能注册或替换本机 DSH 的本地 Agent 路由），并对找到的 CLI 首次运行 `--version`、每 60 秒运行一次登录状态检查（各自限时）——进本地 Agent 页即会在电脑上短暂运行这些检查；`local-agent-refresh` 不在上游 `READS`，只读宿主会拒绝。
- `local-agents {}` → `{available:true, detected[7], custom[]}`；detected 项键 `{route, name, path, version, signIn, signedIn}`，route 为 `local-claude-code`、`local-codex-cli`、`local-copilot-cli`、`local-antigravity`、`local-opencode`、`local-kimi-cli`、`local-qoder`；`signedIn` 分布 true=2、null=5（无登录探测的 CLI 为 null）。本地 Agent 不在 state 中。
- 写端点错误形态（均 HTTP 200、`ok:false`、`error.code="bot/refused"`，均在写入前拒绝）：`local-agent-update/remove {id:不存在}` → "Unknown agent"；`local-agent-add` 命令含空白 → "Command must be a program name, an absolute path, or a ~/path"。

**源码核对（ds-bot 0.3.10 `src/host/local-agents/index.js`、`engines.js`、`adapter.js`，client.js `LocalAgentsPage`/`AgentSignInRow`/`AgentForm`/`shortVersion`）**：
- 自定义 Agent 路由为 `local-acp-<id>`（`CUSTOM_PREFIX`），custom 项 `{id, name, command, args[], route, available}`；`local-agent-add` 经 `validateCustom` 校验并在电脑上找不到程序名时拒绝，返回条目+route；`local-agent-update` 返回条目+route；`local-agent-remove` 返回 custom 视图；网页端写后重读 `local-agents`。
- `local-agent-refresh` 清模型缓存与登录探测后重扫并刷新模型目录；`local-agent-models {route}` → `[{provider, id, name, reasoning?}]`，首次会在电脑上启动该 CLI。手机端把 local-agents、refresh、models、add 登记为慢端点。
- 写端点成功应答、可用模型与「此远端不支持」（未知端点 bot/not-found、available=false）由 `node scripts/mock-dsh-server.mjs --manage`（`scripts/mock-dsh-local-agents.mjs`）按同一形态覆盖；`/mock/local-agents?enabled=0|1&available=0|1&reset=1&resetCalls=1` 控制并返回调用记录（只记参数键）。

## 32. 模型服务商（2026-11-24，dsbot-models-providers）

**实测（只读，本机 dsh Web 宿主 + DS-Bot 0.3.10，`runSpike` 的 `ProbesProviders` 探针组，未改动真实服务商、模型与 key，未调用会请求外部端点的 provider-models）**：
- `providers {}` → `{available:true, providers[1], addable[42], protocols[openai-completions, openai-responses, anthropic-messages, google-generative-ai], revision:<数字>}`；provider 项键 `{id, name, models, declaredModels, refreshable, key, removable, custom}`（`error` 只在出错时出现），本机唯一的服务商为自加、可刷新、可删除，`key:{configured:true, writable:true}`，models 与 declaredModels 各 2 个。视图不含任何 key 值。服务商不在 state 中，hello 不含相应能力名。
- `providers` 读取本身会运行上游 `repairRoutes()`（为自定义路由补齐 compat 默认值），属上游读取行为，探针未做任何写入。服务商写入只 `dropCatalog()`、不推进团队 `state.revision`：state 应答带 `modelsKey`，客户端须在 state 请求中带 `models:<上次 modelsKey>`，否则模型目录不会刷新（评审发现，手机端已改为携带）。
- 写端点错误形态（均 HTTP 200、`ok:false`、`error.code="bot/invalid"`，均在写入前拒绝）：`provider-key` 带引号的 key → "Paste the key without quotes"（checkKey 先于路由检查）；`provider-key {provider:不存在}` → "… takes no API key here"；`provider-add {provider:不存在}` → "DSH offers no provider …"；`provider-models-save {provider:不存在}` → "No editable provider …"；`provider-remove {provider:不存在}` → "… is not set up"。

**源码核对（ds-bot 0.3.10 `src/host/providers.js`、`channel.js`，client.js `ProvidersPage`/`ProviderRow`/`ModelDraft`/`KeyEditor`/`AddProvider`/`BotModelsPage`/`bot-model-batch`）**：
- `provider-key {provider, value}`：`value:null` 移除；写入前 checkKey（非空、非 NAME=value、无引号、仅可见 ASCII），路由不用 key 或 key 在外部设置时拒绝；`provider-add` 内置 `{provider, key?}` 或自定义 `{custom:true, provider(^[a-z][a-z0-9-]*$), name?, baseURL(http/https), api(protocols 之一), key?, models[≥1]}`；`provider-models {provider}` 或 `{baseURL, api?, key?}`（key 只随请求、不保存）；`provider-models-save {provider, models, revision?}`（revision 不一致回 `bot/stale`）；`provider-remove {provider}` 仅自加。写端点都返回 providersView。
- 网页端保存模型前按 fallbacksFor 提示受影响 Bot（手机端只列出受影响 Bot，不推算回退目标）；Bot models 批量切换逐个 `update-bot {id, model}`，跳过本地 Agent 上的 Bot。
- 写端点成功应答、revision 冲突与目录联动由 `node scripts/mock-dsh-server.mjs --manage`（`scripts/mock-dsh-providers.mjs`，同时作为 state.models 来源）覆盖；`/mock/providers?enabled=0|1&available=0|1&bumpRevision=1&reset=1&resetCalls=1` 控制并返回调用记录（只记参数键）。

## 33. 定时任务（2026-11-24，dsbot-schedules）

**实测（只读，本机 dsh Web 宿主 + DS-Bot 0.3.10，`runSpike` 的 `ProbesSchedules` 探针组，未改动用户真实定时任务）**：
- `schedules {}` → `{available:true, canPause:true, tasks[23]}`；顶层键 `[available, canPause, tasks]`；日程不在 `state` 中，`hello` 的 `capabilities` 名单不含日程能力名，客户端入口不门控、按应答降级（`bot/not-found` 说明远端不支持，`available:false` 说明需在 DSH 插件页开启 Automation tasks）。
- 实测 `tasks` 分布：`kind` 为 `{rrule=9, daily=3, cron=1, at=9, after=1}`；`status` 为 `{active=12, ended=10, paused=1}`；`state` 为 `{scheduled=12, finished=10, paused=1}`；23 项均带 `botId`，其中部分任务同时带 `botId` 与 `roomId`（Bot 在群聊会话创建的任务——客户端归入该 Bot 分组并在行内注明所在群聊，不重复归入群聊分组）。
- 实测任务键集覆盖 `{id, title, prompt, kind, scheduledAt, status, state, sessionId, botId, roomId?, time?, timeZone?, expression?, rrule?, afterSeconds?, createdAt?, updatedAt?, lastDelivery?, involvedSessionIds?, target?}`（客户端解码保留展示与操作所需字段，安全忽略其余字段）。
- 写端点错误形态（均 HTTP 200、`ok:false`、`error.code="bot/invalid"`，均在任何写入前拒绝）：`schedule-create` 未知 `target`（含空标题时仍先校验对象）→ `"No such Bot or group chat"`；`schedule-pause` 未知 `{sessionId, id}` → `"No such task"`；`schedule-delete` 未知 `{sessionId, id}` → `"No such task"`。

**源码核对（ds-bot 0.3.10 `src/host/schedules.js`、`channel.js`，client.js `useSchedules`/`timingText`/`nextText`/`canPauseTask`/`TaskRow2`/`TaskForm`/`SchedulesPage`/`BotSchedules`）**：
- `schedule-create {target, title(1–120), prompt, when: {kind:'at', at(ISO，将来)} | {kind:'daily', time(HH:mm[:ss]), timeZone} | {kind:'weekly', time, timeZone, weekdays[1–7，至少一个]} | {kind:'every', minutes(1–10080)}}` → 返回最新 `schedules` 视图；`schedule-pause {sessionId, id, active}`（`canPause` 为真时可用，到点的一次性任务不可暂停）→ 返回最新视图；`schedule-delete {sessionId, id}` → 返回最新视图。`schedules` 在上游 `READS` 内。
- 写端点成功应答、到点一次性任务不可暂停、`canPause` 与 `available` 开关及不支持降级由 `node scripts/mock-dsh-server.mjs --manage`（`scripts/mock-dsh-schedules.mjs`）覆盖；`/mock/schedules?enabled=0|1&available=0|1&canPause=0|1&reset=1&resetCalls=1` 控制并返回调用记录（只记参数键）。

## 34. 密钥设置与落盘加密（2026-11-25，dsbot-secrets-settings）

**实测（只读，本机 dsh Web 宿主 + DS-Bot 0.3.10，`runSpike` 的 `ProbesSecrets` 探针组，未改动用户真实密钥与存储文件）**：
- `state` 顶层与 `live` 均携带 `secrets` 与 `secretStore`：`secrets` 数组 1 项，条目键集 `[createdAt, lastUsedAt, name, purpose, requestedBy, scope, updatedAt]`，不含 `value` 字段；`secretStore` 键 `[locked, mode, source]`，`mode=plaintext`、`locked=false`、`readOnly=false`。`hello` 的 `capabilities` 名单不含密钥管理能力名，客户端以 `state.secrets` 存在门控入口、以 `state.secretStore` 存在显隐落盘加密卡。
- `secret-store {}`（上游 `READS`）→ 键 `[locked, mode, source]`，`mode=plaintext`、`locked=false`、`source=null`、`reason=null`，与 `state.secretStore` 一致。
- 写端点错误形态（均 HTTP 200、`ok:false`、`error.code="bot/refused"`，均在任何写入前拒绝）：`secret-set` 短值 → `"A key must have at least 4 characters"`；`secret-set` 非法名 → `"A name uses capitals, digits, and underscores, and starts with a letter"`；`secret-update` 不存在的键 → `"No such key"`；`secret-delete` 不存在的键 → `"No such key"`；`secret-unlock` / `secret-encrypt` / `secret-decrypt` 短口令 → `"A passphrase has at least 8 characters."`。

**源码核对（ds-bot 0.3.10 `src/host/secrets.js`、`secrets-crypto.js`、`channel.js`，client.js `SecretsPage`/`SecretRow`/`ScopeEditor`/`PurposeEditor`/`ValueEditor`/`AddSecret`/`SecretStoreCard`）**：
- `secret-set` 承担两类写入：带 `requestId` 时为聊天停靠区密钥请求卡作答；带 `name` 时为设置页新增密钥（`{name, value, purpose?, scope?}`）或替换已有密钥的值（`{name, value}`，保留既有 `purpose` 与 `scope`）。`secret-update {name, scope?, purpose?}` 仅更新作用 Bot 与用途、不接收 `value`；`secret-delete {name}` 删除密钥。
- 存储加密端点：`secret-unlock {passphrase}` 为当前进程解锁（不改文件，属 `READS`，只读宿主亦可解锁）；`secret-encrypt {passphrase}` 将明文文件原子改写为加密信封（已加密或只读宿主拒绝）；`secret-decrypt {passphrase}` 验证口令后写回明文，亦为锁定态的恢复路径（未加密或只读宿主拒绝）。口令长度 8–1024 且不做 trim（空白字符有意义）。
- 写端点成功应答、锁定态、口令校验、只读宿主与无 `secrets`/`secretStore` 降级由 `node scripts/mock-dsh-server.mjs --manage`（`scripts/mock-dsh-secrets.mjs`）覆盖；`/mock/secrets?enabled=0|1&storeEnabled=0|1&readOnly=...&lock=1&reason=...&request=0|1&reset=1&resetCalls=1` 控制并返回调用记录（只记参数键，绝不输出值与口令）。

## 35. 关于 DS Bot 与诊断（2026-11-25，dsbot-system-about）

**实测（只读，本机 dsh Web 宿主 + DS-Bot 0.3.10 / dsh 0.2.2-rc.3，`runSpike` 的 `ProbesSystem` 探针组；未调用 `update-install`、`dsh-update-install`、`diagnostics-clear`，只记键集、枚举、计数与长度，不记诊断条目与日志文本）**：
- `update-check {fresh:false}` → 键 `[available, checkedAt, current, dsh, enabled, error, installed, latest, name, notesUrl, phase, repoUrl, source]`，`current=latest=0.3.10`、`source=registry`、`enabled=true`、`phase=idle`、`available=false`，`repoUrl`/`notesUrl` 为 GitHub https 链接；`dsh` 子视图键 `[available, blockedBy, checkedAt, current, error, installed, kind, phase, verified]`，`current=0.2.2-rc.3`、`verified=null`、`kind=npm`、`phase=idle`、`available=false`。
- `repo-stars {}` → 整数。
- `diagnostics {client}` → 键 `[entries, facts, issuesUrl, markdown, total]`；`facts` 键 `[botVersion, dshVersion, models, node, os, team]`（只读宿主时另有 `readOnly`）；`entries` 50 条、按时间新到旧、`total=200`（上游 KEEP 上限）；条目键集为 `{time, level, source, text}` 加可选 `code`、`stack`（另有 `bot`/`where`/`status` 按来源出现），本机样本全为 `level=warn`、`source=host`。
- `diagnostics-log {}` → 已脱敏纯文本（每行一条，栈帧缩进；本机约 81 KB、579 行）。

**源码核对（ds-bot 0.3.10 `src/host/channel.js`、`update.js`、`dsh-update.js`、`diagnostics.js`，client.js `botHint`/`dshHint`/`showsDshRow`/`AboutPage`/`FeedbackPage`/`wakeUpdate`）**：
- 7 个端点均在上游 `READS`（只读宿主也可调用），mobile.js `CAPABILITIES` 无对应能力名。`update-check` 非 fresh 时 10 分钟内返回缓存，fresh 时串行请求 npm 注册表（官方与镜像）或 GitHub，单次 8 秒超时——手机端登记为慢端点（`repo-stars` 同理，上游缓存 1 小时）。
- `update-install` / `dsh-update-install` 立即返回 `phase=installing` 的视图（已在安装中原样返回）；无可用更新、dsh 随桌面应用更新或宿主不支持时抛错，经 channel 映射为 `bot/refused` 加原文。结果只经后续 `update-check` 读回：DS-Bot `installing→installed`（HMR 下先卸载再装，宿主会短暂消失，`updated` 由重启后的标记文件给出），dsh `installing→installed|failed`（`npm install -g @deepseek-ai/dsh@<verified>`）。网页端在任一 `installing` 时以 2 秒间隔重读，否则 60 分钟。
- `diagnostics-clear` 清空内存与 `logs/diagnostics.jsonl`，返回 `true`。
- 成功应答与各状态由 `node scripts/mock-dsh-server.mjs --manage`（`scripts/mock-dsh-system.mjs`）覆盖：`/mock/system?enabled=0|1&stars=0|1&installMs=<ms>&fail=1&source=...&checks=0|1&latest=<v|none>&dshKind=npm|app&dshVerified=<v|none>&blockedBy=<v|none>&noDsh=1&restart=1&bulk=<n>&reset=1&resetCalls=1`（调用记录只记参数键；`bulk` 追加带栈帧的告警以验证约 80 KB 完整日志的渲染）；`/mock/drop-feed` 在 `--manage` 下同样可用，用于模拟电脑上更新后服务重启、App 重连。

## 36. 用量（2026-11-25，dsbot-usage）

**实测（只读，本机 dsh Web 宿主 + DS-Bot 0.3.10，`runSpike` 的 `ProbesUsage` 探针组；只记键集、计数、形态与字节数，不记名称、模型 id 与 token 数值）**：
- `usage {timeZone}` → 顶层键 `[kinds, models, now, owners, rows, scanning]`；本机应答约 59 KB、`rows` 1219 行（最早 2026-10，约一个半月的账本），每行均为 9 元组 `[hour, owner, model, kind, input, cacheRead, cacheWrite, output, calls]`，小时键全部形如 `YYYY-MM-DD HH` 且按 hour 升序；`now` 同为小时键；`scanning=false`。体积对手机无压力，不需要分页或截断。
- `owners` 16 项：`kind` 为 `{bot=12, room=3, other=1}`，其中 1 项带 `deleted:true`（已删除的群聊）；键集为 `[key, kind]`（other）与 `[id, key, kind, name]`（另有 `deleted`）。`models` 3 项，键集 `[key, model, name, provider]`，部分另有 `providerName`。`kinds` 为 `[chat, session-title, group, memory, compaction, side, image]`（客户端对未知用途原样显示）。
- 非法 `timeZone`（`Not/AZone`）→ 正常应答（服务端回退到自身时区，上游 `hourKeys` 的 try/catch），不报错。
- `usage-summary {botId, timeZone}` → 键 `[botId, day, models, month, scanning, week]`，`day/week/month` 键 `[cacheRead, cacheWrite, calls, input, output]`，`models[]` 键 `[cacheRead, cacheWrite, calls, input, key, name, output]`；未知 Bot → HTTP 200、`ok:false`、`error.code="bot/not-found"`、`"Unknown Bot"`。
- 夹具：`android/core/src/test/resources/dsbot/usage-view.json` 由本探针写出（名称与键替换为序号、token 数值替换为合成值、只留最近 48 行，结构与下标关系保持真实）；`usage-summary.json` 按实测键集构造合成值。

**源码核对（ds-bot 0.3.10 `src/host/usage.js` 的 `usageView`/`hourKeys`、`mobile.js` 的 `usageSummary`/`totals`、`channel.js` 的 `usage` 分发与 `READS`，client.js 的 `src/client/usage-data.js` 与 `usage-page.js`）**：
- `usage` 只返回全部历史的小时明细，不接收范围参数；范围分桶、上期对比、排行与热力图全部在网页端客户端计算。上游先等账本扫描至多约 6 秒再以已有数据应答（`scanning` 为真表示仍在读旧会话），不属慢端点。
- 网页端口径：总 token = input + cacheRead + cacheWrite + output；显示的「输入」含 cacheWrite；缓存命中率 = cacheRead /（input + cacheWrite + cacheRead）；`usage-summary` 的 day/week/month 为滚动的 24、7×24、30×24 小时。
- 手机端在 core `Usage` 中复刻上述算法；`UsageTest` 以 `resources/dsbot/usage-ref.mjs` 在 node 中直接运行 `usage-data.js` 原文对同一夹具生成的期望值（`usage-expected-*.json`）逐范围全量比对。
- 界面验收由 `node scripts/mock-dsh-server.mjs --manage`（`scripts/mock-dsh-usage.mjs`）覆盖：多 Bot、群聊、DSH Agent、其他与已删除 Bot，多模型多服务商，跨 13 个月的小时行，按请求 `timeZone` 生成小时键；`/mock/usage?enabled=0|1&summary=0|1&scanning=0|1&empty=1&reset=1&resetCalls=1` 控制并返回调用记录（只记参数键）。

## 37. 后台任务详情（2026-11-25，dsbot-bot-task-detail）

**实测（只读，本机 dsh Web 宿主 + DS-Bot 0.3.10，`runSpike` 的 `ProbesBotTask` 探针组；只记键集、计数与错误码，不记任务标题、Bot 名称与正文内容）**：
- `state.bots[].tasks` 实测发现 24 条已结束任务（`status: completed`）；取首条只读探测 `bot-task-read`：
  - `bot-task-read {botId, taskId}` → 顶层键 `[cursor, items, sessionId]`，`items` 14 条，kind 分布 `{user=1, bot=12, event=1}`，键集 `user/bot=[kind, seq, sessionId, text, time, turn]`、`event=[kind, role, seq, sessionId, text, time, turn]`，`cursor=null`（到最早为 JSON `null`）。
  - `bot-task-read {botId, taskId, steps:true}` → 顶层键 `[cursor, items, sessionId]`，`items` 15 条，kind 分布 `{user=1, bot=12, event=1, steps=1}`，新增 `steps` 条目键集 `[kind, ms, time, tools, turn]`（不带 `sessionId` 与 `seq`），`tools[]` 键集 `[label, name]`（`ok` 为可选布尔值），`cursor=null`。
  - 脱敏夹具：`android/core/src/test/resources/dsbot/bot-task-read-steps.json`（由本次 `runSpike` 经 `Sanitize.json` 脱敏写出）。
- 错误形态（均 HTTP 200、`ok:false`、`error.code="bot/not-found"`，严格遵守 `spike-write-probes-non-mutating` 未向真实任务发写请求）：
  - `bot-task-read` 未知 Bot → `"Unknown Bot"`；真实 Bot + 未知任务 → `"Unknown task"`。
  - `bot-task-send` 未知 Bot → `"Unknown Bot"`；真实 Bot + 未知任务 → `"Unknown task"`。

**源码核对（ds-bot 0.3.10 `src/host/channel.js` 853–869 行与 `READS`、`bot-tasks.js` `taskViewOf`/`deliverToTask`、`parts.js` `taskPage`/`partPage`/`displayItems`/`stepsItems`/`applySteps`、`client.js` `TaskReaderView`）**：
- `bot-task-read` 在上游 `READS` 内（只读宿主也可读），`bot-task-send` 为写端点（只读宿主 `bot/read-only`，空文本或任务已结束回 `bot/invalid`，成功返回 `{botId, taskId, sessionId}`）。
- `parts.js` 在 `taskView: true` 下额外产生 `kind: "task-brief"`（`{name?, text, time, seq, sessionId}`）与跨分段时的 `kind: "divider"`（`{time}`）；`role: "task-followup"` 经 `displayItems` 转为 `kind: "user"`；翻页 `cursor` 形态与 `chat` 一致（`{part, beforeSeq?}` 或 `null`）。
- 网页端 `TaskReaderView` 活动态每 2.5 秒静默刷新最新一页，并与已加载的更早页按 `(item.sessionId ?? page.sessionId, seq)` 去重拼接；任务由活跃转为结束时执行一次最终刷新并切只读（隐藏输入框与停止按钮）。
- `bot-task-read`/`bot-task-send` 不在 0.3.10 `mobile.js` `CAPABILITIES` 内，入口随私聊后台任务条按 `state.bots[].tasks` 字段存在显隐。
- 界面验收由 `scripts/mock-dsh-server.mjs`（`--chat-bars` 与 `--manage`）覆盖 `bot-task-read` 分页与 `steps=true`、`bot-task-send` 追加与结束态 `bot/invalid` 及 `/mock/bot-tasks` 结束切换。

## 38. DeepSeek 服务（2026-11-25，dsbot-deepseek-services）

**实测（只读，本机 dsh Web 宿主 + DS-Bot 0.3.10，`runSpike` 的 `ProbesDeepSeekServices` 探针组；严格遵守 `spike-write-probes-non-mutating`，不修改真实团队偏好，不触发付费语音合成）**：
- `deepseek-services {}` → HTTP 200、`ok:true`，顶层键 `[endpoint, key, ocr, preferences, speech, tts]`：
  - `key` 键 `[configured, ref]`（本机 `configured=true`，无 `reason`）
  - `ocr` 键 `[configured, enabled, supported]`（`supported=true, enabled=false, configured=true`，无 `reason`）
  - `tts` 键 `[configured, enabled, supported, voices]`（`supported=true, enabled=false, configured=true, voices=[stella, tide]`，无 `reason`）
  - `speech` 键 `[configured, providerId, ready, reason, registered]`（`registered=false, providerId=deepseek-asr, configured=true, ready=false`，`reason="This DSH has no speech-to-text service (voice input is not mounted)"`）——确认 0.3.10 听写状态字段名为 `speech` 而非 `asr`
  - `preferences` 键 `[ocr, tts, voice]`（`ocr=false, tts=system, voice=stella`）
  - 脱敏夹具：`android/core/src/test/resources/dsbot/deepseek-services.json`（由本次 `runSpike` 经 `Sanitize.json` 脱敏写出）。
- 错误形态（均 HTTP 200、`ok:false`）：
  - `deepseek-services-config {"ocr":"bad"}` → `error.code="bot/invalid"`、`"ocr must be true, false or null"`
  - `deepseek-services-config {"tts":"bad"}` → `error.code="bot/invalid"`、`"tts must be \"deepseek\", \"system\" or null"`
  - `deepseek-services-config {"voice":"bad-voice"}` → `error.code="bot/invalid"`、`"voice must be stella or tide or null"`
  - `deepseek-synthesize {"text":"   "}` → `error.code="bot/unavailable"`、`"DeepSeek text-to-speech is off. Turn it on in DS Bot settings."`
  - `deepseek-synthesize {"text":"hi","voice":"bad-voice"}` → `error.code="bot/unavailable"`、`"DeepSeek text-to-speech is off. Turn it on in DS Bot settings."`

**源码核对（ds-bot 0.3.10 `src/host/deepseek-services.js`、`channel.js` 186 行 `READS` 与 870–873 行分发、`client.js` `src/client/services-settings.js`）**：
- `deepseek-services` 与 `deepseek-synthesize` 在上游 `READS` 内（只读宿主也可读），`deepseek-services-config` 为写端点（只读宿主 `bot/read-only`）。
- `deepseek-services-config` 接受 `{ocr?: true|false|null, tts?: "deepseek"|"system"|null, voice?: "stella"|"tide"|null}`，`null` 删除已存覆盖恢复默认（`{ocr:false, tts:"system", voice:"stella"}`），写入保存后返回更新后的 `servicesStatus()` 视图。
- `deepseek-synthesize {text, voice?}` 在上游向 `https://api.deepseek.com/v1/tools/tts` 发起请求（`TTS_TIMEOUT_MS = 300_000`，属慢端点），成功返回 `{audioBase64, mediaType: "audio/wav"}`；TTS 关闭或 key 未配置回 `bot/unavailable`，空文本、非法音色、计费字符数超 10,000（汉字计 2 其余计 1）或请求体超 60 KiB 回 `bot/invalid`，上游 HTTP 或 WAV 头校验失败回 `bot/refused`。
- `deepseek-services` / `deepseek-services-config` / `deepseek-synthesize` 不在 0.3.10 `mobile.js` `CAPABILITIES` 内，入口不门控，按 `on-demand-page-degrade` 随应答降级；听写服务商选择在网页端走 DSH 内部通道 `actions.speech().configure`，不在 `/api/bot` 端点内，手机端只读展示 `speech` 状态。
- 界面验收由 `scripts/mock-dsh-server.mjs --manage`（`scripts/mock-dsh-deepseek-services.mjs`）覆盖状态展示、key 缺失/受阻、配置修改与 `null` 恢复默认、试听 WAV 内存播放与错误提示及 `enabled=0` 不支持降级。

## 39. General 高级项（2026-11-25，dsbot-general-advanced）

**实测（只读，本机 dsh Web 宿主 + DS-Bot 0.3.10 / dsh 0.2.2-rc.3，`runSpike` 的 `ProbesGeneralAdvanced` 探针组；严格遵守 `spike-write-probes-non-mutating`，不改动真实团队偏好）**：
- `state` 只读检查：`state.memory=true`，`state.prefs` 键集 `[defaultBotModel, theme, width]`，`memoryAutoRecall`、`memoryLimits`、`backgroundAfterSec` 均为缺省未落盘（`null`）。
- `set-prefs` 越界/非整数错误形态（均 HTTP 200、`ok:false`、`error.code="bot/invalid"`，在 `rt.save()` 之前抛出）：
  - `set-prefs {"backgroundAfterSec":601}` → `"The background wait is 0 to 600 seconds"`
  - `set-prefs {"backgroundAfterSec":-1}` → `"The background wait is 0 to 600 seconds"`
  - `set-prefs {"backgroundAfterSec":1.5}` → `"The background wait is 0 to 600 seconds"`
- **非破坏性取证边界**：严禁对真实服务探测非法 `memoryLimits` 或 `memoryAutoRecall`，因为上游 `channel.js` 246–254 行不会抛 `bot/invalid`，而是过滤后静默 `delete state.prefs.memoryLimits` / `delete state.prefs.memoryAutoRecall` 并执行 `rt.save()`。

**源码核对（ds-bot 0.3.10 `src/host/channel.js` 35–37 与 240–255 行、`constants.js` 89–90 行、`memory-entries.js` 9 行、`steer.js` 88–91 行、`client.js` `MEMORY_SIZES`/`MemorySize`/`SecondsField`/`GeneralPage` 42154–42183 与 42421–42507 行）**：
- 里程碑 intake 问题 `general-advanced-endpoint` 结论：General → Advanced 的记忆与后台工作高级项**全部经既有 `POST /api/bot` `set-prefs` 写入**并随 `state.prefs` 返回（非 DSH 私有通道）；仅 Classic Agent 模式（`actions.setSurface`）、DSH 设置槽位（`settings.general.item`）与桌面应用数据目录迁移（`dsBotApp.moveData`）不经 `/api/bot`。
- `memoryAutoRecall`：默认 `true`（仅 `false` 落盘，传 `true`/`null` 等非 `false` 值删除键）。
- `memoryLimits`：作用域固定为 `group`（默认 12000）、`bot`（默认 8000）、`team`（默认 4000），合法值 `1000..200000` 整数；有效项与已有 `state.prefs.memoryLimits` 浅合并，传 `null` 或过滤后为空时删除整项 `state.prefs.memoryLimits`——因此手机端必须在发送前做 `1000..200000` 整数校验，避免非法值误清全部记忆上限。
- `backgroundAfterSec`：默认 `5` 秒（`BACKGROUND_WAIT_MS = 5_000`），合法值 `0..600` 整数（`0` 表示立即转后台），传 `null` 删除键恢复默认，越界或非整数回 `bot/invalid`。
- 界面验收由 `scripts/mock-dsh-server.mjs --manage`（`scripts/mock-dsh-manage.mjs` 扩展 `set-prefs`）覆盖开关、三项记忆容量、后台秒数、本地非法范围拦截、恢复默认与保留未改动 `width` 等偏好。

## 40. 群轮次（2026-11-25，dsbot-group-rounds）

**实测（只读，本机 dsh Web 宿主 + DS-Bot 0.3.10，`runSpike` 的 `ProbesGroupRounds` 探针组；只记键集、计数、类型与错误形态，不记群名、成员名与正文；遵守 `spike-write-probes-non-mutating`）**：
- `room-chat {roomId}`（2 个真实群）→ 顶层键 `[cursor, items, roomId, running, speaking]`，**`running` 为布尔（`false`）、`speaking` 为 `null`**——与 0.3.5 的 botId 数组形态不同（客户端此前按字符串列表解码，从 room-chat 拿不到进行中与正在回复的成员）。
  - 群 A：7 条，kind 分布 `{user=1, bot=6}`，6 条为轮次展开行（`id` 形如 `<callId>:<index>`），6 条带 `duration`。
  - 群 B：57 条，kind 分布 `{event=16, bot=35, user=6}`，event 分布 `{working=16}`，31 条轮次展开行，14 条带 `duration`，`live` 行 0 条（当时无进行中轮次）。
  - 键集：`bot=[botId, color, duration?, id, kind, name, sessionId, text, time]`、`user=[id, kind, text, time]`、`event=[botId, color, event, id, kind, name, time]`（轮次事件**无 `text`**，客户端此前按 note/text 渲染，全部不可见）。
  - 脱敏夹具：`android/core/src/test/resources/dsbot/room-chat-rounds.json`（群 B，经 `Sanitize.json` 写出）。
- `group-rows`：未知群 `{roomId:"spike-none", from:1}` 与非整数 `from` → HTTP 200、`ok:true`、`{open:false, rows:[], speaking:[]}`（无 `active` 键）；真实群 `{from:1, to:1}` → 键 `[active, open, rows, speaking]`，`rows` 1 条，行键 `[botId, color, name, text]`，`open=false, active=false, speaking=[]`。
- `group-relay-replies`：未知 session 与不含省略标记的预览 → `ok:true`、`value:null`。
- `changes-check {sessionId:"spike-none", seq:1}` → `ok:true`、`value:false`。

**源码核对（ds-bot 0.3.10 `src/host/channel.js` 186 行 `READS` 与 369–386 行、`mobile.js` `roomChat`/`roomItems`/`replyItem`/`ROUND_EVENTS`、`groups.js` `rowsView`/`groupRelayReplies`、`changes.js` `changesAvailable`、`client.js` `GroupReplies`/`RelayEvent`/`openChanges`）**：
- 三端点均在 `READS` 内。`mobile.js roomItems` 对带 range 的 `group_relay` 结果调用 `groupRows` 在服务端展开为 user/bot/event 条目，最新页按 `roomProgress` 插入进行中轮次的 `live: true` 行；`running` 为 `running.length > 0`，`speaking` 为首个进行中轮次的发言成员 id 或 `null`。手机端以 room-chat 为唯一来源，不再调 `group-rows`。
- `group-relay-replies` 只恢复账本之前旧群被 save-token 压缩的转述结果，需要原始结果文本；room-chat 对此类结果（`JSON.parse` 失败）直接跳过且不提供原文，手机端不显示这类轮次（与网页端的残余差异）。
- `changes-check` 只用于桌面端打开 DSH 改动审阅页前的判定；room-chat 条目不含 `changes` 字段，手机端无改动入口。
- 轮次事件 `ROUND_EVENTS = done/stopped/working/approval/failed/missed/lookup/resumed/cut`，客户端按 `client.js RelayEvent` 的中英文案渲染为系统行（core `RoomChat.roundEventLine`）；failed 原因按 `failureLabel` 顺序：`FAILURES` 18 个已知码的本地化标签 → 条目 text（上游 groups.js 写入的是原始错误消息）→ code → 未知错误。
- 成员归属：网页端 post/relay/event 以 `roster.byId[botId]` 为先、条目 name/color 为后备（client.js 31309、31330）；实测群 B 的 35 条 bot 行中 27 条不带 `color`。客户端以 `state.bots` 名册为先（core `RoomChat.roster`/`speakerOf`），名称都缺时显示「某个 Bot」。
- 残余差异：轮次发言的 `duration` 耗时标签（网页端 ≥1 秒显示）手机端不显示；room-chat 的 `event=schedule` 条目网页端显示「定时任务：…」，手机端沿用原始文本（不属本变更）。
- 界面验收由 `scripts/mock-dsh-server.mjs --group-rounds` 覆盖：room-chat 回 0.3.10 形态（`running: true`、`speaking: "bot-2"`），条目含已结束轮次展开行（带 `duration`）、全部 9 种轮次事件与进行中轮次的 `live` 行。

## 41. 电脑操控设置（2026-11-25，dsbot-computer-settings）

**实测（只读/非变更，本机 dsh Web 宿主（Linux）+ DS-Bot 0.3.10，`runSpike` 的 `ProbesComputer` 探针组；只记键集、计数与错误形态，不记 Bot 名称、应用名与 id；遵守 `spike-write-probes-non-mutating`，不调用 `computer-set-enabled` 与 `computer-provider-retry`）**：
- `state.computer` → 键 `[bots, enabled, macos, provider]`，`enabled=false`、`provider={state:"off"}`（仅 `state` 键）、`macos=false`，`bots` 12 条（与名册一致），条目键 `[apps, level]`，级别全部 `background`，授权总数 0——**dsh Web 宿主下发的是完整视图而非计划变更假设的 `{enabled:false}`**，关闭状态下无法从 state 区分桌面宿主与 Web 宿主。脱敏夹具沿用 `android/core/src/test/resources/dsbot/state-full.json`（已含同形 `computer`）。
- `computer-system-status {}` → HTTP 200、`ok:true`、`value:null`（宿主无 `cuaAccess` 服务）。
- 错误形态（均 HTTP 200、`ok:false`，抛错均早于写入与 `rt.save()`）：
  - `computer-set-level {"botId":"spike-none","level":"view"}` → `bot/refused`、`"Unknown Bot"`
  - `computer-set-level {botId:<真实 Bot>, level:"spike-bad"}` → `bot/refused`、`"Level must be one of off, view, background, foreground"`
  - `computer-revoke-app {"botId":"spike-none","appKey":"spike"}` → `bot/refused`、`"Unknown Bot"`
  - `computer-permission {"permission":"accessibility"}` → `bot/refused`、`"System permissions are not available here"`
  - `computer-spike-none {}` → `bot/not-found`、`"Unknown endpoint spike-none"`（computer- 前缀分发到 computerEndpoint 的 default 分支）

**源码核对（ds-bot 0.3.10 `src/host/computer/index.js` 1101–1354 行 `setEnabled`/`setLevel`/`revokeApp`/`computerEndpoint`/`computerView`、154–205 行 `ensureProvider`、`computer/catalog.js` 92 行 `LEVELS`、`channel.js` 134 行 `view().computer`、186 行 `READS`、883–885 行分发、39–42 行 `errorCode`，client.js `src/client/computer.js` `LEVELS`/`levelId`/`PROVIDER_TEXT`/`providerWorks`/`ComputerPage`/`BotComputerCard`）**：
- `computer-set-enabled {enabled}` 与 `computer-provider-retry {}` 返回 `computerView()`；`computer-set-level` 与 `computer-revoke-app` 返回 `true`，均 `rt.save()` 产生新 revision（provider 状态变化同样保存）。`set-level` 的 `background` 删除已存级别（缺省即 background），`off` 中止该 Bot 在途调用并释放占用。
- `provider.state` 取值 `off|loading|ready|error|missing|occupied|unsupported`；`missing` 带 `message` 与 `install{packages, command}`，`error` 带脱敏后的 `message`，`occupied`/`ready` 带 `name`。PROVIDER_PACKAGES 含 darwin/win32/linux，因此 Linux 宿主开启后多为 `missing`（未装 `@deepseek-ai/dsh-computer-use` 与提供方包）或无加载器时的 `error`（`This DSH cannot load packages.`），`unsupported` 只在其他平台出现。
- 宿主支持判定（客户端 `ComputerSettings.View.hostUnsupported`）：最小视图、`unsupported`，以及开启后组件无法装入——`missing`，或 `error` 且 `message` 恰为上游常量 `"This DSH cannot load packages."`（`importPackage` 的 `bot/no-loader`，整串相等比较）；其他 `error` 与 `occupied` 属可重试的组件错误。
- `computer-permission` 没有无效参数可用（非法值回落成 `accessibility`），探针只在同一轮 `computer-system-status` 回 `value:null`（宿主无系统权限服务）时才发，否则跳过，避免在 macOS 桌面宿主上弹出真实授权框。
- `computer-system-status` 在 READS 内，其余 computer-* 为写端点（只读宿主 `bot/read-only`）。macOS 权限形态取自提供方 `cuaAccess.systemStatus()`，本机无法实测；网页端按 `perms[key]?.state ?? perms[key]` 判 `granted`/`true`，客户端按同两种形态容错（待 macOS 宿主实测）。
- 界面验收由 `scripts/mock-dsh-server.mjs --manage`（`scripts/mock-dsh-manage.mjs` 扩展 computer-*，`/mock/computer?host=…` 切换宿主形态）覆盖开关、级别、撤销、重试、macOS 权限、宿主不支持、最小视图与只读宿主。

## 42. 群内搜索（2026-11-26，dsbot-group-search）

**实测（只读，本机 dsh Web 宿主 + DS-Bot 0.3.10，`runSpike` 的 `ProbesGroupSearch` 探针组；`group-search` 在上游 `READS` 内；只记键集、计数、类型与错误形态，不记群名、成员名与正文；查询词只用常见单字符 `e`/`的` 与不可能命中的合成串，不从群消息取词；遵守 `spike-write-probes-non-mutating`）**：
- 未知群 `{roomId:"spike-none", query:"e"}` → HTTP 200、`ok:false`、`bot/not-found`、`"Unknown group chat"`。
- 空白查询 `"   "` 与无命中查询 → `ok:true`、`{results:[], hasMore:false}`（顶层键仅 `[hasMore, results]`）。
- 命中（2 个真实群）：群 A `的` 1 条，命中键 `[botId, name, seq, text]`——**无 `time`、无 `before`/`after`**（上游 `lineOf` 只在记录有对应字段时带键，邻居记录无 text 时不带邻居）；群 B `e`/`的` 各 20 条且 `hasMore:true`，命中键 `[after, before, botId, name, seq, text, time]`，`before`/`after` 键 `[botId, name, seq, text, time]`，`time` 为毫秒数，`seq` 全为整数且严格递减（新到旧）。
- **用户自己的消息带 `name`（记录的 `who`）但不带 `botId`**（`的` 查询 20 条中 19 条带 `botId`、20 条带 `name`）——网页端 `authorOf` 以 `botId === undefined` 判为「你」，客户端同判。
- 分页：以首页最旧 seq 作 `before` 与以 `offset:20` 取第二页，两页 seq 序列完全相同、与首页无重叠、全部小于该 seq，`hasMore` 仍为 true。
- 脱敏夹具：`android/core/src/test/resources/dsbot/group-search-page1.json`（群 B 首页 20 条，经 `Sanitize.json` 写出；`text`/`name` 与 `after` 内的 `botId` 为占位符）。

**源码核对（ds-bot 0.3.10 `src/host/channel.js` `group-search` 分支、`group-search.js` `searchGroupBook`/`lineOf`、`groups.js` `groupSearch`、`client.js` `src/client/group-search.js` `GroupSearch`/`termsOf`/`marked`/`snippetOf`/`authorOf`/`Detail`）**：
- `roomId` 经 `rt.roomOf(id) ?? rt.roomOf(rt.pastOwners.get(id)?.roomId)` 解析，已归档旧群 id 可用；未知群 `fail('Unknown group chat', 'bot/not-found')`。
- 服务端：查询小写、截 200 字符、按空白切分去重、最多 8 词，全部词须出现在记录的搜索文本中；按新到旧、每页最多 20 条；无 text 的记录跳过；`before`（整数 seq）只取更旧记录，`offset`（正整数）跳过前 N 条命中；`hasMore` 仅在第 21 条命中存在时为 true。
- 命中的 `seq` 是**群账本**序号；room-chat 条目 id 是各会话分段内的事件 seq 或 `callId:index`，两套编号不同，不能用作群聊定位键。网页端点按命中项打开 `Detail`（发言者、时间、全文高亮、「这条消息的前后」各一条、「返回结果」），不跳转到聊天位置——客户端同样处理（hit-open-target 人工决定）。
- 网页端交互常量：`MIN_QUERY=2`（`query.trim()` 长度）、`DEBOUNCE_MS=220`、`SNIPPET_CHARS=180`（首个命中词前留 60 字符，截断侧加 `…`）；`termsOf` 小写按 `\s+` 切分去重，`marked` 以查询词交替正则 `giu` 在原文上高亮；加载更多以最旧命中 seq 为 `before`（非整数时 `offset=已有条数`），并按 seq 去重合并；时间用 `clockLabel`（今天/昨天 + 时刻，其余带星期的月日）。
- 界面验收由 `scripts/mock-dsh-server.mjs` 的 group-search 应答覆盖（多页命中、上下文、无命中、未知群）。

## 43. MCP 访问（2026-11-26，dsbot-mcp-access）

**实测（本机 dsh Web 宿主 + DS-Bot 0.3.10，`runSpike` 的 `ProbesMcp` 探针组；只记键集、开关、端口、status 与错误形态，不记客户端名称、id 与范围；写端点只用在写入 `mcp.json` 之前就被拒的参数，遵守 `spike-write-probes-non-mutating`）**：
- `state` 应答顶层带 live 字段 `mcp`（与 `connectors`、`imStatus` 同在 `live()` 中，随 `liveKey` 变化）：键 `[clients, error, on, port, status, url]`；本机 `on:false`、`port:8797`、`status:"off"`、`error:null`、`url` 主机 `127.0.0.1`、`clients:[]`——客户端条目不含 `token`/`hash`。
- `mcp-set {port:80}`（不带 `on`）→ HTTP 200、`ok:false`、`bot/invalid`、`"The port must be a number from 1024 to 65535"`（端口校验先于写入）。
- `mcp-client-add {name:"   "}` → `bot/invalid`、`"Give the client a name"`（名称校验先于生成令牌与写入）。
- `mcp-client-update` / `mcp-client-remove` 不存在的 id → `bot/invalid`、`"This MCP client does not exist"`。
- 脱敏夹具：`android/core/src/test/resources/dsbot/mcp-off.json`（本机关闭视图，原样无敏感值）。

**源码核对（ds-bot 0.3.10 `src/host/mcp-server.js` `install`/`mcpView`/`mcpEndpoint`/`scope`/`clientOf`、`channel.js` `live().mcp` 与 `mcp-*` 分发、`client.js` `src/client/mcp-page.js` `OFF`/`statusText`/`McpPage`/`ScopePicker` 与 live 合并）**：
- `mcp.json`（DS-Bot 目录，权限 600）存 `on`、`port`（缺省 8797）与客户端 `{id, name, hash(令牌 SHA-256), bots, rooms, createdAt, lastUsedAt?}`；`mcpView()` 只下发 `{id, name, bots, rooms, createdAt, lastUsedAt|null}`，`status` 只有 `off`/`on`，`error` 为端口占用（`Port N is in use`）、监听失败原因或开启状态下的只读原因。写端点不调用 `rt.save()`、不产生新 revision。
- 四个写端点均不在 `READS` 内（只读宿主 `bot/read-only`），都 `await reconcile()` 后返回新视图；`mcp-client-add` 返回 `{...视图, token}`，`token = "dsb_" + base64url(32 字节随机)`，只出现在这一次应答中。
- `mcp-client-add {name, bots?, rooms?}`：名称 trim 后 `slice(0, 60)`；`bots` 缺省为非私有 Main Bot，`rooms` 缺省为空。`mcp-client-update {id, name?, bots?, rooms?}`：`bots` 过滤掉不存在与私有 Bot，`rooms` 过滤掉不存在的群，未给的键保持原值。网页端新建只发 `{name}`，删除无确认，复制令牌后显示令牌省略的 `DSBOT_MCP_TOKEN=… npx -p ds-bot ds-bot-mcp --url <url>` 命令示例。
- MCP 服务只监听电脑本机 `127.0.0.1`（「Only programs on this computer can connect」）；成功形态（开关、端口、新建含令牌、改范围、删除）未在本机真实宿主上实测——开启或新建会改写用户真实 `mcp.json` 并开端口——由源码核对与 mock（`scripts/mock-dsh-mcp.mjs`，`/mock/mcp?…` 切换端口占用与只读宿主）覆盖。

## 44. 群共享待办与任务看板（2026-11-26，dsbot-room-todos-tasks）

**实测（本机 dsh Web 宿主 + DS-Bot 0.3.10，`runSpike` 的 `ProbesRoomBoard` 探针组；只记键集、计数、类型与错误形态，不记群名、待办正文、任务目标、进度、结果与备注；写端点只用未知群、不存在的待办/任务 id，`action:clear` 只在目标群没有已完成待办时探测，均在 `rt.save()` 前返回、不改写真实数据）：**

- `state.rooms[]` 展开房间对象：本机唯一的群在 `ProbesRoomBoard` 首次运行前读取的 state 中即带 `tasks:{}`（空对象，由上游其他路径经 `tasksOf` 的 `??=` 懒创建），**不带 `todos`**——两字段都只在首次用到后才出现，客户端不能把「字段缺失」当作远端不支持（夹具 `rooms-board-empty.json`）。
- **探针副作用**：上游 `taskNote`/`taskCancel` 在报 `No such task.` 之前先执行 `tasksOf(room)`（`room.tasks ??= {}`），对没有 `tasks` 字段的群调用会在内存里补上 `tasks:{}` 并在下次 `rt.save()` 落盘；`ProbesRoomBoard` 因此只在目标群已带 `tasks` 字段时发出这两个探针（本机群已带该字段，未产生改写）。`room-todo` 的三个分支只读 `room.todos ?? []`，无此副作用。
- `room-todo {roomId:"spike-none", …}` → HTTP 200、`ok:false`、`room/not-found`、`Unknown group chat`（夹具 `room-todo-unknown-room.json`）。
- 真实群 + 不存在的待办 id：`room-todo {roomId, id, done:true}` 与 `{roomId, id, action:"remove"}` → `ok:false`、**`bot/refused`**、`That to-do is no longer on the list.`（上游 `setRoomTodoDone`/`removeRoomTodo` 抛出的 Error 经统一失败分支映射为 `bot/refused`，不是 `room/not-found`；夹具 `room-todo-refused.json`）。
- 真实群（无已完成待办）`room-todo {roomId, action:"clear"}` → `ok:true`、`{removed:0, todos:[]}`；`removed` 是数量而非条目数组（夹具 `room-todo-clear-empty.json`）。
- `task-note` / `task-cancel`：未知群 → `bot/refused`、`No such group.`（夹具 `task-note-unknown-room.json`）；真实群不存在的任务 id → `bot/refused`、`No such task.`（`task-note` 空白备注时同样先报 `No such task.`，id 校验先于备注校验；夹具 `task-cancel-refused.json`）。

**源码核对、未在本机产生成功样本**（本机无待办与任务；待办只能由群成员经 `group_todo` 工具添加、任务只能由成员登记，取样需让 Bot 在用户真实群里工作，不属零成本可撤销的触发途径；ds-bot 0.3.10 `src/host/channel.js`、`roster.js`、`tasks.js`、`client.js` `src/client/group-settings.js`/`task-board.js`）：

- 待办条目 `{id(UUID), text, done, createdAt, createdBy?, doneAt?, doneBy?}`，最多 `ROOM_TODO_MAX` 条；`room-todo {roomId, id, done?}` 不带 `done` 时翻转，带布尔时设为该值（重复设同值不报错、不落盘）；成功应答 `{item, todos}`（clear 为 `{removed, todos}`）。网页端 `GroupTodos` 按「未完成在前、再按 createdAt」排序，勾选发 `done: !item.done`，「Clear completed」仅在有已完成项时显示，三种写失败都静默吞掉。
- 任务对象 `rooms[].tasks[taskId]={id(t_+8 位十六进制), botId, goal, status, startedAt, updatedAt, progress, result?, reason?, blocked?{cause, code?, status?, from}, notes?[{text, by, at}]}`；`status ∈ active|review|blocked`（开放）与 `done|cancelled`（历史）。网页端 `TaskBoard` 开放任务按 `startedAt` 升序、历史倒序默认收起，只显示最近一条备注，开放任务才有「Add a note」「Cancel」（3 秒内再点一次确认）；受阻原因按 `blocked.cause`：`stopped`→Stopped by the user、`restarted`→Not updated after a restart、其余→Its model call failed (code status)。
- `task-note {roomId, id, text}`：备注 trim 后截 `NOTE_MAX=500`，空备注回 `bot/refused`、`Write a note first.`；成功时追加到 `task.notes` 并以用户身份向群发 `@成员名 备注`，应答为完整 `view()`。`task-cancel {roomId, id}`：已结束任务回 `bot/refused`、`That task is already finished.`；成功时 `status=cancelled`、删除 `blocked`，应答为完整 `view()`。

## 45. Settings 概览页数据源核对（2026-11-26，dsbot-settings-overview）

**源码核对（ds-bot 0.3.10 `client.js` `src/client/settings-overview.js` 41841–42095 行与 `src/client/settings.js` 42097–43015 行；不新增任何 `/api/bot` 端点，全部复用的读端点与 `state` 字段均已由前序探针实测，无需再向真实宿主发探针）：**

- **需要处理（`Attention`）**：按优先级显示三类告警——(1) `providers` 应答里 `key` 存在且 `!key.configured`、且团队中有 Bot 的 `model ?? appliedModel` 命中其模型的服务商，列出服务商名与在用 Bot 名（手机端跳「模型与服务商」页，不内联输入 key）；(2) 若上述缺失列表为空且 `providers` 可用但无可用非本地模型（`needsModel`），引导添加模型服务商；(3) 若 `providers` 已加载完成但无具体缺 key 服务商、且 `state.models` 中仍有 Bot 正在使用 `needsKey: true` 的模型（`keyMissing`），引导打开「Bot 模型」页。
- **团队（`Team`）**：显示 `state.bots.length` 与 `state.rooms.length`（点按回团队页），槽位上限 `TEAM_SLOTS = 6`（含「新建 Bot」槽位）：当 `bots.length + 1 > 6` 时显示前 `4` 个 Bot 与 `+N 全部 Bot` 入口，否则显示全部 Bot；每个 Bot 显示 `avatar`/`color`、Main 标记（`state.mainBotIds`）、缺 key 红点（`needsKey`）及 `modelLine`（`state.models` 命中则显示 `name`，其中 `id === "default"` 的本地 Agent 路由显示「CLI 默认 / CLI default」，未命中目录则显示 ref 的模型部分并标 `(不可用)`）。
- **状态汇总磁贴（`Tiles`）**：
  - **模型（`Models`）**：统计 `state.models` 中 `!needsKey && !isLocalRoute(ref)` 的模型数，展示去重后的可用服务商芯片（最多 3 个）、缺 key 服务商芯片（警告色）与使用本地 Agent 的 Bot 数（`Local N`）；点按进入「模型与服务商」（`providers` 不可用时回退「Bot 模型」）。
  - **连接（`Connect`）**：统计 `mail.accounts.length + activeConnectors(state != "off").length + im.connections.length + (phoneOnline ? 1 : 0)`；`phoneOnline` 为 `state.phone.on && state.phone.status == "online" && state.phone.phones.some(connected)`（手机端仅作只读计数与芯片展示，不提供手机配对入口）；依次显示 Phone、至多 2 个去重 IM 平台名、至多 2 个已开启连接器名与 Mailbox 芯片；若 `state` 中 `connectors`、`im`、`mcp` 均不存在则隐藏该磁贴。
  - **工具（`Tools`）**：显示 `state.capabilities.length`，芯片依次为 `Browser`（`state.browser == true`）、`Computer use`（`state.computer.enabled == true`）与 `Secrets N`（`state.secrets.length > 0`）；若能力库、电脑操控、密钥与浏览器全部不可用则隐藏该磁贴。
  - **近 7 天（`Last 7 days`）**：**核对纠正**——规划描述曾提及 `usage-summary`，实际 `client.js` `useWeek` 调用的是全团队 `usage` 端点（按 `Usage.preset("7d")` 聚合近 7 天总 token 与每日柱高，今日 token 取第 7 桶；`usage.scanning == true` 时每 3 秒重读）；`usage` 返回 `bot/not-found` 时隐藏该磁贴，其余失败/未就绪显示占位 `–`。
  - **定时任务（`Scheduled tasks`）**：调用 `schedules` 端点统计 `status == "active"` 数量及最早 `scheduledAt` 的 `nextText`；`schedules` 返回 `bot/not-found` 时隐藏该磁贴。
  - **外观（`Appearance`）**：读取 `state.prefs` 的 `theme`、`accent`、`layout` 与 `effects`（`full` / `reduced` / `off`，`effects` 在手机端仅作只读摘要展示，如「平铺 · 减弱」），展示明暗双色板与主题名称，点按进入「团队偏好」页。
- **分组设置列表（`HomeList`）与实时状态徽标**：按常规与外观、模型、团队、连接、工具、用量与关于分组列出已有原生设置页并跳往对应页面，带出实时徽标：`capabilityStatuses` 出错优先于 `needsApproval` 待批准、`imStatus` 出错优先于 `requests` 待允许、`mcp` 状态（已开启/连接中/出错/已关闭）、`secretStore.locked` 已锁定、`update-check` 的 `bot.available || dsh.available` 显示「有更新」，不支持的字段或 `bot/not-found` 端点对应行隐藏。
