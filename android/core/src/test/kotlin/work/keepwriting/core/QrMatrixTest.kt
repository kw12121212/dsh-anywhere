package work.keepwriting.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** dsbot-im-bridges：二维码点阵可被解码回原文（按界面同样的静区与放大倍数栅格化）。 */
class QrMatrixTest {
    private fun decode(m: QrMatrix, scale: Int = 4): String {
        val side = (m.size + QrMatrix.QUIET * 2) * scale
        val pixels = IntArray(side * side) { i ->
            val x = (i % side) / scale - QrMatrix.QUIET
            val y = (i / side) / scale - QrMatrix.QUIET
            if (x in 0 until m.size && y in 0 until m.size && m.isDark(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))).text
    }

    @Test
    fun `wechat login link round-trips`() {
        val text = "https://liteapp.weixin.qq.com/q/7GiQu1?qrcode=0f1e2d3c4b5a69788796a5b4c3d2e1f0&bot_type=3"
        val m = QrMatrix.encode(text)!!
        assertTrue(m.size >= 21 && (m.size - 17) % 4 == 0) // 版本 n 的边长为 17+4n
        assertEquals(text, decode(m))
    }

    @Test
    fun `ascii link has no ECI header`() {
        val text = "https://liteapp.weixin.qq.com/q/abc?qrcode=1"
        val plain = com.google.zxing.qrcode.encoder.Encoder.encode(text, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M).matrix
        val m = QrMatrix.encode(text)!!
        assertEquals(plain.width, m.size)
        for (y in 0 until m.size) for (x in 0 until m.size) assertEquals(plain.get(x, y).toInt() == 1, m.isDark(x, y))
    }

    @Test
    fun `utf8 content round-trips`() {
        assertEquals("扫码登录 DS Bot", decode(QrMatrix.encode("扫码登录 DS Bot")!!))
    }

    @Test
    fun `oversized content is rejected`() {
        assertNull(QrMatrix.encode("x".repeat(5000)))
    }
}
