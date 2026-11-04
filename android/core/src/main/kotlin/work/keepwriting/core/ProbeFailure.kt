package work.keepwriting.core

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 探测失败原因分类（桌面端 probe.ts classifyNetError 的 Android 对应物）：
 * 把 HttpURLConnection 抛出的异常映射为错误页展示用的类别。
 */
object ProbeFailure {
    enum class Reason { DNS, REFUSED, TIMEOUT, TLS, OTHER }

    fun classify(exception: Throwable): Reason = when (exception) {
        is UnknownHostException -> Reason.DNS
        is ConnectException -> Reason.REFUSED
        is SocketTimeoutException -> Reason.TIMEOUT
        is SSLException -> Reason.TLS
        else -> when (val cause = exception.cause) {
            null -> Reason.OTHER
            exception -> Reason.OTHER
            else -> classify(cause)
        }
    }
}
