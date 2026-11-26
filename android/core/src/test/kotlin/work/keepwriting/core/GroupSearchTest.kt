package work.keepwriting.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GroupSearch 单元测试（dsbot-group-search）：实测脱敏夹具 + 0.3.10 源码核对形态。 */
class GroupSearchTest {
    private fun parse(text: String): Json.Value = checkNotNull(Json.parse(text))

    private fun realFixture(name: String): Json.Value {
        val text = checkNotNull(GroupSearchTest::class.java.getResource("/dsbot/$name")).readText(Charsets.UTF_8)
        return checkNotNull((parse(text) as Json.Value.Object).members["value"])
    }

    @AfterTest
    fun resetLang() {
        I18n.lang = ChatBars.Lang.ZH
    }

    // ---- 请求 ----

    @Test
    fun `首屏请求不带分页参数且查询去首尾空白`() {
        assertEquals("""{"roomId":"r1","query":"发布 计划"}""", GroupSearch.searchRequest("r1", "  发布 计划　"))
    }

    @Test
    fun `加载更多请求按游标带 before 或 offset`() {
        assertEquals("""{"roomId":"r1","query":"ab","before":2309}""", GroupSearch.searchRequest("r1", "ab", GroupSearch.Cursor.Before(2309)))
        assertEquals("""{"roomId":"r1","query":"ab","offset":20}""", GroupSearch.searchRequest("r1", "ab", GroupSearch.Cursor.Offset(20)))
        assertEquals("""{"roomId":"r\"1","query":"a\\b"}""", GroupSearch.searchRequest("r\"1", "a\\b"))
    }

    @Test
    fun `去首尾空白后不足两个字符不可搜索`() {
        assertFalse(GroupSearch.searchable(""))
        assertFalse(GroupSearch.searchable(" a "))
        assertFalse(GroupSearch.searchable(" x　"))
        assertTrue(GroupSearch.searchable("ab"))
        assertTrue(GroupSearch.searchable("发布"))
        assertTrue(GroupSearch.searchable("a b"))
    }

    @Test
    fun `查询词小写按 JS 空白切分并去重`() {
        assertEquals(listOf("deploy", "计划"), GroupSearch.termsOf(" Deploy　计划  deploy DEPLOY "))
        assertEquals(emptyList(), GroupSearch.termsOf("  \t "))
    }

    // ---- 解码 ----

    @Test
    fun `实测首页夹具解码二十条命中且新到旧`() {
        val page = GroupSearch.decode(realFixture("group-search-page1.json"))
        assertEquals(20, page.results.size)
        assertTrue(page.hasMore)
        val seqs = page.results.map { checkNotNull(it.line.seq) }
        assertEquals(seqs.sortedDescending(), seqs)
        assertTrue(page.results.all { it.line.time != null && it.line.fromMember })
        assertTrue(page.results.any { it.before != null } && page.results.any { it.after != null })
        assertEquals(GroupSearch.Cursor.Before(seqs.last()), GroupSearch.nextCursor(page.results))
    }

    @Test
    fun `缺 time 与邻居的命中照常解码`() {
        val page = GroupSearch.decode(parse("""{"results":[{"seq":7,"text":"你好呀","botId":"b1","name":"研究"}],"hasMore":false}"""))
        val hit = page.results.single()
        assertNull(hit.line.time)
        assertNull(hit.before)
        assertNull(hit.after)
        assertFalse(page.hasMore)
    }

    @Test
    fun `用户消息带 name 不带 botId 时判为自己`() {
        val line = GroupSearch.decode(parse("""{"results":[{"seq":1,"time":1,"text":"hi there","name":"wx"}]}""")).results.single().line
        assertFalse(line.fromMember)
        assertEquals("你", GroupSearch.authorOf(line))
        I18n.lang = ChatBars.Lang.EN
        assertEquals("You", GroupSearch.authorOf(line))
    }

