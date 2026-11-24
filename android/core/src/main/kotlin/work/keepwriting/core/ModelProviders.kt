package work.keepwriting.core

/**
 * 模型与服务商（dsbot-models-providers）：providers 应答解码、key 状态、使用者、模型草稿（网页端 draftFrom/draftSummary）
 * 与受影响 Bot、key 预校验（上游 checkKey）、自定义服务商校验与 provider-* 请求体。复刻 ds-bot 0.3.10 的
 * src/host/providers.js 与 client.js（ProvidersPage、ProviderRow、ModelDraft、AddProvider）（tech/dsbot-api 的
 * provider-endpoints）。API key 只进请求体：不写日志与诊断上报；视图只含 key 是否已设置。
 */
object ModelProviders {
    /** 会请求外部端点或重算模型目录的端点：长读超时、独立线程。 */
    val SLOW_ENDPOINTS: Set<String> = setOf("providers", "models", "provider-models", "provider-add", "provider-models-save")

    const val MAX_MODELS = 500
    const val NAME_MAX = 80

    data class ModelRef(val id: String, val ref: String, val name: String)
    data class Key(val configured: Boolean, val writable: Boolean)

    data class Provider(
        val id: String,
        val name: String,
        val models: List<ModelRef>,
        val declaredModels: List<ModelRef>,
        val refreshable: Boolean,
        /** null：该路由不用 API key。 */
        val key: Key?,
        val removable: Boolean,
        val custom: Boolean,
        val error: String?,
    )

    data class Addable(val id: String, val name: String)

