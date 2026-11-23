package work.keepwriting.core

/**
 * 界面语言（dsbot-phone-prefs）：core 里面向界面的文案经 [tr] 输出 [lang] 对应的语言。只覆盖界面文案；
 * 诊断报告、日志与底层错误细节不在此列。
 *
 * App 经 [source] 注入语言来源（其可观察状态）：界面组合期间调用 core 文案即读取该状态、随之订阅，
 * 切换语言时只重组用到文案的界面，不必整体重建。未注入时用 [lang] 的设定值（JVM 测试）。
 */
object I18n {
    @Volatile
    var source: (() -> ChatBars.Lang)? = null

    @Volatile
    private var fixed: ChatBars.Lang = ChatBars.Lang.ZH

    var lang: ChatBars.Lang
        get() = source?.invoke() ?: fixed
        set(value) {
            fixed = value
        }

    fun tr(zh: String, en: String): String = if (lang == ChatBars.Lang.EN) en else zh
}

/** 手机本机偏好（dsbot-phone-prefs）：外观与语言的枚举、存储值解析与实际语言解析。 */
object PhonePrefs {
    enum class Appearance(val stored: String) { SYSTEM("system"), LIGHT("light"), DARK("dark") }

    enum class Language(val stored: String) { SYSTEM("system"), ZH("zh"), EN("en") }

    /** 存储值到外观；缺失或未知值回退跟随系统。 */
    fun appearance(stored: String?): Appearance = Appearance.entries.firstOrNull { it.stored == stored } ?: Appearance.SYSTEM

    /** 存储值到语言；缺失或未知值回退跟随系统。 */
    fun language(stored: String?): Language = Language.entries.firstOrNull { it.stored == stored } ?: Language.SYSTEM

    /** 实际界面语言：跟随系统时系统语言以 zh 开头为中文，其余为英文。 */
    fun resolve(language: Language, systemTag: String?): ChatBars.Lang = when (language) {
        Language.ZH -> ChatBars.Lang.ZH
        Language.EN -> ChatBars.Lang.EN
        Language.SYSTEM -> if (systemTag?.lowercase()?.startsWith("zh") == true) ChatBars.Lang.ZH else ChatBars.Lang.EN
    }

    /** 是否深色：跟随系统时取系统深色状态。 */
    fun isDark(appearance: Appearance, systemDark: Boolean): Boolean = when (appearance) {
        Appearance.SYSTEM -> systemDark
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
}
