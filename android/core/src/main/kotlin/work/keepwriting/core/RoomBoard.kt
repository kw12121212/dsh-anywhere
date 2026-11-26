package work.keepwriting.core

/**
 * 群共享待办与任务看板（dsbot-room-todos-tasks）：state.rooms[] 的 todos 与 tasks 解码、排序与开放/历史划分，
 * room-todo / task-note / task-cancel 请求体，状态与受阻原因文案、按钮显隐、备注校验与入口判定。
 *
 * 形态见 tech/dsbot-api room-board-endpoints-0310（FINDINGS 第 44 节：错误形态实测，成功形态源码核对）；
 * 排序、显隐与文案照搬 DS-Bot 0.3.10 client.js src/client/group-settings.js（GroupTodos）与
 * src/client/task-board.js（TaskBoard、TaskRow、STATUS_KEY、blockedLine），中文取网页端自带的 zh 文案表。
 * 远端数据不可信：缺失、类型不符或畸形一律降级为不显示，绝不抛异常。
 */
object RoomBoard {
    /** 上游 tasks.js NOTE_MAX：服务端把备注截到 500 字符。 */
    const val NOTE_MAX = 500

    /** 上游 tasks.js OPEN_STATUSES：进行中、待审、阻塞为开放任务，其余（done、cancelled）进历史。 */
    val OPEN_STATUSES = setOf("active", "review", "blocked")

    data class Todo(val id: String, val text: String, val done: Boolean, val createdAt: Long?)

    /** 任务受阻原因（上游 task.blocked）：cause 为 stopped / restarted，其余视为模型调用失败。 */
    data class Blocked(val cause: String?, val code: String?, val status: String?)

    data class Task(
        val id: String,
        val botId: String?,
        val goal: String,
        val status: String,
        val startedAt: Long?,
        val progress: String?,
        val result: String?,
        val reason: String?,
        val blocked: Blocked?,
        /** 只显示最近一条备注（网页端 notes[notes.length - 1]）。 */
        val lastNote: String?,
    ) {
        /** 开放任务才可写备注与取消（网页端 open ? actions : null）。 */
        val open: Boolean get() = status in OPEN_STATUSES
    }

    data class Board(
        /** 未完成在前、再按 createdAt（网页端 GroupTodos 排序）。 */
        val todos: List<Todo>,
        /** 开放任务按 startedAt 升序。 */
        val open: List<Task>,
        /** 已结束任务按 startedAt 倒序（网页端 past.reverse()）。 */
        val past: List<Task>,
    ) {
        val openTodos: Int get() = todos.count { !it.done }
        val doneTodos: Int get() = todos.size - openTodos

        /** 「清除已完成」只在有已完成项时显示。 */
        val canClear: Boolean get() = doneTodos > 0

        /** 群聊页入口上的计数：未完成待办 + 开放任务。 */
        val badge: Int get() = openTodos + open.size
    }

    // ---- 入口 ----

    /**
     * 群聊页是否显示「待办与任务」入口：群带 todos 或 tasks 字段时显示；两字段都缺时，远端完成 hello 握手
     * （非 LEGACY，即 0.3.10 起）也显示——上游只在首次用到后才下发这两个字段（FINDINGS 第 44 节实测：
     * 新群只有 tasks:{}、没有 todos），只按字段存在会让新群看不到空状态。LEGACY 远端（0.3.6）无字段时不显示。
     */
    fun supported(room: Json.Value.Object?, legacy: Boolean): Boolean {
        room ?: return false
        return room.members.containsKey("todos") || room.members.containsKey("tasks") || !legacy
    }

    // ---- 解码 ----

    fun board(room: Json.Value.Object?): Board {
        val todos = todos(room?.members?.get("todos"))
        val tasks = tasks(room?.members?.get("tasks"))
        return Board(
            todos = todos,
            open = tasks.filter { it.open },
            past = tasks.filterNot { it.open }.reversed(),
        )
    }

    /** rooms[].todos：数组，条目缺 id 或 text 丢弃；done 仅布尔 true 为真。 */
    fun todos(value: Json.Value?): List<Todo> {
        val items = (value as? Json.Value.Array)?.items ?: return emptyList()
        return items.mapNotNull { v ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val text = o.str("text") ?: return@mapNotNull null
            Todo(id, text, (o.members["done"] as? Json.Value.BoolValue)?.value == true, o.num("createdAt"))
        }.sortedWith(compareBy<Todo> { if (it.done) 1 else 0 }.thenBy { it.createdAt ?: 0L })
    }

    /** rooms[].tasks：taskId→任务对象；条目缺 id、goal 或 status 丢弃；按 startedAt 升序（缺省视为 0）。 */
    fun tasks(value: Json.Value?): List<Task> {
        val entries = (value as? Json.Value.Object)?.members?.values ?: return emptyList()
        return entries.mapNotNull { v ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val status = o.str("status")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val goal = o.str("goal") ?: return@mapNotNull null
            val blocked = (o.members["blocked"] as? Json.Value.Object)?.let { b ->
                Blocked(b.str("cause"), b.str("code"), b.str("status") ?: b.num("status")?.toString())
            }
            val lastNote = ((o.members["notes"] as? Json.Value.Array)?.items?.lastOrNull() as? Json.Value.Object)?.str("text")
            Task(
                id = id,
                botId = o.str("botId"),
                goal = goal,
                status = status,
                startedAt = o.num("startedAt"),
                progress = o.str("progress")?.takeIf { it.isNotEmpty() },
                result = o.str("result")?.takeIf { it.isNotEmpty() },
                reason = o.str("reason")?.takeIf { it.isNotEmpty() },
                blocked = blocked,
                lastNote = lastNote?.takeIf { it.isNotEmpty() },
            )
        }.sortedBy { it.startedAt ?: 0L }
    }

