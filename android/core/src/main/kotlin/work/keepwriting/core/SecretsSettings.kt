package work.keepwriting.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 密钥设置与落盘加密（dsbot-secrets-settings）：
 * 解码 state.secrets 与 state.secretStore（以及 secret-store 应答）、名称规范化与校验、
 * 密钥值与口令校验、作用范围与时间文案、secret-* 请求体构造。
 * 复刻 DS-Bot 0.3.10 src/host/secrets.js、secrets-crypto.js、channel.js 与 client.js
 * （tech/dsbot-api 的 secret-endpoints-0310）。值与口令只进请求体，绝不回显或写日志。
 */
object SecretsSettings {
    const val MIN_VALUE = 4
    const val MAX_VALUE = 16384
    const val MIN_PASSPHRASE = 8
    const val MAX_PASSPHRASE = 1024
    const val PURPOSE_MAX = 300
    const val ENV_PREFIX = "DSH_SECRET_"

    const val MODE_PLAINTEXT = "plaintext"
    const val MODE_ENCRYPTED = "encrypted"

    const val REASON_MISSING = "missing-passphrase"
    const val REASON_WRONG = "wrong-passphrase"
    const val REASON_SOURCE = "passphrase-source"
    const val REASON_FORMAT = "format"

    private val NAME_REGEX = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    private val LEADING_SIGIL = Regex("^\\$(?:env:)?", RegexOption.IGNORE_CASE)
    private val LEADING_PREFIX = Regex("^DSH_SECRET_", RegexOption.IGNORE_CASE)
    private val NAME_SEPARATORS = Regex("[\\s-]+")

    sealed interface Scope {
        data object All : Scope
        data class Bots(val botIds: List<String>) : Scope
    }

    data class Entry(
        val name: String,
        val purpose: String,
        val scope: Scope,
        val requestedBy: String?,
        val createdAt: Long?,
        val updatedAt: Long?,
        val lastUsedAt: Long?,
    )

    data class StoreStatus(
        val mode: String,
        val locked: Boolean,
        val source: String?,
        val reason: String?,
    ) {
        val isEncrypted: Boolean get() = mode == MODE_ENCRYPTED
    }

    data class BotRef(val id: String, val name: String)

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.num(k: String) = (members[k] as? Json.Value.NumberValue)?.raw?.toDoubleOrNull()?.toLong()
    private fun Json.Value.Object.bool(k: String) = (members[k] as? Json.Value.BoolValue)?.value == true

    /** 远端 state 是否含密钥视图（团队页「密钥」入口门控）。 */
    fun hasSecrets(state: Json.Value.Object?): Boolean = state?.members?.get("secrets") is Json.Value.Array

    /** 远端 state 是否含存储加密状态（页内落盘加密卡显隐）。 */
    fun hasSecretStore(state: Json.Value.Object?): Boolean = state?.members?.get("secretStore") is Json.Value.Object

    /** 只读宿主原因（state.readOnly 非空时返回）。 */
    fun readOnlyReason(state: Json.Value.Object?): String? = state?.str("readOnly")?.takeIf { it.isNotBlank() }

