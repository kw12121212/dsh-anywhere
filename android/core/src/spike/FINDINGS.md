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
