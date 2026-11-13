package work.keepwriting.core

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UpdateDownloader 的下载、续传与校验（fix-android-update-lifecycle）：本地 HttpServer
 * 按 GitHub/S3 的 Range 语义提供资产（起点越界返回 416）。
 */
class UpdateDownloaderTest {
    private val cleanups = mutableListOf<() -> Unit>()
    private val dir: File = Files.createTempDirectory("updates").toFile().also { d -> cleanups += { d.deleteRecursively() } }

    private val version = "1.0.2"
    private val apkName = "DSH-Anywhere-$version.apk"
    private val shaName = "$apkName.sha256"
    private val info = UpdateCheck.UpdateInfo(version, apkName, shaName, VersionSpec.versionCode(version))
    private val apkBytes = ByteArray(300_000) { (it * 31 + 7).toByte() }
    private val shaText = "${sha256Hex(apkBytes)}  $apkName\n"

    /** 收到的 Range 请求头（按资产名）。 */
    private val ranges = mutableMapOf<String, MutableList<String?>>()

    @AfterTest
    fun tearDown() {
        cleanups.forEach { runCatching(it) }
    }

    @Test
    fun retryWithCompleteSha256Succeeds() {
        // 上次中断在 .sha256 已下载完整、APK 尚未开始：重试不得因 416 失败。
        val base = serve(mapOf(apkName to apkBytes, shaName to shaText.toByteArray()))
        File(dir, shaName).writeText(shaText)
        val apk = downloader(base).download(info)
        assertContentEquals(apkBytes, apk.readBytes())
    }

    @Test
    fun freshDownloadVerifiesAndLeavesOnlyFinalFiles() {
        val base = serve(mapOf(apkName to apkBytes, shaName to shaText.toByteArray()))
        val apk = downloader(base).download(info)
        assertContentEquals(apkBytes, apk.readBytes())
        assertEquals(setOf(apkName, shaName), dir.list()!!.toSet(), "校验通过后不留 .part")
    }

    @Test
    fun partialApkResumesFromOffset() {
        val base = serve(mapOf(apkName to apkBytes, shaName to shaText.toByteArray()))
        File(dir, apkName + UpdateDownloader.PART_SUFFIX).writeBytes(apkBytes.copyOfRange(0, 120_000))
        val apk = downloader(base).download(info)
        assertContentEquals(apkBytes, apk.readBytes())
        assertEquals<List<String?>?>(listOf("bytes=120000-"), ranges[apkName], "APK 应从已下载处续传")
        assertEquals<List<String?>?>(listOf(null), ranges[shaName], ".sha256 每次全新获取，不带 Range")
    }

    @Test
    fun completeButUnverifiedApkIsVerifiedWithout416Failure() {
        // 上次 APK 已下载完整但进程在校验前被杀：续传得到 416，直接进入校验。
        val base = serve(mapOf(apkName to apkBytes, shaName to shaText.toByteArray()))
        File(dir, shaName).writeText(shaText)
        File(dir, apkName + UpdateDownloader.PART_SUFFIX).writeBytes(apkBytes)
        val apk = downloader(base).download(info)
        assertContentEquals(apkBytes, apk.readBytes())
        assertEquals<List<String?>?>(listOf("bytes=300000-"), ranges[apkName], "完整的 .part 不得被整包重新下载")
    }

    @Test
    fun serverIgnoringRangeRewritesPartFromZero() {
        // 服务器忽略 Range 返回 200：从零重写并截断旧尾部（旧 .part 比新包更长且内容不同）。
        val base = serve(mapOf(apkName to apkBytes, shaName to shaText.toByteArray()), honorRange = false)
        File(dir, apkName + UpdateDownloader.PART_SUFFIX).writeBytes(ByteArray(400_000) { 1 })
        val apk = downloader(base).download(info)
        assertContentEquals(apkBytes, apk.readBytes())
    }

    @Test
    fun newDownloadRemovesOtherVersionsFiles() {
        val base = serve(mapOf(apkName to apkBytes, shaName to shaText.toByteArray()))
        File(dir, "DSH-Anywhere-1.0.1.apk").writeBytes(apkBytes)
        File(dir, "DSH-Anywhere-1.0.1.apk.part").writeBytes(apkBytes)
        downloader(base).download(info)
        assertEquals(setOf(apkName, shaName), dir.list()!!.toSet(), "开始下载新版本前清理旧 APK")
    }

    @Test
    fun alreadyVerifiedApkIsNotDownloadedAgain() {
        val base = serve(mapOf(apkName to apkBytes, shaName to shaText.toByteArray()))
        File(dir, apkName).writeBytes(apkBytes)
        downloader(base).download(info)
        assertTrue(ranges.isEmpty(), "已校验的包不应再发任何请求：$ranges")
    }