    @Test
    fun `畸形应答与条目降级`() {
        assertEquals(GroupSearch.Page(emptyList(), false), GroupSearch.decode(null))
        assertEquals(GroupSearch.Page(emptyList(), false), GroupSearch.decode(parse("[]")))
        val page = GroupSearch.decode(parse("""{"results":[1,{"seq":2},{"seq":"x","text":"ok","time":"t"},{"seq":3.5,"text":"y"},{"seq":4.0,"text":"z","before":"bad"}],"hasMore":"true"}"""))
        assertFalse(page.hasMore, "hasMore 仅布尔 true 为真")
        assertEquals(listOf("ok", "y", "z"), page.results.map { it.line.text })
        assertEquals(listOf(null, null, 4L), page.results.map { it.line.seq })
        assertNull(page.results[0].line.time)
        assertNull(page.results[2].before)
    }

    // ---- 分页 ----

    @Test
    fun `最旧命中无整数 seq 时游标用 offset`() {
        val page = GroupSearch.decode(parse("""{"results":[{"seq":9,"text":"a"},{"text":"b"}]}"""))
        assertEquals(GroupSearch.Cursor.Offset(2), GroupSearch.nextCursor(page.results))
        assertEquals(GroupSearch.Cursor.Offset(0), GroupSearch.nextCursor(emptyList()))
    }

    @Test
    fun `合并下一页按 seq 去重且无 seq 的命中保留`() {
        val first = GroupSearch.decode(parse("""{"results":[{"seq":9,"text":"a"},{"seq":8,"text":"b"}],"hasMore":true}"""))
        val next = GroupSearch.decode(parse("""{"results":[{"seq":8,"text":"b"},{"seq":5,"text":"c"},{"text":"d"}],"hasMore":false}"""))
        val merged = GroupSearch.merge(first, next)
        assertEquals(listOf("a", "b", "c", "d"), merged.results.map { it.line.text })
        assertFalse(merged.hasMore)
    }

    @Test
    fun `页内重复 seq 只留首条`() {
        val page = GroupSearch.decode(parse("""{"results":[{"seq":3,"text":"a"},{"seq":3,"text":"b"},{"text":"c"},{"text":"d"}]}"""))
        assertEquals(listOf("a", "c", "d"), page.results.map { it.line.text })
        val merged = GroupSearch.merge(page, GroupSearch.decode(parse("""{"results":[{"seq":2,"text":"e"},{"seq":2,"text":"f"}]}""")))
        assertEquals(listOf("a", "c", "d", "e"), merged.results.map { it.line.text })
    }

    // ---- 页面状态 ----

    private fun answer(query: String, json: String) = GroupSearch.Answer(query, GroupSearch.decode(parse(json)))

    @Test
    fun `不足两个字符为输入提示且不显示旧结果与旧错误`() {
        val a = answer("ab", """{"results":[{"seq":1,"text":"ab"}]}""")
        assertEquals(GroupSearch.Display.Prompt, GroupSearch.display(" a ", a, GroupSearch.Failure("a", "x"), null))
        assertEquals(GroupSearch.Display.Prompt, GroupSearch.display("", a, null, null))
    }

    @Test
    fun `修改关键词后旧结果不再显示等新应答期间为搜索中`() {
        val a = answer("release", """{"results":[{"seq":1,"text":"release"}],"hasMore":true}""")
        assertEquals(GroupSearch.Display.Searching, GroupSearch.display("releasex", a, null, null))
        assertEquals(GroupSearch.Display.Searching, GroupSearch.display("ab", null, null, null))
        val shown = GroupSearch.display(" release ", a, null, null)
        assertTrue(shown is GroupSearch.Display.Results && shown.hits.size == 1 && shown.canLoadMore && shown.count == "1 条结果")
    }

