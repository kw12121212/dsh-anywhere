package work.keepwriting.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RoomBoard 单元测试（dsbot-room-todos-tasks）：实测夹具（FINDINGS 第 44 节，错误形态与新群只有 tasks:{}）
 * + DS-Bot 0.3.10 源码核对形态（roster.js 待办条目、tasks.js 任务对象、client.js GroupTodos/TaskBoard 规则）。
 */
class RoomBoardTest {
    private fun parse(text: String): Json.Value = checkNotNull(Json.parse(text))
    private fun obj(text: String): Json.Value.Object = parse(text) as Json.Value.Object
    private fun fixture(name: String): String =
        checkNotNull(RoomBoardTest::class.java.getResource("/dsbot/$name")).readText(Charsets.UTF_8)

    @AfterTest
    fun resetLang() {
        I18n.lang = ChatBars.Lang.ZH
    }

    /** 源码核对形态：roster.js addRoomTodo / setRoomTodoDone 写出的条目。 */
    private val todosRoom = obj(
        """{"id":"r1","todos":[
          {"id":"t-c","text":"third","done":false,"createdAt":300},
          {"id":"t-a","text":"first","done":true,"createdAt":100,"doneAt":400,"doneBy":"b1"},
          {"id":"t-b","text":"second","done":false,"createdAt":200,"createdBy":"b2"},
          {"id":"t-d","text":"fourth","done":"yes","createdAt":50}
        ]}""",
    )

    /** 源码核对形态：tasks.js 任务对象（taskId→任务）。 */
    private val tasksRoom = obj(
        """{"id":"r1","tasks":{
          "t_00000003":{"id":"t_00000003","botId":"b1","goal":"g3","status":"blocked","startedAt":300,"updatedAt":310,"progress":"p3",
            "blocked":{"cause":"model","code":"AUTH","status":401,"from":"active"},"notes":[{"text":"n1","by":"user","at":1},{"text":"n2","by":"user","at":2}]},
          "t_00000001":{"id":"t_00000001","botId":"b2","goal":"g1","status":"active","startedAt":100,"updatedAt":110,"progress":""},
          "t_00000002":{"id":"t_00000002","botId":"b1","goal":"g2","status":"done","startedAt":200,"updatedAt":250,"progress":"p2","result":"r2"},
          "t_00000004":{"id":"t_00000004","botId":"gone","goal":"g4","status":"cancelled","startedAt":400,"updatedAt":450,"progress":"","reason":"why"},
          "t_00000005":{"id":"t_00000005","botId":"b2","goal":"g5","status":"review","startedAt":50,"updatedAt":60,"progress":"p5"}
        }}""",
    )

    @Test
    fun `请求体字面值与上游端点一致`() {
        val R = RoomBoard.Requests
        assertEquals("""{"roomId":"r1","id":"t-a","done":true}""", R.todoCheck("r1", "t-a", true))
        assertEquals("""{"roomId":"r1","id":"t-a","done":false}""", R.todoCheck("r1", "t-a", false))
        assertEquals("""{"roomId":"r1","id":"t-a","action":"remove"}""", R.todoRemove("r1", "t-a"))
        assertEquals("""{"roomId":"r1","action":"clear"}""", R.todoClear("r1"))
        assertEquals("""{"roomId":"r1","id":"t_1","text":"say \"hi\""}""", R.taskNote("r1", "t_1", "say \"hi\""))
        assertEquals("""{"roomId":"r1","id":"t_1"}""", R.taskCancel("r1", "t_1"))
    }

    @Test
    fun `待办未完成在前再按创建时间且 done 仅布尔 true 为真`() {
        val board = RoomBoard.board(todosRoom)
        assertEquals(listOf("t-d", "t-b", "t-c", "t-a"), board.todos.map { it.id })
        assertFalse(board.todos.first { it.id == "t-d" }.done, "done 非布尔视为未完成")
        assertEquals(3, board.openTodos)
        assertEquals(1, board.doneTodos)
        assertTrue(board.canClear)
    }

    @Test
    fun `待办畸形条目丢弃且非数组降级为空`() {
        val board = RoomBoard.board(obj("""{"todos":[{"text":"no id"},{"id":"x"},3,null,{"id":"","text":"empty id"},{"id":"ok","text":"t"}]}"""))
        assertEquals(listOf("ok"), board.todos.map { it.id })
        assertTrue(RoomBoard.board(obj("""{"todos":{"a":1}}""")).todos.isEmpty())
        assertTrue(RoomBoard.board(null).todos.isEmpty())
    }