    /** 解码 state.secrets 列表（绝不含值，按名称排序）。不含该字段返回 null。 */
    fun entries(state: Json.Value.Object?): List<Entry>? {
        val arr = state?.members?.get("secrets") as? Json.Value.Array ?: return null
        return arr.items.mapNotNull(::decodeEntry).sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun decodeEntry(value: Json.Value?): Entry? {
        val o = value as? Json.Value.Object ?: return null
        val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return null
        val scope: Scope = when (val raw = o.members["scope"]) {
            is Json.Value.StringValue -> Scope.All
            is Json.Value.Array -> Scope.Bots(
                raw.items.mapNotNull { (it as? Json.Value.StringValue)?.value?.takeIf { id -> id.isNotBlank() } }.distinct(),
            )
            else -> Scope.All
        }
        return Entry(
            name = name,
            purpose = o.str("purpose") ?: "",
            scope = scope,
            requestedBy = o.str("requestedBy")?.takeIf { it.isNotBlank() },
            createdAt = o.num("createdAt"),
            updatedAt = o.num("updatedAt"),
            lastUsedAt = o.num("lastUsedAt"),
        )
    }

    /** 解码 secretStore 状态（来自 state.secretStore 或 secret-store/unlock/encrypt/decrypt 应答）。 */
    fun decodeStoreStatus(value: Json.Value?): StoreStatus? {
        val o = value as? Json.Value.Object ?: return null
        val mode = o.str("mode")?.takeIf { it == MODE_PLAINTEXT || it == MODE_ENCRYPTED } ?: return null
        val locked = o.bool("locked")
        val source = if (locked) null else o.str("source")?.takeIf { it.isNotBlank() }
        val reason = if (locked) o.str("reason")?.takeIf { it.isNotBlank() } else null
        return StoreStatus(mode = mode, locked = locked, source = source, reason = reason)
    }

    fun storeStatus(state: Json.Value.Object?): StoreStatus? = decodeStoreStatus(state?.members?.get("secretStore"))

    /** 从 state.bots 提取可选 Bot 列表。 */
    fun bots(state: Json.Value.Object?): List<BotRef> =
        ((state?.members?.get("bots") as? Json.Value.Array)?.items ?: emptyList())
            .mapNotNull { it as? Json.Value.Object }
            .mapNotNull { o -> o.str("id")?.takeIf { it.isNotBlank() }?.let { BotRef(it, o.str("name") ?: it) } }

    // ---- 规范化与校验 ----

    /** 与上游 secrets.js normalizeName 一致：去首尾空白、去 $/\$env: 与 DSH_SECRET_ 前缀、空白与连字符转下划线并大写。 */
    fun normalizeName(raw: String): String =
        raw.trim()
            .replace(LEADING_SIGIL, "")
            .replace(LEADING_PREFIX, "")
            .replace(NAME_SEPARATORS, "_")
            .uppercase(Locale.ROOT)

    /** 密钥名称预校验（通过返回 null）。 */
    fun nameProblem(raw: String): String? {
        val normalized = normalizeName(raw)
        return when {
            normalized.isEmpty() -> I18n.tr("请输入密钥名称", "Enter a key name")
            !NAME_REGEX.matches(normalized) -> I18n.tr(
                "名称须以字母开头，仅含大写字母、数字和下划线（不超过 64 字符）",
                "Use capitals, digits, and underscores, starting with a letter (up to 64 characters)",
            )
            else -> null
        }
    }

    /** 密钥值预校验（与上游 secrets.js cleanValue 一致：trim 后 4..16384 字符；通过返回 null）。 */
    fun valueProblem(raw: String): String? {
        val v = raw.trim()
        return when {
            v.isEmpty() -> I18n.tr("请输入密钥值", "Type the key first")
            v.length < MIN_VALUE -> I18n.tr("密钥值至少需要 $MIN_VALUE 个字符", "At least $MIN_VALUE characters")
            v.length > MAX_VALUE -> I18n.tr("密钥值不能超过 $MAX_VALUE 个字符", "At most $MAX_VALUE characters")
            else -> null
        }
    }

    fun canSaveNew(name: String, value: String): Boolean = nameProblem(name) == null && valueProblem(value) == null

    /** 口令预校验（与上游 secrets-crypto.js cleanPassphrase 一致：不做 trim，长度 8..1024；通过返回 null）。 */
    fun passphraseProblem(raw: String): String? = when {
        raw.isEmpty() -> I18n.tr("请输入口令", "Type the passphrase first")
        raw.length < MIN_PASSPHRASE -> I18n.tr("口令至少需要 $MIN_PASSPHRASE 个字符", "At least $MIN_PASSPHRASE characters")
        raw.length > MAX_PASSPHRASE -> I18n.tr("口令不能超过 $MAX_PASSPHRASE 个字符", "At most $MAX_PASSPHRASE characters")
        else -> null
    }

    /** 开启加密时的口令 + 确认口令校验（通过返回 null）。 */
    fun encryptProblem(passphrase: String, confirm: String): String? {
        passphraseProblem(passphrase)?.let { return it }
        return if (confirm != passphrase) I18n.tr("两次输入的口令不一致", "The passphrases do not match") else null
    }

    /** 判断编辑中的作用范围是否与当前一致。 */
    fun sameScope(current: Scope, allMode: Boolean, pickedBotIds: Set<String>): Boolean = when {
        allMode -> current is Scope.All
        current is Scope.Bots -> current.botIds.toSet() == pickedBotIds
        else -> false
    }

    // ---- 展示文案 ----

    fun scopeSummary(scope: Scope, bots: List<BotRef>): String = when (scope) {
        Scope.All -> I18n.tr("所有 Bot", "All Bots")
        is Scope.Bots -> {
            val byId = bots.associateBy { it.id }
            val names = scope.botIds.mapNotNull { byId[it]?.name }
            when {
                names.isEmpty() -> I18n.tr("无 Bot 可用", "No Bot")
                names.size <= 3 -> names.joinToString(if (I18n.lang == ChatBars.Lang.EN) ", " else "、")
                else -> {
                    val head = names.take(3).joinToString(if (I18n.lang == ChatBars.Lang.EN) ", " else "、")
                    I18n.tr("$head 等 ${names.size} 个 Bot", "$head +${names.size - 3} more")
                }
            }
        }
    }

    fun formatTime(ms: Long, now: Long, zone: ZoneId): String {
        val diff = now - ms
        if (diff in 0 until 60_000L) return I18n.tr("刚刚", "just now")
        if (diff in 60_000L until 3_600_000L) {
            val mins = diff / 60_000L
            return I18n.tr("${mins} 分钟前", "${mins}m ago")
        }
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
        val sameYear = at.year == LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).year
        val zh = I18n.lang != ChatBars.Lang.EN
        val pattern = when {
            zh && sameYear -> "M月d日 HH:mm"
            zh -> "yyyy年M月d日 HH:mm"
            sameYear -> "MMM d, HH:mm"
            else -> "MMM d, yyyy, HH:mm"
        }
        return at.format(DateTimeFormatter.ofPattern(pattern, if (zh) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH))
    }

