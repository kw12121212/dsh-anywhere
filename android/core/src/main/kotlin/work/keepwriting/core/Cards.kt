package work.keepwriting.core

/**
 * 交互卡片模型（dsbot-interactive-cards）：Bot 输出不可信——解析带字段校验、
 * 嵌套深度与大小上限，超限或未知类型降级只读文本不崩溃（design 决策
 * card-validation-in-core，JUnit 覆盖）。卡片条目来源：chat/room-chat 的
 * answer/closed kind（question 字段）与 state 的 questions/views/secretRequests
 * 键；作答经 /api/bot 写端点（payload 形态 0.3.5 探针实证，见 tech/dsbot-api）。
 */
object Cards {

    /** 卡片渲染模型（降级安全：任何畸形输入至少产出 ReadOnly）。 */
    sealed interface Card {
        /** 只读降级（未知类型/超限/畸形）：显示原始摘要文本，不可交互。 */
        data class ReadOnly(val title: String?, val body: String) : Card
        /** 审批卡：同意/驳回。 */
        data class Approval(val callId: String, val title: String?, val detail: String?) : Card
        /** 提问卡：单选选项。 */
        data class Question(val callId: String, val title: String?, val options: List<Option>) : Card {
            data class Option(val label: String, val description: String?)
        }
        /** 视图卡（show_view）：文本/指标/表格/公式输入/进度/选择/确认/短表单。 */
        data class View(
            val callId: String,
            val title: String?,
            val sections: List<Section>,
        ) : Card {
            sealed interface Section {
                data class Text(val text: String) : Section
                data class Metric(val label: String, val value: String) : Section
                data class Table(val header: List<String>, val rows: List<List<String>>) : Section
                data class Formula(val name: String, val placeholder: String?) : Section
                data class Progress(val label: String?, val percent: Int) : Section
                data class Choice(val name: String, val options: List<String>) : Section
                data class Confirm(val label: String) : Section
            }
        }
        /** 密钥请求卡：值密码形态，不回显不落日志。 */
        data class SecretRequest(val callId: String, val title: String?, val hint: String?) : Card
        /** 学习问答：单选。 */
        data class Learning(val callId: String, val title: String?, val options: List<String>) : Card
        /** 浏览器交接卡：完成/跳过。 */
        data class BrowserHandoff(val callId: String, val title: String?) : Card
        /** 沙箱 HTML 卡：App 在隔离 WebView 显示。 */
        data class SandboxHtml(val callId: String, val title: String?, val html: String) : Card
    }

    /** 解析上限：嵌套深度与条目/字符规模（超限降级 ReadOnly）。 */
    const val MAX_DEPTH = 16
    const val MAX_ITEMS = 64
    const val MAX_CHARS = 64_000

    /** 从 chat 条目 raw 解析卡片（answer/closed kind 的 question 字段；无 callId 降级只读）。 */
    fun fromChatItem(raw: DsbotModels.ChatItem): Card? {
        val obj = raw.raw as? Json.Value.Object ?: return null
        if (raw.kind !in setOf("answer", "closed")) return null
        val nested = obj.members["question"] as? Json.Value.Object
            ?: (obj.members["questions"] as? Json.Value.Array)?.items?.firstOrNull() as? Json.Value.Object
            ?: obj.members["view"] as? Json.Value.Object
            ?: obj.members["secret"] as? Json.Value.Object
        // closed（已答/已关闭）条目按只读呈现，不再出交互卡（避免重取后复现已答卡）
        if (raw.kind == "closed") {
            return nested?.let { Card.ReadOnly(str(it, "question") ?: str(it, "title"), "已处理") }
        }
        if (nested != null) {
            val card = parse(nested)
            // 多问题条目：首卡之外标注余量（0.3.5 实测 questions[] 可复数）
            val extra = (obj.members["questions"] as? Json.Value.Array)?.items?.size?.minus(1) ?: 0
            if (extra > 0 && card != null) {
                return Card.ReadOnly("本条含 ${extra + 1} 个待办问题", "请在网页端逐个处理（原生端暂不支持多问题条目作答）")
            }
            return card
        }
        // 扁平卡：callId 与作答字段在条目顶层
        if (obj.members.containsKey("callId")) return parse(obj)
        return null
    }

    /** 从 state 的待办键（questions/views/secretRequests 数组元素）解析。 */
    fun fromStateEntry(value: Json.Value): Card? = (value as? Json.Value.Object)?.let { parse(it) }

    /** 统一解析与校验：callId 必有（作答端点需要），否则降级只读。 */
    fun parse(obj: Json.Value.Object): Card? {
        if (Json.encode(obj).length > MAX_CHARS) return Card.ReadOnly(null, "卡片内容过大，已折叠")
        val callId = str(obj, "callId") ?: str(obj, "id") ?: return null
        // 无 kind 的待办对象：带 options/question 字段按提问处理（chat 条目 questions[] 的形态）；
        // 外层 chat kind（answer/closed）不是卡类型——作 fallback 处理（扁平卡形态）
        val rawKind = str(obj, "kind") ?: str(obj, "type")
        val innerType = str(obj, "type") // 扁平卡可同时带外层 kind（answer/closed）与真实卡类型 type
        val kind = when {
            innerType != null && innerType !in setOf("answer", "closed", "event") -> innerType
            rawKind in setOf("answer", "closed", "event") ->
                if (obj.members.containsKey("options") || obj.members.containsKey("question")) "question" else null
            else -> rawKind ?: if (obj.members.containsKey("options") || obj.members.containsKey("question")) "question" else null
        }
        return when (kind) {
            "approval" -> Card.Approval(callId, str(obj, "title"), str(obj, "detail") ?: str(obj, "why"))
            "question", "ask_user" -> parseQuestion(callId, obj)
            "view", "show_view" -> parseView(callId, obj)
            "secret", "secret-request" -> Card.SecretRequest(callId, str(obj, "title"), str(obj, "hint"))
            "learning" -> {
                val options = strList(obj, "options")
                if (options.isEmpty()) Card.ReadOnly(str(obj, "title"), Json.encode(obj).take(400))
                else Card.Learning(callId, str(obj, "title"), options.take(MAX_ITEMS))
            }
            "browser-handoff", "browser" -> Card.BrowserHandoff(callId, str(obj, "title"))
            "html" -> {
                val html = str(obj, "html") ?: return Card.ReadOnly(str(obj, "title"), "空 HTML 卡")
                Card.SandboxHtml(callId, str(obj, "title"), html)
            }
            else -> null // 未知类型交由调用方降级
        }
    }

