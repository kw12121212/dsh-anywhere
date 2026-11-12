package work.keepwriting.core.spike

import work.keepwriting.core.HarnessUrl
import work.keepwriting.core.Json
import work.keepwriting.core.SessionAuth
import work.keepwriting.core.Socks5Dialer
import work.keepwriting.core.TailnetRouting
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.system.exitProcess

/**
 * DS-Bot API spike（dsbot-api-spike）：用真实 dsh 验证 Android 原生调用 DS-Bot API
 * 的完整路径。复用 core 的 Socks5Dialer/TailnetRouting/HarnessUrl/SessionAuth——
 * 拨号原语、token 剥离与 401 判定都与生产路径同源。
 *
 * 环境变量（全部不回显原文）：
 *   DSH_SPIKE_TARGET   必填：dsh-url 输出的带 token tailscale 地址
 *   DSH_SPIKE_SOCKS5   可选：SOCKS5 代理 user:pass@host:port（RFC1929 认证）；
 *                      缺省时 tailnet 目标走平台直连（WSL 本机 tailscale 场景）
 *   DSH_SPIKE_DUMP     可选：dump 目录；"1" = 仓库外临时目录；缺省不落盘
 *   DSH_SPIKE_SEND     可选："1" 时对第一个主 Bot 发送一条 "hi" 验证 send 与
 *                      message/stream 事件（会触发真实模型调用，默认关闭）
 *   DSH_SPIKE_FEED_MS  可选：feed 订阅窗口毫秒（默认：开 send 时 90 秒，否则 25 秒）
 *
 * 退出码：0 = 全部通过；1 = 存在 FAIL；2 = 用法/环境错误。
 */
private var failures = 0
private var dumpDir: Path? = null

