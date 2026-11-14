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
import javax.net.ssl.HttpsURLConnection
import work.keepwriting.core.UpdateCheck
import work.keepwriting.core.UpdateDownloader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自更新管理器（android-self-update）：默认 GitHub API 匿名查询（直连不经隧道——
 * github.com 非 tailnet 目标）；配置了「应用更新源」后改走自托管 latest.json 契约
 *（tailnet 目标经内嵌节点，GitHub 网络不通的设备由自有服务器分发）。断点续传下载到
 * filesDir/updates、清单或 .sha256 恒时校验、FileProvider 调起系统安装器；每进程会话
 * 自动调起至多一次；失败静默（设置页显示原因）。
 */
class UpdateManager(
    context: Context,
    /** 与主连接同一分流拨号（tailnet 目标经内嵌节点：Android HttpURLConnection 会先本地
     *  解析目标主机，ts.net 公网不可解析，自托管源必须经拨号把解析交给代理侧）。 */
    private val dial: (String, Int) -> java.net.Socket,
) {
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
    private val sourceConfig = work.keepwriting.core.UpdateSourceConfig(
        java.io.File(context.filesDir, "config").toPath(),
    )

    /** 当前生效的自托管更新源（null = GitHub）。设置页保存后经 reloadSource 刷新。 */
    @Volatile
    private var sourceBase: String? = (sourceConfig.load() as? work.keepwriting.core.UpdateSourceConfig.Load.Ok)?.endpoint

    private val downloader = UpdateDownloader(
        dir = updatesDir,
        // 按源分流：自托管源经注入拨号（Android HttpURLConnection 会先本地解析目标
        // 主机，ts.net 公网不可解析）；GitHub 资产仍用 HttpURLConnection（其重定向
        // 与 Range 语义由平台处理），流式拷贝不驻留内存；进度逐块回调（含 total，
        // 供百分比显示——206 续传时把已下载部分计入 done/total）。
        streamFetch = { url, rangeFrom, sink, onProgress ->
            val base = sourceBase?.trimEnd('/')
            if (base != null && url.startsWith(base)) {
                val headers = if (rangeFrom > 0) listOf("Range" to "bytes=$rangeFrom-") else emptyList()
                work.keepwriting.core.HttpSocket(dial).get(url, headers, sink) { written, total ->
                    onProgress(rangeFrom + written, if (total > 0) rangeFrom + total else 0L)
                }
            } else {
                urlConnectionFetch(url, rangeFrom, sink, onProgress)
            }
        },
        assetUrl = { name ->
            sourceBase?.trimEnd('/')?.let { "$it/$name" }
                ?: "https://github.com/kw12121212/dsh-anywhere/releases/latest/download/$name"
        },
    )

    /** GitHub 资产的流式取回（HttpURLConnection：跟随重定向、平台 DNS 可用）。 */
    private fun urlConnectionFetch(
        url: String,
        rangeFrom: Long,
        sink: java.io.OutputStream,
        onProgress: (done: Long, total: Long) -> Unit,
    ): Pair<Int, Long> {
        val conn = java.net.URI(url).toURL().openConnection() as HttpsURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        if (rangeFrom > 0) conn.setRequestProperty("Range", "bytes=$rangeFrom-")
        try {
            val code = conn.responseCode
            var written = 0L
            if (code == 200 || code == 206) {
                val total = (conn.contentLengthLong.takeIf { it > 0 } ?: 0L) + rangeFrom
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        written += n
                        onProgress(rangeFrom + written, total)
                    }
                }
            }
            return code to written
        } finally {
            conn.disconnect()
        }
    }

    /** 设置页保存/清除更新源后重读（下次检查与下载即用新源）。 */
    fun reloadSource() {
        sourceBase = (sourceConfig.load() as? work.keepwriting.core.UpdateSourceConfig.Load.Ok)?.endpoint
    }

    /** 当前配置的自托管更新源（设置页展示；null = GitHub）。 */
    val updateSourceEndpoint: String?
        get() = (sourceConfig.load() as? work.keepwriting.core.UpdateSourceConfig.Load.Ok)?.endpoint

    /** 设置页保存更新源：合法保存并生效返回规范化地址，非法返回 null。 */
    fun saveUpdateSource(raw: String): String? = try {
        sourceConfig.save(raw).also { reloadSource() }
    } catch (_: IllegalArgumentException) {
        null
    }

    /** 设置页清除更新源（回到 GitHub）。 */
    fun clearUpdateSource() {
        sourceConfig.clear()
        reloadSource()
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

    /** 检查更新（后台线程调用；下载中不重复检查，防状态覆盖与并发写文件）；
     *  发现新版本自动转入下载，无需用户再点。 */
    fun check() {
        if (state == State.DOWNLOADING) return
        state = State.CHECKING
        Thread {
            try {
                val base = sourceBase
                var info: UpdateCheck.UpdateInfo? = if (base != null) {
                    try {
                        checkSelfHosted(base)
                    } catch (_: TunnelNotReady) {
                        // 启动检查常跑在隧道建连完成前：等一下重试一次，仍不就绪才报错
                        Thread.sleep(4_000)
                        checkSelfHosted(base)
                    }
                } else {
                    checkGitHub()
                }
                if (info != null) currentInfo = info
                val ready = downloader.readyApk(currentVersionCode())
                when (UpdateCheck.afterCheck(info?.version, ready?.first)) {
                    UpdateCheck.CheckOutcome.READY -> restoreReady()
                    UpdateCheck.CheckOutcome.AVAILABLE -> {
                        availableVersion = info!!.version
                        state = State.AVAILABLE
                        // 发现新版本即自动下载（含启动自动检查与隧道恢复补查），
                        // 下载完成后仍由用户确认安装。
                        download()
                    }
                    UpdateCheck.CheckOutcome.UP_TO_DATE -> state = State.UP_TO_DATE
                }
            } catch (e: TunnelNotReady) {
                tunnelRetryPending = true
                if (!restoreReady()) fail("隧道未就绪，等 Tailscale 连上后自动重试")
            } catch (e: Exception) {
                if (!restoreReady()) fail("检查更新失败（${e.javaClass.simpleName}）")
            }
        }.start()
    }

    /** 自托管源在 tailnet 上而内嵌节点未就绪：不可直连（ts.net 公网不可解析）。 */
    private class TunnelNotReady : IllegalStateException("tunnel not ready")

    /** 隧道未就绪导致检查失败后置位；隧道连上（NativeShell 观察 tunnel 状态）自动补查。 */
    @Volatile
    private var tunnelRetryPending = false

    /** 隧道连上时调用：有待补查则立即重查（用户点检查时隧道未就绪也能自动恢复）。 */
    fun onTunnelConnected() {
        if (tunnelRetryPending) {
            tunnelRetryPending = false
            check()
        }
    }

    /** GitHub 模式：latest Release 的 tag 与资产名 → UpdateInfo；不可达抛异常。 */
    private fun checkGitHub(): UpdateCheck.UpdateInfo? {
        val conn = URL(LATEST_API).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val release = JSONObject(body)
            val tag = release.optString("tag_name")
            val names = release.optJSONArray("assets")?.let { arr ->
                List(arr.length()) { arr.optJSONObject(it)?.optString("name") ?: "" }
            } ?: emptyList()
            return UpdateCheck.isNewer(tag, names, currentVersionCode())
        } finally {
            conn.disconnect()
        }
    }

    /** 自托管模式：GET <base>/latest.json 经注入拨号（tailnet 目标由代理侧解析域名）→
     *  UpdateInfo；清单缺失(404)/无效视为无更新（null）；tailnet 源在隧道未就绪时抛
     *  TunnelNotReady（绝不能直连——ts.net 公网 DNS 不可解析）。 */
    private fun checkSelfHosted(base: String): UpdateCheck.UpdateInfo? {
        val host = runCatching { java.net.URI(base).host }.getOrNull()
        if (host != null && SocksTls.isTailnet(host) && SocksTls.factory(appContext) == null) {
            throw TunnelNotReady()
        }
        val res = work.keepwriting.core.HttpSocket(dial).request(
            "GET", base.trimEnd('/') + "/latest.json",
            listOf("Accept" to "application/json"),
        )
        if (res.status == 404) return null
        if (res.status != 200) throw IllegalStateException("HTTP ${res.status}")
        val parsed = work.keepwriting.core.Json.parse(res.bodyText()) ?: return null
        return UpdateCheck.fromManifest(parsed, currentVersionCode())
    }

    /** 下载新版本 APK（后台线程执行；支持 Range 断点续传；已在下载则忽略）。 */
    fun download() {
        val info = currentInfo ?: return
        if (state == State.DOWNLOADING) return
        // tailnet 更新源必须等隧道就绪（下载经 SOCKS TLS；直连 ts.net 必失败）
        val base = sourceBase
        if (base != null) {
            val host = runCatching { java.net.URI(base).host }.getOrNull()
            if (host != null && SocksTls.isTailnet(host) && SocksTls.factory(appContext) == null) {
                fail("隧道未就绪，稍后再试（tailnet 更新源需先连上 Tailscale）")
                return
            }
        }
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