    @Test
    fun `没有已完成待办时不提供清除已完成`() {
        val board = RoomBoard.board(obj("""{"todos":[{"id":"a","text":"t","done":false,"createdAt":1}]}"""))
        assertFalse(board.canClear)
        assertEquals(1, board.badge)
    }

    @Test
    fun `任务按 startedAt 划分开放与历史且历史倒序`() {
        val board = RoomBoard.board(tasksRoom)
        assertEquals(listOf("t_00000005", "t_00000001", "t_00000003"), board.open.map { it.id })
        assertEquals(listOf("t_00000004", "t_00000002"), board.past.map { it.id })
        assertTrue(board.open.all { it.open })
        assertTrue(board.past.none { it.open })
        assertEquals(3, board.badge)
    }

    @Test
    fun `任务字段解码只取最近一条备注且空串视为缺失`() {
        val board = RoomBoard.board(tasksRoom)
        val blocked = board.open.first { it.id == "t_00000003" }
        assertEquals("n2", blocked.lastNote)
        assertEquals("p3", blocked.progress)
        assertEquals(RoomBoard.Blocked("model", "AUTH", "401"), blocked.blocked)
        val active = board.open.first { it.id == "t_00000001" }
        assertNull(active.progress)
        assertNull(active.lastNote)
        assertEquals("r2", board.past.first { it.id == "t_00000002" }.result)
        assertEquals("why", board.past.first { it.id == "t_00000004" }.reason)
    }

    @Test
    fun `任务畸形条目丢弃且非对象降级为空`() {
        val board = RoomBoard.board(
            obj("""{"tasks":{"a":{"goal":"no id","status":"active"},"b":{"id":"b","goal":"g"},"c":{"id":"c","status":"active"},"d":"x","e":{"id":"e","goal":"g","status":"active","startedAt":"soon"}}}"""),
        )
        assertEquals(listOf("e"), board.open.map { it.id })
        assertNull(board.open.single().startedAt)
        assertTrue(RoomBoard.board(obj("""{"tasks":[{"id":"x","goal":"g","status":"active"}]}""")).open.isEmpty())
    }

    @Test
    fun `未知状态进历史并原样显示`() {
        val board = RoomBoard.board(obj("""{"tasks":{"x":{"id":"x","goal":"g","status":"paused","startedAt":1}}}"""))
        assertEquals(listOf("x"), board.past.map { it.id })
        assertEquals("paused", RoomBoard.statusLabel("paused"))
    }

    @Test
    fun `状态文案中英与网页端一致`() {
        assertEquals(listOf("进行中", "待审", "阻塞", "完成", "已取消"), listOf("active", "review", "blocked", "done", "cancelled").map(RoomBoard::statusLabel))
        I18n.lang = ChatBars.Lang.EN
        assertEquals(listOf("In progress", "Awaiting review", "Blocked", "Done", "Cancelled"), listOf("active", "review", "blocked", "done", "cancelled").map(RoomBoard::statusLabel))
    }

    @Test
    fun `受阻原因按 cause 给文案且只在阻塞状态显示`() {
        fun task(status: String, blocked: RoomBoard.Blocked?) = RoomBoard.Task("x", "b", "g", status, 1, null, null, null, blocked, null)
        assertEquals("用户停止", RoomBoard.blockedText(task("blocked", RoomBoard.Blocked("stopped", null, null))))
        assertEquals("重启后未更新", RoomBoard.blockedText(task("blocked", RoomBoard.Blocked("restarted", null, null))))
        assertEquals("模型调用失败（AUTH 401）", RoomBoard.blockedText(task("blocked", RoomBoard.Blocked("model", "AUTH", "401"))))
        assertEquals("模型调用失败（QUOTA）", RoomBoard.blockedText(task("blocked", RoomBoard.Blocked(null, "QUOTA", null))))
        assertNull(RoomBoard.blockedText(task("active", RoomBoard.Blocked("stopped", null, null))))
        assertNull(RoomBoard.blockedText(task("blocked", null)))
        I18n.lang = ChatBars.Lang.EN
        assertEquals("Its model call failed (AUTH 401)", RoomBoard.blockedText(task("blocked", RoomBoard.Blocked("model", "AUTH", "401"))))
        assertEquals("Stopped by the user", RoomBoard.blockedText(task("blocked", RoomBoard.Blocked("stopped", null, null))))
    }