    /** 列表行副标题：来源、更新时间与最近使用时间（绝不含值）。 */
    fun metaLine(entry: Entry, bots: List<BotRef>, now: Long, zone: ZoneId): String {
        val byId = bots.associateBy { it.id }
        val parts = mutableListOf<String>()
        val asker = if (entry.requestedBy != null) {
            byId[entry.requestedBy]?.name ?: I18n.tr("已删除的 Bot", "a deleted Bot")
        } else {
            I18n.tr("你", "you")
        }
        parts += I18n.tr("来源：$asker", "Added by $asker")
        val stamp = entry.updatedAt ?: entry.createdAt
        if (stamp != null) {
            val label = if (entry.updatedAt != null && entry.createdAt != null && entry.updatedAt > entry.createdAt) {
                I18n.tr("更新于 ${formatTime(stamp, now, zone)}", "updated ${formatTime(stamp, now, zone)}")
            } else {
                I18n.tr("添加于 ${formatTime(stamp, now, zone)}", "added ${formatTime(stamp, now, zone)}")
            }
            parts += label
        }
        parts += if (entry.lastUsedAt != null) {
            I18n.tr("最近使用 ${formatTime(entry.lastUsedAt, now, zone)}", "last used ${formatTime(entry.lastUsedAt, now, zone)}")
        } else {
            I18n.tr("尚未使用", "not used yet")
        }
        return parts.joinToString(" · ")
    }

