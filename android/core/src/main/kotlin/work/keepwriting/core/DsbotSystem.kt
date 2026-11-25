package work.keepwriting.core

import java.time.ZoneId

/**
 * 关于 DS Bot 与诊断（dsbot-system-about）：update-check 视图（DS-Bot 本体与 dsh 子视图）解码、更新行状态文案与按钮
 * 判定、与 App 已测基准比对、安装中轮询判定、repo-stars 与 diagnostics 报告解码、条目副标题与各端点请求体。复刻
 * ds-bot 0.3.10 的 src/host/update.js（view、check、start、repoStars）、dsh-update.js（view、start）、diagnostics.js
 * （report、logText、clear）与 client.js（botHint、dshHint、showsDshRow、AboutPage、FeedbackPage）
 * （tech/dsbot-api 的 system-endpoints-0310）。全部端点在上游 READS 内、hello 无对应能力名，入口不门控。
 */
object DsbotSystem {
    /** 服务端会请求 npm 注册表或 GitHub（单次最长 8 秒、可串行多次）的端点：长读超时、独立线程。 */
    val SLOW_ENDPOINTS: Set<String> = setOf("update-check", "repo-stars")

    /** DS-Bot 本体的更新视图（update.js view）。 */
    data class BotUpdate(
        val current: String?,
        val latest: String?,
        val source: String,
        val notesUrl: String?,
        val repoUrl: String?,
        val enabled: Boolean,
        val checkedAt: Long,
        val phase: String,
        val installed: String?,
        val error: String?,
        val available: Boolean,
    )

    /** dsh 的更新视图（dsh-update.js view）。 */
    data class DshUpdate(
        val current: String?,
        val verified: String?,
        val kind: String,
        val blockedBy: String?,
        val phase: String,
        val installed: String?,
        val error: String?,
        val available: Boolean,
    )

    data class Update(val bot: BotUpdate, val dsh: DshUpdate?)

    /** 更新行右侧的按钮。 */
    enum class Button { NONE, CHECK, CHECK_DISABLED, INSTALL, RETRY, INSTALLING }

    data class Row(val hint: String?, val button: Button)

    enum class Baseline { MATCHED, MISMATCHED, UNKNOWN }

    data class Facts(
        val botVersion: String?,
        val dshVersion: String?,
        val os: String?,
        val node: String?,
        val team: String?,
        val models: String?,
        val readOnly: String?,
    )

    data class Entry(
        val time: Long,
        val level: String,
        val source: String,
        val text: String,
        val bot: String?,
        val where: String?,
        val code: String?,
        val status: String?,
    )

    data class Report(val facts: Facts, val entries: List<Entry>, val total: Int)

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value?.takeIf { it.isNotEmpty() }
    private fun Json.Value.Object.num(k: String) = (members[k] as? Json.Value.NumberValue)?.raw?.toDoubleOrNull()?.toLong()
    private fun Json.Value.Object.bool(k: String) = (members[k] as? Json.Value.BoolValue)?.value

    /** update-check / update-install 应答（dsh 子视图可缺）；不是对象时 null。未知 phase/source 原样保留，按缺省分支显示。 */
    fun decodeUpdate(value: Json.Value?): Update? {
        val o = value as? Json.Value.Object ?: return null
        val bot = BotUpdate(
            current = o.str("current"),
            latest = o.str("latest"),
            source = o.str("source") ?: "unknown",
            notesUrl = o.str("notesUrl")?.takeIf(::isWebLink),
            repoUrl = o.str("repoUrl")?.takeIf(::isWebLink),
            enabled = o.bool("enabled") != false,
            checkedAt = o.num("checkedAt") ?: 0L,
            phase = o.str("phase") ?: "idle",
            installed = o.str("installed"),
            error = o.str("error"),
            available = o.bool("available") == true,
        )
        return Update(bot, decodeDsh(o.members["dsh"]))
    }

    /** dsh 子视图（update-check 的 value.dsh，或 dsh-update-install 的应答）；缺失、null 或不是对象时 null。 */
    fun decodeDsh(value: Json.Value?): DshUpdate? {
        val d = value as? Json.Value.Object ?: return null
        return DshUpdate(
            current = d.str("current"),
            verified = d.str("verified"),
            // 上游 showsDshRow 只认明确的 kind=npm：缺失或未知时不提供在电脑上 npm 更新（评审发现）
            kind = d.str("kind") ?: "unknown",
            blockedBy = d.str("blockedBy"),
            phase = d.str("phase") ?: "idle",
            installed = d.str("installed"),
            error = d.str("error"),
            available = d.bool("available") == true,
        )
    }

