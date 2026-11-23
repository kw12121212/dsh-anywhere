package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-bot-manage：真实 state/efforts 夹具解码、模型分组与引擎、名称校验、入口显隐与请求体字面值。 */
class BotManageTest {
    private fun fixture(name: String): Json.Value =
        (Json.parse(checkNotNull(BotManageTest::class.java.getResource("/dsbot/$name")).readText(Charsets.UTF_8)) as Json.Value.Object).members["value"]!!

    private val real = fixture("state-full.json") as Json.Value.Object
    private fun obj(t: String) = Json.parse(t) as Json.Value.Object

    private val mainId = "session-2f6d9790-919f-4a53-b13c-e016a3acf4e8"

    @Test
    fun realStateMainBotProfile() {
        val p = assertNotNull(BotManage.profileOf(real, mainId))
        assertTrue(p.isMain)
        assertTrue(p.isTile)
        assertTrue(p.onlyMain)
        assertTrue(p.inGroup)
        assertEquals("whale", p.shape)
        assertEquals("deepseek", p.color)
        assertFalse(p.hasPhoto)
        assertEquals("dsh-opencode-go/deepseek-v4.1-flash", p.currentModel)
        // 唯一 Main Bot：不能取消、不能删除、不能隐藏/置顶；私有开关禁用并说明
        assertFalse(BotManage.canToggleMain(p))
        assertFalse(BotManage.canDelete(p))
        assertFalse(BotManage.canPin(p))
        assertFalse(BotManage.canHide(p))
        assertFalse(BotManage.canTogglePrivate(p))
        assertTrue(BotManage.canDuplicate(p))
        assertNull(BotManage.profileOf(real, "nope"))
    }

    @Test
    fun realStateModelsPermissionsAndArchives() {
        val groups = BotManage.models(real)
        assertTrue(groups.isNotEmpty())
        // 同一服务商的模型归在一组，组按首次出现顺序
        val names = groups.map { it.providerName }
        assertEquals(names.distinct(), names)
        assertEquals("GLM Coding Plan", groups[0].providerName)
        assertEquals(listOf("glm-coding-plan/glm-5.3", "glm-coding-plan/glm-5.3-flash"), groups[0].models.map { it.ref })
        val perms = assertNotNull(BotManage.permissions(real))
        assertEquals(listOf("read-only", "workspace-write", "danger-full-access"), perms.options.map { it.value })
        assertEquals("danger-full-access", perms.defaultPreset)
        assertEquals(emptyList(), BotManage.privateArchives(real))
        assertEquals(real.members["bots"].let { (it as Json.Value.Array).items.size }, BotManage.botNames(real).size)
    }

    @Test
    fun effortsFixtures() {
        val e = BotManage.efforts(fixture("efforts.json"))
        assertEquals(listOf("low", "high", "max"), e.efforts.map { it.id })
        assertEquals("High", e.efforts[1].name)
        assertNull(e.defaultEffort)
        assertTrue(BotManage.efforts(fixture("efforts-empty.json")).efforts.isEmpty())
        // 畸形：缺 id 跳过、name 缺省为 id、非对象应答为空
        val odd = BotManage.efforts(obj("""{"efforts":[{"name":"x"},{"id":"low"},3],"defaultEffort":"low"}"""))
        assertEquals(listOf("low"), odd.efforts.map { it.name })
        assertEquals("low", odd.defaultEffort)
        assertTrue(BotManage.efforts(Json.Value.NullValue).efforts.isEmpty())
    }

    @Test
    fun engineChangeNeedsConfirm() {
        assertEquals("model", BotManage.engineOf("dsh-opencode-go/deepseek-v4.1-flash"))
        assertEquals("local-claude-code", BotManage.engineOf("local-claude-code/default"))
        assertNull(BotManage.engineOf(null))
        assertTrue(BotManage.needsEngineConfirm("dsh/a", "local-codex-cli/default"))
        assertTrue(BotManage.needsEngineConfirm("local-claude-code/default", "local-codex-cli/default"))
        assertTrue(BotManage.needsEngineConfirm("local-claude-code/default", "glm/glm-5"))
        assertFalse(BotManage.needsEngineConfirm("dsh/a", "glm/b"))
        assertFalse(BotManage.needsEngineConfirm(null, "glm/b"))
        // 当前跑在本地 Agent、目标为普通模型：需要确认
        assertTrue(BotManage.needsEngineConfirm("local-claude-code/default", "glm/b"))
        assertEquals("model", BotManage.engineOf("a"))
    }

