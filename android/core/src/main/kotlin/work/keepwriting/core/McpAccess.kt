package work.keepwriting.core

/**
 * MCP 访问（dsbot-mcp-access）：
 * 解码 state 中的 live mcp 视图（与 mcp-set / mcp-client-add / mcp-client-update / mcp-client-remove 返回的同形视图），
 * 派生状态说明、校验端口与名称、合并客户端可访问范围的名称，并构造 mcp-* 请求体；
 * mcp-client-add 应答中的一次性令牌与视图分离，令牌类型的 toString 脱敏。
 * 复刻 DS-Bot 0.3.10 src/host/mcp-server.js（mcpView/mcpEndpoint/scope）与 client.js src/client/mcp-page.js
 * （OFF/statusText/McpPage/ScopePicker）（tech/dsbot-api 的 mcp-access-endpoints-0310）。所有解码对缺失与类型不符容错，不抛异常。
 */
object McpAccess {
    /** mcp-server.js DEFAULT_PORT。 */
    const val DEFAULT_PORT = 8797
    const val PORT_MIN = 1024
    const val PORT_MAX = 65535

    /** 客户端名称上限（mcp-server.js：trim 后 slice(0, 60)）。 */
    const val NAME_MAX = 60

    /** 令牌前缀（mcp-server.js：dsb_ + base64url）。 */
    const val TOKEN_PREFIX = "dsb_"

    data class Client(
        val id: String,
        val name: String,
        val bots: List<String>,
        val rooms: List<String>,
        val createdAt: Long?,
        val lastUsedAt: Long?,
    )

    data class View(
        val on: Boolean,
        val port: Int,
        /** 服务端 status：只有 "on" 表示已在监听。 */
        val listening: Boolean,
        val error: String?,
        val url: String,
        val clients: List<Client>,
    )

    /** 状态说明（client.js statusText：error 优先，其次关闭，再次已开启，其余为连接中）。 */
    sealed interface Status {
        data class Error(val message: String) : Status
        data object Off : Status
        data class On(val url: String) : Status
        data object Connecting : Status
    }

    /** 一次性令牌：只在内存中保存，toString 不输出明文（防止日志、异常与诊断意外打印）。 */
    class Token(val value: String) {
        override fun toString(): String = "Token(***)"
        override fun equals(other: Any?): Boolean = other is Token && other.value == value
        override fun hashCode(): Int = value.hashCode()
    }

    /** mcp-client-add 的应答：新视图与一次性令牌（令牌缺失时为 null）。 */
    data class Created(val view: View, val token: Token?)

    /** 可选范围候选（非私有 Bot 或群）。 */
    data class Candidate(val id: String, val name: String)

    data class Scope(val bots: List<Candidate>, val rooms: List<Candidate>)

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.long(k: String) = (members[k] as? Json.Value.NumberValue)?.raw?.toDoubleOrNull()?.toLong()
    private fun Json.Value.Object.ids(k: String): List<String> =
        ((members[k] as? Json.Value.Array)?.items ?: emptyList())
            .mapNotNull { (it as? Json.Value.StringValue)?.value?.takeIf { s -> s.isNotEmpty() } }
            .distinct()

    /** 远端 state 是否含 MCP 访问视图（团队页入口门控，设计决策 data-presence-gating）。 */
    fun has(state: Json.Value.Object?): Boolean = state?.members?.get("mcp") is Json.Value.Object

    /** 从 state 解码 mcp；不含该字段返回 null。 */
    fun view(state: Json.Value.Object?): View? = (state?.members?.get("mcp") as? Json.Value.Object)?.let(::decode)

