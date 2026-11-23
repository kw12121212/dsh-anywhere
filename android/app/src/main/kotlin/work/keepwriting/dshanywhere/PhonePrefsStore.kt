package work.keepwriting.dshanywhere

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import work.keepwriting.core.ChatBars
import work.keepwriting.core.I18n
import work.keepwriting.core.PhonePrefs

/**
 * 手机本机外观与语言（dsbot-phone-prefs）：独立偏好文件 phone（键 appearance/language），可观察状态供界面
 * 即时切换；core 的 I18n 经 source 读取 [lang]（读快照状态，任意线程安全）。主线程写。
 */
class PhonePrefsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("phone", Context.MODE_PRIVATE)

    var appearance by mutableStateOf(PhonePrefs.appearance(prefs.getString(KEY_APPEARANCE, null)))
        private set
    var language by mutableStateOf(PhonePrefs.language(prefs.getString(KEY_LANGUAGE, null)))
        private set

    /** 实际界面语言（跟随系统时按系统语言解析）。组合期间读取即订阅，切换时界面重组。 */
    var lang by mutableStateOf(resolve(language))
        private set

    init {
        I18n.source = { lang }
    }

    fun chooseAppearance(value: PhonePrefs.Appearance) {
        appearance = value
        prefs.edit().putString(KEY_APPEARANCE, value.stored).apply()
    }

    fun chooseLanguage(value: PhonePrefs.Language) {
        language = value
        prefs.edit().putString(KEY_LANGUAGE, value.stored).apply()
        refreshLang()
    }

    /** 系统语言可能在后台被改：回前台时重新解析（跟随系统时）。 */
    fun refreshLang() {
        lang = resolve(language)
    }

    private fun resolve(language: PhonePrefs.Language): ChatBars.Lang =
        PhonePrefs.resolve(language, java.util.Locale.getDefault().toLanguageTag())

    companion object {
        private const val KEY_APPEARANCE = "appearance"
        private const val KEY_LANGUAGE = "language"

        /** 当前存储实例（AppGraph 创建）；tr() 经它读取可观察的语言状态。 */
        @Volatile
        var current: PhonePrefsStore? = null
    }
}

/**
 * 界面文案中英文对照（同 core 的 I18n.tr）：组合期间调用即订阅可观察的语言状态，切换语言时调用处重组。
 */
fun tr(zh: String, en: String): String = I18n.tr(zh, en)