    @Test
    fun nameRules() {
        val team = mapOf("b1" to "Alice", "b2" to " Bob ")
        assertEquals(BotManage.NameProblem.EMPTY, BotManage.nameProblem("   ", team))
        assertEquals(BotManage.NameProblem.BAD_CHAR, BotManage.nameProblem("a@b", team))
        assertEquals(BotManage.NameProblem.BAD_CHAR, BotManage.nameProblem("a`b", team))
        assertEquals(BotManage.NameProblem.BAD_CHAR, BotManage.nameProblem("a\nb", team))
        assertEquals(BotManage.NameProblem.CALLS_ALL, BotManage.nameProblem("Everyone", team))
        assertEquals(BotManage.NameProblem.CALLS_ALL, BotManage.nameProblem("所有人", team))
        assertEquals(BotManage.NameProblem.USER_WORD, BotManage.nameProblem(" YOU ", team))
        assertEquals(BotManage.NameProblem.TAKEN, BotManage.nameProblem("alice", team))
        assertEquals(BotManage.NameProblem.TAKEN, BotManage.nameProblem("bob", team))
        // 改名时与自己同名不算重名
        assertNull(BotManage.nameProblem("ALICE", team, selfId = "b1"))
        assertNull(BotManage.nameProblem("Carol", team))
        // 40 字截断后再比较（与服务端一致）
        assertNull(BotManage.nameProblem("x".repeatChars(60), team))
        for (p in BotManage.NameProblem.values()) assertTrue(BotManage.nameProblemText(p).isNotEmpty())
    }

    private fun String.repeatChars(n: Int): String = buildString { repeat(n) { append(this@repeatChars) } }

    @Test
    fun visibilityRules() {
        val state = obj(
            """{"mainBotIds":["m1","m2"],
              "bots":[{"id":"m1","name":"A"},{"id":"m2","name":"B"},
                {"id":"g","name":"G","workDir":"/w/g"},
                {"id":"solo","name":"S","workDir":"/w/s","pinned":true,"hidden":true,"effort":"high","permission":"read-only","avatar":{"shape":"star","image":"data:image/png;base64,AA"}},
                {"id":"nofolder","name":"N"},
                {"id":"priv","name":"P","private":true,"workDir":"/w/p"}],
              "rooms":[{"id":"r","members":["g","m2"]}],
              "privateArchives":[{"id":"old","name":"旧私有","workDir":"/w/old"},{"name":"缺 id"}]}""",
        )
        fun p(id: String) = BotManage.profileOf(state, id)!!
        // 第二个 Main Bot：可取消 Main、可置顶不可隐藏、不可删除
        assertTrue(BotManage.canToggleMain(p("m2")))
        assertTrue(BotManage.canPin(p("m2")))
        assertFalse(BotManage.canHide(p("m2")))
        assertFalse(BotManage.canDelete(p("m2")))
        // 在群里：私有禁用并说明
        assertFalse(BotManage.canTogglePrivate(p("g")))
        assertTrue(BotManage.privateHint(p("g")).isNotEmpty())
        // 没有自己的文件夹：私有禁用
        assertFalse(BotManage.canTogglePrivate(p("nofolder")))
        // 满足条件：可设私有
        val solo = p("solo")
        assertTrue(BotManage.canTogglePrivate(solo))
        assertTrue(solo.pinned && solo.hidden && solo.hasPhoto)
        assertEquals("high", solo.effort)
        assertEquals("read-only", solo.permission)
        assertTrue(BotManage.canHide(solo) && BotManage.canDelete(solo) && BotManage.canDuplicate(solo))
        // 私有 Bot：可关闭私有、不能设 Main、不能复制
        val priv = p("priv")
        assertTrue(BotManage.canTogglePrivate(priv))
        assertFalse(BotManage.canToggleMain(priv))
        assertFalse(BotManage.canDuplicate(priv))
        assertEquals(listOf(BotManage.PrivateArchive("old", "旧私有", "/w/old")), BotManage.privateArchives(state))
        assertNull(BotManage.permissions(state))
        assertTrue(BotManage.models(state).isEmpty())
        assertNull(BotManage.profileOf(null, "m1"))
        // 照片头像的引用形态（avatars:'ref'）同样算已设照片
        assertTrue(BotManage.profileOf(obj("""{"bots":[{"id":"r","avatar":{"imageRef":"x"}}]}"""), "r")!!.hasPhoto)
    }