    fun storeModeLabel(status: StoreStatus): String = when {
        status.locked -> I18n.tr("已加密（已锁定）", "Encrypted (locked)")
        status.isEncrypted -> I18n.tr("已加密（已解锁）", "Encrypted (unlocked)")
        else -> I18n.tr("明文存储", "Plaintext")
    }

    fun storeDescription(status: StoreStatus): String = when {
        status.locked -> lockedReasonText(status.reason)
        status.isEncrypted -> I18n.tr(
            "密钥在磁盘上加密保存，DS Bot 运行期间在内存中保持解锁。",
            "Keys are stored encrypted. They stay decrypted in memory while DS Bot runs.",
        )
        else -> I18n.tr(
            "密钥保存在仅当前系统用户可读的文件中，但内容未加密。",
            "Keys are stored in a file only this user can read, but the values are not encrypted.",
        )
    }

    fun lockedReasonText(reason: String?): String = when (reason) {
        REASON_SOURCE -> I18n.tr(
            "环境变量 DS_BOT_SECRETS_PASSPHRASE_FILE 指向的口令文件无法读取。",
            "The passphrase file named by DS_BOT_SECRETS_PASSPHRASE_FILE could not be read.",
        )
        REASON_WRONG -> I18n.tr(
            "环境变量或口令文件中的口令无法解锁密钥文件。",
            "The passphrase in the environment or the passphrase file did not unlock the secrets.",
        )
        REASON_FORMAT -> I18n.tr(
            "密钥文件格式不受支持或已损坏。",
            "The secrets file is damaged or not in a form this DS Bot can read.",
        )
        else -> I18n.tr(
            "启动时未找到口令。在此输入口令为当前进程解锁，或设置 DS_BOT_SECRETS_PASSPHRASE_FILE 以实现无人值守启动。",
            "No passphrase was found at startup. Enter it here, or set DS_BOT_SECRETS_PASSPHRASE_FILE for unattended starts.",
        )
    }

    // ---- 请求体 ----

    /** 请求体（DS-Bot 0.3.10 secrets.js endpoints 契约）。 */
    object Requests {
        private fun q(s: String) = JsonString.of(s)

        private fun scopeJson(scope: Scope): String = when (scope) {
            Scope.All -> "\"all\""
            is Scope.Bots -> "[" + scope.botIds.distinct().joinToString(",") { q(it) } + "]"
        }

        fun store(): String = "{}"

        /** 设置页新增密钥（secret-set）。 */
        fun setNew(name: String, value: String, purpose: String = "", scope: Scope = Scope.All): String =
            "{\"name\":${q(normalizeName(name))},\"value\":${q(value.trim())},\"purpose\":${q(purpose.trim().take(PURPOSE_MAX))},\"scope\":${scopeJson(scope)}}"

        /** 设置页替换已有密钥的值（secret-set，仅传 name 与 value，保留既有 scope 与 purpose）。 */
        fun replaceValue(name: String, value: String): String =
            "{\"name\":${q(normalizeName(name))},\"value\":${q(value.trim())}}"

        /** 修改作用 Bot 与/或用途（secret-update，不修改值）。 */
        fun update(name: String, scope: Scope? = null, purpose: String? = null): String {
            val parts = mutableListOf("\"name\":${q(normalizeName(name))}")
            if (scope != null) parts += "\"scope\":${scopeJson(scope)}"
            if (purpose != null) parts += "\"purpose\":${q(purpose.trim().take(PURPOSE_MAX))}"
            return "{${parts.joinToString(",")}}"
        }

        fun delete(name: String): String = "{\"name\":${q(normalizeName(name))}}"

        /** 口令不做 trim（空白字符有意义，与 secrets-crypto.js cleanPassphrase 一致）。 */
        fun unlock(passphrase: String): String = "{\"passphrase\":${q(passphrase)}}"
        fun encrypt(passphrase: String): String = "{\"passphrase\":${q(passphrase)}}"
        fun decrypt(passphrase: String): String = "{\"passphrase\":${q(passphrase)}}"
    }
}
