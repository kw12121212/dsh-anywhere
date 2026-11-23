package work.keepwriting.core

/**
 * 工作文件夹（dsbot-folders）：folders / folder-list 应答解码、隐藏项过滤、完整路径校验、列表请求代号与写端点请求体。
 * 端点形态见 tech/dsbot-api 的 folder-endpoints-0310（folders 与 folder-list 实测；写端点按 ds-bot 0.3.10
 * folders.js / roster.js 源码核对）。手机端不调用 folder-pick（它在电脑桌面弹对话框）。
 */
object WorkFolders {
    data class Folder(val path: String, val title: String)

    /** folders 应答：团队工作区、宿主的选择方式（native/browse/null）与最近用过的文件夹。 */
    data class Overview(val team: String?, val picker: String?, val folders: List<Folder>) {
        /** 宿主支持 folder-list 逐级浏览。 */
        val canBrowse: Boolean get() = picker == "browse"
    }

    data class Entry(val name: String, val path: String, val hidden: Boolean)

    data class Listing(val path: String, val home: String?, val crumbs: List<Entry>, val entries: List<Entry>, val truncated: Boolean)

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.bool(k: String) = (members[k] as? Json.Value.BoolValue)?.value == true
    private fun Json.Value.Object.arr(k: String) = (members[k] as? Json.Value.Array)?.items.orEmpty()

    private fun entries(list: List<Json.Value>): List<Entry> = list.mapNotNull { e ->
        val o = e as? Json.Value.Object ?: return@mapNotNull null
        val path = o.str("path") ?: return@mapNotNull null
        Entry(o.str("name") ?: path.trimEnd('/').substringAfterLast('/').ifEmpty { path }, path, o.bool("hidden"))
    }

    fun decodeOverview(value: Json.Value?): Overview {
        val o = value as? Json.Value.Object ?: return Overview(null, null, emptyList())
        val folders = o.arr("folders").mapNotNull { f ->
            val fo = f as? Json.Value.Object ?: return@mapNotNull null
            val path = fo.str("path") ?: return@mapNotNull null
            Folder(path, fo.str("title") ?: path)
        }
        return Overview(o.str("team"), o.str("picker")?.takeIf { it == "native" || it == "browse" }, folders)
    }

    /** folder-list 应答；缺 path 时为 null（调用方按读取失败处理）。 */
    fun decodeListing(value: Json.Value?): Listing? {
        val o = value as? Json.Value.Object ?: return null
        val path = o.str("path") ?: return null
        return Listing(path, o.str("home"), entries(o.arr("crumbs")), entries(o.arr("entries")), o.bool("truncated"))
    }

    /** 列表中要显示的子文件夹：默认隐藏以 hidden 标记的项。 */
    fun visible(listing: Listing, showHidden: Boolean): List<Entry> =
        if (showHidden) listing.entries else listing.entries.filter { !it.hidden }

    /** 去掉首尾空白与成对引号（上游先 unquote：Windows「复制为路径」带双引号）。 */
    fun unquote(input: String): String {
        val t = input.trim()
        return if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '\'' && t.last() == '\''))) t.substring(1, t.length - 1).trim() else t
    }

    /**
     * 手输路径的本地校验：只接受完整路径——/…、\…（含 UNC \\server\share）、~ 或 ~/…、盘符 X:\ 或 X:/，
     * 覆盖上游 cleanWorkDir（unquote 后 expandHome 再 isAbsolute）在 POSIX 与 Windows 宿主上接受的形态。
     */
    fun isFullPath(input: String): Boolean {
        val t = unquote(input)
        if (t.isEmpty()) return false
        if (t.startsWith("/") || t.startsWith("\\") || t == "~" || t.startsWith("~/") || t.startsWith("~\\")) return true
        return t.length >= 3 && t[0].isLetter() && t[1] == ':' && (t[2] == '\\' || t[2] == '/')
    }

    fun pathProblemText(): String =
        I18n.tr("请输入完整路径（以 /、~ 或盘符如 C:\\ 开头）", "Enter the full path (starting with /, ~ or a drive such as C:\\)")

    /**
     * 列表请求代号：每次发起浏览取新代号，只接受最新代号的应答——连续点进多级时，先发的慢应答不会覆盖后来的位置。
     * 只在主线程使用。
     */
    class ListingGate {
        private var latest = 0L
        fun next(): Long = ++latest
        fun accept(ticket: Long): Boolean = ticket == latest
    }

    object Requests {
        fun list(path: String?): String = if (path.isNullOrEmpty()) "{}" else "{\"path\":" + JsonString.of(path) + "}"

        fun teamWorkspace(path: String): String = "{\"path\":" + JsonString.of(path.trim()) + "}"

        /** Bot 的文件夹：空串表示改回团队工作区。 */
        fun botFolder(id: String, path: String): String = "{\"id\":" + JsonString.of(id) + ",\"workDir\":" + JsonString.of(path.trim()) + "}"

        /** 群的文件夹：空串表示各 Bot 用自己的文件夹。 */
        fun roomFolder(id: String, path: String): String = "{\"id\":" + JsonString.of(id) + ",\"workDir\":" + JsonString.of(path.trim()) + "}"
    }

    /** team-workspace 应答 {team} 的实际路径。 */
    fun teamOf(value: Json.Value?): String? = ((value as? Json.Value.Object)?.members?.get("team") as? Json.Value.StringValue)?.value

    /** 群的工作文件夹（state rooms[].workDir）；没有群文件夹时为 null（各 Bot 用自己的文件夹）。 */
    fun roomFolder(state: Json.Value.Object?, roomId: String): String? =
        state?.arr("rooms")?.mapNotNull { it as? Json.Value.Object }?.firstOrNull { it.str("id") == roomId }?.str("workDir")

    /** 群名（群详情页标题）。 */
    fun roomName(state: Json.Value.Object?, roomId: String): String? =
        state?.arr("rooms")?.mapNotNull { it as? Json.Value.Object }?.firstOrNull { it.str("id") == roomId }?.str("name")
}
