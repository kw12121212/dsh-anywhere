package work.keepwriting.core

/**
 * 敏感剪贴板清理的判断（dsbot-connectors-mail）：App 复制设备码、授权链接或回调地址后，只记内容摘要；
 * 离开过 App（onStop）再回到前台取得焦点时、或兜底定时到点时，剪贴板文本摘要仍相同就清除。
 * 读不到剪贴板（Android 10+ 无焦点时 getPrimaryClip 为 null、hasPrimaryClip 也为 false）不算结论，保留摘要待下次。
 * 只在 App 内取回焦点（关闭对话框、拉下通知栏）不清，免得用户刚复制还没来得及粘贴就被清掉。
 */
class ClipboardGuard {
    private var digest: String? = null
    private var leftSinceCopy = false

    /** 正在等待清理（有本 App 复制、尚未确认清除或被覆盖的内容）。 */
    val pending: Boolean get() = digest != null

    fun onCopy(digest: String) {
        this.digest = digest
        leftSinceCopy = false
    }

    /** Activity onStop：用户离开了 App。 */
    fun onLeave() {
        if (digest != null) leftSinceCopy = true
    }

    /** 回到前台取得焦点：[current] 为当前剪贴板文本摘要（读不到为 null）；返回是否应清除。 */
    fun onFocus(current: String?): Boolean = if (!leftSinceCopy) false else decide(current)

    /** 兜底定时到点：无论是否离开过都判断；读不到时保留。 */
    fun onTimer(current: String?): Boolean = decide(current)

    private fun decide(current: String?): Boolean {
        val want = digest ?: return false
        if (current == null) return false // 读不到：不下结论
        digest = null
        leftSinceCopy = false
        return current == want
    }
}
