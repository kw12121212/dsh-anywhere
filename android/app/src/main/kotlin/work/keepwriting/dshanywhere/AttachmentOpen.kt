package work.keepwriting.dshanywhere

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * 附件交系统应用打开（dsbot-attachments）：字节写 cache/attachments 临时文件，
 * 经 FileProvider 以扩展名推断的 MIME 调起系统查看器/播放器（音频视频由系统播放器
 * 出窗口播放，PDF 等由系统查看器打开）。contentResolver.getType 对 file:// 恒 null，
 * MIME 必须按扩展名映射。无应用可处理时返回 false（调用方退回内嵌查看对话框）。
 */
object AttachmentOpen {
    /** [chooser] 为 true 时经系统「选择打开方式」选择器交用户挑应用，否则按系统默认。 */
    fun openWith(context: Context, name: String, bytes: ByteArray, chooser: Boolean = false): Boolean {
        val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
        // 单槽复用：上一次的临时文件先清，缓存目录不随查看次数增长。
        dir.listFiles()?.forEach { it.delete() }
        val safe = name.replace(Regex("[^\\w.\\-()-]"), "_").takeLast(80).ifEmpty { "attachment" }
        val f = File(dir, safe)
        f.writeBytes(bytes)
        return try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", f,
            )
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeOf(safe))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // 选择器包一层：URI 授权留在内层 intent， chooser intent 只补 NEW_TASK
            val launch = if (chooser) Intent.createChooser(view, "选择打开方式")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else view
            context.startActivity(launch)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 扩展名 → MIME（覆盖对话附件常见形态；未知统一 octet-stream 由用户挑应用）。 */
    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "wav" -> "audio/x-wav"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "flac" -> "audio/flac"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        "txt", "md", "log" -> "text/plain"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "xml" -> "text/xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}
