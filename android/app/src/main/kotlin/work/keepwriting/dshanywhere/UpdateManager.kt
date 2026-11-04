package work.keepwriting.dshanywhere

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import org.json.JSONObject
import work.keepwriting.core.UpdateCheck
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
    private var currentInfo: UpdateCheck.UpdateInfo? = null
    private var autoPromptUsed = false

    /** 前后台标志（Activity onStart/onStop 维护）：后台调起安装器会被系统静默丢弃，
     *  必须主动分流到通知（Android 10+ 的 BAL 限制不抛异常）。 */
    @Volatile
    var appInForeground: Boolean = false

    fun downloadedApk(): File? {
        val info = currentInfo ?: return null
        val f = File(updatesDir, info.apkAsset)
        return if (f.isFile) f else null
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
                        fail("检查更新失败（HTTP ${conn.responseCode}）")
                        return@Thread
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val release = JSONObject(body)
                    val tag = release.optString("tag_name")
                    val names = release.optJSONArray("assets")?.let { arr ->
                        List(arr.length()) { arr.optJSONObject(it)?.optString("name") ?: "" }
                    } ?: emptyList()
                    val current = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                    @Suppress("DEPRECATION")
                    val currentCode = current.versionCode
                    val info = UpdateCheck.isNewer(tag, names, currentCode)
                    if (info == null) {
                        state = State.UP_TO_DATE
                    } else {
                        currentInfo = info
                        availableVersion = info.version
                        state = State.AVAILABLE
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                fail("检查更新失败（${e.javaClass.simpleName}）")
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
                updatesDir.mkdirs()
                // 新下载开始前清理旧 APK（安装完成前不删当前已就绪包）。
                if (state == State.DOWNLOADING) {
                    updatesDir.listFiles()?.forEach {
                        if (it.name != info.apkAsset && it.name != info.sha256Asset) it.delete()
                    }
                }
                val apkFile = File(updatesDir, info.apkAsset)
                val shaFile = File(updatesDir, info.sha256Asset)
                downloadAsset(info.sha256Asset, shaFile)
                downloadAsset(info.apkAsset, apkFile) { done, total ->
                    downloadProgress = if (total > 0) done.toFloat() / total else 0f
                }
                // 校验：.sha256 与 APK 内容恒时比对。
                val expected = UpdateCheck.parseSha256(shaFile.readText())
                val actual = sha256Of(apkFile)
                if (expected == null || actual == null ||
                    !MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
                ) {
                    apkFile.delete()
                    shaFile.delete()
                    fail("校验失败：已删除下载文件，未安装")
                    return@Thread
                }
                downloadProgress = 1f
                state = State.READY_TO_INSTALL
                // 每进程会话自动调起至多一次；后台经通知（点击后调起，用户手势规避 BAL 限制）。
                if (!autoPromptUsed) {
                    autoPromptUsed = true
                    if (appInForeground) promptInstall() else notifyReady()
                }
            } catch (e: Exception) {
                fail("下载失败（${e.javaClass.simpleName}）")
            }
        }.start()
    }

    /** 调起系统安装器（前台直接调起；本方法由 UI/通知点击调用）。 */
    fun promptInstall() {
        val apk = downloadedApk() ?: return
        val uri: Uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android-package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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

    private fun downloadAsset(name: String, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val url = "https://github.com/kw12121212/dsh-anywhere/releases/latest/download/$name"
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        try {
            // 断点续传：已有部分文件时带 Range。
            if (target.isFile && target.length() > 0) {
                conn.setRequestProperty("Range", "bytes=${target.length()}-")
            }
            val resumeFrom = if (conn.responseCode == 206) target.length() else 0L
            if (conn.responseCode != 200 && conn.responseCode != 206) {
                throw java.io.IOException("HTTP ${conn.responseCode}")
            }
            val total = (conn.contentLengthLong.takeIf { it > 0 } ?: 0L) + resumeFrom
            val out = java.io.RandomAccessFile(target, "rw").apply {
                if (resumeFrom == 0L) setLength(0) // 服务器忽略 Range：从零重写并截断旧尾部
                seek(resumeFrom)
            }
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var done = resumeFrom
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
            out.close()
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256Of(f: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    private fun fail(reason: String) {
        failureReason = reason
        state = State.FAILED
    }

    companion object {
        const val LATEST_API = "https://api.github.com/repos/kw12121212/dsh-anywhere/releases/latest"
        const val CHANNEL_ID = "dsh_update"
        const val NOTIFY_ID = 4101
        const val ACTION_INSTALL_READY = "work.keepwriting.dshanywhere.INSTALL_READY"
    }
}