    @Test
    fun requestBodies() {
        val R = BotManage.Requests
        assertEquals(
            """{"name":"Bob","role":"研究","instructions":"写\"周报\"","model":"glm/x","effort":"high","color":"blue","avatar":{"shape":"star"}}""",
            R.createBot(" Bob ", " 研究 ", "写\"周报\"", "glm/x", "high", "blue", "star"),
        )
        // 只有名称：其余缺省不发；没选模型时不发 effort
        assertEquals("""{"name":"Bob"}""", R.createBot("Bob", "", " ", null, "high", null, null))
        assertEquals("""{"id":"b1","role":"研究"}""", R.updateText("b1", "role", "研究"))
        assertEquals("""{"id":"b1","effort":null}""", R.updateText("b1", "effort", null))
        assertEquals("""{"id":"b1","private":true}""", R.updateBool("b1", "private", true))
        assertEquals("""{"id":"b1","model":"local-codex-cli/default","changeEngine":true}""", R.updateModel("b1", "local-codex-cli/default", true))
        assertEquals("""{"id":"b1","model":"glm/x"}""", R.updateModel("b1", "glm/x", false))
        assertEquals("""{"id":"b1","avatar":{"shape":"moon"}}""", R.updateShape("b1", "moon"))
        assertEquals("""{"id":"b1"}""", R.idOnly("b1"))
        assertEquals("""{"id":"b1","main":false}""", R.setMain("b1", false))
        assertEquals("""{"id":"b1","pinned":true}""", R.setFlag("b1", "pinned", true))
        assertEquals("""{"ref":"glm/x"}""", R.efforts("glm/x"))
        // 每个请求体都是合法 JSON
        for (body in listOf(R.createBot("a\nb", "", "", null, null, null, null), R.updateText("b", "instructions", "多行\n文本\t制表"))) {
            assertNotNull(Json.parse(body))
        }
    }

    @Test
    fun errorTextPrefersServerMessage() {
        // 上游拒绝（普通 Error → bot/refused）显示具体原因
        assertEquals("Name Bob is not available", BotManage.errorText("bot/refused", "Name Bob is not available"))
        assertEquals("团队当前只读（另一处正在使用）", BotManage.errorText("bot/read-only", ""))
        // 内部异常原文不给用户看
        assertEquals("服务端出错，请稍后重试", BotManage.errorText("bot/internal", "TypeError: x is undefined"))
        val texts = listOf("bot/read-only", "bot/not-found", "bot/stale", "bot/refused", "bot/internal").map { BotManage.errorText(it, null) } + BotManage.errorText(null, null)
        assertEquals(texts.size, texts.distinct().size)
    }

    @Test
    fun palette() {
        assertEquals(20, BotManage.SHAPES.size)
        assertEquals(12, BotManage.COLORS.size)
        assertEquals(0x4D6BFE, BotManage.colorRgb("deepseek"))
        assertEquals(0x8A8F98, BotManage.colorRgb("unknown"))
        // 每种形象都有专门标签（不回落到原始名）
        for (s in BotManage.SHAPES) assertTrue(BotManage.shapeLabel(s) != s, s)
        assertEquals("custom", BotManage.shapeLabel("custom"))
    }
}
