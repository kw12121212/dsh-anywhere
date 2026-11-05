package work.keepwriting.core

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * 诊断上报的持久化与组包（diagnostics-reporting 规格）：端点配置（独立文件、原子写、
 * 三态读取）、有界待发队列（每份一个文件、原子落盘、超限丢最旧、损坏丢弃）与批量组包
 * （每批 ≤20 份且编码后 ≤1 MiB）。与 harness 配置同目录不同文件（separate-state-files）。
 */

/** 端点配置：diagnostics.json，内容 {"diagnosticsEndpoint": "https://…"}\n。 */
class DiagEndpointConfig(private val dir: Path) {
    sealed interface Load {
        data object Missing : Load
        data object Invalid : Load
        data class Ok(val endpoint: String) : Load
    }

    private val file: Path get() = dir.resolve(FILE_NAME)

    /** 读取：缺失为 Missing；损坏、字段非字符串或校验失败为 Invalid；否则 Ok（已规范化）。 */
    fun load(): Load {
        if (!Files.isRegularFile(file)) return Load.Missing
        val data = try {
            file.readText()
        } catch (_: Exception) {
            return Load.Invalid
        }
        val parsed = Json.parse(data) ?: return Load.Invalid
        val raw = (parsed as? Json.Value.Object)?.members?.get("diagnosticsEndpoint") as? Json.Value.StringValue
            ?: return Load.Invalid
        val endpoint = DiagEndpoint.validate(raw.value) ?: return Load.Invalid
        return Load.Ok(endpoint)
    }

    /** 校验并原子保存端点，返回规范化值；非法抛 [IllegalArgumentException]，不修改已有配置。 */
    fun save(raw: String): String {
        val endpoint = DiagEndpoint.validate(raw)
            ?: throw IllegalArgumentException("diagnostics endpoint invalid")
        Files.createDirectories(dir)
        val tmp = dir.resolve(".${DiagEndpointConfig.FILE_NAME}.${Integer.toHexString(SecureRandom().nextInt()).removePrefix("-")}.tmp")
        try {
            Files.writeString(tmp, "{\n  \"diagnosticsEndpoint\": \"$endpoint\"\n}\n")
            moveAtomic(tmp, file)
        } finally {
            tmp.deleteIfExists()
        }
        return endpoint
    }

    /** 清除配置（回到未配置）；文件不存在时同样成功。 */
    fun clear() {
        file.deleteIfExists()
    }

    companion object {
        const val FILE_NAME = "diagnostics.json"
    }
}

/**
 * 有界待发队列：每份报告一个文件（文件名 epochMillis-id.json，字典序即时间序），
 * 原子落盘（临时文件 + 重命名），超上限丢最旧；读取时损坏/无法解析的文件被删除并跳过。
 */
class DiagQueue(private val dir: Path, private val capacity: Int = DiagLimits.DEFAULT_QUEUE_CAPACITY) {
    /** 原子写入一份报告；超过上限时删最旧的文件。 */
    fun add(report: DiagReport) {
        Files.createDirectories(dir)
        val name = "%019d-%s.json".format(diagEpochMillis(), report.id)
        val tmp = dir.resolve(".${name}.${Integer.toHexString(SecureRandom().nextInt()).removePrefix("-")}.tmp")
        try {
            Files.writeString(tmp, DiagJson.encodeReport(report))
            moveAtomic(tmp, dir.resolve(name))
        } finally {
            tmp.deleteIfExists()
        }
        enforceCapacity()
    }

    /** 读取全部待发报告（时间序）；损坏文件删除并跳过，不阻断后续。 */
    fun load(): List<Named> {
        if (!Files.isDirectory(dir)) return emptyList()
        val out = mutableListOf<Named>()
        for (f in Files.list(dir).use { stream ->
                stream.filter { it.name.endsWith(".json") }.sorted().toList()
            }) {
            val report = try {
                DiagJson.decodeReport(f.readText())
            } catch (_: Exception) {
                null
            }
            if (report == null) {
                f.deleteIfExists()
                continue
            }
            out.add(Named(f.name, report))
        }
        return out
    }

    /** 删除已发送（或按 4xx 丢弃）的文件；不存在的名字忽略。 */
    fun remove(names: List<String>) {
        names.forEach { dir.resolve(it).deleteIfExists() }
    }

    /** 清空队列（设置清除端点时调用）。 */
    fun clear() {
        if (!Files.isDirectory(dir)) return
        Files.list(dir).use { stream -> stream.forEach { it.deleteIfExists() } }
    }

    fun size(): Int = load().size

    private fun enforceCapacity() {
        val names = Files.list(dir).use { stream ->
            stream.filter { it.name.endsWith(".json") }.map { it.name }.sorted().toList()
        }
        var excess = names.size - capacity
        var i = 0
        while (excess > 0) {
            dir.resolve(names[i]).deleteIfExists()
            excess--
            i++
        }
    }

    /** 队列中的一份：文件名（删除凭据）与报告。 */
    data class Named(val name: String, val report: DiagReport)

    companion object {
        /** 可注入的时间源（测试用）。 */
        internal var nowSource: () -> Long = System::currentTimeMillis

        private fun diagEpochMillis(): Long = nowSource()
    }
}

/** 批量组包：每批 ≤20 份且编码后 ≤1 MiB；单份超限独占一批（由截断上限保证不会出现）。 */
object DiagBatching {
    fun batches(items: List<DiagQueue.Named>): List<List<DiagQueue.Named>> {
        val out = mutableListOf<MutableList<DiagQueue.Named>>()
        var current = mutableListOf<DiagQueue.Named>()
        var currentBytes = 2 + 10 + 2 // {"reports":[]} 的骨架
        for (item in items) {
            val size = DiagJson.encodedSize(item.report) + 1
            val wouldOverflowBytes = currentBytes + size > DiagLimits.MAX_BATCH_BYTES
            if (current.isNotEmpty() && (wouldOverflowBytes || current.size >= DiagLimits.MAX_BATCH_REPORTS)) {
                out.add(current)
                current = mutableListOf()
                currentBytes = 2 + 10 + 2
            }
            current.add(item)
            currentBytes += size
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }
}

/** 同会话错误态去重：同类错误（按分类键）至多上报一次。 */
class DiagErrorDedup {
    private val seen = mutableSetOf<String>()

    /** 记录分类键；此前已见过返回 true（不再上报）。 */
    fun seen(key: String): Boolean = !seen.add(key)

    fun reset() {
        seen.clear()
    }
}

private fun moveAtomic(tmp: Path, target: Path) {
    try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
