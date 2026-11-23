package work.keepwriting.dshanywhere

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** 统一触感反馈（dsbot-phone-prefs）：成功与失败可区分；须在主线程调用。 */
object Haptics {
    fun confirm(view: View) {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun reject(view: View) {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }
}
