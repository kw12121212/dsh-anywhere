package work.keepwriting.core

/**
 * SSE 帧解析器（dsbot-api-client，契约来自 ds-bot feed.js 与 spike 实测）：
 * GET /api/bot/feed 返回 text/event-stream，帧形态为 `event: <名>\ndata: <JSON>\n\n`，
 * 行间穿插 `: keepalive` 注释（每 20s），data 允许多行（以 \n 拼接）。解析器无 IO、
 * 可增量喂入任意切分的字节——传输层读到多少喂多少，跨块半行由内部缓冲衔接。
 */
class SseParser {
    data class Frame(val event: String, val data: String)

    private val buffer = StringBuilder()
    private var eventName: String? = null
    private val dataLines = mutableListOf<String>()

    /** 喂入一段文本，返回其中完整收帧的帧（0 到多个；半行留在缓冲）。 */
    fun feed(chunk: String): List<Frame> {
        val out = mutableListOf<Frame>()
        buffer.append(chunk)
        while (true) {
            val nl = buffer.indexOf('\n')
            if (nl < 0) break
            val line = buffer.substring(0, nl).trimEnd('\r')
            buffer.delete(0, nl + 1)
            when {
                line.startsWith(":") -> {} // 注释/保活
                line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                line.startsWith("data:") -> dataLines.add(line.substringAfter(':').trim())
                line.isEmpty() -> {
                    // 空行收帧：有 data 才是帧边界，没有则只是心跳间隔
                    if (dataLines.isNotEmpty()) {
                        out.add(Frame(eventName ?: "message", dataLines.joinToString("\n")))
                    }
                    eventName = null
                    dataLines.clear()
                }
                // 其余行（id: 等本协议未用）忽略
            }
        }
        return out
    }

    /** 流结束时冲刷：缓冲里残留的完整 data 行收为最后一帧（无空行结尾的宽容处理）。 */
    fun finish(): List<Frame> {
        val out = mutableListOf<Frame>()
        if (buffer.isNotEmpty()) {
            val rest = feed("\n")
            out.addAll(rest)
        }
        if (dataLines.isNotEmpty()) {
            out.add(Frame(eventName ?: "message", dataLines.joinToString("\n")))
            eventName = null
            dataLines.clear()
        }
        return out
    }
}
