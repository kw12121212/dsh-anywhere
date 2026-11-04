package work.keepwriting.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 共享测试向量驱动：读仓库根 testdata/harness-url-vectors.json，逐条断言校验结果、
 * 错误类别与剥离后地址。与桌面端 Vitest 读同一份文件（harness-url-config 平台无关，
 * 两端无差异项）。
 */
class HarnessUrlTest {
    private fun vectors(): Json.Value.Object {
        val file = File("..", "..").resolve("testdata/harness-url-vectors.json")
        assertTrue(file.isFile, "找不到共享向量文件：${file.absolutePath}")
        return Json.parse(file.readText()) as? Json.Value.Object
            ?: fail("共享向量文件不是合法 JSON 对象")
    }

    private fun members(value: Json.Value): Map<String, Json.Value> = (value as Json.Value.Object).members

    private fun string(value: Json.Value): String = (value as Json.Value.StringValue).value

    @Test
    fun normalizeVectors() {
        val cases = vectors().members["normalize"] as Json.Value.Array
        assertTrue(cases.items.size >= 15, "normalize 向量应覆盖主要形态，当前 ${cases.items.size} 条")
        for ((index, item) in cases.items.withIndex()) {
            val m = members(item)
            val input = string(m["input"]!!)
            val valid = (m["valid"] as Json.Value.BoolValue).value
            if (valid) {
                val expected = string(m["stripped"]!!)
                val actual = try {
                    HarnessUrl.normalize(input)
                } catch (e: HarnessUrl.ValidationError) {
                    fail("向量 #$index 输入 $input 应合法，却得到 ${e.category}")
                }
                assertEquals(expected, actual, "向量 #$index 输入 $input 的剥离结果")
            } else {
                val expected = when (val category = string(m["errorCategory"]!!)) {
                    "empty" -> HarnessUrl.ErrorCategory.EMPTY
                    "malformed" -> HarnessUrl.ErrorCategory.MALFORMED
                    "notAbsolute" -> HarnessUrl.ErrorCategory.NOT_ABSOLUTE
                    "unsupportedScheme" -> HarnessUrl.ErrorCategory.UNSUPPORTED_SCHEME
                    else -> fail("未知错误类别：$category")
                }
                try {
                    HarnessUrl.normalize(input)
                    fail("向量 #$index 输入 $input 应以 $expected 拒绝，却成功了")
                } catch (e: HarnessUrl.ValidationError) {
                    assertEquals(expected, e.category, "向量 #$index 输入 $input 的错误类别")
                }
            }
        }
    }

    @Test
    fun redactStripsTokenAndHidesPassword() {
        assertEquals("https://dsh.example.test/", HarnessUrl.redact("https://dsh.example.test/?token=abc123"))
        assertEquals("https://user:xxxxx@dsh.example.test/", HarnessUrl.redact("https://user:secret@dsh.example.test/"))
        assertEquals("<无法解析的地址>", HarnessUrl.redact("not a url token=abc123"))
    }
}

