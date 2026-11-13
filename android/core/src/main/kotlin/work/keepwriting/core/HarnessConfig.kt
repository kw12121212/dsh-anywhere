package work.keepwriting.core

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * harness 配置的读取与原子持久化：目录由调用方注入（:app 传 Context.filesDir 下的
 * config 目录，JUnit 传临时目录），格式与桌面 config.json 一致
 * （{"harnessURL": "..."}\n）。读取区分三态（missing/invalid/ok），保存剥离 token
 * 后原子落盘（临时文件 + 原子重命名），写入中断不会留下半截文件。
 */
class HarnessConfig(private val dir: Path) {
    sealed interface Load {
        data object Missing : Load
        data object Invalid : Load
        data class Ok(val url: String) : Load
    }

    private val file: Path get() = dir.resolve("config.json")

    /** 读取配置：文件缺失为 Missing；无法读取、JSON 损坏、harnessURL 非字符串或
     *  地址校验失败为 Invalid；否则 Ok（干净地址，token 已剥离）。 */
    fun load(): Load {
        if (!Files.isRegularFile(file)) return Load.Missing
        val data = try {
            file.readText()
        } catch (_: Exception) {
            return Load.Invalid
        }
        val parsed = Json.parse(data) ?: return Load.Invalid
        val raw = (parsed as? Json.Value.Object)?.members?.get("harnessURL") as? Json.Value.StringValue
            ?: return Load.Invalid
        return try {
            Load.Ok(HarnessUrl.normalize(raw.value))
        } catch (_: HarnessUrl.ValidationError) {
            Load.Invalid
        }
    }

    /** 校验 raw，原子地保存去掉 token 的干净地址并返回它；校验失败抛 ValidationError，
     *  不修改已有配置。文件字节形态与桌面 saveConfig（JSON.stringify(obj, null, 2) +
     *  换行）一致：2 空格缩进多行。 */
    fun save(raw: String): String {
        val clean = HarnessUrl.normalize(raw)
        Files.createDirectories(dir)
        val tmp = dir.resolve(".config.json.${Integer.toHexString(SecureRandom().nextInt()).removePrefix("-")}.tmp")
        try {
            // Files.writeString 是 Java 11 / Android API 33+；writeText 走
            // newOutputStream（API 26+），core 会被打进 APK，只能用低版本路径。
            tmp.writeText("{\n  \"harnessURL\": \"$clean\"\n}\n")
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
        return clean
    }

    companion object {
        /** 目录中不应残留的临时文件前缀（验收与向量断言用）。 */
        const val TMP_PREFIX = ".config.json."
    }
}
