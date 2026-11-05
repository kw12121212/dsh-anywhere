package work.keepwriting.dshanywhere

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import org.json.JSONObject
import work.keepwriting.core.UpdateCheck
import work.keepwriting.core.UpdateDownloader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自更新管理器（android-self-update）：GitHub API 匿名查询（直连不经隧道——github.com
 * 非 tailnet 目标）、断点续传下载到 filesDir/updates、.sha256 恒时校验、FileProvider
 * 调起系统安装器；每进程会话自动调起至多一次；失败静默（设置页显示原因）。
 */
class UpdateManager(context: Context) {
    enum class State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY_TO_INSTALL, FAILED }

    var state by mutableStateOf(State.IDLE)
        private set
    var availableVersion by mutableStateOf<String?>(null)
        private set
    var downloadProgress by mutableStateOf(0f)
        private set
    var failureReason by mutableStateOf("")
        private set

    private val appContext = context.applicationContext
    private val updatesDir = File(context.filesDir, "updates")
    @Volatile private var currentInfo: UpdateCheck.UpdateInfo? = null
    @Volatile private var autoPromptUsed = false
    @Volatile private var startupCheckDone = false
    /** 已打开「安装未知应用」授权页、等用户返回后调起安装器（只等一次，不循环跳转）。 */
    @Volatile private var installAfterGrant = false
    /** 授权页已覆盖本 App（onStop 之后）：只有真正从授权页返回的那次 onResume 才处理，
     *  避免 onCreate/onNewIntent 中发起跳转时 Activity 自身的 onResume 提前清掉标记。 */
    @Volatile private var installAwaitingReturn = false
    private val prefs = appContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val downloader = UpdateDownloader(updatesDir) { name ->
        "https://github.com/kw12121212/dsh-anywhere/releases/latest/download/$name"
    }

    init {
        // 已校验的更新包在进程重启后仍可安装（磁盘上的正式 APK 即已校验）。
        restoreReady()
    }

    /** 前后台标志（Activity onStart/onStop 维护）：后台调起安装器会被系统静默丢弃，
     *  必须主动分流到通知（Android 10+ 的 BAL 限制不抛异常）。 */
    @Volatile
    var appInForeground: Boolean = false

    /** 已校验、版本高于本机的更新包（读磁盘：冷启动的通知点击同样可用）。 */
    fun downloadedApk(): File? = downloader.readyApk(currentVersionCode())?.second

    /** 启动后自动检查：每个进程至多一次（Activity 重建不重复检查）。 */
    fun checkOnStartup() {
        if (startupCheckDone) return
        startupCheckDone = true
        check()
    }

    /** 检查更新（后台线程调用；下载中不重复检查，防状态覆盖与并发写文件）。 */
    fun check() {
        if (state == State.DOWNLOADING) return
        state = State.CHECKING
        Thread {
            try {
                val conn = URL(LATEST_API).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                try {
                    if (conn.responseCode != 200) {
                        if (!restoreReady()) fail("检查更新失败（HTTP ${conn.responseCode}）")
                        return@Thread
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val release = JSONObject(body)
                    val tag = release.optString("tag_name")
                    val names = release.optJSONArray("assets")?.let { arr ->
                        List(arr.length()) { arr.optJSONObject(it)?.optString("name") ?: "" }
                    } ?: emptyList()
                    val info = UpdateCheck.isNewer(tag, names, currentVersionCode())
                    if (info != null) currentInfo = info
                    val ready = downloader.readyApk(currentVersionCode())
                    when (UpdateCheck.afterCheck(info?.version, ready?.first)) {
                        UpdateCheck.CheckOutcome.READY -> restoreReady()
                        UpdateCheck.CheckOutcome.AVAILABLE -> {
                            availableVersion = info!!.version
                            state = State.AVAILABLE
                        }
                        UpdateCheck.CheckOutcome.UP_TO_DATE -> state = State.UP_TO_DATE
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                if (!restoreReady()) fail("检查更新失败（${e.javaClass.simpleName}）")
            }
        }.start()
    }

    /** 下载新版本 APK（后台线程执行；支持 Range 断点续传；已在下载则忽略）。 */
    fun download() {
        val info = currentInfo ?: return
        if (state == State.DOWNLOADING) return
        state = State.DOWNLOADING
        Thread {
            try {
                downloader.download(info) { done, total ->
                    downloadProgress = if (total > 0) done.toFloat() / total else 0f
                }
                downloadProgress = 1f
                state = State.READY_TO_INSTALL
                // 每进程会话自动调起至多一次；后台经通知（点击后调起，用户手势规避 BAL 限制）。
                if (!autoPromptUsed) {
                    autoPromptUsed = true
                    if (appInForeground) promptInstall() else notifyReady()
                }
            } catch (_: UpdateDownloader.VerificationFailed) {
                fail("校验失败：已删除下载文件，未安装")
            } catch (e: Exception) {
                fail("下载失败（${e.javaClass.simpleName}）")
            }
        }.start()
    }

    /**
     * 调起系统安装器（前台直接调起；本方法由 UI/通知点击调用）。未授权「安装未知应用」
     * 时按需打开本 App 的授权页，用户返回后由 [onResumed] 决定是否调起。
     */
    fun promptInstall() {
        val apk = downloadedApk() ?: return
        if (Build.VERSION.SDK_INT >= 26 && !appContext.packageManager.canRequestPackageInstalls()) {
            installAfterGrant = true
            installAwaitingReturn = false
            runCatching {
                appContext.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { installAfterGrant = false }
            return
        }
        val uri: Uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Activity 回到前台：从授权页返回且已授权则调起安装器；未授权只清除标记，不再跳转。 */
    fun onResumed() {
        if (!installAfterGrant || !installAwaitingReturn) return
        installAfterGrant = false
        installAwaitingReturn = false
        if (Build.VERSION.SDK_INT < 26 || appContext.packageManager.canRequestPackageInstalls()) promptInstall()
    }

    /** Activity 退到后台（onStop）：若正等待授权页返回，则下一次 onResume 即为返回。 */
    fun onLeftForeground() {
        if (installAfterGrant) installAwaitingReturn = true
    }

    /**
     * 通知权限（Android 13+）是否应在本次下载时请求：未授权且从未请求过才返回 true，
     * 并记下已请求——至多请求一次，拒绝后不再打扰。
     */
    fun claimNotificationPermissionRequest(granted: Boolean): Boolean {
        if (granted || prefs.getBoolean(PREF_NOTIFICATION_ASKED, false)) return false
        prefs.edit().putBoolean(PREF_NOTIFICATION_ASKED, true).apply()
        return true
    }

    private fun notifyReady() {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "更新", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pi = PendingIntent.getActivity(
            appContext, 0,
            Intent(appContext, MainActivity::class.java).apply { action = ACTION_INSTALL_READY },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = "新版本 ${availableVersion ?: ""} 已就绪，点击安装"
        val notification = androidx.core.app.NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DSH Anywhere 更新")
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFY_ID, notification) }
    }

    /** 磁盘上有已校验的更新包时恢复为可安装（返回是否恢复）。 */
    private fun restoreReady(): Boolean {
        val ready = downloader.readyApk(currentVersionCode()) ?: return false
        availableVersion = ready.first
        downloadProgress = 1f
        state = State.READY_TO_INSTALL
        return true
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Int =
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionCode

    private fun fail(reason: String) {
        failureReason = reason
        state = State.FAILED
    }

    companion object {
        const val LATEST_API = "https://api.github.com/repos/kw12121212/dsh-anywhere/releases/latest"
        const val CHANNEL_ID = "dsh_update"
        const val NOTIFY_ID = 4101
        const val ACTION_INSTALL_READY = "work.keepwriting.dshanywhere.INSTALL_READY"
        private const val PREF_NOTIFICATION_ASKED = "notification_permission_asked"
    }
}
