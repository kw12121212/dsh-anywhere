package work.keepwriting.core

import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * 测试共用 TLS 脚手架（自签证书 SAN=dsh.test）：DialProbeHttpsTest 与
 * HttpSocketTest 各自需要「本地真实 TLS 服务端 + 只信任该证书的客户端工厂」，
 * 同一份 keytool 逻辑抽到这里避免两处漂移。
 */
internal object TestTls {
    private const val PASSWORD = "changeit"

    private val keyStore: KeyStore by lazy {
        val dir = Files.createTempDirectory("core-test-tls").toFile()
        val file = File(dir, "server.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "2", "-dname", "CN=dsh.test", "-ext", "san=dns:dsh.test",
            "-storetype", "PKCS12", "-keystore", file.path,
            "-storepass", PASSWORD, "-keypass", PASSWORD,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "keytool 生成测试证书失败：$output" }
        KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD.toCharArray()) } }
            .also { dir.deleteRecursively() }
    }

    fun serverContext(): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, PASSWORD.toCharArray())
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    val trustingFactory: SSLSocketFactory by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
    }
}
