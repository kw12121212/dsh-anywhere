package work.keepwriting.core

/**
 * 团队偏好（dsbot-prefs-theme）：state.prefs 解码与缺省、网页端主题配色表、强调色与深色映射、set-prefs 请求体。
 * 缺省与配色复刻 ds-bot 0.3.10 的 channel.js setPrefs 与 client themes.js / inks.js（tech/dsbot-api 的 team-prefs-0310）。
 * 明暗由本机外观决定，团队偏好只决定配色。
 */
object TeamPrefs {
    data class Prefs(
        /** 主题标识（未知标识原样保留，配色回退 deepseek）。 */
        val theme: String,
        /** #rrggbb；null 为主题默认。 */
        val accent: String?,
        val layout: String,
        val motion: String,
        val enterToSend: Boolean,
        val defaultBotModel: String?,
    ) {
        val flat: Boolean get() = layout == "flat"
        val quiet: Boolean get() = motion == "quiet"
    }

    val DEFAULT = Prefs("deepseek", null, "bubble", "normal", true, null)

    /** 一套配色（0xRRGGBB）。 */
    data class Palette(val bg: Int, val bubble: Int, val card: Int, val text: Int, val user: Int, val userText: Int, val accent: Int)

    private data class Theme(val label: String, val light: Palette, val dark: Palette)

    private fun hex(s: String): Int = s.removePrefix("#").toInt(16)
    private fun pal(bg: String, bubble: String, card: String, text: String, user: String, userText: String, accent: String) =
        Palette(hex(bg), hex(bubble), hex(card), hex(text), hex(user), hex(userText), hex(accent))

    /** 网页端 themes.js 的四套主题（sidebar 不用于手机）。 */
    private val THEMES: Map<String, Theme> = linkedMapOf(
        "deepseek" to Theme(
            "DeepSeek",
            pal("#fcfcfc", "#eeeeee", "#ffffff", "#141414", "#070707", "#fcfcfc", "#4d6bfe"),
            pal("#141414", "#262626", "#1f1f1f", "#f2f2f2", "#f2f2f2", "#141414", "#5b7bff"),
        ),
        "mono" to Theme(
            "Mono",
            pal("#ffffff", "#f0f0f0", "#ffffff", "#0d0d0d", "#0d0d0d", "#ffffff", "#0d0d0d"),
            pal("#0f0f0f", "#242424", "#1c1c1c", "#f4f4f4", "#f4f4f4", "#0f0f0f", "#f4f4f4"),
        ),
        "paper" to Theme(
            "Paper",
            pal("#faf9f5", "#ebe7dc", "#ffffff", "#1f1e1b", "#2b2a26", "#faf9f5", "#d97757"),
            pal("#1f1e1b", "#34322d", "#2a2925", "#f3f0e8", "#f3f0e8", "#1f1e1b", "#e08a6d"),
        ),
        "moonlight" to Theme(
            "Moonlight",
            pal("#f7f9fc", "#e5ebf5", "#ffffff", "#121826", "#1e3a8a", "#ffffff", "#1e86ff"),
            pal("#0b1020", "#1a2340", "#141c33", "#e8edf7", "#3b6fd8", "#ffffff", "#4f9dff"),
        ),
    )

    val THEME_IDS: List<String> get() = THEMES.keys.toList()

    fun themeLabel(id: String): String = THEMES[id]?.label ?: id

    /** 网页端 ACCENT_INKS：(名称, 浅色首色, 深色第二色)。 */
    val ACCENTS: List<Triple<String, String, String>> = listOf(
        Triple("deepseek", "#4D6BFE", "#6A84FF"),
        Triple("charcoal", "#141416", "#F1F2F4"),
        Triple("graphite", "#2D2D2D", "#D6D6DA"),
        Triple("rose", "#EC2F6B", "#FF5A7A"),
        Triple("sky", "#0AA8D6", "#2EE6E0"),
        Triple("tangerine", "#FF6900", "#FF8A2E"),
        Triple("cobalt", "#082DFF", "#5C78FF"),
    )

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value

