package work.keepwriting.core

/**
 * Bot 详情与设置（dsbot-bot-manage）：从 state 解码 Bot 资料、模型目录、思考强度与权限目录，做与网页端一致的
 * 入口显隐判定，并生成各写端点的请求体。端点形态见 tech/dsbot-api 的 bot-manage-endpoints-0310（state 字段与
 * efforts 实测；写端点按 ds-bot 0.3.10 channel.js/roster.js/privacy.js 源码核对）。拒绝原因以服务端为准，
 * 这里的校验只用于提前提示。
 */
object BotManage {
    /** 网页端 CHARACTERS 的 20 种形象（同名同序）。 */
    val SHAPES: List<String> = listOf(
        "whale", "star", "moon", "wave", "squircle", "zed", "hex", "gumdrop", "peanut", "saucer",
        "bunny", "kitty", "beacon", "plus", "magnet", "acorn", "pebbles", "cushion", "ring", "gem",
    )

    /** 形象名的界面标签（网页端英文标签：zed=Bull、hex=Blossom，其余按形象本义）。 */
    fun shapeLabel(shape: String?): String = when (shape) {
        "whale" -> I18n.tr("鲸鱼", "Whale")
        "star" -> I18n.tr("星星", "Star")
        "moon" -> I18n.tr("月亮", "Moon")
        "wave" -> I18n.tr("波浪", "Wave")
        "squircle" -> I18n.tr("圆角方", "Squircle")
        "zed" -> I18n.tr("公牛", "Bull")
        "hex" -> I18n.tr("花朵", "Blossom")
        "gumdrop" -> I18n.tr("软糖", "Gumdrop")
        "peanut" -> I18n.tr("花生", "Peanut")
        "saucer" -> I18n.tr("飞碟", "Saucer")
        "bunny" -> I18n.tr("兔子", "Bunny")
        "kitty" -> I18n.tr("小猫", "Kitty")
        "beacon" -> I18n.tr("灯塔", "Beacon")
        "plus" -> I18n.tr("加号", "Plus")
        "magnet" -> I18n.tr("磁铁", "Magnet")
        "acorn" -> I18n.tr("橡果", "Acorn")
        "pebbles" -> I18n.tr("鹅卵石", "Pebbles")
        "cushion" -> I18n.tr("靠垫", "Cushion")
        "ring" -> I18n.tr("圆环", "Ring")
        "gem" -> I18n.tr("宝石", "Gem")
        null -> I18n.tr("默认", "Default")
        else -> shape
    }

    /** 网页端 CHARACTER_COLORS 的 12 种颜色（同名同序）。 */
    val COLORS: List<String> = listOf(
        "black", "brown", "red", "orange", "yellow", "green", "cyan", "blue", "violet", "magenta", "gray", "aurora",
    )

    /** 颜色名 → 0xRRGGBB（网页端 inks.js 的首色；未知颜色按 gray）。 */
    fun colorRgb(name: String?): Int = when (name) {
        "deepseek" -> 0x4D6BFE
        "charcoal" -> 0x141416
        "graphite" -> 0x2D2D2D
        "rose" -> 0xEC2F6B
        "sky" -> 0x0AA8D6
        "tangerine" -> 0xFF6900
        "cobalt" -> 0x082DFF
        "black" -> 0x000000
        "brown" -> 0x9A6B44
        "red" -> 0xFF3E51
        "orange" -> 0xFF6B1A
        "yellow" -> 0xFFB020
        "green" -> 0x10B981
        "cyan" -> 0x06B6D4
        "blue" -> 0x2563EB
        "violet" -> 0x8B5CF6
        "magenta" -> 0xF43F85
        "aurora" -> 0x00B8D4
        else -> 0x8A8F98
    }

    const val NAME_MAX = 40
    const val ROLE_MAX = 24

    data class Profile(
        val id: String,
        val name: String,
        val role: String,
        val instructions: String,
        val model: String?,
        val appliedModel: String?,
        val effort: String?,
        val permission: String?,
        val isPrivate: Boolean,
        val pinned: Boolean,
        val hidden: Boolean,
        val color: String?,
        val shape: String?,
        val hasPhoto: Boolean,
        val workDir: String?,
        val isMain: Boolean,
        /** 第一个 Main Bot（侧栏磁贴）：网页端不提供置顶/隐藏。 */
        val isTile: Boolean,
        /** 唯一的 Main Bot：不能取消 Main 角色。 */
        val onlyMain: Boolean,
        val inGroup: Boolean,
    ) {
        /** 模型显示用：要求的模型优先，其次最近实际使用的。 */
        val currentModel: String? get() = model ?: appliedModel
    }

