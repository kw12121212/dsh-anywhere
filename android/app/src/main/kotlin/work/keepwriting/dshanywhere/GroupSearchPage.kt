package work.keepwriting.dshanywhere

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import work.keepwriting.core.DsbotApi
import work.keepwriting.core.GroupSearch
import work.keepwriting.core.Json
import work.keepwriting.core.RoomChat
import java.time.ZoneId

/**
 * 群内搜索页（dsbot-group-search）：从群聊页工具行进入的全屏页。交互照搬 DS-Bot 0.3.10 网页端
 * GroupSearch——至少 2 个字符、220ms 防抖、结果新到旧（发言者、时间、高亮片段）、加载更多按 seq 去重；
 * 点按命中项在本页打开详情（全文高亮与前后各一条消息），不跳转群聊位置。纯逻辑在 core GroupSearch。
 * 修改关键词以代次丢弃过期应答；只调用只读的 group-search。
 */
@Composable
fun GroupSearchPage(
    roomId: String,
    state: Json.Value.Object?,
    api: ManageApi,
    onBack: () -> Unit,
) {
    val roster = remember(state) { RoomChat.roster(state) }
    val roomName = remember(state, roomId) { roomNameOf(state, roomId) }
    var query by remember(roomId) { mutableStateOf("") }
    // 结果与失败都带所属查询（去首尾空白）；是否显示由 core GroupSearch.display 判定
    var answer by remember(roomId) { mutableStateOf<GroupSearch.Answer?>(null) }
    var failure by remember(roomId) { mutableStateOf<GroupSearch.Failure?>(null) }
    var moreFailure by remember(roomId) { mutableStateOf<GroupSearch.Failure?>(null) }
    var paging by remember(roomId) { mutableStateOf(false) }
    var picked by remember(roomId) { mutableStateOf<GroupSearch.Hit?>(null) }
    var generation by remember(roomId) { mutableIntStateOf(0) }
    val text = GroupSearch.queryText(query)
    val terms = remember(query) { GroupSearch.termsOf(query) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun failureOf(res: DsbotApi.Result): String? = when (res) {
        is DsbotApi.Result.Ok -> null
        is DsbotApi.Result.BotError -> GroupSearch.errorText(res.code, res.message)
        is DsbotApi.Result.Failure -> GroupSearch.errorText("network", null)
    }

    // 查询变化：新代次丢弃在途应答并关闭详情；够长时防抖后发首屏请求，请求前清掉旧失败
    LaunchedEffect(text) {
        generation += 1
        val mine = generation
        picked = null
        paging = false
        if (!GroupSearch.searchable(text)) return@LaunchedEffect
        delay(GroupSearch.DEBOUNCE_MS)
        failure = null
        api.read("group-search", GroupSearch.searchRequest(roomId, text)) { res ->
            if (mine != generation) return@read
            when (val err = failureOf(res)) {
                null -> answer = GroupSearch.Answer(text, GroupSearch.decode((res as DsbotApi.Result.Ok).value))
                else -> failure = GroupSearch.Failure(text, err)
            }
        }
    }

    fun loadMore() {
        val cursor = GroupSearch.loadMoreCursor(query, answer, paging) ?: return
        val mine = generation
        paging = true
        moreFailure = null
        api.read("group-search", GroupSearch.searchRequest(roomId, text, cursor)) { res ->
            if (mine != generation) return@read
            paging = false
            when (val err = failureOf(res)) {
                null -> answer = answer?.let { it.copy(page = GroupSearch.merge(it.page, GroupSearch.decode((res as DsbotApi.Result.Ok).value))) }
                else -> moreFailure = GroupSearch.Failure(text, err)
            }
        }
    }

    // 系统返回键：详情先回结果列表，结果列表再离开搜索页（外层 BackHandler）
    BackHandler(enabled = picked != null) { picked = null }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text(tr("返回", "Back")) }
                    Text(GroupSearch.Copy.title, style = MaterialTheme.typography.titleMedium)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) ({
                        IconButton(onClick = { query = ""; picked = null }) {
                            Icon(Icons.Filled.Close, contentDescription = GroupSearch.Copy.clear)
                        }
                    }) else null,
                    placeholder = { Text(GroupSearch.Copy.placeholder(roomName), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).focusRequester(focus),
                )
            }
        }
        val hit = picked
        if (hit != null) {
            HitDetail(hit, roster, terms) { picked = null }
            return@Column
        }
        when (val shown = GroupSearch.display(query, answer, failure, moreFailure)) {
            GroupSearch.Display.Prompt -> EmptyState(GroupSearch.Copy.promptLabel, GroupSearch.Copy.promptHint)
            GroupSearch.Display.Searching -> EmptyState(GroupSearch.Copy.searching, null)
            is GroupSearch.Display.Failed -> EmptyState(shown.text, null)
            GroupSearch.Display.Empty -> EmptyState(GroupSearch.Copy.noResults, GroupSearch.Copy.noResultsHint)
            is GroupSearch.Display.Results -> {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(shown.hits, key = { index, h -> h.line.seq?.let { "s$it" } ?: "i$index" }) { _, h ->
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable { picked = h }.padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Meta(h.line, roster)
                            Text(
                                highlighted(GroupSearch.snippetOf(h.line.text, terms), terms),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(shown.count, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    shown.moreError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                    if (shown.canLoadMore) {
                        TextButton(enabled = !paging, onClick = { loadMore() }) { Text(GroupSearch.Copy.loadMore) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(label: String, hint: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 发言者与时间行：成员名按名册取色，自己的消息用主题色。 */
@Composable
private fun Meta(line: GroupSearch.Line, roster: Map<String, RoomChat.Speaker>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            GroupSearch.authorOf(line, roster),
            style = MaterialTheme.typography.labelLarge,
            color = GroupSearch.colorOf(line, roster)?.let { speakerColor(it) } ?: MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        line.time?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                GroupSearch.timeLabel(it, System.currentTimeMillis(), ZoneId.systemDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 命中详情（网页端 Detail）：返回结果、发言者与时间、全文高亮、这条消息的前后。 */
@Composable
private fun HitDetail(hit: GroupSearch.Hit, roster: Map<String, RoomChat.Speaker>, terms: List<String>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        TextButton(onClick = onBack) { Text(GroupSearch.Copy.backToResults) }
        Meta(hit.line, roster)
        SelectionContainer {
            Text(highlighted(hit.line.text, terms), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
        }
        if (hit.before != null || hit.after != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(GroupSearch.Copy.around, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (line in listOfNotNull(hit.before, hit.after)) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Meta(line, roster)
                    Text(highlighted(line.text, terms), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun highlighted(text: String, terms: List<String>): AnnotatedString {
    val mark = MaterialTheme.colorScheme.tertiaryContainer
    val onMark = MaterialTheme.colorScheme.onTertiaryContainer
    return remember(text, terms, mark) { mark(text, GroupSearch.highlights(text, terms), mark, onMark) }
}

private fun mark(text: String, ranges: List<IntRange>, background: Color, foreground: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    for (r in ranges) addStyle(SpanStyle(background = background, color = foreground, fontWeight = FontWeight.SemiBold), r.first, r.last + 1)
}

private fun roomNameOf(state: Json.Value.Object?, roomId: String): String? {
    val rooms = (state?.members?.get("rooms") as? Json.Value.Array)?.items ?: return null
    val room = rooms.firstOrNull { ((it as? Json.Value.Object)?.members?.get("id") as? Json.Value.StringValue)?.value == roomId } as? Json.Value.Object
    return (room?.members?.get("name") as? Json.Value.StringValue)?.value?.takeIf { it.isNotBlank() }
}