    /** 从 state 解码（state.prefs 只含与默认不同的键）；没有 state 时为 null。 */
    fun decode(state: Json.Value.Object?): Prefs? {
        state ?: return null
        val p = state.members["prefs"] as? Json.Value.Object ?: return DEFAULT
        return Prefs(
            theme = p.str("theme")?.takeIf { it.isNotBlank() } ?: DEFAULT.theme,
            accent = p.str("accent")?.takeIf { Regex("^#[0-9a-fA-F]{6}$").matches(it) },
            layout = if (p.str("layout") == "flat") "flat" else "bubble",
            motion = p.str("motion")?.takeIf { it == "quiet" || it == "lively" } ?: "normal",
            enterToSend = (p.members["enterToSend"] as? Json.Value.BoolValue)?.value != false,
            defaultBotModel = p.str("defaultBotModel"),
        )
    }

    /**
     * 配色：按本机明暗取主题对应一组（未知主题回退 deepseek）；有强调色时覆盖，深色下 ACCENT_INKS 首色映射为第二色
     *（网页端 darkAccent），其他自定义颜色两种明暗都原样用。
     */
    fun palette(prefs: Prefs, dark: Boolean): Palette {
        val theme = THEMES[prefs.theme] ?: THEMES.getValue("deepseek")
        val base = if (dark) theme.dark else theme.light
        val accent = prefs.accent ?: return base
        // 严格相等（与网页端 darkAccent 的 === 一致）：取色器得到的小写值算自定义色，深色下原样显示
        val mapped = if (dark) ACCENTS.firstOrNull { it.second == accent }?.third ?: accent else accent
        return base.copy(accent = hex(mapped))
    }

    /** 网页端的深色墨（accentInk 的 DARK_INK）。 */
    const val DARK_INK = 0x141414

    /** WCAG 相对亮度（与网页端 luminance 相同）。 */
    fun luminance(rgb: Int): Double = listOf(16, 8, 0)
        .map { ((rgb shr it) and 0xFF) / 255.0 }
        .map { if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        .let { 0.2126 * it[0] + 0.7152 * it[1] + 0.0722 * it[2] }

    /** 强调色上的文字色：与深色墨、白色中对比度较高者（照搬网页端 accentInk）。返回 0xRRGGBB。 */
    fun inkOnAccent(rgb: Int): Int {
        val l = luminance(rgb) + 0.05
        return if (l * l > 1.05 * (luminance(DARK_INK) + 0.05)) DARK_INK else 0xFFFFFF
    }

    /** 预设强调色（ACCENTS 首色严格相等）；网页端取色器选的其他颜色为 null（自定义）。 */
    fun presetAccent(accent: String?): String? = ACCENTS.firstOrNull { it.second == accent }?.first

    fun accentLabel(name: String): String = when (name) {
        "deepseek" -> "DeepSeek"
        "charcoal" -> I18n.tr("炭黑", "Charcoal")
        "graphite" -> I18n.tr("石墨", "Graphite")
        "rose" -> I18n.tr("玫红", "Rose")
        "sky" -> I18n.tr("天蓝", "Sky")
        "tangerine" -> I18n.tr("橘橙", "Tangerine")
        "cobalt" -> I18n.tr("钴蓝", "Cobalt")
        else -> name
    }

    fun motionLabel(m: String): String = when (m) {
        "quiet" -> I18n.tr("安静", "Quiet")
        "lively" -> I18n.tr("活泼", "Lively")
        else -> I18n.tr("普通", "Normal")
    }

    fun layoutLabel(l: String): String = if (l == "flat") I18n.tr("平铺", "Flat") else I18n.tr("气泡", "Bubbles")

    object Requests {
        private fun q(s: String) = JsonString.of(s)
        fun theme(id: String) = "{\"theme\":" + q(id) + "}"
        /** null = 主题默认（清除）。 */
        fun accent(hex: String?) = "{\"accent\":" + (hex?.let(::q) ?: "null") + "}"
        fun layout(l: String) = "{\"layout\":" + q(l) + "}"
        fun motion(m: String) = "{\"motion\":" + q(m) + "}"
        fun enterToSend(on: Boolean) = "{\"enterToSend\":$on}"
        /** null = 跟随 DSH 默认（清除）。 */
        fun defaultBotModel(ref: String?) = "{\"defaultBotModel\":" + (ref?.let(::q) ?: "null") + "}"
    }
}
