package work.keepwriting.core

/**
 * DS-Bot 0.3.10 的版本握手（dsbot-baseline-0310，tech/dsbot-api 的 hello-capabilities）：
 * POST /api/bot {endpoint:"hello", payload:{api:[客户端支持的版本]}} 返回协商后的 api、
 * capabilities 名单与 notices 游标（boot/seq）；feed 订阅的首帧 hello 也带 api/boot/seq。
 *
 * 上游约定：api 只在旧客户端会误读应答时才升级，其余新增（端点、字段、条目/提醒/feed
 * 事件种类）一律登记在 capabilities，客户端跳过不认识的项。实测形态见
 * android/core/src/spike/FINDINGS.md 第 12 节与夹具 dsbot/hello*.json。
 */
object DsbotHello {
    /** 本客户端理解的 api 版本（与 ds-bot mobile.js 的 API_VERSION 对齐）。 */
    const val CLIENT_API = 1

    /** hello 请求的 payload：只提供客户端支持的版本，由服务端选双方共有的最高版本。 */
    val REQUEST_PAYLOAD = "{\"api\":[$CLIENT_API]}"

    /** hello 端点应答。api 为 null 表示双方没有共同版本（协商失败）。 */
    data class Hello(
        val api: Int?,
        val capabilities: Set<String>,
        val boot: String?,
        val seq: Long?,
        val pluginVersion: String?,
    )

    /** feed 首帧 hello：revision 之外，0.3.10 起附带 api 与 notices 游标。 */
    data class FeedHello(val revision: String?, val api: Int?, val boot: String?, val seq: Long?)

    /** 解码 hello 应答；没有 capabilities 数组即不是可信的握手应答，返回 null。 */
    fun decode(value: Json.Value): Hello? {
        val obj = value as? Json.Value.Object ?: return null
        val caps = obj.members["capabilities"] as? Json.Value.Array ?: return null
        val plugin = obj.members["plugin"] as? Json.Value.Object
        return Hello(
            api = intOf(obj.members["api"]),
            capabilities = caps.items.mapNotNull { (it as? Json.Value.StringValue)?.value }.toSet(),
            boot = (obj.members["boot"] as? Json.Value.StringValue)?.value,
            seq = longOf(obj.members["seq"]),
            pluginVersion = (plugin?.members?.get("version") as? Json.Value.StringValue)?.value,
        )
    }

    /** 解码 feed 的 hello 帧（0.3.6 只有 revision，其余字段为 null）；非对象返回 null。 */
    fun decodeFeedHello(dataJson: String): FeedHello? {
        val obj = Json.parse(dataJson) as? Json.Value.Object ?: return null
        val rev = obj.members["revision"]
        return FeedHello(
            revision = (rev as? Json.Value.NumberValue)?.raw ?: (rev as? Json.Value.StringValue)?.value,
            api = intOf(obj.members["api"]),
            boot = (obj.members["boot"] as? Json.Value.StringValue)?.value,
            seq = longOf(obj.members["seq"]),
        )
    }

    private fun intOf(v: Json.Value?): Int? = (v as? Json.Value.NumberValue)?.raw?.toIntOrNull()

    private fun longOf(v: Json.Value?): Long? = (v as? Json.Value.NumberValue)?.raw?.toLongOrNull()
}

/**
 * 远端能力集合：本里程碑新增功能的入口一律经 [has] 判定。
 *
 *  - DECLARED：hello 成功且协商出客户端支持的 api——只认名单里声明的能力
 *  - LEGACY：远端没有 hello（0.3.6 及更早回 bot/not-found）或读取失败——新能力全部
 *    视为不可用；0.3.6 已有的私聊/群聊/卡片/附件/Side chat 不经能力位门控，不受影响
 *  - INCOMPATIBLE：hello 可用但没有共同 api 版本——新能力不可用，并按版本不一致提示
 */
class Capabilities private constructor(
    val mode: Mode,
    val names: Set<String>,
    /** 读取时远端的 boot（服务重启即变化，App 据此在 feed 重连后重读）。 */
    val boot: String?,
) {
    enum class Mode { DECLARED, LEGACY, INCOMPATIBLE }

    /** 能力是否可用：只有 DECLARED 且名单里声明了才算。 */
    fun has(name: String): Boolean = mode == Mode.DECLARED && name in names

    /** 是否应按版本不一致显示提示条（与 update-check 的版本比对并列）。 */
    val versionMismatch: Boolean get() = mode == Mode.INCOMPATIBLE

    /**
     * feed 首帧 hello 到达时是否需要重读（连同 update-check）：boot 与读取时不同即远端
     * 已重启或换了插件；hello 不再带 boot 而当前不是 LEGACY，说明远端退回了不支持握手
     * 的版本，旧的能力集合必须作废。同一 boot 的断线重连能力不会变，不重读。
     */
    fun shouldReread(hello: DsbotHello.FeedHello): Boolean =
        if (hello.boot == null) mode != Mode.LEGACY else hello.boot != boot

    override fun equals(other: Any?): Boolean =
        other is Capabilities && other.mode == mode && other.names == names && other.boot == boot

    override fun hashCode(): Int = (mode.hashCode() * 31 + names.hashCode()) * 31 + (boot?.hashCode() ?: 0)

    override fun toString(): String = "Capabilities($mode, ${names.size} names, boot=$boot)"

    companion object {
        /** 尚未读取或远端不支持 hello。 */
        val LEGACY = Capabilities(Mode.LEGACY, emptySet(), null)

        /** 由 hello 应答的解码结果构造（App 把 DsbotApi.parseResponse 的结果交给这里）。 */
        fun from(result: DsbotApi.Result): Capabilities {
            val ok = result as? DsbotApi.Result.Ok ?: return LEGACY
            val hello = DsbotHello.decode(ok.value) ?: return LEGACY
            val api = hello.api
            return if (api == null || api > DsbotHello.CLIENT_API) {
                Capabilities(Mode.INCOMPATIBLE, emptySet(), hello.boot)
            } else {
                Capabilities(Mode.DECLARED, hello.capabilities, hello.boot)
            }
        }
    }
}
