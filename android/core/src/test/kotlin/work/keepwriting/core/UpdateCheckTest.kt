package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 自更新判定的表驱动测试（GitHub API 录制响应形态）。 */
class UpdateCheckTest {
    @Test
    fun parseTagNameTable() {
        assertEquals("1.2.3", UpdateCheck.parseTagName("v1.2.3"))
        assertNull(UpdateCheck.parseTagName("1.2.3"), "无 v 前缀非法")
        assertNull(UpdateCheck.parseTagName("v1.2"), "非 x.y.z 非法")
        assertNull(UpdateCheck.parseTagName("v1.2.3-rc1"), "预发布号非法")
        assertNull(UpdateCheck.parseTagName(null))
    }

    @Test
    fun versionCodeFormulaMatchesScaffold() {
        assertEquals(10001, UpdateCheck.versionCodeOf("1.0.1"))
        assertEquals(10203, UpdateCheck.versionCodeOf("1.2.3"))
        assertNull(UpdateCheck.versionCodeOf("dev"))
    }

    @Test
    fun pickApkAssetTable() {
        val assets = listOf("dsh-anywhere_1.0.1_windows_amd64_installer.exe", "DSH-Anywhere-1.0.1.apk", "DSH-Anywhere-1.0.1.apk.sha256")
        // 正常：与 tag 版本精确匹配。
        assertEquals("DSH-Anywhere-1.0.1.apk" to "DSH-Anywhere-1.0.1.apk.sha256", UpdateCheck.pickApkAsset(assets, "1.0.1"))
        // tag 版本与资产版本错位（发布事故形态）：不选——防止旧 APK 被当新版下载。
        assertNull(UpdateCheck.pickApkAsset(assets, "1.1.0"), "tag 与资产版本错位应视为无 APK")
        // 只有桌面安装包：无 APK 资产。
        assertNull(UpdateCheck.pickApkAsset(listOf("dsh-anywhere_1.0.1_macos_universal.dmg", "SHA256SUMS"), "1.0.1"))
        // 名字形态不符不选。
        assertNull(UpdateCheck.pickApkAsset(listOf("dsh-anywhere.apk", "DSH-Anywhere-dev.apk"), "1.0.1"))
    }

    @Test
    fun parseSha256Table() {
        val good = "a".repeat(64)
        assertEquals(good, UpdateCheck.parseSha256("$good  DSH-Anywhere-1.0.1.apk\n"))
        assertEquals(good, UpdateCheck.parseSha256(good.uppercase() + "  x.apk"))
        assertNull(UpdateCheck.parseSha256("z".repeat(63) + "  x.apk"), "63 位非法")
        assertNull(UpdateCheck.parseSha256("not a hash"), "非 hex 非法")
        assertNull(UpdateCheck.parseSha256(""))
    }

    @Test
    fun isNewerCombination() {
        val assets = listOf("DSH-Anywhere-1.1.0.apk", "DSH-Anywhere-1.1.0.apk.sha256", "SHA256SUMS")
        // 高版本：命中。
        val info = UpdateCheck.isNewer("v1.1.0", assets, currentVersionCode = 10001)
        assertEquals("1.1.0", info?.version)
        assertEquals(10100, info?.versionCode)
        // 同版本/更低：无更新。
        assertNull(UpdateCheck.isNewer("v1.1.0", assets, currentVersionCode = 10100))
        assertNull(UpdateCheck.isNewer("v1.0.0", assets, currentVersionCode = 10001))
        // 无 APK 资产：无更新。
        assertNull(UpdateCheck.isNewer("v1.2.0", listOf("dsh-anywhere_1.2.0_macos_universal.dmg"), 10001))
        // tag 非法：无更新。
        assertNull(UpdateCheck.isNewer("release-1.2.0", assets, 10001))
        assertTrue(true)
    }

    // ---- 自托管清单（latest.json，pca /dsh-updates 契约）----

    private fun manifest(json: String): Json.Value? = Json.parse(json)

    @Test
    fun fromManifestParsesValidEntry() {
        val v = manifest(
            """{"version_name":"1.4.2","version_code":10402,
               "sha256":"${"a".repeat(64)}","size":12345,"created_at":"2026-11-13T00:00:00Z"}""",
        )
        val info = UpdateCheck.fromManifest(checkNotNull(v), currentVersionCode = 10401)
        checkNotNull(info)
        assertEquals("1.4.2", info.version)
        assertEquals(10402, info.versionCode)
        assertEquals("DSH-Anywhere-1.4.2.apk", info.apkAsset)
        assertEquals("a".repeat(64), info.manifestSha256)
    }

    @Test
    fun fromManifestRejectsOlderOrInvalid() {
        val v = checkNotNull(manifest("""{"version_name":"1.4.1","version_code":10401,"sha256":"${"a".repeat(64)}"}"""))
        assertNull(UpdateCheck.fromManifest(v, currentVersionCode = 10401), "不高于本机：无更新")
        val badVersion = checkNotNull(manifest("""{"version_name":"1.x","version_code":99,"sha256":""}"""))
        assertNull(UpdateCheck.fromManifest(badVersion, 10000), "版本非法")
        assertNull(UpdateCheck.fromManifest(checkNotNull(manifest("[1]")), 10000), "非对象")
    }

    @Test
    fun fromManifestWithoutShaFallsBackToAssetMode() {
        val v = checkNotNull(manifest("""{"version_name":"1.4.2","version_code":10402}"""))
        val info = UpdateCheck.fromManifest(v, 10401)
        checkNotNull(info)
        assertNull(info.manifestSha256, "sha256 缺失/非法：退回 .sha256 资产校验")
    }
}
