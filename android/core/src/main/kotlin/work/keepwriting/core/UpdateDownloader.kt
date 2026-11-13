package work.keepwriting.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 更新包下载与校验（android-self-update）：断点续传下载 APK 与同名 .sha256 到
 * [dir]，恒时比对校验。资产地址由 [assetUrl] 按资产名给出（生产为 GitHub Release
 * 下载地址，JUnit 为本地 HttpServer）。
 *
 * 磁盘约定（fix-android-update-lifecycle）：APK 先写 <apk>.part，校验通过才改名为
 * 正式 <apk>——正式文件存在即「已校验可安装」，进程被杀、界面重建与再次检查都据此
 * 恢复；中途被杀只留下 .part，下次续传（或 416 已完整）后重新校验。
 */
class UpdateDownloader(
    private val dir: File,
    private val assetUrl: (name: String) -> String,
) {
    /** 校验失败（不一致、.sha256 缺失或格式不符）：已下载文件已删除。 */
    class VerificationFailed : IOException("sha256 mismatch")

    /** 资产请求得到非 200/206 状态。 */
    private class HttpStatus(val code: Int) : IOException("HTTP $code")

    /** 下载并校验 [info] 的 APK；成功返回已校验的 APK 文件（已校验过则直接返回）。 */
    fun download(info: UpdateCheck.UpdateInfo, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): File {
        dir.mkdirs()
        val apkFile = File(dir, info.apkAsset)
        val partFile = File(dir, info.apkAsset + PART_SUFFIX)
        val shaFile = File(dir, info.sha256Asset)
        // 新下载开始前清理其他版本的残留（本版本的续传进度与已校验包保留）。
        dir.listFiles()?.forEach {
            if (it.name != apkFile.name && it.name != partFile.name && it.name != shaFile.name) it.deleteRecursively()
        }
        if (apkFile.isFile) return apkFile
        // 期望 sha256 的两个来源：自托管清单直接给出；GitHub 模式抓 .sha256 资产
        //（体积很小，每次全新获取，不续传——已完整时续传请求只会得到 416）。
        val expected: String?
        if (info.manifestSha256 != null) {
            expected = info.manifestSha256
        } else {
            shaFile.delete()
            try {
                fetch(info.sha256Asset, shaFile, resume = false)
            } catch (e: HttpStatus) {
                // Release 缺 .sha256：无从校验，删除已下载部分并按校验失败处理。
                if (e.code != 404) throw e
                partFile.delete()
                shaFile.delete()
                throw VerificationFailed()
            }
            expected = UpdateCheck.parseSha256(shaFile.readText())
        }
        fetch(info.apkAsset, partFile, resume = true, onProgress = onProgress)
        // 校验：期望 sha256 与 APK 内容恒时比对。
        val actual = sha256Of(partFile)
        if (expected == null || actual == null ||
            !MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
        ) {
            partFile.delete()
            shaFile.delete()
            throw VerificationFailed()
        }
        if (!partFile.renameTo(apkFile)) throw IOException("rename failed")
        return apkFile
    }

    /**
     * 恢复已校验的更新包：返回版本高于 [currentVersionCode] 的正式 APK（版本，文件）；
     * 版本不高于本机的正式 APK（已安装完成）连同其 .part/.sha256 一并删除。
     */
    fun readyApk(currentVersionCode: Int): Pair<String, File>? {
        var ready: Pair<String, File>? = null
        for (f in dir.listFiles().orEmpty()) {
            val version = APK_NAME.matchEntire(f.name)?.groupValues?.get(1) ?: continue
            val code = UpdateCheck.versionCodeOf(version) ?: continue
            if (code <= currentVersionCode) {
                dir.listFiles().orEmpty().filter { it.name.startsWith(f.name) }.forEach { it.delete() }
            } else if (f.isFile && (ready == null || code > UpdateCheck.versionCodeOf(ready.first)!!)) {
                ready = version to f
            }
        }
        return ready
    }

    /** 下载资产到 [target]；[resume] 时对已有部分带 Range 续传：206 追加、200 重写、416 视为已完整。 */
    private fun fetch(name: String, target: File, resume: Boolean, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val conn = URL(assetUrl(name)).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        try {
            val existing = if (resume && target.isFile) target.length() else 0L
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
            val code = conn.responseCode
            // 起点越界：服务器上的文件不比已下载部分更长，即已下载完整，交给校验判定。
            if (existing > 0 && code == 416) return
            if (code != 200 && code != 206) throw HttpStatus(code)
            val resumeFrom = if (code == 206) existing else 0L
            val total = (conn.contentLengthLong.takeIf { it > 0 } ?: 0L) + resumeFrom
            RandomAccessFile(target, "rw").use { out ->
                if (resumeFrom == 0L) out.setLength(0) // 服务器忽略 Range：从零重写并截断旧尾部
                out.seek(resumeFrom)
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
            }
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

    companion object {
        const val PART_SUFFIX = ".part"
        private val APK_NAME = Regex("""^DSH-Anywhere-(\d+\.\d+\.\d+)\.apk$""")
    }
}
