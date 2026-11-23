package work.keepwriting.core

/**
 * 工作短语（dsbot-chat-bars-0310）：live work 条目（{tool?, said?, note?, target?, switching?, folder?}）
 * 到一句「正在做什么」。顺序对齐 DS-Bot 0.3.10 网页端 workPhrase/toolActivity：
 * 切换 → Bot 自述（said/note，去句尾标点）→ 无工具「思考中」→ 专属工具 → 正则表 → 通用；
 * 有 target 时附「 · target」。只学行为不搬代码；ZH/EN 两套文案。
 */
object WorkPhrase {
    private const val OWN_MAX = 160
    private const val TARGET_MAX = 80

    private enum class P(val zh: String, val en: String) {
        THINKING("思考中", "Thinking about it"),
        ON_IT("处理中", "On it"),
        SWITCH_FOLDER("切换工作文件夹", "Switching working folder"),
        FRESH_CHAT("转入新对话", "Moving to a fresh conversation"),
        WAIT_ANSWER("等你回答", "Waiting for your answer"),
        ASK_BOT("询问其他 Bot", "Asking another Bot"),
        STOP_BOT("停止其他 Bot", "Stopping another Bot"),
        CREATE_BOT("创建 Bot", "Creating a Bot"),
        UPDATE_BOT("更新 Bot", "Updating a Bot"),
        TEAM("查看团队", "Checking the team"),
        READ_GROUP("阅读群聊", "Reading a group chat"),
        READ_OWN("回看聊天", "Looking back through the chat"),
        REMEMBER("写入记忆", "Saving to memory"),
        FORGET("更新记忆", "Updating memory"),
        RECALL("查看记忆", "Checking memory"),
        KEYS("查看密钥", "Checking the keys"),
        CREATE_GROUP("创建群聊", "Creating a group"),
        UPDATE_GROUP("更新群聊", "Updating the group"),
        DELETE_GROUP("删除群聊", "Deleting the group"),
        POST_GROUP("在群里发言", "Posting in the group"),
        COMPUTER("使用电脑", "Using the computer"),
        SEARCH_WEB("联网搜索", "Searching online"),
        READ_PAGE("阅读网页", "Reading a page"),
        SHELL("运行命令", "Running shell commands"),
        OPEN_FILE("打开文件", "Opening a file"),
        FIND_FILES("查找文件", "Looking through files"),
        WRITE("写入修改", "Writing changes"),
        IMAGE("处理图片", "Working on an image"),
    }

    private val EXACT = mapOf(
        "ask_user_question" to P.WAIT_ANSWER,
        "list_secrets" to P.KEYS,
        "message_bot" to P.ASK_BOT,
        "stop_bot" to P.STOP_BOT,
        "create_bot" to P.CREATE_BOT,
        "update_bot" to P.UPDATE_BOT,
        "list_bots" to P.TEAM,
        "read_group_chat" to P.READ_GROUP,
        "read_own_chat" to P.READ_OWN,
        "bot_remember" to P.REMEMBER, "remember" to P.REMEMBER,
        "bot_forget" to P.FORGET, "forget" to P.FORGET,
        "bot_recall" to P.RECALL, "recall" to P.RECALL,
        "create_group" to P.CREATE_GROUP,
        "update_group" to P.UPDATE_GROUP,
        "delete_group" to P.DELETE_GROUP,
        "post_to_group" to P.POST_GROUP,
    )

    /** 网页端不给标签、回落通用短语的工具（卡片类与群接力）。 */
    private val GENERIC = setOf("ask_user", "request_secret", "group_relay")

    private val PATTERNS: List<Pair<Regex, P>> = listOf(
        Regex("^cua(_driver_native__|$)|^computer_") to P.COMPUTER,
        Regex("web_?search|search_?web", RegexOption.IGNORE_CASE) to P.SEARCH_WEB,
        Regex("fetch|browse|browser|web", RegexOption.IGNORE_CASE) to P.READ_PAGE,
        Regex("shell|bash|pwsh|powershell|exec|command|terminal", RegexOption.IGNORE_CASE) to P.SHELL,
        Regex("^(read|view|cat)\\b|read_", RegexOption.IGNORE_CASE) to P.OPEN_FILE,
        Regex("grep|glob|list_dir|^ls$|find|search", RegexOption.IGNORE_CASE) to P.FIND_FILES,
        Regex("write|edit|patch|apply|replace", RegexOption.IGNORE_CASE) to P.WRITE,
        Regex("image|photo|draw", RegexOption.IGNORE_CASE) to P.IMAGE,
    )

    private val TRAILING = Regex("[\\s.。…]+$")

    /** 工具名到短语；未知工具返回 null（调用方回落通用短语）。 */
    fun toolLabel(tool: String, lang: ChatBars.Lang = I18n.lang): String? {
        if (tool in GENERIC) return null
        val p = EXACT[tool] ?: PATTERNS.firstOrNull { it.first.containsMatchIn(tool) }?.second ?: return null
        return p.text(lang)
    }

    fun phrase(doing: Json.Value.Object?, lang: ChatBars.Lang = I18n.lang): String {
        val m = doing?.members ?: return P.THINKING.text(lang)
        if ((m["switching"] as? Json.Value.BoolValue)?.value == true) {
            val folder = (m["folder"] as? Json.Value.BoolValue)?.value == true
            return (if (folder) P.SWITCH_FOLDER else P.FRESH_CHAT).text(lang)
        }
        val said = str(m, "said") ?: str(m, "note")
        val own = said?.replace(TRAILING, "")?.trim()
        if (!own.isNullOrEmpty()) return ChatBars.clip(own, OWN_MAX)
        val tool = str(m, "tool") ?: return P.THINKING.text(lang)
        val label = toolLabel(tool, lang) ?: P.ON_IT.text(lang)
        val target = str(m, "target")?.trim()
        return if (target.isNullOrEmpty()) label else "$label · ${ChatBars.clip(target, TARGET_MAX)}"
    }

    private fun P.text(lang: ChatBars.Lang) = if (lang == ChatBars.Lang.ZH) zh else en

    private fun str(m: Map<String, Json.Value>, key: String): String? =
        (m[key] as? Json.Value.StringValue)?.value?.takeIf { it.isNotBlank() }
}
