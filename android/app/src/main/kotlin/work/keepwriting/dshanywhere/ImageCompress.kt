package work.keepwriting.dshanywhere

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import work.keepwriting.core.ImageSizing
import java.io.ByteArrayOutputStream

/**
 * 照片附件压缩（dsbot-outbox-retry）：照片类图片（非 PNG/GIF 的 image 类型）按 EXIF 方向校正后，
 * 长边超过 2048 才等比缩小并以 JPEG 82 重编码；未超限、PNG（截图/透明图）、GIF（动图）、非图片
 * 与任何解码失败一律原样返回。在控制器的 executor 线程调用。
 */
object ImageCompress {
    const val JPEG_QUALITY = 82

    data class Result(val bytes: ByteArray, val mediaType: String?, val name: String)

    fun maybeCompress(bytes: ByteArray, mediaType: String?, name: String): Result {
        val original = Result(bytes, mediaType, name)
        val type = mediaType?.lowercase() ?: return original
        if (!type.startsWith("image/") || type == "image/png" || type == "image/gif") return original
        return runCatching { compress(bytes, name) }.getOrNull() ?: original
    }

    private fun compress(bytes: ByteArray, name: String): Result? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val orientation = exifOrientation(bytes)
        val swap = orientation in SWAPPED
        // 校正方向后的显示尺寸决定是否需要缩小
        val (w, h) = if (swap) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
        val target = ImageSizing.target(w, h) ?: return null // 未超限：不放大、不重编码
        val sampled = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = ImageSizing.sampleSize(bounds.outWidth, bounds.outHeight) },
        ) ?: return null
        // 缩放与方向校正合成一个矩阵，一次 createBitmap 得到结果（避免先出同尺寸旋转副本的内存峰值）
        val (srcW, srcH) = if (swap) sampled.height to sampled.width else sampled.width to sampled.height
        val matrix = Matrix()
        applyOrientation(matrix, orientation)
        matrix.postScale(target.first.toFloat() / srcW, target.second.toFloat() / srcH)
        val result = Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true)
        val out = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (result !== sampled) result.recycle()
        sampled.recycle()
        val jpegName = name.substringBeforeLast('.', name) + ".jpg"
        return Result(out.toByteArray(), "image/jpeg", jpegName)
    }

    /**
     * 解码为按 EXIF 方向校正、长边不超过 [maxEdge] 的位图（dsbot-avatars 裁剪用）；无法解码返回 null。
     */
    fun loadOriented(bytes: ByteArray, maxEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampled = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = ImageSizing.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge) },
        ) ?: return null
        val orientation = exifOrientation(bytes)
        val swap = orientation in SWAPPED
        val (srcW, srcH) = if (swap) sampled.height to sampled.width else sampled.width to sampled.height
        val target = ImageSizing.target(srcW, srcH, maxEdge) ?: (srcW to srcH)
        val matrix = Matrix()
        applyOrientation(matrix, orientation)
        matrix.postScale(target.first.toFloat() / srcW, target.second.toFloat() / srcH)
        val out = Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true)
        if (out !== sampled) sampled.recycle()
        out
    }.getOrNull()

    /** EXIF 方向 5–8 显示时宽高互换。 */
    private val SWAPPED = setOf(
        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270,
    )

    /** EXIF 方向（含翻转与转置 2/4/5/7）到变换矩阵。 */
    private fun applyOrientation(m: Matrix, orientation: Int) {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
    }

    private fun exifOrientation(bytes: ByteArray): Int = runCatching {
        ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
}
