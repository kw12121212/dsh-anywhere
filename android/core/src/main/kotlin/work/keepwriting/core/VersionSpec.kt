package work.keepwriting.core

/**
 * 版本派生约定（tech/packaging 的 package-version-single-source 扩展到 Android）：
 * versionName 直接取仓库根 package.json 的 version，versionCode 按
 * MAJOR*10000+MINOR*100+PATCH 派生。Gradle 侧（android/app/build.gradle.kts）以
 * 同一公式注入 APK；本对象与它的测试把公式语义锁进回归命令（只需 JDK 17+），
 * 防止两端漂移。
 */
object VersionSpec {
    val VERSION_PATTERN = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

    /** versionCode 派生公式：x.y.z → x*10000 + y*100 + z。 */
    fun versionCode(versionName: String): Int {
        val match = VERSION_PATTERN.find(versionName)
            ?: throw IllegalArgumentException("version 必须是 x.y.z 形式（当前：$versionName）")
        val (major, minor, patch) = match.destructured
        return major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
    }

    /**
     * 从仓库根 package.json 的原文提取 version 字段（轻量正则，不引 JSON 依赖）。
     * 测试从仓库根读真文件；生产侧 Gradle 自行解析，两端以同一正则约束格式。
     */
    fun versionFromPackageJson(raw: String): String {
        val match = Regex(""""version"\s*:\s*"([^"]+)"""").find(raw)
            ?: throw IllegalArgumentException("package.json 缺少 version 字段")
        val version = match.groupValues[1]
        require(VERSION_PATTERN.matches(version)) { "package.json 的 version 必须是 x.y.z 形式（当前：$version）" }
        return version
    }
}