    /** 只接受 http(s) 链接（交系统浏览器打开前的过滤）。 */
    fun isWebLink(url: String): Boolean = Regex("^https?://[^\\s]+$", RegexOption.IGNORE_CASE).matches(url)

    /** 与 App 已测基准比对（两侧版本都读到才判定）。 */
    fun baseline(update: Update?): Baseline =
        when (DsbotVersions.againstBaseline(DsbotVersions.Remote(update?.bot?.current, update?.dsh?.current))) {
            DsbotVersions.Verdict.MATCHED -> Baseline.MATCHED
            DsbotVersions.Verdict.MISMATCHED -> Baseline.MISMATCHED
            DsbotVersions.Verdict.UNKNOWN -> Baseline.UNKNOWN
        }

    fun baselineText(b: Baseline): String = when (b) {
        Baseline.MATCHED -> I18n.tr("与 App 已测基准一致", "Matches the version this app was tested with")
        Baseline.MISMATCHED -> I18n.tr("与 App 已测基准不一致，部分功能可能不可用", "Differs from the version this app was tested with; some features may not work")
        Baseline.UNKNOWN -> I18n.tr("无法判断是否与 App 已测基准一致", "Could not compare with the version this app was tested with")
    }

    /** DS-Bot 行（上游 botHint 与 AboutPage 的 botControl；手机端没有桌面应用桥，ownApp 分支不适用）。 */
    fun botRow(u: BotUpdate, now: Long, zone: ZoneId): Row {
        val hint = when {
            u.phase == "installing" -> I18n.tr("正在更新 DS-Bot…", "Updating DS Bot…")
            u.phase == "installed" -> (u.installed ?: u.latest).let { I18n.tr("重启 DSH 后开始使用 DS-Bot $it。", "Restart DSH to start using DS Bot $it.") }
            u.phase == "updated" -> (u.installed ?: u.latest).let { I18n.tr("DS-Bot $it 已安装。", "DS Bot $it is installed.") }
            u.phase == "failed" -> I18n.tr("更新未完成：${u.error.orEmpty()}", "The update did not finish: ${u.error.orEmpty()}")
            u.available -> I18n.tr("DS-Bot ${u.latest} 可更新", "DS Bot ${u.latest} is available")
            u.source == "app" -> I18n.tr("随 DS Bot 桌面应用更新。", "Updates come with the DS Bot app.")
            !u.enabled -> I18n.tr("更新检查已关闭。", "Update checks are off.")
            u.source == "path" -> I18n.tr("从本地目录安装，请在那里更新。", "Installed from a local folder; update it there.")
            u.checkedAt == 0L -> null
            u.latest == null && u.source != "registry" -> I18n.tr("此安装方式不提供更新。", "Updates are not offered for this install.")
            else -> {
                val state = if (u.latest != null) I18n.tr("已是最新", "Up to date") else I18n.tr("无法检查更新。", "Could not check for updates.")
                val at = timeText(u.checkedAt, now, zone)
                "$state · " + I18n.tr("检查于 $at", "Checked $at")
            }
        }
        val button = when {
            u.phase == "installed" || u.phase == "updated" || u.source == "app" -> Button.NONE
            u.phase == "installing" -> Button.INSTALLING
            u.available -> if (u.phase == "failed") Button.RETRY else Button.INSTALL
            !u.enabled -> Button.CHECK_DISABLED
            else -> Button.CHECK
        }
        return Row(hint, button)
    }

    private fun bare(v: String?) = v.orEmpty().trim().removePrefix("v").removePrefix("V").substringBefore('+')
    private fun sameVersion(a: String?, b: String?) = bare(a).isNotEmpty() && bare(a) == bare(b)

    /** 远端有比当前更新的已验证 dsh 版本（上游 showsDshRow 的版本条件）。 */
    private fun dshNewer(d: DshUpdate) = d.verified != null && d.current != null && !sameVersion(d.verified, d.current)

    /** 是否显示 dsh 行：远端报告了 dsh 当前版本。 */
    fun showsDsh(d: DshUpdate?): Boolean = d?.current != null