fun main() {
    val target = System.getenv("DSH_SPIKE_TARGET")
        ?: run {
            System.err.println("缺少 DSH_SPIKE_TARGET（dsh-url 输出的带 token 地址；不回显）")
            exitProcess(2)
        }
    if (!HarnessUrl.hasToken(target)) {
        System.err.println("DSH_SPIKE_TARGET 查询参数中没有 token（应为 dsh-url 的原始输出）")
        exitProcess(2)
    }
    val base = HarnessUrl.normalize(target)
    val socks5 = System.getenv("DSH_SPIKE_SOCKS5")
    val sendEnabled = System.getenv("DSH_SPIKE_SEND") == "1"
    val feedMs = System.getenv("DSH_SPIKE_FEED_MS")?.toLongOrNull()
        ?: if (sendEnabled) 90_000L else 25_000L

    dumpDir = when (val d = System.getenv("DSH_SPIKE_DUMP")) {
        null, "" -> null
        "1" -> Files.createTempDirectory("dsh-spike-dump-").also {
            info("dump", "原始响应（脱敏后）写入临时目录 ${it}（仓库外，不提交）")
        }
        else -> Files.createDirectories(Path.of(d))
    }

    // 拨号：有 SOCKS5 时 tailnet 目标走代理（复用生产 Socks5Dialer），其余直连。
    val dial: (String, Int) -> Socket = if (socks5 != null) {
        val at = socks5.lastIndexOf('@')
        if (at <= 0 || !socks5.contains(':')) {
            System.err.println("DSH_SPIKE_SOCKS5 形态应为 user:pass@host:port（Socks5Dialer 走 RFC1929 认证）")
            exitProcess(2)
        }
        val auth = socks5.substring(0, at)
        val host = socks5.substring(at + 1).substringBefore(':')
        val port = socks5.substring(at + 1).substringAfter(':').toIntOrNull()
        if (host.isEmpty() || port == null || !auth.contains(':')) {
            System.err.println("DSH_SPIKE_SOCKS5 形态应为 user:pass@host:port")
            exitProcess(2)
        }
        info("dial", "tailnet 目标经 SOCKS5 $host:$port；其余直连")
        val d = Socks5Dialer(host, port, auth.substringBefore(':'), auth.substringAfter(':'))
        TailnetRouting.splitDial(tailnet = d::connect)
    } else {
        info("dial", "未配置 SOCKS5：全部直连（WSL 本机 tailscale 场景）；Host=${uriOf(base).host}")
        TailnetRouting.splitDial(tailnet = TailnetRouting::directConnect)
    }
    val http = SpikeHttp(dial)

    // ---- 1. 带 token 地址建立 dsh-auth 会话 ----
    val jar = mutableMapOf<String, String>() // name -> value（只在内存）
    fun jarHeader(): String? = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifEmpty { null }
    var tokenStatus = -1
    var redirects = 0
    var url = target
    try {
        while (true) {
            val res = http.request("GET", url, listOfNotNull(jarHeader()?.let { "Cookie" to it }))
            res.headers.filter { it.first.equals("Set-Cookie", ignoreCase = true) }.forEach { (_, v) ->
                val name = v.substringBefore('=').trim()
                val value = v.substringAfter('=', "").substringBefore(';').trim()
                if (name.isNotEmpty() && value.isNotEmpty()) jar[name] = value
                dump("01-auth-setcookie-${jar.size}", Sanitize.setCookieLine(v))
            }
            if (res.status in 301..308 && redirects < 5) {
                val loc = res.header("Location")
                if (!loc.isNullOrEmpty()) {
                    dump("01-auth-redirect-$redirects", "Location: ${Sanitize.url(loc)}")
                    url = resolve(base, loc)
                    redirects++
                    continue
                }
            }
            tokenStatus = res.status
            dump("01-auth-final", "status=${res.status} redirects=$redirects\nbody=${Sanitize.plain(res.bodyText(), "page")}")
            break
        }
    } catch (e: Exception) {
        fail("auth-token-session", "请求失败: ${e.javaClass.simpleName} ${Sanitize.plain(e.message ?: "", "err")}")
        exitProcess(1)
    }
    val authCookies = jar.keys.filter { it.startsWith("dsh-auth") }
    if (tokenStatus == 200 && authCookies.isNotEmpty()) {
        // cookie 名含随机成分，与值一样按凭据形态对待：只报数量，不报名
        pass("auth-token-session", "HTTP 200；取得 dsh-auth 会话 cookie ×${authCookies.size}（名称与值均不回显），重定向 $redirects 次")
    } else {
        fail("auth-token-session", "终态 HTTP $tokenStatus，dsh-auth cookie 数=${authCookies.size}")
    }
    val cookieHeader = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    val jsonHeaders = listOf("Cookie" to cookieHeader, "Content-Type" to "application/json")

    // normalize 产出的根路径带尾部 "/"，直接拼接会出现 "//api/bot"——dsh 的 /api 前缀
    // 路由要求路径以 "/api/" 开头，双斜杠会静默落到 SPA 静态兜底（POST→405 GET,HEAD）。
    val apiBase = base.trimEnd('/')

    fun post(endpoint: String, payload: String, headers: List<Pair<String, String>> = jsonHeaders): SpikeHttp.Response =
        http.request("POST", "$apiBase/api/bot", headers, "{\"endpoint\":\"$endpoint\",\"payload\":$payload}".toByteArray(Charsets.UTF_8))

    // ---- 2. state 全量 ----
    var revision: String? = null
    var liveKey: String? = null
    var firstBotId: String? = null
    var firstBotSession: String? = null
    val stateRes = post("state", "{\"locale\":\"zh-CN\"}")
    dumpJson("02-state-full", stateRes)
    val stateVal = parseOk("api-state-full", stateRes)
    if (stateVal != null) {
        val obj = stateVal as? Json.Value.Object
        revision = (obj?.members?.get("revision") as? Json.Value.NumberValue)?.raw
            ?: (obj?.members?.get("revision") as? Json.Value.StringValue)?.value
        liveKey = (obj?.members?.get("liveKey") as? Json.Value.StringValue)?.value
        val bots = obj?.members?.get("bots") as? Json.Value.Array
        val rooms = obj?.members?.get("rooms") as? Json.Value.Array
        firstBotId = bots?.items?.firstOrNull()?.let { (it as? Json.Value.Object)?.members?.get("id") as? Json.Value.StringValue }?.value
        firstBotSession = bots?.items?.firstOrNull()?.let { (it as? Json.Value.Object)?.members?.get("sessionId") as? Json.Value.StringValue }?.value
        val topKeys = obj?.members?.keys?.joinToString(",") ?: ""
        if (revision != null && bots != null && rooms != null) {
            pass("api-state-full", "revision=$revision；bots=${bots.items.size}、rooms=${rooms.items.size}；顶层键: $topKeys")
        } else {
            fail("api-state-full", "结构缺失：revision/bots/rooms 不全（revision=$revision）")
        }
    }

    // ---- 3. state 增量（since / live → unchanged）----
    if (revision != null) {
        val sinceRes = post("state", "{\"since\":$revision}")
        dumpJson("03-state-since", sinceRes)
        val sinceVal = parseOk("api-state-since", sinceRes) as? Json.Value.Object
        val unchanged = sinceVal?.members?.get("unchanged") as? Json.Value.BoolValue
        val liveKey2 = (sinceVal?.members?.get("liveKey") as? Json.Value.StringValue)?.value
        if (unchanged?.value == true) {
            pass("api-state-since", "unchanged=true，liveKey 字段${if (liveKey2 != null) "存在" else "缺失"}")
        } else {
            fail("api-state-since", "unchanged=${unchanged?.value}（期望 true）")
        }
        if (liveKey2 != null) {
            val bothRes = post("state", "{\"since\":$revision,\"live\":\"${liveKey2.replace("\"", "\\\"")}\"}")
            dumpJson("03-state-since-live", bothRes)
            val bothVal = parseOk("api-state-since-live", bothRes) as? Json.Value.Object
            val keys = bothVal?.members?.keys?.toSet()
            if (bothVal?.members?.get("unchanged") is Json.Value.BoolValue &&
                (bothVal.members["unchanged"] as Json.Value.BoolValue).value &&
                keys == setOf("revision", "unchanged", "liveKey")
            ) {
                pass("api-state-since-live", "since+live 全命中：仅返回 {revision, unchanged, liveKey}，无 live 字段")
            } else {
                // since 命中但 live 键过期：返回 unchanged+live 字段集，也是合法增量形态
                val isUnchanged = (bothVal?.members?.get("unchanged") as? Json.Value.BoolValue)?.value == true
                if (isUnchanged) {
                    pass("api-state-since-live", "since+live：unchanged=true 且返回 ${keys?.joinToString(",")}（live 键已翻新，字段集重新下发）")
                } else {
                    fail("api-state-since-live", "unchanged 缺失（keys=${keys?.joinToString(",")}）")
                }
            }
        }
    }

    // ---- 4. chat 条目 ----
    if (firstBotId != null) {
        val chatRes = post("chat", "{\"botId\":\"$firstBotId\"}")
        dumpJson("04-chat-page", chatRes)
        val chatVal = parseOk("api-chat", chatRes)
        val items = (chatVal as? Json.Value.Object)?.members?.get("items") as? Json.Value.Array
        if (items != null) {
            val kinds = items.items.mapNotNull { (it as? Json.Value.Object)?.members?.get("kind") as? Json.Value.StringValue }
                .groupingBy { it.value }.eachCount()
            pass("api-chat", "bot=$firstBotId 条目数=${items.items.size}；kind 分布=$kinds（文本已脱敏入 dump）")
        } else {
            fail("api-chat", "返回结构无 items 数组（keys=${(chatVal as? Json.Value.Object)?.members?.keys?.joinToString(",")}）")
        }
    }

    // ---- 5. update-check 版本来源 ----
    val verRes = post("update-check", "{}")
    dumpJson("05-update-check", verRes)
    val verVal = parseOk("version-update-check", verRes) as? Json.Value.Object
    val dsbotVer = (verVal?.members?.get("current") as? Json.Value.StringValue)?.value
    val dshVer = ((verVal?.members?.get("dsh") as? Json.Value.Object)?.members?.get("current") as? Json.Value.StringValue)?.value
    if (dsbotVer != null && dshVer != null) {
        pass("version-update-check", "DS-Bot value.current=$dsbotVer；dsh 版本 value.dsh.current=$dshVer")
    } else {
        fail("version-update-check", "版本字段缺失：current=$dsbotVer dsh.current=$dshVer")
    }

    // ---- 6. send（可选）+ feed SSE 订阅 ----
    // feed 先在后台线程订阅，再 send——流式回复的 stream start/chunk/end 紧跟 send
    // 触发，订阅晚了就会错过；证据收齐（hello + stream + message/live）即提前收窗。
    val feedEvents = Collections.synchronizedList(mutableListOf<String>())
    val feedFrames = Collections.synchronizedList(mutableListOf<String>())
    val feedStart = System.currentTimeMillis()
    val deadline = feedStart + feedMs
    val feedThread = Thread {
        try {
            http.feed(
                "$apiBase/api/bot/feed",
                listOf("Cookie" to cookieHeader),
                stop = {
                    System.currentTimeMillis() >= deadline ||
                        (sendEnabled && feedEvents.contains("hello") &&
                            feedEvents.contains("stream") &&
                            (feedEvents.contains("message") || feedEvents.contains("live")))
                },
            ) { event, data ->
                feedEvents.add(event)
                feedFrames.add("event: $event\ndata: ${Sanitize.json(data)}")
            }
        } catch (_: Exception) {
            // 订阅窗口异常在汇总检查里呈现
        }
    }
    feedThread.isDaemon = true
    feedThread.start()
    // 等 hello 到达再 send，确保订阅生效
    val helloWait = System.currentTimeMillis() + 10_000
    while (!feedEvents.contains("hello") && System.currentTimeMillis() < helloWait) Thread.sleep(100)

    if (sendEnabled && firstBotSession != null) {
        val sendRes = post("send", "{\"sessionId\":\"$firstBotSession\",\"text\":\"hi\"}")
        dumpJson("06-send", sendRes)
        val sendVal = parseOk("api-send", sendRes)
        if ((sendVal as? Json.Value.BoolValue)?.value == true) {
            pass("api-send", "sessionId=$firstBotSession 文本已投递（ok=true），feed 订阅中等待事件")
        }
    } else if (sendEnabled) {
        fail("api-send", "state 里没有可用的 Bot sessionId")
    }

    feedThread.join(feedMs + 15_000)
    feedFrames.forEachIndexed { i, f -> dump("07-feed-frame-$i", f) }
    val names = feedEvents.distinct()
    if (names.contains("hello")) {
        pass("feed-events", "事件=${names.joinToString(",")}（窗口 ${System.currentTimeMillis() - feedStart}ms）")
    } else {
        fail("feed-events", "未收到 hello 事件（事件=${names.joinToString(",")}）")
    }

    // ---- 7. 401/403 触发条件 ----
    val noCookie = http.request("POST", "$apiBase/api/bot", listOf("Content-Type" to "application/json"),
        "{\"endpoint\":\"state\",\"payload\":{}}".toByteArray(Charsets.UTF_8))
    dumpText("08-401-no-cookie", noCookie)
    if (noCookie.status == 401) {
        val expired = SessionAuth.isSessionExpired(noCookie.status, noCookie.header("Content-Type"), noCookie.bodyText())
        pass("err-401-no-cookie", "无 cookie POST /api/bot → 401；固定失效标记=${if (expired) "匹配" else "不匹配"}")
    } else {
        fail("err-401-no-cookie", "无 cookie 期望 401，实际 ${noCookie.status}")
    }

    val wrongHost = http.request("POST", "$apiBase/api/bot",
        listOf("Host" to "spike-invalid-host.example", "Content-Type" to "application/json"),
        "{\"endpoint\":\"state\",\"payload\":{}}".toByteArray(Charsets.UTF_8))
    dumpText("09-wrong-host", wrongHost)
    check4xx("err-wrong-host", wrongHost, "Host 与 trusted-host 不一致")

    val crossOrigin = http.request("POST", "$apiBase/api/bot",
        listOf("Cookie" to cookieHeader, "Content-Type" to "application/json", "Origin" to "https://spike-origin.example"),
        "{\"endpoint\":\"state\",\"payload\":{}}".toByteArray(Charsets.UTF_8))
    dumpText("10-cross-origin", crossOrigin)
    // 记录性质：原生请求本就不带 Origin；带跨站 Origin 的行为只作事实记录。
    info("err-cross-origin", "带跨站 Origin + 有效 cookie → HTTP ${crossOrigin.status}（${if (crossOrigin.status in 200..299) "未拦截" else "被拦截"}；原生路径不带 Origin，此项仅记录）")

    dumpDir?.let { Files.writeString(it.resolve("report.txt"), "DS-Bot API spike @ ${Sanitize.url(base)}\n") }
    info("summary", "dump=${dumpDir?.toString() ?: "<未开启>"}；FAIL=$failures")
    if (failures > 0) exitProcess(1)
    println("SPIKE PASS")
}

