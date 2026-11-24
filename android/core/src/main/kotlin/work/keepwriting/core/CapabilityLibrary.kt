package work.keepwriting.core

/**
 * 能力（dsbot-capabilities）：团队能力库（MCP stdio/HTTP、技能、能力包）、Bot 的常驻/按需挂载与
 * live capabilityStatuses 的解码与派生，以及 capability-save/delete/approve、bot-capability 请求体。
 * 字段、排序与派生复刻 ds-bot 0.3.10 的 src/host/capabilities.js 与 client capability-settings.js
 *（tech/dsbot-api 的 capability-endpoints-0310）。入口以 state.capabilities 字段存在门控（hello 不声明能力名）。
 * 能力的 env/headers/args 可含 $DSH_SECRET_<NAME> 引用：按原文处理，不解析、不写日志与诊断上报。
 */
object CapabilityLibrary {
    const val MCP = "mcp"
    const val SKILL = "skill"
    const val PACK = "pack"
    const val STDIO = "stdio"
    const val HTTP = "streamable-http"

    /** 服务端保留的连接器名（capabilities.js CONNECTOR_IDS）。 */
    val RESERVED = setOf("github", "mail")
    const val NAME_MAX = 32
    private val NAME = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")

    data class Item(
        val name: String,
        val kind: String,
        val description: String,
        /** 提议它的 botId；null 为在界面上由用户添加。 */
        val createdBy: String?,
        val approved: Boolean,
        val transport: String?,
        val command: String?,
        val args: List<String>,
        /** 原始 env/headers 对象（值为字符串，可能含密钥引用）。 */
        val env: Json.Value.Object?,
        val url: String?,
        val headers: Json.Value.Object?,
        val content: String?,
        val items: List<String>,
    ) {
        /** Bot 提议、尚未批准的本地命令 MCP（只有这类需要批准）。 */
        val needsApproval: Boolean get() = kind == MCP && transport == STDIO && !approved
    }

    data class Status(val state: String, val error: String?)

