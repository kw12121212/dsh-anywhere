package work.keepwriting.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Android API 级别纪律守卫（lesson: urldecoder-writestring-ap33）：core 是纯 JVM 模块，
 * 没有 Android lint 的 API 级别检查，而它会被打进 APK 在 minSdk 26 的设备上运行——
 * Java 9+/11+/16+ 的 JDK 便捷方法（URLDecoder/URLEncoder 的 Charset 重载、Files.writeString、
 * Stream.toList 等）在 compileSdk/JDK 17 下编译通过、宿主 JVM 测试全绿，却在 API 33/34 以下
 * 真机直接 NoSuchMethodError（1.2.0 在 API 31 华为真机粘贴带 ?token 地址即崩的根因）。
 * 本测试扫描 main 源码集，出现禁止形态即失败，把这类缺口挡在 JVM 测试层。
 */
class ApiLevelDisciplineTest {
    /** 每条规则：违规形态的正则 + 说明。命中即报出文件与行。 */
    private val rules = listOf(
        Regex("""(URLDecoder\.decode|URLEncoder\.encode)\([^)]*Charsets\.""") to
            "URLDecoder/URLEncoder 的 (String, Charset) 重载是 Java 10 / API 33+，用 (String, \"UTF-8\") 老重载",
        Regex("""Files\.(writeString|readString)\(""") to
            "Files.writeString/readString 是 Java 11 / API 33+，用 kotlin.io.path.writeText/readText（走 newInputStream/newOutputStream，API 26+）",
        Regex("""\.(readNBytes|readAllBytes|transferTo)\(""") to
            "InputStream/Reader 的这些方法是 Java 9 / API 33+，手写 read 循环或用 Kotlin 的 readBytes/readText",
        Regex("""(?<![A-Za-z])(List|Map|Set)\.of\(""") to
            "List.of/Map.of/Set.of 是 Java 9 / API 33+，用 Kotlin 的 listOf/mapOf/setOf",
    )

    /** Stream.toList() 是 Java 16 / API 34+（Collectors.toList() 是 API 24+，放行）。 */
    private val streamToListRule = Regex("""(?<!Collectors)\.toList\(\)""") to
        "java.util.stream.Stream.toList() 是 Java 16 / API 34+，用 collect(Collectors.toList())"

    /** 定位 main 源码集根：从测试类自身的编译输出位置（…/core/build/classes/…）向上
     *  回到模块根再进 src/main/kotlin，不依赖测试工作目录。 */
    private fun mainRoot(): File {
        val codeSource = ApiLevelDisciplineTest::class.java.protectionDomain.codeSource.location
        var dir = File(codeSource.toURI()).parentFile // …/kotlin/test 或 …/java/test
        while (dir != null && dir.name != "build") dir = dir.parentFile
        val moduleRoot = dir?.parentFile
        val root = moduleRoot?.resolve("src/main/kotlin")
        if (root != null && root.isDirectory) return root
        // 回退：按工作目录向上探测（android 根或 core 根）
        var cur: File? = File(System.getProperty("user.dir")!!)
        repeat(4) {
            for (candidate in listOf(File(cur, "src/main/kotlin"), File(cur, "core/src/main/kotlin"))) {
                if (candidate.isDirectory) return candidate
            }
            cur = cur?.parentFile
        }
        error("找不到 src/main/kotlin（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun mainSources(): Sequence<File> =
        mainRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }

    private fun checkAll(rule: Pair<Regex, String>, files: Sequence<Pair<String, List<String>>>): List<String> =
        files.flatMap { (name, lines) ->
            lines.mapIndexedNotNull { i, line ->
                // 注释行跳过：说明文字里出现被禁 API 名是正常且必要的（含本守卫的提示语本身）
                val trimmed = line.trim()
                val isComment = trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
                if (!isComment && rule.first.containsMatchIn(line)) {
                    "$name:${i + 1}: ${rule.second}（${trimmed.take(80)}）"
                } else null
            }
        }.toList()

    @Test
    fun `main 源码不出现高于 minSdk 26 的 JDK 便捷方法`() {
        val root = mainRoot()
        val sources = mainSources().map { it.path to it.readLines() }.toList()
        assertTrue(sources.isNotEmpty(), "应能找到 main 源码集（root=$root）")
        val offenders = rules.flatMap { checkAll(it, sources.asSequence()) } +
            checkAll(streamToListRule, sources.asSequence().filter { (name, _) ->
                // 只对确实在用 java.util.stream 的文件检查 Stream.toList（Kotlin 集合的 toList() 合法）
                File(name).readText().contains("java.util.stream")
            })
        assertTrue(offenders.isEmpty(), "发现 Android API 级别违规：\n${offenders.joinToString("\n")}")
    }

    @Test
    fun `守卫规则自检：逐条命中样本且不误报合法形态`() {
        val samples = mapOf(
            rules[0].first to listOf(
                "URLDecoder.decode(k, Charsets.UTF_8)" to true,
                "URLEncoder.encode(s, Charsets.UTF_8)" to true,
                """URLDecoder.decode(k, "UTF-8")""" to false,
                """URLEncoder.encode(s, "UTF-8")""" to false,
            ),
            rules[1].first to listOf(
                "Files.writeString(p, s)" to true,
                "Files.readString(p)" to true,
                "p.writeText(s)" to false,
                "p.readText()" to false,
            ),
            rules[2].first to listOf(
                "in.readNBytes(10)" to true,
                "in.transferTo(out)" to true,
                "in.read(buf)" to false,
                "in.readBytes()" to false,
            ),
            rules[3].first to listOf(
                "List.of(1, 2)" to true,
                "Map.of(\"a\", 1)" to true,
                "listOf(1, 2)" to false,
                "mapOf(\"a\" to 1)" to false,
                // Kotlin 扩展函数定义处的合法形态
                "fun <T> List<T>.of() = 1" to false,
            ),
            streamToListRule.first to listOf(
                "stream.toList()" to true,
                "collect(Collectors.toList())" to false,
                // 正则层面任何 .toList() 都命中；「只对 import java.util.stream 的文件生效」
                // 的门控在扫描侧（main 源码检查里按文件过滤），Kotlin 集合的 toList() 由此放行
                "liveInFlight.toList()" to true,
            ),
        )
        (rules + streamToListRule).forEach { (pattern, _) ->
            samples.getValue(pattern).forEach { (line, expectHit) ->
                val hit = pattern.containsMatchIn(line)
                assertTrue(
                    hit == expectHit,
                    "守卫规则误判：『$line』期望命中=$expectHit 实际=$hit（规则 ${pattern.pattern}）",
                )
            }
        }
    }
}