// ---- 报告与 dump 辅助 ----

private fun uriOf(url: String) = java.net.URI(url)

private fun resolve(base: String, location: String): String {
    val loc = location.trim()
    return if (loc.startsWith("http://") || loc.startsWith("https://")) loc
    else base.trimEnd('/') + if (loc.startsWith("/")) loc else "/$loc"
}

private fun parseOk(check: String, res: SpikeHttp.Response): Json.Value? {
    if (res.status != 200) {
        fail(check, "HTTP ${res.status}（期望 200）")
        return null
    }
    val root = Json.parse(res.bodyText())
    if (root == null) {
        fail(check, "响应不是合法 JSON")
        return null
    }
    val obj = root as? Json.Value.Object
    val ok = obj?.members?.get("ok")
    if (ok is Json.Value.BoolValue && ok.value) {
        return obj.members["value"] ?: Json.Value.NullValue
    }
    val err = obj?.members?.get("error") as? Json.Value.Object
    fail(check, "ok=false：code=${(err?.members?.get("code") as? Json.Value.StringValue)?.value} message=${Sanitize.plain((err?.members?.get("message") as? Json.Value.StringValue)?.value ?: "", "err")}")
    return null
}

private fun check4xx(check: String, res: SpikeHttp.Response, what: String) {
    if (res.status in 400..499) pass(check, "$what → HTTP ${res.status}")
    else fail(check, "$what 期望 4xx，实际 ${res.status}")
}

