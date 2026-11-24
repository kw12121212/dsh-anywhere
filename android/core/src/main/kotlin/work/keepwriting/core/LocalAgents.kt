package work.keepwriting.core

/**
 * 本地 Agent（dsbot-local-agents）：local-agents 应答（{available, detected[], custom[]}）与 local-agent-models 应答解码、
 * 简短版本、登录状态文案、自定义 Agent 表单校验与参数切分、local-agent-* 请求体。复刻 ds-bot 0.3.10 的
 * src/host/local-agents/index.js（localAgentsEndpoint）、engines.js（validateCustom）与 client.js（LocalAgentsPage、
 * AgentSignInRow、AgentForm、shortVersion）（tech/dsbot-api 的 local-agent-endpoints）。0.3.6 已有，入口不门控。
 */
object LocalAgents {
    /** 服务端会扫描电脑、运行 CLI 探测或启动 CLI 的端点：长读超时、独立线程。 */
    val SLOW_ENDPOINTS: Set<String> = setOf("local-agents", "local-agent-refresh", "local-agent-models", "local-agent-add")

    const val NAME_MAX = 40

    data class Detected(val route: String, val name: String, val path: String?, val version: String?, val signIn: String?, val signedIn: Boolean?)
    data class Custom(val id: String, val name: String, val command: String, val args: List<String>, val route: String?, val available: Boolean)
    data class View(val available: Boolean, val detected: List<Detected>, val custom: List<Custom>)
    data class Model(val id: String, val name: String)

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.objects(k: String) = ((members[k] as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { it as? Json.Value.Object }

    /** local-agents / local-agent-refresh 应答；不是对象时 null。available 缺省视为可用（只有明确 false 才不可用）。 */
    fun decode(value: Json.Value?): View? {
        val o = value as? Json.Value.Object ?: return null
        return View(
            available = (o.members["available"] as? Json.Value.BoolValue)?.value != false,
            detected = o.objects("detected").mapNotNull { d ->
                val route = d.str("route")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Detected(
                    route, d.str("name") ?: route, d.str("path")?.takeIf { it.isNotEmpty() }, d.str("version")?.takeIf { it.isNotEmpty() },
                    d.str("signIn")?.takeIf { it.isNotBlank() }, (d.members["signedIn"] as? Json.Value.BoolValue)?.value,
                )
            },
            custom = o.objects("custom").mapNotNull { c ->
                val id = c.str("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Custom(
                    id, c.str("name") ?: id, c.str("command").orEmpty(),
                    ((c.members["args"] as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { (it as? Json.Value.StringValue)?.value },
                    c.str("route"), (c.members["available"] as? Json.Value.BoolValue)?.value != false,
                )
            },
        )
    }

    /** local-agent-models 应答。 */
    fun models(value: Json.Value?): List<Model> =
        ((value as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { v ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Model(id, o.str("name")?.takeIf { it.isNotEmpty() } ?: id)
        }

    /** 简短版本（网页端 shortVersion）：去掉结尾括号说明与开头的程序名。 */
    fun shortVersion(version: String?): String {
        if (version.isNullOrBlank()) return ""
        return version.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").replace(Regex("^[a-z][\\w-]*\\s+(?=v?\\d)", RegexOption.IGNORE_CASE), "").trim()
    }

    /** 登录状态文案：已登录 / 未登录 / 未知（null）。 */
    fun signInText(d: Detected): String? = when (d.signedIn) {
        true -> I18n.tr("已登录", "Signed in")
        false -> I18n.tr("未登录", "Not signed in")
        null -> null
    }

    /** 可以提示登录（有登录命令且尚未确认已登录）。 */
    fun canSignIn(d: Detected): Boolean = d.signIn != null && d.signedIn != true

    /** 命令与参数的显示文本。 */
    fun commandLine(c: Custom): String = (listOf(c.command) + c.args).joinToString(" ")

    /** 参数按空白切分（网页端 AgentForm）。 */
    fun splitArgs(text: String): List<String> = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private val PROGRAM = Regex("^[\\w.-]+$")
    private val DRIVE = Regex("^[A-Za-z]:[\\\\/]")

    /** 自定义 Agent 的本地预校验（上游 validateCustom）；通过时 null。 */
    fun formProblem(name: String, command: String, args: String): String? {
        val cmd = command.trim()
        return when {
            name.trim().isEmpty() -> I18n.tr("填写名称", "Name is required")
            cmd.isEmpty() || cmd.any { it.isWhitespace() } || !(cmd.startsWith("/") || cmd.startsWith("~/") || DRIVE.containsMatchIn(cmd) || PROGRAM.matches(cmd)) ->
                I18n.tr("命令须为程序名、绝对路径或 ~/ 路径", "Command must be a program name, an absolute path, or a ~/path")
            args.contains('\n') || args.contains('\r') || args.contains('\u0000') -> I18n.tr("参数不能包含换行", "Arguments must not contain newlines")
            else -> null
        }
    }

    /** 请求体（local-agents/index.js localAgentsEndpoint 的参数，源码核对）。 */
    object Requests {
        private fun q(s: String) = JsonString.of(s)
        private fun form(name: String, command: String, args: String) =
            "\"name\":" + q(name.trim().take(NAME_MAX)) + ",\"command\":" + q(command.trim()) + ",\"args\":[" + splitArgs(args).joinToString(",") { q(it) } + "]"
        fun list() = "{}"
        fun refresh() = "{}"
        fun models(route: String) = "{\"route\":" + q(route) + "}"
        fun add(name: String, command: String, args: String) = "{" + form(name, command, args) + "}"
        fun update(id: String, name: String, command: String, args: String) = "{\"id\":" + q(id) + "," + form(name, command, args) + "}"
        fun remove(id: String) = "{\"id\":" + q(id) + "}"
    }
}
