package work.keepwriting.core

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 应用更新源配置（自托管 latest.json 契约；DiagnosticsManager 同目录不同文件，
 * separate-state-files）：三态读取、原子保存、https 校验复用 DiagEndpoint 的规则。
 * 为空（未配置）时 UpdateManager 走 GitHub Release。
 */
class UpdateSourceConfig(private val dir: Path) {
    sealed interface Load {
        data object Missing : Load
        data object Invalid : Load
        data class Ok(val endpoint: String) : Load
    }

    private val file: Path get() = dir.resolve(FILE_NAME)

    fun load(): Load {
        if (!Files.isRegularFile(file)) return Load.Missing
        val data = try {
            file.readText()
        } catch (_: Exception) {
            return Load.Invalid
        }
        val parsed = Json.parse(data) ?: return Load.Invalid
        val raw = (parsed as? Json.Value.Object)?.members?.get("updateSourceEndpoint") as? Json.Value.StringValue
            ?: return Load.Invalid
        val endpoint = DiagEndpoint.validate(raw.value) ?: return Load.Invalid
        return Load.Ok(endpoint)
    }

    /** 校验并原子保存更新源，返回规范化值；非法抛 IllegalArgumentException。 */
    fun save(raw: String): String {
        val endpoint = DiagEndpoint.validate(raw) ?: throw IllegalArgumentException("update source invalid")
        Files.createDirectories(dir)
        val tmp = dir.resolve(".${FILE_NAME}.${Integer.toHexString(SecureRandom().nextInt()).removePrefix("-")}.tmp")
        try {
            tmp.writeText("{\n  \"updateSourceEndpoint\": \"$endpoint\"\n}\n")
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.deleteIfExists()
        }
        return endpoint
    }

    /** 清除配置（回到 GitHub 源）；文件不存在时同样成功。 */
    fun clear() {
        file.deleteIfExists()
    }

    companion object {
        const val FILE_NAME = "update-source.json"
    }
}