    /** [revision]：设置的 revision 原样 JSON 文本（数字或字符串，保存模型时原样回传；上游以 !== 比较）。 */
    data class View(val available: Boolean, val providers: List<Provider>, val addable: List<Addable>, val protocols: List<String>, val revision: String?)

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.bool(k: String) = (members[k] as? Json.Value.BoolValue)?.value == true
    private fun Json.Value.Object.objects(k: String) = ((members[k] as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { it as? Json.Value.Object }

    private fun refs(o: Json.Value.Object, k: String, provider: String) = o.objects(k).mapNotNull { m ->
        val id = m.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ModelRef(id, m.str("ref") ?: "$provider/$id", m.str("name")?.takeIf { it.isNotBlank() } ?: id)
    }

    /** providers 应答（写端点也返回同一视图）；不是对象时 null。 */
    fun decode(value: Json.Value?): View? {
        val o = value as? Json.Value.Object ?: return null
        if ((o.members["available"] as? Json.Value.BoolValue)?.value == false) return View(false, emptyList(), emptyList(), emptyList(), null)
        val providers = o.objects("providers").mapNotNull { p ->
            val id = p.str("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val k = p.members["key"] as? Json.Value.Object
            Provider(
                id = id, name = p.str("name")?.takeIf { it.isNotEmpty() } ?: id,
                models = refs(p, "models", id), declaredModels = refs(p, "declaredModels", id),
                refreshable = p.bool("refreshable"),
                key = k?.let { Key(it.bool("configured"), (it.members["writable"] as? Json.Value.BoolValue)?.value != false) },
                removable = p.bool("removable"), custom = p.bool("custom"), error = p.str("error")?.takeIf { it.isNotEmpty() },
            )
        }
        val addable = o.objects("addable").mapNotNull { a -> a.str("id")?.let { Addable(it, a.str("name") ?: it) } }.sortedBy { it.name.lowercase() }
        val protocols = ((o.members["protocols"] as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { (it as? Json.Value.StringValue)?.value }
        val revision = when (val r = o.members["revision"]) {
            is Json.Value.StringValue, is Json.Value.NumberValue -> Json.encode(r)
            else -> null
        }
        return View(true, providers, addable, protocols, revision)
    }

    /** key 状态文案（网页端 keyState）；不用 key 的路由为 null。 */
    fun keyText(key: Key?): String? = when {
        key == null -> null
        !key.configured -> I18n.tr("需要 API key", "Needs an API key")
        key.writable -> I18n.tr("key 已设置", "Key set")
        else -> I18n.tr("key 在 DS Bot 之外设置", "Key set outside DS Bot")
    }

    /** 服务商行的说明：出错原因，否则模型数。 */
    fun hint(p: Provider): String = p.error ?: if (p.models.isEmpty()) I18n.tr("没有模型", "No models") else I18n.tr("${p.models.size} 个模型", "${p.models.size} models")

    /** 协议显示名（网页端 PROTOCOL_NAMES）。 */
    fun protocolName(api: String): String = when (api) {
        "openai-completions" -> "OpenAI Chat Completions"
        "openai-responses" -> "OpenAI Responses"
        "anthropic-messages" -> "Anthropic Messages"
        "google-generative-ai" -> "Google Gemini"
        else -> api
    }

    data class BotRef(val id: String, val name: String, val model: String?, val appliedModel: String?) {
        /** Bot 的当前模型：要求的优先于已应用的（网页端 modelRefOf）。 */
        val current: String? get() = model ?: appliedModel
    }

    fun bots(state: Json.Value.Object?): List<BotRef> =
        ((state?.members?.get("bots") as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { it as? Json.Value.Object }.mapNotNull { b ->
            val id = b.str("id") ?: return@mapNotNull null
            BotRef(id, b.str("name") ?: id, b.str("model"), b.str("appliedModel"))
        }

    /** 上游已知的本地 Agent CLI（client.js AGENT_BADGES 的键，路由为 local-<键>）。 */
    private val AGENT_CLIS = setOf(
        "devin-cli", "claude-code", "codex-cli", "cursor-cli", "copilot-cli", "gemini-cli", "antigravity", "opencode", "cline", "kilo",
        "goose", "grok-cli", "kimi-cli", "qwen-code", "codebuddy", "qoder", "minimax-code", "glm-cli", "kiro-cli", "factory-droid",
        "auggie", "mistral-vibe", "junie", "iflow",
    )

    /**
     * 是否本地 Agent 的路由（上游 isLocalRoute/badgeOf）：local-acp-<id> 的自定义 ACP Agent，或 local-<已知 CLI>。
     * 评审发现：只看 local- 前缀会把自定义服务商（如 local-ollama）的模型误当本地 Agent。
     */
    fun isLocal(ref: String?): Boolean {
        val route = ref?.substringBefore('/') ?: return false
        if (route.startsWith("local-acp-")) return true
        return route.startsWith("local-") && route.removePrefix("local-") in AGENT_CLIS
    }

    /** 正在使用该服务商模型的 Bot（网页端 usersOf：model 或 appliedModel 命中其模型 ref）。 */
    fun users(p: Provider, bots: List<BotRef>): List<BotRef> {
        val refs = p.models.map { it.ref }.toSet()
        return bots.filter { it.current in refs }
    }

    // ---- 刷新模型草稿 ----

    data class Discovered(val id: String, val name: String?, val raw: Json.Value.Object)

    /** provider-models 应答。 */
    fun discovered(value: Json.Value?): List<Discovered> =
        ((value as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { v ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val id = o.str("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Discovered(id, o.str("name")?.takeIf { it.isNotBlank() }, o)
        }

    data class Row(val id: String, val ref: String, val name: String, val existing: Boolean, val returned: Boolean, val selected: Boolean, val raw: Json.Value.Object?)

    data class Draft(val rows: List<Row>, val revision: String?) {
        val selected: List<Row> get() = rows.filter { it.selected }
        val added: Int get() = selected.count { !it.existing }
        val removed: Int get() = rows.count { it.existing && !it.selected }
        fun toggle(id: String) = copy(rows = rows.map { if (it.id == id) it.copy(selected = !it.selected) else it })
    }

    /** 草稿（网页端 draftFrom）：端点返回的在前（去重），端点未返回的已有模型在后，默认全选。已有 = declaredModels，否则 models。 */
    fun draftFrom(p: Provider, found: List<Discovered>, revision: String?): Draft {
        val existing = p.declaredModels.ifEmpty { p.models }
        val existingIds = existing.map { it.id }.toSet()
        val seen = HashSet<String>()
        val rows = mutableListOf<Row>()
        for (m in found) {
            if (!seen.add(m.id)) continue
            rows.add(Row(m.id, "${p.id}/${m.id}", m.name ?: m.id, m.id in existingIds, returned = true, selected = true, raw = m.raw))
        }
        for (m in existing) {
            if (!seen.add(m.id)) continue
            rows.add(Row(m.id, m.ref, m.name, existing = true, returned = false, selected = true, raw = null))
        }
        return Draft(rows, revision)
    }

    /**
     * 保存会移除的模型上、非本地 Agent 的 Bot：按 Bot 当前模型（model 优先于 appliedModel）判断——网页端 fallbacksFor 中
     * 会切换或失败的正是这些；当前模型保留、只有已应用模型被移除的 Bot 不受影响（评审发现）。不推算回退目标。
     */
    fun affected(p: Provider, draft: Draft, bots: List<BotRef>): List<BotRef> {
        val removed = draft.rows.filter { it.existing && !it.selected }.flatMap { listOf(it.ref, "${p.id}/${it.id}") }.toSet()
        return bots.filter { b -> !isLocal(b.current) && b.current in removed }
    }

    // ---- 校验 ----

    private val NAME_VALUE = Regex("^[A-Za-z_][A-Za-z0-9_]*=[^=]")
    private val QUOTED = Regex("^([\"']).*\\1$")
    private val VISIBLE_ASCII = Regex("^[\\x21-\\x7E]+$")
    /** 上游 providers.js ROUTE_ID：连字符不能连续、不能在结尾。 */
    private val ROUTE_ID = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")

    /** key 本地预校验（上游 checkKey）；通过时 null。 */
    fun keyProblem(value: String): String? {
        val key = value.trim()
        return when {
            key.isEmpty() -> I18n.tr("填写 API key", "Enter the API key")
            NAME_VALUE.containsMatchIn(key) -> I18n.tr("只粘贴 key 本身，不要 NAME=value 这一整行", "Paste only the key, not a NAME=value line")
            QUOTED.matches(key) -> I18n.tr("粘贴 key 时不要带引号", "Paste the key without quotes")
            !VISIBLE_ASCII.matches(key) -> I18n.tr("API key 不含空格或特殊字符", "An API key has no spaces or special characters")
            else -> null
        }
    }

    private val ENDPOINT = Regex("^https?://[^\\s/?#]+", RegexOption.IGNORE_CASE)

    /** 端点须为 http/https 地址（上游 checkEndpoint 用 WHATWG URL；这里只要求协议与非空主机部分，主机名允许下划线等）。 */
    fun endpointOk(value: String): Boolean = ENDPOINT.containsMatchIn(value.trim()) && value.trim().none { it.isWhitespace() }

    /** 手填的模型 id：逗号或空白分隔。 */
    fun typedIds(text: String): List<String> = text.split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** 自定义服务商的模型：勾选的查找结果在前，再加手填且未勾选的（网页端 CustomForm）。 */
    fun customModels(found: List<Discovered>, picked: Set<String>, typed: String): List<String> {
        val pickedIds = found.filter { it.id in picked }.map { it.id }
        return (pickedIds + typedIds(typed).filter { it !in picked }).distinct()
    }

    /** 自定义服务商本地预校验；通过时 null（key 为空可以；非空时按 checkKey）。 */
    fun customProblem(provider: String, baseURL: String, api: String, protocols: List<String>, key: String, models: List<String>): String? = when {
        !ROUTE_ID.matches(provider.trim()) -> I18n.tr("服务商 id 为小写字母、数字与连字符，以字母开头，连字符不能连续或结尾", "A provider id is lowercase letters, digits and dashes, starting with a letter")
        !endpointOk(baseURL) -> I18n.tr("端点须为 http 或 https 地址", "Enter the endpoint as an http or https address")
        protocols.isNotEmpty() && api !in protocols -> I18n.tr("选择协议", "Choose a protocol")
        key.isNotBlank() && keyProblem(key) != null -> keyProblem(key)
        models.isEmpty() -> I18n.tr("至少添加一个模型", "Add at least one model")
        models.size > MAX_MODELS -> I18n.tr("最多 $MAX_MODELS 个模型", "At most $MAX_MODELS models")
        else -> null
    }

    /** 请求体（providers.js 与 channel.js 的参数，源码核对）。 */
    object Requests {
        private fun q(s: String) = JsonString.of(s)
        fun list() = "{}"
        fun refreshCatalog() = "{}"
        fun efforts(ref: String) = "{\"ref\":" + q(ref) + "}"
        fun setKey(provider: String, value: String) = "{\"provider\":" + q(provider) + ",\"value\":" + q(value.trim()) + "}"
        fun clearKey(provider: String) = "{\"provider\":" + q(provider) + ",\"value\":null}"
        fun addKnown(provider: String, key: String) =
            "{\"provider\":" + q(provider) + (if (key.isNotBlank()) ",\"key\":" + q(key.trim()) else "") + "}"
        fun addCustom(provider: String, name: String, baseURL: String, api: String, key: String, models: List<String>) =
            "{\"custom\":true,\"provider\":" + q(provider.trim()) +
                (if (name.isNotBlank()) ",\"name\":" + q(name.trim().take(NAME_MAX)) else "") +
                ",\"baseURL\":" + q(baseURL.trim()) + ",\"api\":" + q(api) +
                (if (key.isNotBlank()) ",\"key\":" + q(key.trim()) else "") +
                ",\"models\":[" + models.joinToString(",") { "{\"id\":" + q(it) + "}" } + "]}"
        fun discoverStored(provider: String) = "{\"provider\":" + q(provider) + "}"
        fun discoverEndpoint(baseURL: String, api: String, key: String) =
            "{\"baseURL\":" + q(baseURL.trim()) + ",\"api\":" + q(api) + (if (key.isNotBlank()) ",\"key\":" + q(key.trim()) else "") + "}"
        /** 保存所选模型：端点返回的带回原对象，其余只带 id（网页端 save）。 */
        fun saveModels(provider: String, draft: Draft): String {
            val models = draft.selected.joinToString(",") { r -> r.raw?.let { Json.encode(it) } ?: ("{\"id\":" + q(r.id) + "}") }
            return "{\"provider\":" + q(provider) + ",\"models\":[" + models + "]" + (draft.revision?.let { ",\"revision\":$it" } ?: "") + "}"
        }
        fun remove(provider: String) = "{\"provider\":" + q(provider) + "}"
    }
}

/**
 * Bot 模型批量切换（dsbot-models-providers，网页端 bot-model-batch.js）：计划（本地 Agent 跳过、模型不在目录中跳过、
 * 已是该模型不变、其余切换）与结果汇总；切换经 update-bot {id, model} 逐个写入。
 */
object BotModelBatch {
    enum class Action { CHANGE, KEEP, SKIP_LOCAL, SKIP_UNAVAILABLE }

    data class Step(val bot: ModelProviders.BotRef, val action: Action)

    enum class Status { UPDATED, UNCHANGED, SKIPPED, FAILED }

    data class Result(val id: String, val name: String, val status: Status, val message: String? = null)

    data class Summary(val total: Int, val updated: Int, val unchanged: Int, val skipped: Int, val failed: Int)

    /** 计划（planModelChange）：[served] 为目录中的模型 ref。 */
    fun plan(bots: List<ModelProviders.BotRef>, ref: String, served: Set<String>): List<Step> = bots.map { b ->
        when {
            ModelProviders.isLocal(b.current) -> Step(b, Action.SKIP_LOCAL)
            ref !in served -> Step(b, Action.SKIP_UNAVAILABLE)
            b.model == ref -> Step(b, Action.KEEP)
            else -> Step(b, Action.CHANGE)
        }
    }

    fun summarize(results: List<Result>) = Summary(
        results.size, results.count { it.status == Status.UPDATED }, results.count { it.status == Status.UNCHANGED },
        results.count { it.status == Status.SKIPPED }, results.count { it.status == Status.FAILED },
    )

    fun summaryText(s: Summary): String = listOfNotNull(
        I18n.tr("已更新 ${s.updated}", "${s.updated} updated"),
        I18n.tr("未变 ${s.unchanged}", "${s.unchanged} unchanged").takeIf { s.unchanged > 0 },
        I18n.tr("跳过 ${s.skipped}", "${s.skipped} skipped").takeIf { s.skipped > 0 },
        I18n.tr("失败 ${s.failed}", "${s.failed} failed").takeIf { s.failed > 0 },
    ).joinToString(" · ")

    fun statusText(r: Result): String = when (r.status) {
        Status.UPDATED -> I18n.tr("已切换", "Switched")
        Status.UNCHANGED -> I18n.tr("已是该模型", "Already on it")
        Status.SKIPPED -> I18n.tr("跳过", "Skipped")
        Status.FAILED -> I18n.tr("失败：", "Failed: ") + (r.message ?: "")
    }

    /** update-bot 请求体（只改模型；目标是 DS Bot 自身模型，不涉及引擎切换）。 */
    fun request(botId: String, ref: String) = "{\"id\":" + JsonString.of(botId) + ",\"model\":" + JsonString.of(ref) + "}"
}