    @Test
    fun `成员名名册为先缺失为一个 Bot`() {
        val board = RoomBoard.board(tasksRoom)
        val roster = mapOf("b1" to "Alpha", "b2" to "Beta")
        assertEquals("Alpha", RoomBoard.memberName(board.open.first { it.id == "t_00000003" }, roster))
        assertEquals("一个 Bot", RoomBoard.memberName(board.past.first { it.id == "t_00000004" }, roster))
    }

    @Test
    fun `备注去首尾空白为空时不发送`() {
        assertNull(RoomBoard.noteText("   \n "))
        assertEquals("go on", RoomBoard.noteText("  go on \n"))
    }

    @Test
    fun `入口按字段存在或 hello 握手显示`() {
        val empty = obj(fixture("rooms-board-empty.json"))
        assertTrue(RoomBoard.supported(empty, legacy = true), "实测新群带 tasks:{} 即显示")
        assertTrue(RoomBoard.supported(obj("""{"id":"r","todos":[]}"""), legacy = true))
        assertTrue(RoomBoard.supported(obj("""{"id":"r"}"""), legacy = false), "0.3.10 新群无字段时按 hello 握手显示")
        assertFalse(RoomBoard.supported(obj("""{"id":"r"}"""), legacy = true), "0.3.6 无字段不显示")
        assertFalse(RoomBoard.supported(null, legacy = false))
    }

    @Test
    fun `实测新群夹具解码为空看板`() {
        val board = RoomBoard.board(obj(fixture("rooms-board-empty.json")))
        assertTrue(board.todos.isEmpty() && board.open.isEmpty() && board.past.isEmpty())
        assertEquals(0, board.badge)
        assertEquals("待办与任务", RoomBoard.Copy.entryLabel(board))
        assertEquals("待办与任务 3", RoomBoard.Copy.entryLabel(RoomBoard.board(tasksRoom)))
    }

    @Test
    fun `实测错误应答解码为登记的错误码并本地化原因`() {
        val cases = mapOf(
            "room-todo-unknown-room.json" to ("room/not-found" to "找不到这个群聊"),
            "room-todo-refused.json" to ("bot/refused" to "这条待办已不在列表里"),
            "task-cancel-refused.json" to ("bot/refused" to "这个任务已不存在"),
            "task-note-unknown-room.json" to ("bot/refused" to "找不到这个群聊"),
        )
        for ((name, expected) in cases) {
            val err = DsbotApi.parseResponse(200, fixture(name)) as DsbotApi.Result.BotError
            assertEquals(expected.first, err.code, name)
            assertEquals(expected.second, RoomBoard.errorText(err.message), name)
        }
    }

    @Test
    fun `实测 clear 空操作应答 removed 为数量`() {
        val ok = DsbotApi.parseResponse(200, fixture("room-todo-clear-empty.json")) as DsbotApi.Result.Ok
        val v = ok.value as Json.Value.Object
        assertEquals("0", (v.members["removed"] as Json.Value.NumberValue).raw)
        assertTrue(RoomBoard.todos(v.members["todos"]).isEmpty())
    }

    @Test
    fun `失败文案已知原因本地化其余原样`() {
        assertEquals("此远端版本不支持群待办与任务", RoomBoard.errorText("Unknown endpoint room-todo"))
        assertEquals("这个任务已经结束了", RoomBoard.errorText("That task is already finished."))
        assertEquals("先写下备注", RoomBoard.errorText("Write a note first."))
        assertEquals("网络错误，请稍后重试", RoomBoard.errorText("网络错误，请稍后重试"))
        I18n.lang = ChatBars.Lang.EN
        assertEquals("Unknown group chat", RoomBoard.errorText("No such group."))
    }

    @Test
    fun `页面文案中英切换`() {
        assertEquals("2 项未完成", RoomBoard.Copy.openCount(2))
        assertEquals("历史（3）", RoomBoard.Copy.history(3))
        assertEquals("备注：x", RoomBoard.Copy.note("x"))
        I18n.lang = ChatBars.Lang.EN
        assertEquals("2 open", RoomBoard.Copy.openCount(2))
        assertEquals("History (3)", RoomBoard.Copy.history(3))
        assertEquals("To-dos & tasks", RoomBoard.Copy.entry)
        assertEquals("No shared to-dos yet. Ask a Bot in this group to add one.", RoomBoard.Copy.todosEmpty)
    }
}
