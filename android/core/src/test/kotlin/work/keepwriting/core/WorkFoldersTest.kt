package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-folders：folders/folder-list 真实夹具解码、隐藏过滤、完整路径校验、列表代号与请求体。 */
class WorkFoldersTest {
    private fun text(name: String) = checkNotNull(WorkFoldersTest::class.java.getResource("/dsbot/$name")).readText(Charsets.UTF_8)
    private fun value(name: String): Json.Value = (Json.parse(text(name)) as Json.Value.Object).members["value"]!!
    private fun obj(t: String) = Json.parse(t) as Json.Value.Object

    @Test
    fun overviewFixture() {
        val o = WorkFolders.decodeOverview(value("folders.json"))
        assertEquals("/home/user/.dsh/ds-bot/workspace", o.team)
        assertEquals("browse", o.picker)
        assertTrue(o.canBrowse)
        assertEquals(listOf("alpha", "notes"), o.folders.map { it.title })
        // native 不支持手机浏览；未知取值视为 null
        assertFalse(WorkFolders.decodeOverview(obj("""{"team":"/t","picker":"native","folders":[]}""")).canBrowse)
        assertNull(WorkFolders.decodeOverview(obj("""{"picker":"weird","folders":[{"title":"缺路径"},{"path":"/p"}]}""")).picker)
        assertEquals(listOf(WorkFolders.Folder("/p", "/p")), WorkFolders.decodeOverview(obj("""{"folders":[{"title":"缺路径"},{"path":"/p"}]}""")).folders)
        assertNull(WorkFolders.decodeOverview(null).team)
    }

    @Test
    fun listingFixtureAndError() {
        val l = assertNotNull(WorkFolders.decodeListing(value("folder-list-usr.json")))
        assertEquals("/usr", l.path)
        assertEquals("/home/user", l.home)
        assertEquals(listOf("/", "/usr"), l.crumbs.map { it.path })
        assertEquals(listOf("bin", "games", "include"), l.entries.map { it.name })
        assertFalse(l.truncated)
        // 不存在的路径：bot/refused，message 带具体原因
        val err = assertIs<DsbotApi.Result.BotError>(DsbotApi.parseResponse(200, text("folder-list-missing.json")))
        assertEquals("bot/refused", err.code)
        assertTrue(BotManage.errorText(err.code, err.message).contains("ENOENT"))
        assertNull(WorkFolders.decodeListing(obj("""{"entries":[]}""")))
    }

    @Test
    fun hiddenFilterAndNameFallback() {
        val l = WorkFolders.decodeListing(
            obj("""{"path":"/h","entries":[{"name":".cache","path":"/h/.cache","hidden":true},{"path":"/h/work/"},{"name":"x"}],"truncated":true}"""),
        )!!
        assertTrue(l.truncated)
        assertEquals(listOf("work"), WorkFolders.visible(l, showHidden = false).map { it.name })
        assertEquals(listOf(".cache", "work"), WorkFolders.visible(l, showHidden = true).map { it.name })
    }

    @Test
    fun fullPathRule() {
        for (ok in listOf("/", "/home/user", " ~/notes ", "~", "C:\\Users\\a", "d:/work", "\"C:\\Users\\x\"", "'/srv/a b'", "\\\\server\\share", "\\foo")) assertTrue(WorkFolders.isFullPath(ok), ok)
        for (bad in listOf("", "  ", "projects/alpha", "./x", "~user", "C:", "1:/x", "\"\"", "\"rel/path\"")) assertFalse(WorkFolders.isFullPath(bad), bad)
        assertEquals("C:\\Users\\x", WorkFolders.unquote(" \"C:\\Users\\x\" "))
        assertEquals("\"half", WorkFolders.unquote("\"half"))
        assertTrue(WorkFolders.pathProblemText().isNotEmpty())
    }

    @Test
    fun listingGateDropsStaleAnswers() {
        val gate = WorkFolders.ListingGate()
        val first = gate.next()
        val second = gate.next()
        assertFalse(gate.accept(first))
        assertTrue(gate.accept(second))
    }

    @Test
    fun requestBodies() {
        assertEquals("{}", WorkFolders.Requests.list(null))
        assertEquals("{}", WorkFolders.Requests.list(""))
        assertEquals("""{"path":"/usr"}""", WorkFolders.Requests.list("/usr"))
        assertEquals("""{"path":"/home/user/新 文件夹"}""", WorkFolders.Requests.teamWorkspace(" /home/user/新 文件夹 "))
        assertEquals("""{"id":"b1","workDir":"/w/a"}""", WorkFolders.Requests.botFolder("b1", "/w/a"))
        assertEquals("""{"id":"b1","workDir":""}""", WorkFolders.Requests.botFolder("b1", ""))
        assertEquals("""{"id":"r1","workDir":""}""", WorkFolders.Requests.roomFolder("r1", ""))
        assertEquals("""{"id":"r1","workDir":"C:\\w"}""", WorkFolders.Requests.roomFolder("r1", "C:\\w"))
        assertEquals("/new", WorkFolders.teamOf(obj("""{"team":"/new"}""")))
    }

    @Test
    fun roomFields() {
        val s = obj("""{"rooms":[{"id":"r1","name":"项目群","workDir":"/w"},{"id":"r2","name":"闲聊"}]}""")
        assertEquals("/w", WorkFolders.roomFolder(s, "r1"))
        assertNull(WorkFolders.roomFolder(s, "r2"))
        assertEquals("闲聊", WorkFolders.roomName(s, "r2"))
        assertNull(WorkFolders.roomName(null, "r1"))
    }
}