    @Test
    fun `首屏失败只对所属查询显示且改词可重新搜索`() {
        val fail = GroupSearch.Failure("deploy", "找不到这个群聊")
        assertEquals(GroupSearch.Display.Failed("找不到这个群聊"), GroupSearch.display("deploy", null, fail, null))
        // 改词后旧失败不再显示，新查询等待应答，应答到达即显示结果
        assertEquals(GroupSearch.Display.Searching, GroupSearch.display("deploy2", null, fail, null))
        val shown = GroupSearch.display("deploy2", answer("deploy2", """{"results":[{"seq":5,"text":"deploy2"}]}"""), fail, null)
        assertTrue(shown is GroupSearch.Display.Results)
    }

    @Test
    fun `无命中显示空状态`() {
        assertEquals(GroupSearch.Display.Empty, GroupSearch.display("zzq", answer("zzq", """{"results":[],"hasMore":false}"""), null, null))
    }

    @Test
    fun `加载更多失败在页脚显示并保留已有结果`() {
        val a = answer("ab", """{"results":[{"seq":9,"text":"ab"},{"seq":8,"text":"ab"}],"hasMore":true}""")
        val shown = GroupSearch.display("ab", a, null, GroupSearch.Failure("ab", "搜索失败：网络不可用，请重试"))
        assertTrue(shown is GroupSearch.Display.Results)
        assertEquals(2, shown.hits.size)
        assertEquals("搜索失败：网络不可用，请重试", shown.moreError)
        val other = GroupSearch.display("ab", a, null, GroupSearch.Failure("abc", "旧"))
        assertNull((other as GroupSearch.Display.Results).moreError, "其他查询的加载失败不显示")
    }

    @Test
    fun `加载更多游标只在当前查询有更多且不在加载中时给出`() {
        val a = answer("ab", """{"results":[{"seq":9,"text":"ab"},{"seq":8,"text":"ab"}],"hasMore":true}""")
        assertEquals(GroupSearch.Cursor.Before(8), GroupSearch.loadMoreCursor(" ab", a, paging = false))
        assertNull(GroupSearch.loadMoreCursor("ab", a, paging = true))
        assertNull(GroupSearch.loadMoreCursor("abc", a, paging = false))
        assertNull(GroupSearch.loadMoreCursor("ab", a.copy(page = a.page.copy(hasMore = false)), paging = false))
        assertNull(GroupSearch.loadMoreCursor("ab", null, paging = false))
    }

    @Test
    fun `搜索页文案随语言切换`() {
        val zh = listOf(GroupSearch.Copy.title, GroupSearch.Copy.placeholder("项目群"), GroupSearch.Copy.placeholder(null), GroupSearch.Copy.promptLabel, GroupSearch.Copy.promptHint, GroupSearch.Copy.searching, GroupSearch.Copy.noResults, GroupSearch.Copy.noResultsHint, GroupSearch.Copy.loadMore, GroupSearch.Copy.backToResults, GroupSearch.Copy.around, GroupSearch.Copy.clear)
        assertEquals(listOf("搜索这个群", "在 项目群 里搜索消息", "搜索这个群的消息", "搜索这个群的消息", "至少输入两个字", "搜索中…", "没有找到消息", "换个词试试", "加载更多", "返回结果", "这条消息的前后", "清除搜索"), zh)
        I18n.lang = ChatBars.Lang.EN
        val en = listOf(GroupSearch.Copy.title, GroupSearch.Copy.placeholder("Ops"), GroupSearch.Copy.placeholder(" "), GroupSearch.Copy.promptLabel, GroupSearch.Copy.promptHint, GroupSearch.Copy.searching, GroupSearch.Copy.noResults, GroupSearch.Copy.noResultsHint, GroupSearch.Copy.loadMore, GroupSearch.Copy.backToResults, GroupSearch.Copy.around, GroupSearch.Copy.clear)
        assertEquals(listOf("Search this group", "Search messages in Ops", "Search this group’s messages", "Search this group’s messages", "Type at least two characters", "Searching…", "No messages found", "Try a different word", "Load more", "Back to results", "Around this message", "Clear search"), en)
    }

    // ---- 片段与高亮 ----

    @Test
    fun `短文本片段原样返回`() {
        val text = "x".repeat(180)
        assertEquals(text, GroupSearch.snippetOf(text, listOf("q")))
    }