    private fun parseQuestion(callId: String, obj: Json.Value.Object): Card {
        val title = str(obj, "question") ?: str(obj, "title")
        val options = (obj.members["options"] as? Json.Value.Array)?.items.orEmpty()
            .mapNotNull { it as? Json.Value.Object }
            .mapNotNull { o -> str(o, "label")?.let { Card.Question.Option(it, str(o, "description")) } }
        return if (options.isEmpty()) Card.ReadOnly(title, Json.encode(obj).take(400))
        else Card.Question(callId, title, options.take(MAX_ITEMS))
    }

    private fun parseView(callId: String, obj: Json.Value.Object): Card {
        val title = str(obj, "title") ?: str(obj, "question")
        val sections = mutableListOf<Card.View.Section>()
        var depth = 0
        fun walk(node: Json.Value.Object) {
            if (++depth > MAX_DEPTH || sections.size >= MAX_ITEMS) return
            for ((key, value) in node.members) {
                when (key) {
                    "text", "note" -> (value as? Json.Value.StringValue)?.let {
                        sections.add(Card.View.Section.Text(it.value))
                    }
                    "metrics" -> (value as? Json.Value.Object)?.members?.forEach { (k, v) ->
                        (v as? Json.Value.StringValue)?.let { sections.add(Card.View.Section.Metric(k, it.value)) }
                    }
                    "table" -> (value as? Json.Value.Object)?.let { t ->
                        val header = strList(t, "header")
                        val rows = (t.members["rows"] as? Json.Value.Array)?.items
                            ?.mapNotNull { r -> (r as? Json.Value.Array)?.items?.mapNotNull { c -> (c as? Json.Value.StringValue)?.value } }
                            .orEmpty()
                        if (header.isNotEmpty()) sections.add(Card.View.Section.Table(header, rows.take(MAX_ITEMS)))
                    }
                    "formula", "input" -> (value as? Json.Value.Object)?.let { f ->
                        str(f, "name")?.let { sections.add(Card.View.Section.Formula(it, str(f, "placeholder"))) }
                    }
                    "progress" -> (value as? Json.Value.Object)?.let { pr ->
                        val pct = (pr.members["percent"] as? Json.Value.NumberValue)?.raw?.toIntOrNull()?.coerceIn(0, 100) ?: 0
                        sections.add(Card.View.Section.Progress(str(pr, "label"), pct))
                    }
                    "choice" -> (value as? Json.Value.Object)?.let { c ->
                        str(c, "name")?.let { n ->
                            val opts = strList(c, "options")
                            if (opts.isNotEmpty()) sections.add(Card.View.Section.Choice(n, opts.take(MAX_ITEMS)))
                        }
                    }
                    "confirm" -> (value as? Json.Value.StringValue)?.let {
                        sections.add(Card.View.Section.Confirm(it.value))
                    }
                    "sections" -> (value as? Json.Value.Array)?.items?.forEach { walk(it as? Json.Value.Object ?: return@forEach) }
                }
            }
            depth--
        }
        walk(obj)
        return if (sections.isEmpty()) Card.ReadOnly(title, Json.encode(obj).take(400))
        else Card.View(callId, title, sections)
    }

    // ---- 作答端点 payload 构造（0.3.5 探针实证形态）----

    fun approvalAnswer(callId: String, approved: Boolean): String =
        "{\"callId\":${JsonString.of(callId)},\"approved\":$approved}"

    fun answerQuestion(callId: String, choice: Int): String =
        "{\"callId\":${JsonString.of(callId)},\"choice\":$choice}"

    fun dismiss(callId: String): String = "{\"callId\":${JsonString.of(callId)}}"

    fun viewReply(callId: String, valuesJson: String): String =
        "{\"callId\":${JsonString.of(callId)},\"values\":$valuesJson}"

    fun secretSet(callId: String, value: String): String =
        "{\"callId\":${JsonString.of(callId)},\"value\":${JsonString.of(value)}}"

    fun learningAnswer(callId: String, choice: Int): String =
        "{\"callId\":${JsonString.of(callId)},\"choice\":$choice}"

    fun browserGiveBack(callId: String, result: String): String =
        "{\"callId\":${JsonString.of(callId)},\"result\":${JsonString.of(result)}}"

    // ---- 基础取值 ----

    private fun str(obj: Json.Value.Object, key: String): String? =
        (obj.members[key] as? Json.Value.StringValue)?.value?.takeIf { it.isNotEmpty() }

    private fun rawOf(obj: Json.Value.Object, key: String): String? = str(obj, key)

    private fun strList(obj: Json.Value.Object, key: String): List<String> =
        (obj.members[key] as? Json.Value.Array)?.items?.mapNotNull {
            (it as? Json.Value.StringValue)?.value?.takeIf { s -> s.isNotEmpty() }
        } ?: emptyList()
}