    @Test
    fun checksumMismatchDeletesFilesAndThrows() {
        val tampered = apkBytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val base = serve(mapOf(apkName to tampered, shaName to shaText.toByteArray()))
        assertFailsWith<UpdateDownloader.VerificationFailed> { downloader(base).download(info) }
        assertEquals(emptyList(), dir.list()!!.toList(), "校验失败后不留任何下载文件")
    }

    @Test
    fun manifestShaSkipsShaAssetAndVerifies() {
        // 自托管清单模式：latest.json 已带 sha256，服务器只有 APK 也能下载并校验通过。
        val base = serve(mapOf(apkName to apkBytes))
        val manifestInfo = info.copy(manifestSha256 = sha256Hex(apkBytes))
        val apk = downloader(base).download(manifestInfo)
        assertContentEquals(apkBytes, apk.readBytes())
        assertEquals(setOf(apkName), dir.list()!!.toSet(), "清单模式不落 .sha256 文件")
    }

    @Test
    fun manifestShaMismatchThrows() {
        val base = serve(mapOf(apkName to apkBytes))
        val wrong = "0".repeat(64)
        assertFailsWith<UpdateDownloader.VerificationFailed> {
            downloader(base).download(info.copy(manifestSha256 = wrong))
        }
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test
    fun missingSha256FailsAsVerificationAndDeletesFiles() {
        val base = serve(mapOf(apkName to apkBytes))
        File(dir, apkName + UpdateDownloader.PART_SUFFIX).writeBytes(apkBytes.copyOfRange(0, 1000))
        assertFailsWith<UpdateDownloader.VerificationFailed> { downloader(base).download(info) }
        assertEquals(emptyList(), dir.list()!!.toList(), "缺 .sha256 时删除已下载文件，不留可安装的 APK")
    }

    @Test
    fun afterCheckKeepsReadyUnlessSuperseded() {
        // 已校验包即最新版本、或没有更新的 Release：保持可安装（再次检查不回退）。
        assertEquals(UpdateCheck.CheckOutcome.READY, UpdateCheck.afterCheck("1.0.2", "1.0.2"))
        assertEquals(UpdateCheck.CheckOutcome.READY, UpdateCheck.afterCheck(null, "1.0.2"))
        // 更新的 Release 取代旧的已校验包。
        assertEquals(UpdateCheck.CheckOutcome.AVAILABLE, UpdateCheck.afterCheck("1.0.3", "1.0.2"))
        assertEquals(UpdateCheck.CheckOutcome.AVAILABLE, UpdateCheck.afterCheck("1.0.3", null))
        assertEquals(UpdateCheck.CheckOutcome.UP_TO_DATE, UpdateCheck.afterCheck(null, null))
    }

    @Test
    fun readyApkRestoresNewerVerifiedPackageAndDropsInstalledOnes() {
        val d = UpdateDownloader(dir) { error("不应发请求") }
        File(dir, apkName).writeBytes(apkBytes)
        File(dir, "DSH-Anywhere-1.0.3.apk.part").writeBytes(apkBytes)
        File(dir, "DSH-Anywhere-1.0.1.apk").writeBytes(apkBytes)
        File(dir, "DSH-Anywhere-1.0.1.apk.sha256").writeText(shaText)
        val ready = d.readyApk(currentVersionCode = VersionSpec.versionCode("1.0.1"))
        assertEquals(version, ready?.first, "只认已校验（正式文件名）且高于本机的包")
        assertEquals(File(dir, apkName), ready?.second)
        assertFalse(File(dir, "DSH-Anywhere-1.0.1.apk").exists(), "已安装版本的包应被清理")
        assertFalse(File(dir, "DSH-Anywhere-1.0.1.apk.sha256").exists())
        // 本机已升级到该版本：不再视为可安装，并清理。
        assertNull(d.readyApk(currentVersionCode = VersionSpec.versionCode(version)))
        assertFalse(File(dir, apkName).exists())
    }

    private fun downloader(base: String) = UpdateDownloader(dir) { name -> "$base/$name" }

    /** 起本地资产服务：支持 Range（206）、起点越界 416、缺失 404。 */
    private fun serve(assets: Map<String, ByteArray>, honorRange: Boolean = true): String {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/") { ex ->
            val name = ex.requestURI.path.removePrefix("/")
            val range = ex.requestHeaders.getFirst("Range")
            synchronized(ranges) { ranges.getOrPut(name) { mutableListOf() }.add(range) }
            val body = assets[name]
            when {
                body == null -> ex.sendResponseHeaders(404, -1)
                range == null || !honorRange -> {
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                else -> {
                    val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                    if (start >= body.size) {
                        ex.responseHeaders.add("Content-Range", "bytes */${body.size}")
                        ex.sendResponseHeaders(416, -1)
                    } else {
                        ex.responseHeaders.add("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                        ex.sendResponseHeaders(206, (body.size - start).toLong())
                        ex.responseBody.use { it.write(body, start, body.size - start) }
                    }
                }
            }
            ex.close()
        }
        server.start()
        cleanups += { server.stop(0) }
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