    @Test
    fun `长文本片段从命中词前六十字符截取并加省略号`() {
        val text = "a".repeat(100) + "KEY" + "b".repeat(300)
        val snippet = GroupSearch.snippetOf(text, listOf("key"))
        assertEquals("…" + text.substring(40, 220) + "…", snippet)
        assertTrue(snippet.contains("KEY"))
    }

    @Test
    fun `命中词靠前或未命中时片段从开头截取`() {
        val text = "KEY" + "b".repeat(300)
        assertEquals(text.substring(0, 180) + "…", GroupSearch.snippetOf(text, listOf("key")))
        assertEquals(text.substring(0, 180) + "…", GroupSearch.snippetOf(text, listOf("none")))
        val tail = "c".repeat(300) + "KEY"
        assertEquals("…" + tail.substring(240), GroupSearch.snippetOf(tail, listOf("key")), "截到末尾时尾部不加省略号")
    }

    @Test
    fun `片段取多个查询词中最靠前的命中`() {
        val text = "c".repeat(200) + "beta" + "c".repeat(50) + "alpha" + "c".repeat(100)
        assertTrue(GroupSearch.snippetOf(text, listOf("alpha", "beta")).startsWith("…" + "c".repeat(60) + "beta"))
    }

    @Test
    fun `高亮区间不区分大小写且转义正则特殊字符`() {
        val text = "Deploy a.b then DEPLOY (x) 计划"
        val ranges = GroupSearch.highlights(text, GroupSearch.termsOf("deploy A.B (x) 计划"))
        assertEquals(listOf("Deploy", "a.b", "DEPLOY", "(x)", "计划"), ranges.map { text.substring(it.first, it.last + 1) })
        assertEquals(emptyList(), GroupSearch.highlights("axb", listOf("a.b")), "点号按字面匹配")
        assertEquals(emptyList(), GroupSearch.highlights(text, emptyList()))
    }

    @Test
    fun `高亮按查询词顺序交替匹配不重叠`() {
        val text = "abcd"
        val ranges = GroupSearch.highlights(text, listOf("ab", "abc", "cd"))
        assertEquals(listOf(0..1, 2..3), ranges)
    }

    /**
     * 与网页端算法逐项一致（upstream-client-algorithm-parity）：期望值由 group-search-ref.mjs 在 node 中执行
     * ds-bot 0.3.10 client.js 的 src/client/group-search.js 原文（termsOf、snippetOf、marked）生成。
     */
    @Test
    fun `查询词片段与高亮区间与上游网页端算法一致`() {
        val text = checkNotNull(GroupSearchTest::class.java.getResource("/dsbot/group-search-expected.json")).readText(Charsets.UTF_8)
        val cases = (parse(text) as Json.Value.Array).items.map { it as Json.Value.Object }
        assertTrue(cases.size >= 15)
        fun str(o: Json.Value.Object, k: String) = (o.members[k] as Json.Value.StringValue).value
        fun ranges(o: Json.Value.Object, k: String) = (o.members[k] as Json.Value.Array).items.map { r ->
            val (a, b) = (r as Json.Value.Array).items.map { (it as Json.Value.NumberValue).raw.toInt() }
            a until b
        }
        for (c in cases) {
            val query = str(c, "query")
            val terms = GroupSearch.termsOf(query)
            assertEquals((c.members["terms"] as Json.Value.Array).items.map { (it as Json.Value.StringValue).value }, terms, "terms: $query")
            val snippet = GroupSearch.snippetOf(str(c, "text"), terms)
            assertEquals(str(c, "snippet"), snippet, "snippet: $query")
            assertEquals(ranges(c, "textRanges"), GroupSearch.highlights(str(c, "text"), terms), "text ranges: $query")
            assertEquals(ranges(c, "snippetRanges"), GroupSearch.highlights(snippet, terms), "snippet ranges: $query")
        }
    }

    // ---- 发言者与文案 ----

