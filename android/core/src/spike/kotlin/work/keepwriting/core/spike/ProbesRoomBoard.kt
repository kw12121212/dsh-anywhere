package work.keepwriting.core.spike

import work.keepwriting.core.Json
import work.keepwriting.core.JsonString

/**
 * 群共享待办与任务看板探针组（dsbot-room-todos-tasks）：
 * 只读登记 state.rooms[] 中 todos（数组）与 tasks（taskId→任务对象）的键集、计数、类型与 status 分布，
 * 不打印群名、待办正文、任务目标、进度、结果与备注；写端点只取在 rt.save() 之前就返回的错误形态：
 * room-todo 未知群（room/not-found）、真实群上不存在的待办 id（勾选与 action:remove）、
 * task-note / task-cancel 不存在的任务 id（仅当群已带 tasks 字段：上游 tasksOf 的 ??= 会懒创建）与未知群（bot/refused）；action:clear 只在目标群没有已完成待办时探测
 * （removed 为空时上游不落盘）。严格遵守 spike-write-probes-non-mutating：不对真实待办或任务做成功的写操作——
 * 成功的 task-note 会以用户身份在群里发消息并唤起成员。
 */
internal class ProbesRoomBoard(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    fun run(stateVal: Json.Value?) {
        record("todo-unknown-room", "room-todo", """{"roomId":"spike-none","id":"spike-none","done":true}""")
        record("note-unknown-room", "task-note", """{"roomId":"spike-none","id":"spike-none","text":"spike"}""")
        record("cancel-unknown-room", "task-cancel", """{"roomId":"spike-none","id":"spike-none"}""")
        val rooms = roomsOf(stateVal)
        if (rooms.isEmpty()) {
            info("rb-rooms", "state.rooms 为空，跳过真实群探针（按源码核对形态）")
            return
        }
        inspect(rooms)
        val target = rooms.first()
        val roomId = JsonString.of((target.members["id"] as? Json.Value.StringValue)?.value ?: return)
        record("todo-check-unknown", "room-todo", """{"roomId":$roomId,"id":"spike-none","done":true}""")
        record("todo-remove-unknown", "room-todo", """{"roomId":$roomId,"id":"spike-none","action":"remove"}""")
        // 上游 taskNote/taskCancel 在报 No such task. 之前先执行 tasksOf(room)（room.tasks ??= {}）：群没有 tasks 字段时
        // 探针会在内存里补上 tasks:{}，下次 rt.save() 即落盘——只在字段已存在时探测，避免改写真实群
        if (target.members.containsKey("tasks")) {
            record("note-unknown-task", "task-note", """{"roomId":$roomId,"id":"spike-none","text":"spike"}""")
            record("note-blank-unknown-task", "task-note", """{"roomId":$roomId,"id":"spike-none","text":"   "}""")
            record("cancel-unknown-task", "task-cancel", """{"roomId":$roomId,"id":"spike-none"}""")
        } else {
            info("rb-task-probes", "目标群没有 tasks 字段，跳过 task-note/task-cancel 探针（上游 tasksOf 的 ??= 会懒创建并落盘）")
        }
        // clear：只在目标群没有已完成待办时探测（removed 为空，上游不调用 rt.save()）
        val done = todosOf(target).count { (it.members["done"] as? Json.Value.BoolValue)?.value == true }
        if (done == 0) {
            record("todo-clear-none-done", "room-todo", """{"roomId":$roomId,"action":"clear"}""", allowOk = true)
        } else {
            info("rb-todo-clear", "目标群有 $done 条已完成待办，跳过 clear 以免改写真实数据")
        }
        // 有数据时把带 todos/tasks 的群（只保留这两个字段）经 Sanitize.json 写出供夹具使用
        val sample = rooms.firstOrNull { it.members.containsKey("todos") || it.members.containsKey("tasks") }
        if (sample != null) {
            val picked = Json.Value.Object(
                sample.members.filterKeys { it == "id" || it == "todos" || it == "tasks" }
                    .mapValues { (k, v) -> if (k == "tasks") redactTaskReasons(v) else v },
            )
            dump("rooms-board", Sanitize.json(Json.encode(picked)))
        }
    }

    /**
     * 任务的 reason 是 Bot 写的取消原因（用户内容），但 reason 在别的端点是服务端系统说明（如 deepseek-services 的
     * speech.reason），不能加进 Sanitize 的全局内容键；只在导出任务看板时单独替换为占位符。
     */
    private fun redactTaskReasons(tasks: Json.Value): Json.Value {
        val o = tasks as? Json.Value.Object ?: return tasks
        return Json.Value.Object(
            o.members.mapValues { (_, t) ->
                val task = t as? Json.Value.Object ?: return@mapValues t
                val reason = task.members["reason"] as? Json.Value.StringValue ?: return@mapValues t
                Json.Value.Object(task.members + ("reason" to Json.Value.StringValue("<redacted:content ${reason.value.length} chars>")))
            },
        )
    }

    private fun roomsOf(stateVal: Json.Value?): List<Json.Value.Object> =
        ((stateVal as? Json.Value.Object)?.members?.get("rooms") as? Json.Value.Array)?.items.orEmpty().mapNotNull { it as? Json.Value.Object }

    private fun todosOf(room: Json.Value.Object): List<Json.Value.Object> =
        (room.members["todos"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { it as? Json.Value.Object }

    private fun inspect(rooms: List<Json.Value.Object>) {
        val withTodos = rooms.count { it.members.containsKey("todos") }
        val withTasks = rooms.count { it.members.containsKey("tasks") }
        val todos = rooms.flatMap { todosOf(it) }
        val tasksFields = rooms.mapNotNull { it.members["tasks"] }
        val tasks = tasksFields.flatMap { (it as? Json.Value.Object)?.members?.values.orEmpty() }.mapNotNull { it as? Json.Value.Object }
        val statuses = tasks.groupingBy { (it.members["status"] as? Json.Value.StringValue)?.value ?: "?" }.eachCount()
        fun keySets(items: List<Json.Value.Object>) = items.map { it.members.keys.sorted().joinToString(",") }.toSet()
        pass(
            "rb-state",
            "群=${rooms.size} 带todos=$withTodos 带tasks=$withTasks todos类型=${rooms.mapNotNull { it.members["todos"] }.map { typeOf(it) }.toSet()} " +
                "tasks类型=${tasksFields.map { typeOf(it) }.toSet()} 待办=${todos.size}（已完成 ${todos.count { (it.members["done"] as? Json.Value.BoolValue)?.value == true }}） " +
                "待办键集=${keySets(todos)} 任务=${tasks.size} 任务键集=${keySets(tasks)} status分布=$statuses " +
                "blocked键集=${keySets(tasks.mapNotNull { it.members["blocked"] as? Json.Value.Object })} " +
                "notes元素键集=${keySets(tasks.flatMap { (it.members["notes"] as? Json.Value.Array)?.items.orEmpty() }.mapNotNull { it as? Json.Value.Object })}",
        )
    }

    private fun typeOf(value: Json.Value?): String = when (value) {
        null -> "absent"
        is Json.Value.NullValue -> "null"
        is Json.Value.BoolValue -> "bool"
        is Json.Value.StringValue -> "string"
        is Json.Value.NumberValue -> "number"
        is Json.Value.Array -> "array"
        is Json.Value.Object -> "object"
    }

    /** 记录应答形态（只打印键集与错误码/消息，不打印 value 内容）。 */
    private fun record(label: String, endpoint: String, payload: String, allowOk: Boolean = false) {
        val res = try {
            post(endpoint, payload)
        } catch (e: Exception) {
            fail("rb-$label", "请求异常 ${e.javaClass.simpleName}")
            return
        }
        dumpJson("rb-$label", res)
        val root = Json.parse(res.bodyText()) as? Json.Value.Object
        val err = root?.members?.get("error") as? Json.Value.Object
        val value = root?.members?.get("value")
        val ok = (root?.members?.get("ok") as? Json.Value.BoolValue)?.value
        val shape = (value as? Json.Value.Object)?.let { o -> o.members.mapValues { typeOf(it.value) }.toSortedMap().toString() } ?: typeOf(value)
        val line = "POST $endpoint → HTTP ${res.status} ok=$ok value=$shape " +
            "error.code=${(err?.members?.get("code") as? Json.Value.StringValue)?.value} " +
            "message=${(err?.members?.get("message") as? Json.Value.StringValue)?.value}"
        // 除 clear（无已完成项时为空操作）外这些探针都必须被拒；ok=true 说明上游行为变了，标 FAIL 以便人工核查
        if (ok == true && !allowOk) fail("rb-$label", "意外成功（可能已改写真实数据，需人工核查）：$line") else info("rb-$label", line)
    }
}