    /**
     * dsh 行（上游 dshHint 与 dshControl）：npm 安装且有已验证新版本时可在电脑上更新；随桌面应用更新时只提示
     * （手机端没有桌面应用桥，不提供按钮）；需要先更新 DS-Bot 时提示所需版本。
     */
    fun dshRow(d: DshUpdate): Row {
        val npmOffer = d.kind == "npm" && dshNewer(d)
        val hint = when {
            d.phase == "installing" -> I18n.tr("正在更新 DSH…", "Updating DSH…")
            d.phase == "installed" -> (d.installed ?: d.verified).let { I18n.tr("重启 DSH 后开始使用 DSH $it。", "Restart DSH to start using DSH $it.") }
            d.phase == "failed" -> I18n.tr("更新未完成：${d.error.orEmpty()}", "The update did not finish: ${d.error.orEmpty()}")
            !d.available && d.blockedBy != null -> I18n.tr("请先把 DS-Bot 更新到 ${d.blockedBy}。", "Update DS Bot ${d.blockedBy} first.")
            npmOffer && d.available -> I18n.tr("DSH ${d.verified} 可更新", "DSH ${d.verified} is available")
            d.kind == "app" && dshNewer(d) -> I18n.tr("DSH ${d.verified} 随桌面应用更新（当前 ${d.current}）", "DSH ${d.verified} comes with the desktop app (now ${d.current})")
            else -> I18n.tr("版本 ${d.current}", "Version ${d.current}")
        }
        val button = when {
            !npmOffer || d.phase == "installed" -> Button.NONE
            d.phase == "installing" -> Button.INSTALLING
            d.available || d.phase != "idle" -> if (d.phase == "failed") Button.RETRY else Button.INSTALL
            else -> Button.NONE
        }
        return Row(hint, button)
    }

    /** 任一更新进行中：关于页可见且前台时约 2 秒重读一次（上游 wakeUpdate 的 2 秒间隔）。 */
    fun installing(update: Update?): Boolean = update?.bot?.phase == "installing" || update?.dsh?.phase == "installing"

    const val POLL_MS = 2_000L

    /** repo-stars 应答：非负整数，否则 null（不显示数字）。 */
    fun stars(value: Json.Value?): Int? =
        (value as? Json.Value.NumberValue)?.raw?.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()

    /** Star 数紧凑显示（上游 Intl compact）：中文按万、英文按 K/M，保留一位小数。 */
    fun starText(count: Int): String {
        fun one(x: Double) = String.format(java.util.Locale.ROOT, "%.1f", x).removeSuffix(".0")
        return if (I18n.lang == ChatBars.Lang.EN) when {
            count < 1_000 -> "$count"
            count < 1_000_000 -> one(count / 1_000.0) + "K"
            else -> one(count / 1_000_000.0) + "M"
        } else when {
            count < 10_000 -> "$count"
            count < 100_000_000 -> one(count / 10_000.0) + "万"
            else -> one(count / 100_000_000.0) + "亿"
        }
    }