/** 读取三态与原子写入的向量与行为测试。 */
class HarnessConfigTest {
    @Test
    fun readConfigVectors() {
        val vectorFile = File("..", "..").resolve("testdata/harness-url-vectors.json")
        val cases = (Json.parse(vectorFile.readText()) as Json.Value.Object)
            .members["readConfig"] as Json.Value.Array
        assertTrue(cases.items.size >= 6, "readConfig 向量应覆盖三态，当前 ${cases.items.size} 条")
        for ((index, item) in cases.items.withIndex()) {
            val m = (item as Json.Value.Object).members
            val dir = kotlin.io.path.createTempDirectory("dsh-config-vector-")
            try {
                val config = HarnessConfig(dir)
                val content = m["fileContent"]
                if (content != null && content != Json.Value.NullValue) {
                    dir.resolve("config.json").toFile().writeText((content as Json.Value.StringValue).value)
                }
                val expectedStatus = (m["expectedStatus"] as Json.Value.StringValue).value
                val load = config.load()
                when (expectedStatus) {
                    "missing" -> assertEquals(HarnessConfig.Load.Missing, load, "向量 #$index")
                    "invalid" -> assertEquals(HarnessConfig.Load.Invalid, load, "向量 #$index")
                    "ok" -> {
                        val url = (load as? HarnessConfig.Load.Ok)?.url
                            ?: fail("向量 #$index 应为 Ok，实际 $load")
                        assertEquals((m["expectedUrl"] as Json.Value.StringValue).value, url, "向量 #$index")
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun saveIsAtomicAndRoundTrips() {
        val dir = kotlin.io.path.createTempDirectory("dsh-config-save-")
        try {
            val config = HarnessConfig(dir)
            assertEquals(HarnessConfig.Load.Missing, config.load())
            val clean = config.save("http://127.0.0.1:13080/?token=abc123")
            assertEquals("http://127.0.0.1:13080/", clean)
            assertEquals(HarnessConfig.Load.Ok("http://127.0.0.1:13080/"), config.load())
            // 只存干净地址：文件内容不含 token；字节形态与桌面 saveConfig 一致
            // （JSON.stringify(obj, null, 2) + 换行，2 空格缩进多行）。
            val content = dir.resolve("config.json").toFile().readText()
            assertTrue(!content.contains("abc123"), "配置文件不得包含 token：$content")
            assertEquals("{\n  \"harnessURL\": \"http://127.0.0.1:13080/\"\n}\n", content)
            // 原子写入不残留临时文件。
            val leftovers = dir.toFile().listFiles()?.filter { it.name.startsWith(HarnessConfig.TMP_PREFIX) } ?: emptyList()
            assertTrue(leftovers.isEmpty(), "不应残留临时文件：$leftovers")
            // 覆盖保存。
            config.save("https://b.example.test/")
            assertEquals(HarnessConfig.Load.Ok("https://b.example.test/"), config.load())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun pathologicalDeepNestingIsInvalidNotCrash() {
        val dir = kotlin.io.path.createTempDirectory("dsh-config-deep-")
        try {
            val config = HarnessConfig(dir)
            // 超过 Json.MAX_DEPTH 的深嵌套：解析失败 → Invalid，绝不抛 StackOverflowError。
            val deep = "{\"a\":".repeat(Json.MAX_DEPTH + 8) + "1" + "}".repeat(Json.MAX_DEPTH + 8)
            dir.resolve("config.json").toFile().writeText(deep)
            assertEquals(HarnessConfig.Load.Invalid, config.load())
            // 深度内的合法嵌套正常解析（对象数组各若干层）。
            dir.resolve("config.json").toFile().writeText(
                "{\"harnessURL\": \"https://dsh.example.test/\", \"deep\": ${"[1,".repeat(64)}1${"]".repeat(64)}}"
            )
            assertEquals(HarnessConfig.Load.Ok("https://dsh.example.test/"), config.load())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun jsonNumberGrammarMatchesJsonParse() {
        // 与 JSON.parse 一致拒绝：裸负号、前导零、缺小数/指数数字。
        for (bad in listOf("\"x\": -", "\"x\": 01", "\"x\": 1.", "\"x\": 1.5e", "\"x\": -.5")) {
            val doc = "{$bad}"
            assertEquals(null, Json.parse(doc), "应拒绝：$doc")
        }
        for (good in listOf("\"x\": 0", "\"x\": -3", "\"x\": 1.25", "\"x\": 1.5e10", "\"x\": 2E-4")) {
            val doc = "{$good}"
            assertTrue(Json.parse(doc) != null, "应接受：$doc")
        }
    }

    @Test
    fun saveRejectsInvalidWithoutTouchingExisting() {
        val dir = kotlin.io.path.createTempDirectory("dsh-config-invalid-")
        try {
            val config = HarnessConfig(dir)
            config.save("https://a.example.test/")
            try {
                config.save("ftp://dsh.example.test/")
                fail("非法地址应被拒绝")
            } catch (_: HarnessUrl.ValidationError) {
            }
            assertEquals(HarnessConfig.Load.Ok("https://a.example.test/"), config.load())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
