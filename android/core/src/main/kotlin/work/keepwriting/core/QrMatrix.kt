package work.keepwriting.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * 二维码点阵（dsbot-im-bridges 微信扫码登录）：以 ZXing core 编码（纠错级别 M，同网页端 qrcode-generator 的 M 级；非 Latin-1 内容才用 UTF-8 ECI），
 * 界面按点阵绘制并四周留 [QUIET] 个模块的空白。
 */
class QrMatrix private constructor(val size: Int, private val dark: BooleanArray) {
    fun isDark(x: Int, y: Int): Boolean = dark[y * size + x]

    companion object {
        /** 静区宽度（模块数，同网页端 QUIET2）。 */
        const val QUIET = 4

        /** 编码失败（内容超出二维码容量等）时为 null。 */
        fun encode(text: String): QrMatrix? = runCatching {
            // 能用 ISO-8859-1 表示的（如登录链接）不加 UTF-8 ECI 头，与网页端 qrcode-generator 一致，免得个别扫码器处理不好 ECI
            val latin1 = text.all { it.code <= 0xFF }
            val code = if (latin1) Encoder.encode(text, ErrorCorrectionLevel.M) else Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
            val m = code.matrix
            val n = m.width
            QrMatrix(n, BooleanArray(n * n) { i -> m.get(i % n, i / n).toInt() == 1 })
        }.getOrNull()
    }
}