private fun pass(check: String, detail: String) = println("PASS $check — $detail")

private fun fail(check: String, detail: String) {
    failures++
    println("FAIL $check — $detail")
}

private fun info(check: String, detail: String) = println("INFO $check — $detail")

private fun dumpJson(name: String, res: SpikeHttp.Response) {
    val dir = dumpDir ?: return
    val body = if (res.header("Content-Type")?.contains("json") == true) Sanitize.json(res.bodyText()) else Sanitize.plain(res.bodyText())
    Files.writeString(dir.resolve(name.sanitizeFile()), "HTTP ${res.status}\n$body\n")
}

private fun dumpText(name: String, res: SpikeHttp.Response) {
    val dir = dumpDir ?: return
    Files.writeString(
        dir.resolve(name.sanitizeFile()),
        "HTTP ${res.status} ${res.reason}\n${res.headers.joinToString("\n") { (k, v) ->
            if (k.equals("Set-Cookie", true)) "$k: ${Sanitize.setCookieLine(v)}" else "$k: $v"
        }}\n\n${Sanitize.plain(res.bodyText(), "body")}\n",
    )
}

private fun dump(name: String, content: String) {
    val dir = dumpDir ?: return
    Files.writeString(dir.resolve(name.sanitizeFile()), content + "\n")
}

private fun String.sanitizeFile(): String = replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt"