    /** diagnostics 应答；不是对象时 null。条目保持服务端顺序（新到旧），缺 time 或 text 的条目丢弃。 */
    fun decodeReport(value: Json.Value?): Report? {
        val o = value as? Json.Value.Object ?: return null
        val f = o.members["facts"] as? Json.Value.Object
        val facts = Facts(
            f?.str("botVersion"), f?.str("dshVersion"), f?.str("os"), f?.str("node"),
            f?.str("team"), f?.str("models"), f?.str("readOnly"),
        )
        val entries = ((o.members["entries"] as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { v ->
            val e = v as? Json.Value.Object ?: return@mapNotNull null
            val time = e.num("time") ?: return@mapNotNull null
            val text = (e.members["text"] as? Json.Value.StringValue)?.value ?: return@mapNotNull null
            Entry(
                time = time,
                level = if (e.str("level") == "warn") "warn" else "error",
                source = e.str("source") ?: "host",
                text = text,
                bot = e.str("bot"),
                where = e.str("where"),
                code = e.str("code"),
                status = e.members["status"]?.let { s ->
                    (s as? Json.Value.NumberValue)?.raw ?: (s as? Json.Value.StringValue)?.value?.takeIf { it.isNotEmpty() }
                },
            )
        }
        val total = o.num("total")?.toInt()?.coerceAtLeast(entries.size) ?: entries.size
        return Report(facts, entries, total)
    }

    /** 报告中的版本、系统与团队信息（缺失的项不显示）。 */
    fun factLines(f: Facts): List<Pair<String, String>> = listOfNotNull(
        f.botVersion?.let { "DS-Bot" to it },
        f.dshVersion?.let { "DSH" to it },
        f.os?.let { I18n.tr("系统", "OS") to it },
        f.node?.let { "Node" to it },
        f.team?.let { I18n.tr("团队", "Team") to it },
        f.models?.let { I18n.tr("模型", "Models") to it },
        f.readOnly?.let { I18n.tr("只读", "Read-only") to it },
    )

    /** 服务端时间文案：服务端时钟可能略快于手机，晚于手机当前时间的时刻按「刚刚」处理，免得刚检查完显示成绝对日期。 */
    fun timeText(ms: Long, now: Long, zone: ZoneId): String = SecretsSettings.formatTime(minOf(ms, now), now, zone)

    /** 模型调用失败码文案（上游 client.js FAILURES）。 */
    private fun knownFailure(code: String?): String? = when (code) {
        "MISSING_CREDENTIAL" -> I18n.tr("模型没有 API key", "The model has no API key")
        "INVALID_CREDENTIAL" -> I18n.tr("API key 格式不对", "The API key is not in the right format")
        "AUTH" -> I18n.tr("API key 被拒绝", "The API key was rejected")
        "CONTENT_POLICY" -> I18n.tr("模型服务拦截了该内容", "The model service blocked the content")
        "QUOTA", "ACCOUNT_QUOTA" -> I18n.tr("账号额度不足", "The account is out of quota")
        "ACCOUNT_SIGN_IN_REQUIRED" -> I18n.tr("DeepSeek 账号需要登录", "The DeepSeek account needs a sign-in")
        "ACCOUNT_SIGNED_OUT" -> I18n.tr("DeepSeek 账号已退出登录", "The DeepSeek account is signed out")
        "ACCOUNT_TOKEN_INVALID" -> I18n.tr("DeepSeek 账号登录已失效", "The DeepSeek account sign-in has expired")
        "RATE_LIMIT" -> I18n.tr("对模型的请求过多", "Too many requests to the model")
        "CONTEXT_WINDOW_EXCEEDED" -> I18n.tr("对话超出模型的上下文长度", "The conversation is too long for the model")
        "INVALID_REQUEST" -> I18n.tr("模型服务拒绝了请求", "The model service refused the request")
        "SERVER" -> I18n.tr("模型服务出错", "The model service failed")
        "TIMEOUT" -> I18n.tr("模型回答超时", "The model took too long to answer")
        "TRANSPORT" -> I18n.tr("无法连接模型服务", "Could not reach the model service")
        "MODEL_SWITCH_COMPACTION_FAILED" -> I18n.tr("切换模型时对话压缩失败", "The conversation could not be compacted for the model switch")
        "SESSION" -> I18n.tr("其群聊会话无法打开", "Its group conversation could not open")
        "SESSION_RESET" -> I18n.tr("其群聊会话无法打开，下次将新开会话", "Its group conversation could not open, so it starts a new one next time")
        else -> null
    }

    /** 条目摘要（上游 entrySummary/failureLabel）：模型调用条目的已知失败码显示友好文案，其余显示原文。 */
    fun entrySummary(e: Entry): String =
        if (e.source == "turn") knownFailure(e.code) ?: e.text.ifEmpty { e.code ?: I18n.tr("未知错误", "Unknown error") } else e.text

    /** 条目来源（上游 SOURCES）。 */
    fun sourceLabel(source: String): String = when (source) {
        "turn" -> I18n.tr("模型调用", "Model call")
        "browser" -> I18n.tr("浏览器", "Browser")
        else -> "DS-Bot"
    }

    /** 条目副标题：时间 · Bot · 位置 · 来源。 */
    fun entryMeta(e: Entry, now: Long, zone: ZoneId): String =
        listOfNotNull(timeText(e.time, now, zone), e.bot, e.where, sourceLabel(e.source)).joinToString(" · ")

    /** 条目错误码标签（含 HTTP 状态）；无错误码时 null。 */
    fun codeTag(e: Entry): String? = e.code?.let { if (e.status != null) "$it ${e.status}" else it }

    /** diagnostics-log 应答：纯文本；不是字符串时 null。 */
    fun logText(value: Json.Value?): String? = (value as? Json.Value.StringValue)?.value

    /** 端点请求体。 */
    object Requests {
        fun check(fresh: Boolean) = """{"fresh":$fresh}"""
        fun install() = "{}"
        fun dshInstall() = "{}"
        fun stars() = "{}"

        /** 只带界面类型与语言，不带浏览器 UA 与客户端错误。 */
        fun diagnostics(lang: ChatBars.Lang) = """{"client":{"surface":"android","language":"${if (lang == ChatBars.Lang.EN) "en" else "zh"}"}}"""
        fun log() = "{}"
        fun clear() = "{}"
    }
}