    @Test
    fun `成员发言者名册优先条目名后备都缺为某个 Bot`() {
        val roster = mapOf("b1" to RoomChat.Speaker("b1", "新名字", "blue"))
        fun member(json: String) = GroupSearch.decode(parse("""{"results":[$json]}""")).results.single().line
        assertEquals("新名字", GroupSearch.authorOf(member("""{"seq":1,"text":"t","botId":"b1","name":"旧名字"}"""), roster))
        assertEquals("blue", GroupSearch.colorOf(member("""{"seq":1,"text":"t","botId":"b1"}"""), roster))
        assertEquals("旧名字", GroupSearch.authorOf(member("""{"seq":1,"text":"t","botId":"b2","name":"旧名字"}"""), roster))
        assertEquals("某个 Bot", GroupSearch.authorOf(member("""{"seq":1,"text":"t","botId":null}"""), roster))
        I18n.lang = ChatBars.Lang.EN
        assertEquals("a Bot", GroupSearch.authorOf(member("""{"seq":1,"text":"t","botId":"b9"}"""), roster))
    }

    @Test
    fun `实测夹具成员经名册取当前名`() {
        val page = GroupSearch.decode(realFixture("group-search-page1.json"))
        val id = checkNotNull(page.results.first().line.botId)
        val roster = mapOf(id to RoomChat.Speaker(id, "名册名", "green"))
        assertEquals("名册名", GroupSearch.authorOf(page.results.first().line, roster))
    }

    @Test
    fun `失败文案服务端原因优先并本地化已知原因`() {
        assertEquals("找不到这个群聊", GroupSearch.errorText("bot/not-found", "Unknown group chat"))
        assertEquals("此远端版本不支持群内搜索", GroupSearch.errorText("bot/not-found", "Unknown endpoint group-search"))
        assertEquals("Query too long", GroupSearch.errorText("bot/refused", "Query too long"))
        assertEquals("搜索失败（bot/refused）", GroupSearch.errorText("bot/refused", " "))
        assertEquals("搜索失败：网络不可用，请重试", GroupSearch.errorText("network", null))
        I18n.lang = ChatBars.Lang.EN
        assertEquals("Unknown group chat", GroupSearch.errorText("bot/not-found", "Unknown group chat"))
        assertEquals("Search failed: network unavailable, try again", GroupSearch.errorText(null, null))
    }

    @Test
    fun `命中计数中英文案`() {
        assertEquals("1 条结果", GroupSearch.countLabel(1))
        assertEquals("20 条结果", GroupSearch.countLabel(20))
        I18n.lang = ChatBars.Lang.EN
        assertEquals("1 match", GroupSearch.countLabel(1))
        assertEquals("40 matches", GroupSearch.countLabel(40))
    }

    @Test
    fun `时间文案今天昨天与更早`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 11, 26, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("今天 09:05", GroupSearch.timeLabel(at(2026, 11, 26, 9, 5), now, zone))
        assertEquals("昨天 23:59", GroupSearch.timeLabel(at(2026, 11, 25, 23, 59), now, zone))
        assertEquals("11月24日周二 08:00", GroupSearch.timeLabel(at(2026, 11, 24, 8, 0), now, zone))
        assertEquals("2025年12月31日周三 08:00", GroupSearch.timeLabel(at(2025, 12, 31, 8, 0), now, zone))
        I18n.lang = ChatBars.Lang.EN
        val today = GroupSearch.timeLabel(at(2026, 11, 26, 14, 5), now, zone)
        assertTrue(today.startsWith("Today 2:05") && today.endsWith("PM"), today)
        val older = GroupSearch.timeLabel(at(2026, 11, 24, 8, 0), now, zone)
        assertTrue(older.startsWith("Tue, Nov 24 8:00"), older)
        assertNotNull(GroupSearch.timeLabel(at(2025, 12, 31, 8, 0), now, zone).takeIf { it.startsWith("Wed, Dec 31, 2025 ") })
    }
}
