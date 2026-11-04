package work.keepwriting.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 版本派生公式的门禁测试：读仓库根 package.json，断言 versionName 合法且
 * versionCode 与公式一致——android/app 的 Gradle 注入与这里必须同源同一公式。
 */
class VersionSpecTest {
    private val repoRootPackageJson: File
        get() = File("..", "..").resolve("package.json")

    @Test
    fun versionCodeFormula() {
        assertEquals(10000, VersionSpec.versionCode("1.0.0"))
        assertEquals(10001, VersionSpec.versionCode("1.0.1"))
        assertEquals(21307, VersionSpec.versionCode("2.13.7"))
        assertEquals(30000, VersionSpec.versionCode("3.0.0"))
    }

    @Test
    fun versionCodeRejectsNonXyz() {
        assertFailsWith<IllegalArgumentException> { VersionSpec.versionCode("dev") }
        assertFailsWith<IllegalArgumentException> { VersionSpec.versionCode("1.0") }
        assertFailsWith<IllegalArgumentException> { VersionSpec.versionCode("1.0.0.0") }
        assertFailsWith<IllegalArgumentException> { VersionSpec.versionCode("1.0.0-rc1") }
    }

    @Test
    fun packageJsonVersionDerivesConsistently() {
        val raw = repoRootPackageJson.readText()
        val versionName = VersionSpec.versionFromPackageJson(raw)
        assertTrue(VersionSpec.VERSION_PATTERN.matches(versionName), "versionName 应为 x.y.z：$versionName")
        assertEquals(versionName, VersionSpec.versionFromPackageJson(raw), "同一文件应得到同一 version")
        assertEquals(
            VersionSpec.versionCode(versionName),
            versionName.split(".").let { (maj, min, pat) -> maj.toInt() * 10000 + min.toInt() * 100 + pat.toInt() },
            "versionCode 应等于 MAJOR*10000+MINOR*100+PATCH",
        )
    }

    @Test
    fun versionFromPackageJsonRejectsMalformed() {
        assertFailsWith<IllegalArgumentException> { VersionSpec.versionFromPackageJson("{}") }
        assertFailsWith<IllegalArgumentException> { VersionSpec.versionFromPackageJson("""{"version":"dev"}""") }
    }
}