    enum class Mode(val wire: String) { ALWAYS("always"), CATALOG("catalog"), OFF("off") }

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.strings(k: String) =
        ((members[k] as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { (it as? Json.Value.StringValue)?.value }

    /** 远端是否提供能力库（state 含 capabilities 数组）；旧版远端没有此字段时隐藏全部能力入口。 */
    fun hasLibrary(state: Json.Value.Object?): Boolean = state?.members?.get("capabilities") is Json.Value.Array

    /** 能力库（按名称排序，与网页端 Shared capabilities 列表一致）；无效条目跳过。 */
    fun library(state: Json.Value.Object?): List<Item> =
        ((state?.members?.get("capabilities") as? Json.Value.Array)?.items ?: emptyList())
            .mapNotNull(::decodeItem)
            .sortedBy { it.name }

    fun decodeItem(value: Json.Value): Item? {
        val o = value as? Json.Value.Object ?: return null
        val name = o.str("name")?.takeIf { it.isNotEmpty() } ?: return null
        val kind = o.str("kind") ?: return null
        return Item(
            name = name,
            kind = kind,
            description = o.str("description") ?: "",
            createdBy = o.str("createdBy"),
            approved = (o.members["approved"] as? Json.Value.BoolValue)?.value == true,
            transport = o.str("transport"),
            command = o.str("command"),
            args = o.strings("args"),
            env = o.members["env"] as? Json.Value.Object,
            url = o.str("url"),
            headers = o.members["headers"] as? Json.Value.Object,
            content = o.str("content"),
            items = o.strings("items"),
        )
    }

    private fun bots(state: Json.Value.Object?): List<Json.Value.Object> =
        ((state?.members?.get("bots") as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { it as? Json.Value.Object }

    private fun bot(state: Json.Value.Object?, botId: String) = bots(state).firstOrNull { it.str("id") == botId }

    /** 该 Bot 上某项能力保存的挂载模式：bots[].capabilities=常驻，bots[].packs=按需目录，否则关闭。 */
    fun savedMode(state: Json.Value.Object?, botId: String, name: String): Mode {
        val b = bot(state, botId) ?: return Mode.OFF
        return when (name) {
            in b.strings("capabilities") -> Mode.ALWAYS
            in b.strings("packs") -> Mode.CATALOG
            else -> Mode.OFF
        }
    }

    /** Bot 详情「能力」行的摘要：常驻与按需各几项（只计能力库中存在的项）；都没有时为「未开启」。 */
    fun botSummary(state: Json.Value.Object?, botId: String): String {
        val names = library(state).map { it.name }.toSet()
        val b = bot(state, botId)
        val always = b?.strings("capabilities").orEmpty().count { it in names }
        val catalog = b?.strings("packs").orEmpty().count { it in names }
        if (always == 0 && catalog == 0) return I18n.tr("未开启能力", "None on")
        return listOfNotNull(
            if (always > 0) I18n.tr("$always 项常驻", "$always always on") else null,
            if (catalog > 0) I18n.tr("$catalog 项按需", "$catalog in catalog") else null,
        ).joinToString(" · ")
    }

    /** live capabilityStatuses[botId]（能力包已由服务端合成为子项最严重状态）。 */
    fun botStatuses(state: Json.Value.Object?, botId: String): Map<String, Status> {
        val all = state?.members?.get("capabilityStatuses") as? Json.Value.Object ?: return emptyMap()
        val mine = all.members[botId] as? Json.Value.Object ?: return emptyMap()
        return mine.members.mapNotNull { (name, v) ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val s = o.str("state") ?: return@mapNotNull null
            name to Status(s, o.str("error"))
        }.toMap()
    }

    /** 严重程度（越大越严重）：出错 > 待批准 > 连接中 > 就绪；与上游 statusFor 的取值顺序一致。 */
    fun severity(state: String): Int = when (state) {
        "error" -> 3
        "needs-approval" -> 2
        "connecting" -> 1
        else -> 0
    }

    /** 团队层面的单一状态：跨所有 Bot 取最严重者（同级取先出现者）；没有任何 Bot 上报时为 null。 */
    fun teamStatus(state: Json.Value.Object?, name: String): Status? =
        bots(state).mapNotNull { b -> b.str("id")?.let { botStatuses(state, it)[name] } }
            .maxWithOrNull(compareBy { severity(it.state) })

    /**
     * 使用该能力的 Bot 名（按 state.bots 顺序）：直接挂载（常驻或按需），或挂载了包含它的能力包。
     */
    fun users(state: Json.Value.Object?, name: String): List<String> {
        val packs = library(state).filter { it.kind == PACK && name in it.items }.map { it.name }.toSet()
        return bots(state).filter { b ->
            val attached = b.strings("capabilities") + b.strings("packs")
            name in attached || attached.any { it in packs }
        }.map { it.str("name") ?: it.str("id") ?: "" }
    }

    /** 来源：用户添加为 null；否则为提议它的 Bot 名（Bot 已删除时回退 id）。 */
    fun sourceBot(state: Json.Value.Object?, item: Item): String? {
        val by = item.createdBy ?: return null
        return bot(state, by)?.str("name") ?: by
    }

    /** 列表行显示的单一状态：待批准优先，其余取团队层面最严重状态；未被任何 Bot 使用时为 null。 */
    fun listStatus(state: Json.Value.Object?, item: Item): Status? =
        if (item.needsApproval) Status("needs-approval", null) else teamStatus(state, item.name)

    fun statusText(s: Status?): String? = when {
        s == null -> null
        s.state == "ready" -> I18n.tr("就绪", "Ready")
        s.state == "needs-approval" -> I18n.tr("待批准", "Needs approval")
        s.state == "error" -> I18n.tr("无法连接：", "Could not connect: ") + (s.error ?: "")
        else -> I18n.tr("连接中…", "Connecting…")
    }

    fun kindLabel(item: Item): String = when (item.kind) {
        MCP -> if (item.transport == STDIO) I18n.tr("MCP · 本地命令", "MCP · Local command") else I18n.tr("MCP · HTTP 服务", "MCP · HTTP server")
        SKILL -> I18n.tr("技能", "Skill")
        PACK -> I18n.tr("能力包", "Pack")
        else -> item.kind
    }

    /** 列表次要文字（网页端 CapabilityRow；种类已由标签显示）：能力包为说明 + 子项；其他为说明（可为空）。 */
    fun secondary(item: Item): String =
        if (item.kind == PACK) listOf(item.description, item.items.joinToString(", ").ifEmpty { I18n.tr("无内容", "No items") })
            .filter { it.isNotEmpty() }.joinToString(" · ")
        else item.description

    /** 列表排序：待批准置顶，其次出错，其余按名称。 */
    fun listOrder(state: Json.Value.Object?, items: List<Item>): List<Item> =
        items.sortedWith(compareBy<Item>({ if (it.needsApproval) 0 else if (listStatus(state, it)?.state == "error") 1 else 2 }, { it.name }))

    /** Bot 能力页的一行（网页端 BotCapabilitiesCard）。 */
    data class BotRow(
        val item: Item,
        val mode: Mode,
        /** 经由能力包生效的来源：(能力包名, 该包的模式)。 */
        val viaPacks: List<Pair<String, Mode>>,
        val needsApproval: Boolean,
        /** 常驻且不待批准时显示的状态。 */
        val status: Status?,
    )

    /**
     * Bot 能力页各行：能力包在前、其余按名称；[modeOf] 为当前生效（含在途选择）的模式。
     * 经由能力包：本项非常驻时，列出把它包含在内且自身常驻的包（本项关闭时也列出按需的包）。
     */
    fun botRows(state: Json.Value.Object?, botId: String, modeOf: (String) -> Mode = { savedMode(state, botId, it) }): List<BotRow> {
        val items = library(state).sortedWith(compareBy<Item>({ if (it.kind == PACK) 0 else 1 }, { it.name }))
        val statuses = botStatuses(state, botId)
        return items.map { item ->
            val mode = modeOf(item.name)
            val via = if (mode == Mode.ALWAYS) emptyList() else items.filter { p ->
                p.kind == PACK && item.name in p.items &&
                    (modeOf(p.name) == Mode.ALWAYS || (mode == Mode.OFF && modeOf(p.name) == Mode.CATALOG))
            }.map { it.name to modeOf(it.name) }
            val approval = (mode != Mode.OFF || via.isNotEmpty()) && item.needsApproval
            BotRow(item, mode, via, approval, if (mode == Mode.ALWAYS && !approval) statuses[item.name] else null)
        }
    }

    enum class NameProblem { INVALID, RESERVED, TAKEN }

    /** 名称本地预校验（与服务端 validateCapabilityName 同规则）；[taken] 为新建时已存在的名称。 */
    fun nameProblem(raw: String, taken: Collection<String> = emptyList()): NameProblem? {
        val name = raw.trim()
        if (name.length > NAME_MAX || !NAME.matches(name)) return NameProblem.INVALID
        if (name in RESERVED) return NameProblem.RESERVED
        if (name in taken) return NameProblem.TAKEN
        return null
    }

    fun nameProblemText(p: NameProblem): String = when (p) {
        NameProblem.INVALID -> I18n.tr("名称只能用小写字母、数字与单个连字符，以字母开头，不超过 32 个字符。", "Use lowercase letters, digits, and single hyphens, starting with a letter, up to 32 characters.")
        NameProblem.RESERVED -> I18n.tr("这是连接器保留的名称，请换一个。", "That name is reserved for a connector. Choose another.")
        NameProblem.TAKEN -> I18n.tr("已有同名能力，请换一个名称或编辑那一项。", "A capability with this name exists. Choose another or edit it.")
    }

    /** 编辑表单草稿（网页端 CapabilityForm）：参数每行一项，环境变量与请求头为 JSON 对象文本。 */
    data class Draft(
        val name: String = "",
        val kind: String = MCP,
        val description: String = "",
        val transport: String = STDIO,
        val command: String = "",
        val argsText: String = "",
        val envText: String = "{}",
        val url: String = "",
        val headersText: String = "{}",
        val content: String = "",
        val items: List<String> = emptyList(),
        /** 编辑已保存能力时为原名（名称与种类不可改）。 */
        val originalName: String? = null,
    )

    /** 已保存能力 → 编辑草稿（JSON 对象按两空格缩进展开，与网页端一致）。 */
    fun draftOf(item: Item): Draft = Draft(
        name = item.name,
        kind = item.kind,
        description = item.description,
        transport = item.transport ?: STDIO,
        command = item.command ?: "",
        argsText = item.args.joinToString("\n"),
        envText = pretty(item.env),
        url = item.url ?: "",
        headersText = pretty(item.headers),
        content = item.content ?: "",
        items = item.items,
        originalName = item.name,
    )

    private fun pretty(o: Json.Value.Object?): String {
        if (o == null || o.members.isEmpty()) return "{}"
        return o.members.entries.joinToString(",\n", "{\n", "\n}") { (k, v) -> "  " + JsonString.of(k) + ": " + Json.encode(v) }
    }

    /** 能力包可选的子项：已保存的 MCP 与技能（不含自身）。 */
    fun packCandidates(items: List<Item>, self: String): List<Item> = items.filter { it.name != self && (it.kind == MCP || it.kind == SKILL) }

    sealed interface Built {
        data class Ok(val payload: String) : Built
        data class Invalid(val message: String) : Built
    }

    /** JSON 对象文本（空白视为 {}）→ 对象；不是对象或有非文本值时为 null（服务端 stringMap 同样拒绝）。 */
    private fun parseMap(text: String): Json.Value.Object? {
        val v = Json.parse(text.ifBlank { "{}" }) as? Json.Value.Object ?: return null
        return v.takeIf { it.members.values.all { x -> x is Json.Value.StringValue } }
    }

    /**
     * 草稿 → capability-save 请求体；名称不合规、JSON 不是字符串值对象时返回 Invalid（不发送）。
     * 新建时 [taken] 为已有名称；编辑时不校验同名。
     */
    fun build(draft: Draft, taken: Collection<String> = emptyList()): Built {
        val name = draft.name.trim()
        if (draft.originalName == null) nameProblem(name, taken)?.let { return Built.Invalid(nameProblemText(it)) }
        val f = StringBuilder("{")
        f.append("\"name\":").append(JsonString.of(name))
        f.append(",\"kind\":").append(JsonString.of(draft.kind))
        f.append(",\"description\":").append(JsonString.of(draft.description))
        when {
            draft.kind == MCP && draft.transport == STDIO -> {
                val env = parseMap(draft.envText)
                    ?: return Built.Invalid(I18n.tr("环境变量须是值为文本的 JSON 对象。", "Environment must be a JSON object of text values."))
                val args = draft.argsText.split("\n").filter { it.isNotEmpty() }
                f.append(",\"transport\":\"stdio\",\"command\":").append(JsonString.of(draft.command))
                f.append(",\"args\":").append(args.joinToString(",", "[", "]") { JsonString.of(it) })
                f.append(",\"env\":").append(Json.encode(env))
            }
            draft.kind == MCP -> {
                val headers = parseMap(draft.headersText)
                    ?: return Built.Invalid(I18n.tr("请求头须是值为文本的 JSON 对象。", "Headers must be a JSON object of text values."))
                f.append(",\"transport\":\"streamable-http\",\"url\":").append(JsonString.of(draft.url))
                f.append(",\"headers\":").append(Json.encode(headers))
            }
            draft.kind == SKILL -> f.append(",\"content\":").append(JsonString.of(draft.content))
            else -> f.append(",\"items\":").append(draft.items.joinToString(",", "[", "]") { JsonString.of(it) })
        }
        return Built.Ok(f.append("}").toString())
    }

    object Requests {
        private fun q(s: String) = JsonString.of(s)
        fun delete(name: String) = "{\"name\":" + q(name) + "}"
        fun approve(name: String) = "{\"name\":" + q(name) + "}"
        /** OFF 发 attached=false（mode 照发 always，服务端只在 attached 时使用）。 */
        fun attach(botId: String, name: String, mode: Mode): String =
            "{\"botId\":" + q(botId) + ",\"name\":" + q(name) + ",\"attached\":" + (mode != Mode.OFF) +
                ",\"mode\":" + q(if (mode == Mode.CATALOG) "catalog" else "always") + "}"
    }
}
