package work.keepwriting.core

import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 密钥设置与落盘加密纯逻辑测试（dsbot-secrets-settings）：
 * 覆盖 state.secrets/secretStore 解码（含真实脱敏夹具 state-full.json）、名称规范化与校验、
 * 值与口令边界（含口令不做 trim）、作用 Bot 摘要与时间文案、锁定原因文案与全部请求体字面值。
 */
class SecretsSettingsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = 1_794_300_000_000L

    @AfterTest
    fun tearDown() {
        I18n.lang = ChatBars.Lang.ZH
    }

    private fun obj(json: String) = Json.parse(json) as Json.Value.Object

    @Test
    fun decodesRealStateFixtureSecretsAndMissingStoreDegrades() {
        val text = javaClass.getResource("/dsbot/state-full.json")!!.readText()
        val root = (Json.parse(text) as Json.Value.Object).members["value"] as Json.Value.Object
        assertTrue(SecretsSettings.hasSecrets(root))
        assertFalse(SecretsSettings.hasSecretStore(root))
        assertNull(SecretsSettings.storeStatus(root))
        val entries = SecretsSettings.entries(root)
        assertNotNull(entries)
        assertEquals(1, entries.size)
        val first = entries[0]
        assertTrue(first.name.isNotBlank())
        assertTrue(first.scope is SecretsSettings.Scope.Bots)
        assertEquals(listOf("session-d40fbbaa-9f4f-47c6-ae3b-a9c19aada3b3"), (first.scope as SecretsSettings.Scope.Bots).botIds)
        assertEquals("session-d40fbbaa-9f4f-47c6-ae3b-a9c19aada3b3", first.requestedBy)
        assertNotNull(first.createdAt)
        assertNotNull(first.updatedAt)
        assertNotNull(first.lastUsedAt)
    }

    @Test
    fun decodesSecretsAndStoreStatusAndReadOnly() {
        val state = obj(
            """
            {
              "readOnly": "Another DS Bot window is open",
              "bots": [
                {"id": "b1", "name": "Alice"},
                {"id": "b2", "name": "Bob"}
              ],
              "secrets": [
                {"name": "Z_KEY", "purpose": "Deploy", "scope": "all", "requestedBy": null, "createdAt": 1794299980000, "updatedAt": 1794299980000},
                {"name": "A_KEY", "purpose": "Repo", "scope": ["b1", "b2", "b1"], "requestedBy": "b1", "createdAt": 1794299000000, "updatedAt": 1794299500000, "lastUsedAt": 1794299940000}
              ],
              "secretStore": {"mode": "encrypted", "locked": true, "source": null, "reason": "missing-passphrase"}
            }
            """.trimIndent(),
        )
        assertTrue(SecretsSettings.hasSecrets(state))
        assertTrue(SecretsSettings.hasSecretStore(state))
        assertEquals("Another DS Bot window is open", SecretsSettings.readOnlyReason(state))

        val entries = SecretsSettings.entries(state)!!
        assertEquals(listOf("A_KEY", "Z_KEY"), entries.map { it.name })
        assertEquals(SecretsSettings.Scope.Bots(listOf("b1", "b2")), entries[0].scope)
        assertEquals(SecretsSettings.Scope.All, entries[1].scope)

        val store = SecretsSettings.storeStatus(state)!!
        assertEquals("encrypted", store.mode)
        assertTrue(store.isEncrypted)
        assertTrue(store.locked)
        assertNull(store.source)
        assertEquals("missing-passphrase", store.reason)

        val bots = SecretsSettings.bots(state)
        assertEquals("Alice、Bob", SecretsSettings.scopeSummary(entries[0].scope, bots))
        assertEquals("所有 Bot", SecretsSettings.scopeSummary(entries[1].scope, bots))
        assertEquals("无 Bot 可用", SecretsSettings.scopeSummary(SecretsSettings.Scope.Bots(emptyList()), bots))

        val meta0 = SecretsSettings.metaLine(entries[0], bots, now, zone)
        assertTrue(meta0.contains("Alice"))
        assertTrue(meta0.contains("更新于"))
        assertTrue(meta0.contains("最近使用"))

        val meta1 = SecretsSettings.metaLine(entries[1], bots, now, zone)
        assertTrue(meta1.contains("来源：你"))
        assertTrue(meta1.contains("添加于 刚刚"))
        assertTrue(meta1.contains("尚未使用"))
    }

    @Test
    fun missingSecretsReturnsNull() {
        val state = obj("""{"bots":[]}""")
        assertFalse(SecretsSettings.hasSecrets(state))
        assertNull(SecretsSettings.entries(state))
        assertFalse(SecretsSettings.hasSecretStore(state))
        assertNull(SecretsSettings.storeStatus(state))
        assertNull(SecretsSettings.readOnlyReason(state))
    }

    @Test
    fun normalizesAndValidatesName() {
        assertEquals("GITHUB_TOKEN", SecretsSettings.normalizeName("  github-token  "))
        assertEquals("MY_KEY", SecretsSettings.normalizeName("\$DSH_SECRET_my key"))
        assertEquals("FOO_BAR", SecretsSettings.normalizeName("\$env:DSH_SECRET_foo-bar"))
        assertNull(SecretsSettings.nameProblem("github-token"))
        assertNull(SecretsSettings.nameProblem("\$DSH_SECRET_API_2"))
        assertNotNull(SecretsSettings.nameProblem("   "))
        assertNotNull(SecretsSettings.nameProblem("123_BAD"))
        assertNotNull(SecretsSettings.nameProblem("BAD!NAME"))
        assertNotNull(SecretsSettings.nameProblem("A" + "B".repeat(64)))
        assertNull(SecretsSettings.nameProblem("A" + "B".repeat(63)))
    }

    @Test
    fun validatesValueAndPassphraseBoundaries() {
        assertNotNull(SecretsSettings.valueProblem(""))
        assertNotNull(SecretsSettings.valueProblem("   abc   "))
        assertNull(SecretsSettings.valueProblem("  abcd  "))
        assertNull(SecretsSettings.valueProblem("x".repeat(SecretsSettings.MAX_VALUE)))
        assertNotNull(SecretsSettings.valueProblem("x".repeat(SecretsSettings.MAX_VALUE + 1)))

        // 口令不做 trim：含首尾空格的 8 位口令有效，7 位无效
        assertNotNull(SecretsSettings.passphraseProblem(""))
        assertNotNull(SecretsSettings.passphraseProblem("1234567"))
        assertNull(SecretsSettings.passphraseProblem("  1234  "))
        assertNull(SecretsSettings.passphraseProblem("p".repeat(SecretsSettings.MAX_PASSPHRASE)))
        assertNotNull(SecretsSettings.passphraseProblem("p".repeat(SecretsSettings.MAX_PASSPHRASE + 1)))

        assertNotNull(SecretsSettings.encryptProblem("pass1234", "pass1235"))
        assertNull(SecretsSettings.encryptProblem("pass1234", "pass1234"))
        assertTrue(SecretsSettings.canSaveNew("api_key", "1234"))
        assertFalse(SecretsSettings.canSaveNew("123", "1234"))
    }

    @Test
    fun buildsExactRequestPayloads() {
        val R = SecretsSettings.Requests
        assertEquals("{}", R.store())
        assertEquals(
            """{"name":"GITHUB_TOKEN","value":"ghp_1234","purpose":"CI access","scope":"all"}""",
            R.setNew("github-token", "  ghp_1234  ", "  CI access  "),
        )
        assertEquals(
            """{"name":"API_KEY","value":"abcd","purpose":"","scope":["b1","b2"]}""",
            R.setNew("api_key", "abcd", "", SecretsSettings.Scope.Bots(listOf("b1", "b2", "b1"))),
        )
        assertEquals(
            """{"name":"GITHUB_TOKEN","value":"newval99"}""",
            R.replaceValue("github_token", "  newval99 "),
        )
        assertEquals(
            """{"name":"GITHUB_TOKEN","scope":["b1"]}""",
            R.update("github_token", scope = SecretsSettings.Scope.Bots(listOf("b1"))),
        )
        assertEquals(
            """{"name":"GITHUB_TOKEN","purpose":"New purpose"}""",
            R.update("github_token", purpose = "  New purpose  "),
        )
        assertEquals("""{"name":"GITHUB_TOKEN"}""", R.delete("github_token"))
        // 口令保留首尾空白
        assertEquals("""{"passphrase":"  pass 123  "}""", R.unlock("  pass 123  "))
        assertEquals("""{"passphrase":"  pass 123  "}""", R.encrypt("  pass 123  "))
        assertEquals("""{"passphrase":"  pass 123  "}""", R.decrypt("  pass 123  "))
    }

    @Test
    fun sameScopeAndStoreLabelsInBothLanguages() {
        assertTrue(SecretsSettings.sameScope(SecretsSettings.Scope.All, true, emptySet()))
        assertFalse(SecretsSettings.sameScope(SecretsSettings.Scope.All, false, emptySet()))
        assertTrue(SecretsSettings.sameScope(SecretsSettings.Scope.Bots(listOf("a", "b")), false, setOf("b", "a")))
        assertFalse(SecretsSettings.sameScope(SecretsSettings.Scope.Bots(listOf("a")), false, setOf("a", "b")))

        val plain = SecretsSettings.StoreStatus("plaintext", false, null, null)
        val enc = SecretsSettings.StoreStatus("encrypted", false, "user", null)
        val locked = SecretsSettings.StoreStatus("encrypted", true, null, "wrong-passphrase")

        assertEquals("明文存储", SecretsSettings.storeModeLabel(plain))
        assertEquals("已加密（已解锁）", SecretsSettings.storeModeLabel(enc))
        assertEquals("已加密（已锁定）", SecretsSettings.storeModeLabel(locked))
        assertTrue(SecretsSettings.storeDescription(locked).contains("无法解锁"))

        I18n.lang = ChatBars.Lang.EN
        assertEquals("Plaintext", SecretsSettings.storeModeLabel(plain))
        assertEquals("Encrypted (unlocked)", SecretsSettings.storeModeLabel(enc))
        assertEquals("Encrypted (locked)", SecretsSettings.storeModeLabel(locked))
        assertTrue(SecretsSettings.lockedReasonText("passphrase-source").contains("DS_BOT_SECRETS_PASSPHRASE_FILE"))
        assertTrue(SecretsSettings.lockedReasonText("format").contains("damaged"))
    }
}