    data class Model(val ref: String, val name: String, val provider: String, val providerName: String, val needsKey: Boolean)

    data class ModelGroup(val providerName: String, val models: List<Model>)

    data class Effort(val id: String, val name: String, val description: String?)

    data class Efforts(val efforts: List<Effort>, val defaultEffort: String?)

    data class Permission(val value: String, val name: String)

    data class Permissions(val options: List<Permission>, val defaultPreset: String?)

    data class PrivateArchive(val id: String, val name: String, val workDir: String?)

    // ---- 解码 ----

    private fun Json.Value.Object.str(key: String): String? = (members[key] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.bool(key: String): Boolean = (members[key] as? Json.Value.BoolValue)?.value == true
    private fun Json.Value.Object.arr(key: String): List<Json.Value> = (members[key] as? Json.Value.Array)?.items ?: emptyList()
    private fun Json.Value.Object.strings(key: String): List<String> = arr(key).mapNotNull { (it as? Json.Value.StringValue)?.value }

    /** state 里某个 Bot 的资料；不存在或形态不对返回 null。 */
    fun profileOf(state: Json.Value.Object?, botId: String): Profile? {
        state ?: return null
        val bot = state.arr("bots").firstOrNull { (it as? Json.Value.Object)?.str("id") == botId } as? Json.Value.Object
            ?: return null
        val mains = state.strings("mainBotIds")
        val avatar = bot.members["avatar"] as? Json.Value.Object
        val inGroup = state.arr("rooms").any { r -> (r as? Json.Value.Object)?.strings("members")?.contains(botId) == true }
        return Profile(
            id = botId,
            name = bot.str("name") ?: "",
            role = bot.str("role") ?: "",
            instructions = bot.str("instructions") ?: "",
            model = bot.str("model"),
            appliedModel = bot.str("appliedModel"),
            effort = bot.str("effort"),
            permission = bot.str("permission"),
            isPrivate = bot.bool("private"),
            pinned = bot.bool("pinned"),
            hidden = bot.bool("hidden"),
            color = bot.str("color"),
            shape = avatar?.str("shape"),
            hasPhoto = avatar?.members?.containsKey("image") == true || avatar?.members?.containsKey("imageRef") == true,
            workDir = bot.str("workDir"),
            isMain = botId in mains,
            isTile = mains.firstOrNull() == botId,
            onlyMain = mains.size == 1 && mains[0] == botId,
            inGroup = inGroup,
        )
    }

    /** 团队里全部 Bot 的名字（重名检查用），按 id。 */
    fun botNames(state: Json.Value.Object?): Map<String, String> =
        state?.arr("bots").orEmpty().mapNotNull { b ->
            val o = b as? Json.Value.Object ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            id to (o.str("name") ?: "")
        }.toMap()

    /** state.models 按服务商分组（保持服务端顺序）；畸形条目跳过。 */
    fun models(state: Json.Value.Object?): List<ModelGroup> {
        val list = state?.arr("models").orEmpty().mapNotNull { m ->
            val o = m as? Json.Value.Object ?: return@mapNotNull null
            val ref = o.str("ref") ?: return@mapNotNull null
            val provider = o.str("provider") ?: ref.substringBefore('/')
            Model(ref, o.str("name") ?: o.str("id") ?: ref, provider, o.str("providerName") ?: provider, o.bool("needsKey"))
        }
        val groups = LinkedHashMap<String, MutableList<Model>>()
        for (m in list) groups.getOrPut(m.providerName) { mutableListOf() }.add(m)
        return groups.map { (k, v) -> ModelGroup(k, v) }
    }

    /** 模型 ref 的显示名（目录里没有时显示 ref 本身）。 */
    fun modelName(state: Json.Value.Object?, ref: String?): String? {
        ref ?: return null
        return models(state).flatMap { it.models }.firstOrNull { it.ref == ref }?.let { "${it.name} · ${it.providerName}" } ?: ref
    }

    /**
     * 运行引擎：服务商以 local- 开头的是本地 Agent（该服务商即引擎），其余为 model；空 ref 为 null。
     * 近似判定：上游只把已知的 local-<cli> 与 local-acp-* 当本地 Agent；服务商恰好以 local- 开头时只会多一次确认，无害。
     */
    fun engineOf(ref: String?): String? {
        if (ref.isNullOrEmpty()) return null
        val provider = ref.substringBefore('/')
        return if (provider.startsWith("local-")) provider else "model"
    }

    /** 从 [current] 换到 [next] 是否改变运行引擎（需二次确认并带 changeEngine:true）。当前没有模型时视为 model。 */
    fun needsEngineConfirm(current: String?, next: String): Boolean = (engineOf(current) ?: "model") != engineOf(next)

    /** efforts 应答（实测：{efforts:[{id,name}]}，无级别时为空数组）。 */
    fun efforts(value: Json.Value?): Efforts {
        val o = value as? Json.Value.Object ?: return Efforts(emptyList(), null)
        val list = o.arr("efforts").mapNotNull { e ->
            val eo = e as? Json.Value.Object ?: return@mapNotNull null
            val id = eo.str("id") ?: return@mapNotNull null
            Effort(id, eo.str("name") ?: id, eo.str("description"))
        }
        return Efforts(list, o.str("defaultEffort"))
    }

    /** state.permissions（实测 {options[{value,name}], defaultPreset}）；缺失或无选项为 null（入口隐藏）。 */
    fun permissions(state: Json.Value.Object?): Permissions? {
        val o = state?.members?.get("permissions") as? Json.Value.Object ?: return null
        val options = o.arr("options").mapNotNull { p ->
            val po = p as? Json.Value.Object ?: return@mapNotNull null
            val v = po.str("value") ?: return@mapNotNull null
            Permission(v, po.str("name") ?: v)
        }
        if (options.isEmpty()) return null
        return Permissions(options, o.str("defaultPreset"))
    }

    /** 保留关闭的已删除私有 Bot。 */
    fun privateArchives(state: Json.Value.Object?): List<PrivateArchive> =
        state?.arr("privateArchives").orEmpty().mapNotNull { a ->
            val o = a as? Json.Value.Object ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            PrivateArchive(id, o.str("name") ?: id, o.str("workDir"))
        }

    // ---- 名称校验（上游 shared/mentions.js nameProblem 与 roster.js 重名规则）----

    private val ALL_WORDS = listOf("all", "everyone", "所有人", "全体")
    private val USER_NAMES = listOf("user", "you")

    enum class NameProblem { EMPTY, BAD_CHAR, CALLS_ALL, USER_WORD, TAKEN }

    /**
     * 名称问题；[others] 为团队 Bot 的 id→名，[selfId] 是正在改名的 Bot（不与自己比）。名称先 trim 并截到 40 字，
     * 与服务端一致。无问题返回 null。
     */
    fun nameProblem(name: String, others: Map<String, String>, selfId: String? = null): NameProblem? {
        val text = name.trim().take(NAME_MAX)
        if (text.isEmpty()) return NameProblem.EMPTY
        if (text.any { it == '@' || it == '`' || it == '\r' || it == '\n' }) return NameProblem.BAD_CHAR
        val lower = text.lowercase()
        if (lower in ALL_WORDS) return NameProblem.CALLS_ALL
        if (lower in USER_NAMES) return NameProblem.USER_WORD
        if (others.any { (id, n) -> id != selfId && n.trim().lowercase() == lower }) return NameProblem.TAKEN
        return null
    }

    fun nameProblemText(p: NameProblem): String = when (p) {
        NameProblem.EMPTY -> I18n.tr("请输入名称", "Enter a name")
        NameProblem.BAD_CHAR -> I18n.tr("名称不能包含 @、` 或换行", "A name cannot contain @, ` or a line break")
        NameProblem.CALLS_ALL -> I18n.tr("这个词在群里会呼叫所有人，不能作为 Bot 名称", "This word calls everyone in a group, so it cannot be a Bot name")
        NameProblem.USER_WORD -> I18n.tr("这个词在聊天里代表你，不能作为 Bot 名称", "This word stands for you in a chat, so it cannot be a Bot name")
        NameProblem.TAKEN -> I18n.tr("已有同名的 Bot", "A Bot with this name already exists")
    }

    // ---- 入口显隐（网页端 BotSettings）----

    /** 私有开关：已私有可随时关闭；打开需要非 Main、不在群里、有自己的工作文件夹。 */
    fun canTogglePrivate(p: Profile): Boolean = p.isPrivate || (!p.isMain && !p.inGroup && !p.workDir.isNullOrEmpty())

    /** 私有开关不可用时的说明（可用时为私有含义说明）。 */
    fun privateHint(p: Profile): String = when {
        p.isPrivate -> I18n.tr("其他 Bot 读不到它的文件夹、记忆和聊天，它分享给别人的内容需要你批准。", "Other Bots cannot read its folder, memory or chats, and you approve what it shares with them.")
        p.isMain -> I18n.tr("Main Bot 不能设为私有。", "A Main Bot cannot be private.")
        p.inGroup -> I18n.tr("先让它离开所在的群聊，才能设为私有。", "Leave its group chats before making it private.")
        p.workDir.isNullOrEmpty() -> I18n.tr("先为它选择自己的工作文件夹。", "Choose a working folder of its own first.")
        else -> I18n.tr("其他 Bot 读不到它的文件夹、记忆和聊天，它分享给别人的内容需要你批准。", "Other Bots cannot read its folder, memory or chats, and you approve what it shares with them.")
    }

    /** Main Bot 开关：私有 Bot 不可设；唯一 Main Bot 不可取消。 */
    fun canToggleMain(p: Profile): Boolean = !p.isPrivate && !p.onlyMain

    fun canDelete(p: Profile): Boolean = !p.isMain

    fun canDuplicate(p: Profile): Boolean = !p.isPrivate

    /** 侧栏置顶：磁贴（第一个 Main Bot）不提供；隐藏：任何 Main Bot 都不提供。 */
    fun canPin(p: Profile): Boolean = !p.isTile

    fun canHide(p: Profile): Boolean = !p.isTile && !p.isMain

    // ---- 请求体（POST /api/bot 的 payload）----

    object Requests {
        private fun q(s: String) = JsonString.of(s)
        private fun field(k: String, v: String?) = q(k) + ":" + (v?.let(::q) ?: "null")

        /** create-bot：空的可选项不发送（服务端取默认）；effort 只在选了模型时发送。 */
        fun createBot(name: String, role: String, instructions: String, model: String?, effort: String?, color: String?, shape: String?): String {
            val parts = mutableListOf(field("name", name.trim()))
            if (role.isNotBlank()) parts += field("role", role.trim())
            if (instructions.isNotBlank()) parts += field("instructions", instructions.trim())
            if (!model.isNullOrEmpty()) {
                parts += field("model", model)
                if (!effort.isNullOrEmpty()) parts += field("effort", effort)
            }
            if (!color.isNullOrEmpty()) parts += field("color", color)
            if (!shape.isNullOrEmpty()) parts += q("avatar") + ":{" + field("shape", shape) + "}"
            return "{" + parts.joinToString(",") + "}"
        }

        /** update-bot 的单字段补丁：value 为 null 表示清除（color/effort）。 */
        fun updateText(id: String, key: String, value: String?): String = "{" + field("id", id) + "," + field(key, value) + "}"

        fun updateBool(id: String, key: String, value: Boolean): String = "{" + field("id", id) + "," + q(key) + ":" + value + "}"

        /** 换模型：引擎变化时带 changeEngine:true（用户已确认）。 */
        fun updateModel(id: String, ref: String, changeEngine: Boolean): String =
            "{" + field("id", id) + "," + field("model", ref) + (if (changeEngine) ",\"changeEngine\":true" else "") + "}"

        /** 换形象：只改 shape（照片头像保留在 dsbot-avatars 处理；这里设形象即换成形象头像）。 */
        fun updateShape(id: String, shape: String): String = "{" + field("id", id) + ",\"avatar\":{" + field("shape", shape) + "}}"

        fun idOnly(id: String): String = "{" + field("id", id) + "}"

        fun setMain(id: String, main: Boolean): String = "{" + field("id", id) + ",\"main\":" + main + "}"

        fun setFlag(id: String, key: String, value: Boolean): String = "{" + field("id", id) + "," + q(key) + ":" + value + "}"

        fun efforts(ref: String): String = "{" + field("ref", ref) + "}"
    }

    /** 写操作失败的可读原因：服务端 message 优先（具体原因），缺失时按错误码。 */
    fun errorText(code: String?, message: String?): String {
        // bot/internal 的 message 是内部异常原文（如 TypeError），不直接给用户看
        if (code != "bot/internal" && !message.isNullOrBlank()) return message
        return when (code?.let(DsbotApi::reasonOf)) {
            null -> I18n.tr("网络错误，请稍后重试", "Network error. Try again later")
            DsbotApi.Reason.READ_ONLY -> I18n.tr("团队当前只读（另一处正在使用）", "The team is read-only right now (in use elsewhere)")
            DsbotApi.Reason.NOT_FOUND -> I18n.tr("对象已不存在", "It no longer exists")
            DsbotApi.Reason.STALE -> I18n.tr("内容已变化，请刷新后重试", "It changed. Refresh and try again")
            DsbotApi.Reason.BAD_INPUT -> I18n.tr("输入无效", "Invalid input")
            DsbotApi.Reason.SERVER_ERROR -> I18n.tr("服务端出错，请稍后重试", "The server hit an error. Try again later")
            else -> I18n.tr("操作被拒绝", "The server refused this change")
        }
    }
}