    /** 解码 mcp 视图；缺失字段按 client.js OFF 常量的缺省值。 */
    fun decode(raw: Json.Value.Object): View {
        val port = (raw.members["port"] as? Json.Value.NumberValue)?.raw?.toIntOrNull()?.takeIf { it in 1..PORT_MAX } ?: DEFAULT_PORT
        val clients = ((raw.members["clients"] as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { v ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Client(
                id = id,
                name = o.str("name")?.takeIf { it.isNotBlank() } ?: id,
                bots = o.ids("bots"),
                rooms = o.ids("rooms"),
                createdAt = o.long("createdAt"),
                lastUsedAt = o.long("lastUsedAt"),
            )
        }
        return View(
            on = (raw.members["on"] as? Json.Value.BoolValue)?.value == true,
            port = port,
            listening = raw.str("status") == "on",
            error = raw.str("error")?.takeIf { it.isNotBlank() },
            url = raw.str("url")?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:$port/mcp",
            clients = clients,
        )
    }

    /** 写端点返回值若为 mcp 视图则解码，否则 null。 */
    fun decodeValue(value: Json.Value?): View? =
        (value as? Json.Value.Object)?.takeIf { it.members.containsKey("on") && it.members.containsKey("clients") }?.let(::decode)

    /** mcp-client-add 应答：视图 + 一次性令牌（只接受 dsb_ 前缀的字符串）；不是视图时返回 null。 */
    fun decodeCreated(value: Json.Value?): Created? {
        val view = decodeValue(value) ?: return null
        val token = ((value as Json.Value.Object).members["token"] as? Json.Value.StringValue)?.value
            ?.takeIf { it.startsWith(TOKEN_PREFIX) && it.length > TOKEN_PREFIX.length }
        return Created(view, token?.let(::Token))
    }

    fun status(view: View): Status = when {
        view.error != null -> Status.Error(view.error)
        !view.on -> Status.Off
        view.listening -> Status.On(view.url)
        else -> Status.Connecting
    }

    /** 端口输入校验：1024–65535 的整数（mcp-server.js mcp-set）；不合法返回 null。 */
    fun parsePort(input: String): Int? {
        val t = input.trim()
        if (t.isEmpty() || t.length > 5 || !t.all { it in '0'..'9' }) return null
        return t.toInt().takeIf { it in PORT_MIN..PORT_MAX }
    }

    /** 端口可保存：合法且与当前端口不同（client.js：未改动时 Save 禁用）。 */
    fun canSavePort(input: String, view: View): Boolean = parsePort(input)?.let { it != view.port } == true

    /**
     * 名称规整：去首尾空白、截断到 60 个 UTF-16 单元（与服务端 slice(0, 60) 同一计量，服务端不会再截），
     * 截断处落在代理对中间时少取一个单元，不产生半个字符；空返回 null。
     */
    fun normalizeName(input: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (t.length <= NAME_MAX) return t
        val end = if (Character.isHighSurrogate(t[NAME_MAX - 1])) NAME_MAX - 1 else NAME_MAX
        return t.substring(0, end).trim().takeIf { it.isNotEmpty() }
    }

    /** 范围候选：非私有 Bot（mcp-server.js scope 过滤私有）与全部群，保持服务端顺序。 */
    fun scope(state: Json.Value.Object?): Scope {
        val bots = GroupManage.bots(state).filter { !it.isPrivate }.map { Candidate(it.id, it.name) }
        val rooms = ((state?.members?.get("rooms") as? Json.Value.Array)?.items ?: emptyList()).mapNotNull { v ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Candidate(id, o.str("name")?.takeIf { it.isNotBlank() } ?: id)
        }
        return Scope(bots, rooms)
    }

    /** 客户端可访问对象的名称（client.js names：先 Bot 后群，候选中找不到的略去）。 */
    fun reachable(client: Client, scope: Scope): List<String> =
        client.bots.mapNotNull { id -> scope.bots.firstOrNull { it.id == id }?.name } +
            client.rooms.mapNotNull { id -> scope.rooms.firstOrNull { it.id == id }?.name }

    /** 勾选切换后的新列表（client.js ScopePicker.toggle：已含则移除，否则追加到末尾）。 */
    fun toggle(current: List<String>, id: String): List<String> =
        if (id in current) current.filter { it != id } else current + id

    /** 请求体（DS-Bot 0.3.10 mcp-server.js mcpEndpoint 契约）。 */
    object Requests {
        private fun q(s: String) = JsonString.of(s)
        private fun list(ids: List<String>) = ids.joinToString(",", "[", "]") { q(it) }

        fun setOn(on: Boolean): String = "{\"on\":$on}"

        fun setPort(port: Int): String = "{\"port\":$port}"

        /** 只发名称：范围由服务端缺省（非私有 Main Bot，设计决策 create-name-only）。 */
        fun add(name: String): String = "{\"name\":${q(name)}}"

        fun rename(id: String, name: String): String = "{\"id\":${q(id)},\"name\":${q(name)}}"

        fun setBots(id: String, bots: List<String>): String = "{\"id\":${q(id)},\"bots\":${list(bots)}}"

        fun setRooms(id: String, rooms: List<String>): String = "{\"id\":${q(id)},\"rooms\":${list(rooms)}}"

        fun remove(id: String): String = "{\"id\":${q(id)}}"
    }

    const val SET = "mcp-set"
    const val CLIENT_ADD = "mcp-client-add"
    const val CLIENT_UPDATE = "mcp-client-update"
    const val CLIENT_REMOVE = "mcp-client-remove"
}
