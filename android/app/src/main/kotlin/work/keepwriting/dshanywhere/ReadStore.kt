package work.keepwriting.dshanywhere

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import work.keepwriting.core.ReadState
import java.io.File
import java.util.concurrent.Executors

/**
 * 本机已读状态存储（dsbot-read-state）：应用私有文件 read-state.json（独立状态文件），可观察的
 * [state] 供团队页计算未读点。主线程标记、单线程写盘：临时文件写完再原子改名，写失败置脏，
 * 下次标记时重写全量（state-persistence 的原子写入与容错读取）。
 */
class ReadStore(dir: File) {
    private val file = File(dir, "read-state.json")
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "read-state-writer").apply { isDaemon = true } }

    @Volatile
    private var dirty = false

    var state by mutableStateOf(load())
        private set

    /** 标记已读（主线程调用）：只前移；状态有变化或上次写入失败时投递写盘。 */
    fun mark(host: String, id: String, at: Long) {
        val next = state.mark(host, id, at)
        if (next === state && !dirty) return
        state = next
        val text = next.encode()
        writer.execute { write(text) }
    }

    /** 某主机首次出现团队时以当前最近活动时间打底（主线程；该主机已有记录则不变）。 */
    fun seed(host: String, lastAts: Map<String, Long?>) {
        val next = state.seed(host, lastAts)
        if (next === state) return
        state = next
        val text = next.encode()
        writer.execute { write(text) }
    }

    fun isUnread(host: String?, id: String, lastAt: Long?, open: Boolean): Boolean =
        host != null && state.isUnread(host, id, lastAt, open)

    private fun load(): ReadState = try {
        if (file.isFile) ReadState.decode(file.readText(Charsets.UTF_8)) else ReadState.EMPTY
    } catch (_: Exception) {
        ReadState.EMPTY
    }

    private fun write(text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        dirty = try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync() // 落盘后再改名：断电时不留下空文件
            }
            !tmp.renameTo(file)
        } catch (_: Exception) {
            true
        }
    }
}