    // ---- 请求体 ----

    object Requests {
        /** 勾选/取消勾选：发明确的目标值（网页端 done: !item.done），不依赖服务端翻转。 */
        fun todoCheck(roomId: String, id: String, done: Boolean): String =
            "{\"roomId\":${JsonString.of(roomId)},\"id\":${JsonString.of(id)},\"done\":$done}"

        fun todoRemove(roomId: String, id: String): String =
            "{\"roomId\":${JsonString.of(roomId)},\"id\":${JsonString.of(id)},\"action\":\"remove\"}"

        fun todoClear(roomId: String): String = "{\"roomId\":${JsonString.of(roomId)},\"action\":\"clear\"}"

        fun taskNote(roomId: String, id: String, text: String): String =
            "{\"roomId\":${JsonString.of(roomId)},\"id\":${JsonString.of(id)},\"text\":${JsonString.of(text)}}"

        fun taskCancel(roomId: String, id: String): String =
            "{\"roomId\":${JsonString.of(roomId)},\"id\":${JsonString.of(id)}}"
    }

    /** 要发送的备注：去首尾空白，为空时不发送（网页端 text.trim() === "" 时不发）。 */
    fun noteText(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

    // ---- 文案 ----

    /** 状态标签（网页端 STATUS_KEY，未知状态原样显示）。 */
    fun statusLabel(status: String): String = when (status) {
        "active" -> I18n.tr("进行中", "In progress")
        "review" -> I18n.tr("待审", "Awaiting review")
        "blocked" -> I18n.tr("阻塞", "Blocked")
        "done" -> I18n.tr("完成", "Done")
        "cancelled" -> I18n.tr("已取消", "Cancelled")
        else -> status
    }

    /** 受阻原因（网页端 blockedLine）：只在 status 为 blocked 且带 blocked 时显示。 */
    fun blockedText(task: Task): String? {
        val b = task.blocked ?: return null
        if (task.status != "blocked") return null
        return when (b.cause) {
            "stopped" -> I18n.tr("用户停止", "Stopped by the user")
            "restarted" -> I18n.tr("重启后未更新", "Not updated after a restart")
            else -> {
                val code = (b.code ?: "") + (b.status?.let { " $it" } ?: "")
                I18n.tr("模型调用失败（$code）", "Its model call failed ($code)")
            }
        }
    }

    /** 成员名：团队名册为先，缺失时为「一个 Bot」（网页端 bot?.name ?? t("a Bot")）。 */
    fun memberName(task: Task, roster: Map<String, String>): String =
        task.botId?.let { roster[it] } ?: I18n.tr("一个 Bot", "a Bot")

    /**
     * 写操作失败文案：上游已知原因本地化，其余显示服务端原因原文（ManageApi 已按 BotManage.errorText 处理
     * 网络失败与内部错误）；远端无此端点时提示版本不支持。
     */
    fun errorText(message: String): String = when {
        message.contains("Unknown endpoint", ignoreCase = true) ->
            I18n.tr("此远端版本不支持群待办与任务", "This host does not support group to-dos and tasks")
        message == "Unknown group chat" || message == "No such group." -> I18n.tr("找不到这个群聊", "Unknown group chat")
        message == "That to-do is no longer on the list." -> I18n.tr("这条待办已不在列表里", "That to-do is no longer on the list.")
        message == "No such task." -> I18n.tr("这个任务已不存在", "No such task.")
        message == "That task is already finished." -> I18n.tr("这个任务已经结束了", "That task is already finished.")
        message == "Write a note first." -> I18n.tr("先写下备注", "Write a note first.")
        else -> message
    }

    object Copy {
        val entry get() = I18n.tr("待办与任务", "To-dos & tasks")
        fun entryLabel(board: Board) = if (board.badge > 0) "$entry ${board.badge}" else entry
        val title get() = entry
        val todosTitle get() = I18n.tr("共享待办", "Shared to-do")
        fun openCount(count: Int) = I18n.tr("$count 项未完成", "$count open")
        val clearDone get() = I18n.tr("清除已完成", "Clear completed")
        val todosEmpty get() = I18n.tr("还没有共享待办。让群里的一个 Bot 加一条。", "No shared to-dos yet. Ask a Bot in this group to add one.")
        val delete get() = I18n.tr("删除", "Delete")
        val tasksTitle get() = I18n.tr("任务", "Tasks")
        val tasksEmpty get() = I18n.tr("这个群还没有进行中的任务。", "No task is running in this group.")
        fun history(count: Int) = I18n.tr("历史（$count）", "History ($count)")
        val hideHistory get() = I18n.tr("收起历史", "Hide history")
        fun note(text: String) = I18n.tr("备注：$text", "Note: $text")
        val addNote get() = I18n.tr("补一句", "Add a note")
        val notePlaceholder get() = I18n.tr("要补什么？", "What should change?")
        val send get() = I18n.tr("发送", "Send")
        val cancel get() = I18n.tr("取消", "Cancel")
        val cancelTask get() = I18n.tr("取消任务", "Cancel task")
        fun cancelConfirm(member: String) =
            I18n.tr("取消 $member 的这项任务？取消后它不再继续这项工作。", "Cancel this task for $member? It stops working on it.")
        val keep get() = I18n.tr("不取消", "Keep it")
        val noteSentHint get() = I18n.tr("备注会以你的名义发到群里并提醒该成员。", "The note is posted in the group as you and calls the member.")
    }

    private fun Json.Value.Object.str(key: String): String? = (members[key] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.num(key: String): Long? =
        (members[key] as? Json.Value.NumberValue)?.raw?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
}
