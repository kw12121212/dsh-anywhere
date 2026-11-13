package work.keepwriting.core

/**
 * 自更新判定（android-self-update）：tag 解析、APK 资产选择、.sha256 解析与
 * 「是否有新版本」组合判定。全部纯函数——GitHub API 响应以 JSON 文本传入，
 * JUnit 用录制夹具覆盖。
 */
object UpdateCheck {
    /** 解析 Release tag 名：v1.2.3 → "1.2.3"；非法（非 v 前缀/非 x.y.z）返回 null。 */
    fun parseTagName(tag: String?): String? {
        if (tag == null || !tag.startsWith("v")) return null
        val version = tag.substring(1)
        return if (VersionSpec.VERSION_PATTERN.matches(version)) version else null
    }

    /** 由版本号派生 versionCode（与 android-scaffold 的注入公式一致）。 */
    fun versionCodeOf(version: String): Int? {
        if (!VersionSpec.VERSION_PATTERN.matches(version)) return null
        return try {
            VersionSpec.versionCode(version)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** 从 Release 资产名中选出与 tag 版本精确匹配的 DSH-Anywhere-<版本>.apk；无则 null。 */
    fun pickApkAsset(assetNames: List<String>, version: String): Pair<String, String>? {
        val apk = "DSH-Anywhere-$version.apk"
        return if (apk in assetNames) apk to "$apk.sha256" else null
    }

    /**
     * 解析 .sha256 文件内容（sha256sum 兼容格式 "<64位hex>  <文件名>"）；
     * 格式或长度不符返回 null。
     */
    fun parseSha256(content: String): String? {
        val hash = content.trim().split(Regex("\\s+")).firstOrNull() ?: return null
        return if (Regex("^[0-9a-fA-F]{64}$").matches(hash)) hash.lowercase() else null
    }

    /** 组合判定：最新 Release 是否含比 [currentVersionCode] 更新的可安装 APK。
     *  [manifestSha256] 非空表示自托管清单模式（latest.json 已带 sha256），下载器
     *  跳过 .sha256 资产直接按它校验。 */
    data class UpdateInfo(
        val version: String,
        val apkAsset: String,
        val sha256Asset: String,
        val versionCode: Int,
        val manifestSha256: String? = null,
    )

    /** 检查更新后的界面结论（fix-android-update-lifecycle）。 */
    enum class CheckOutcome { READY, AVAILABLE, UP_TO_DATE }

    /**
     * 合并「最新 Release 的可更新版本」[latestVersion]（无则 null）与「磁盘上已校验的
     * 更新包版本」[readyVersion]：已校验包就是最新版本或没有更新的 Release 时保持可安装
     * （再次检查不回退到可下载）；更新的 Release 取代旧的已校验包。
     */
    fun afterCheck(latestVersion: String?, readyVersion: String?): CheckOutcome = when {
        readyVersion != null && (latestVersion == null || latestVersion == readyVersion) -> CheckOutcome.READY
        latestVersion != null -> CheckOutcome.AVAILABLE
        else -> CheckOutcome.UP_TO_DATE
    }

    fun isNewer(tag: String?, assetNames: List<String>, currentVersionCode: Int): UpdateInfo? {
        val version = parseTagName(tag) ?: return null
        val code = versionCodeOf(version) ?: return null
        if (code <= currentVersionCode) return null
        val (apk, sha) = pickApkAsset(assetNames, version) ?: return null
        return UpdateInfo(version, apk, sha, code)
    }

    /**
     * 解析自托管更新清单（latest.json：version_name/version_code/sha256/size，
     * pca 的 /dsh-updates/ 契约）：版本非法、不高于本机或清单形态不符返回 null。
     * sha256 缺失或格式不符按 null 处理（下载器退回 .sha256 资产校验）。
     */
    fun fromManifest(value: Json.Value, currentVersionCode: Int): UpdateInfo? {
        val obj = value as? Json.Value.Object ?: return null
        val version = (obj.members["version_name"] as? Json.Value.StringValue)?.value ?: return null
        if (!VersionSpec.VERSION_PATTERN.matches(version)) return null
        val code = versionCodeOf(version) ?: return null
        if (code <= currentVersionCode) return null
        val sha = (obj.members["sha256"] as? Json.Value.StringValue)?.value
            ?.takeIf { Regex("^[0-9a-fA-F]{64}$").matches(it) }?.lowercase()
        return UpdateInfo(
            version = version,
            apkAsset = "DSH-Anywhere-$version.apk",
            sha256Asset = "DSH-Anywhere-$version.apk.sha256",
            versionCode = code,
            manifestSha256 = sha,
        )
    }
}
