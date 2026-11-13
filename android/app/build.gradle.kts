import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ── 版本注入：仓库根 package.json 是唯一版本登记处（tech/packaging 的
// package-version-single-source）──
// versionName 构建期直接取该值，versionCode 按 MAJOR*10000+MINOR*100+PATCH 派生，
// 与 core 的 VersionSpecTest 同一公式。读取、解析或校验失败时构建直接报错退出，
// 不静默回退，Gradle 中不出现第二个版本号。
fun versionFromPackageJson(): Pair<String, Int> {
    val manifestFile = rootDir.parentFile.resolve("package.json")
    val manifest = try {
        @Suppress("UNCHECKED_CAST")
        JsonSlurper().parse(manifestFile) as Map<String, Any>
    } catch (e: Exception) {
        throw GradleException("读取 ${manifestFile} 失败：${e.message}")
    }
    val version = manifest["version"] as? String
        ?: throw GradleException("package.json 缺少 version 字段")
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)$""").find(version)
        ?: throw GradleException("package.json 的 version 必须是 x.y.z 形式（当前：$version）")
    val (major, minor, patch) = match.destructured
    return version to (major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt())
}

val (appVersionName, appVersionCode) = versionFromPackageJson()

// ── 签名 keystore：来自构建机本机，不硬编码进仓库 ──
// 配置文件优先级：环境变量 DSH_KEYSTORE_PROPS > keystore 脚本的缺省位置。
// keystore 由 scripts/android-keystore.mts 生成（不入库；debug 与 release 统一签名是
// APK 自更新的前提——tech/android-client 的 android-uniform-signing 规则）。
fun keystoreProps(): Properties? {
    val path = System.getenv("DSH_KEYSTORE_PROPS")
        ?: "${System.getProperty("user.home")}/.config/dsh-anywhere/keys/keystore.properties"
    val f = File(path)
    if (!f.isFile) return null
    return Properties().apply { f.inputStream().use { load(it) } }
}

val dshKeystore = keystoreProps()

android {
    namespace = "work.keepwriting.dshanywhere"
    compileSdk = 36

    defaultConfig {
        applicationId = "work.keepwriting.dshanywhere"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (dshKeystore != null) {
            create("dsh") {
                storeFile = File(dshKeystore.getProperty("storeFile"))
                storePassword = dshKeystore.getProperty("storePassword")
                keyAlias = dshKeystore.getProperty("keyAlias")
                keyPassword = dshKeystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // debug 与 release 一律用同一份专用 release keystore 签名（里程碑 intake
        // 问题 android-identity 已确认）。
        debug {
            if (dshKeystore != null) signingConfig = signingConfigs.getByName("dsh")
        }
        release {
            if (dshKeystore != null) signingConfig = signingConfigs.getByName("dsh")
            isMinifyEnabled = false
        }
    }

    // 出包（assemble*/bundle*/package*）必须拿到 keystore：缺失即失败并指出生成方法。
    // 编译与单元测试不需要签名，无 keystore 的机器可照常跑 core 测试与 :app 单测。
    gradle.taskGraph.whenReady {
        val signingWanted = allTasks.any {
            it.name.startsWith("assemble") || it.name.startsWith("bundle") ||
                it.name.startsWith("package") || it.name.contains("Apk", ignoreCase = true)
        }
        if (signingWanted && dshKeystore == null) {
            throw GradleException(
                "缺少 Android 签名 keystore：先运行 node scripts/android-keystore.mts 生成" +
                    "（存放在 ~/.config/dsh-anywhere/keys/，不进仓库，请自行备份），" +
                    "或用 DSH_KEYSTORE_PROPS 指向已有配置。"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// 验收辅助：打印派生出的版本号（build-acceptance 断言 versionName 等于 package.json
// version、versionCode 等于公式结果）。
tasks.register("printVersion") {
    doLast {
        println("versionName=$appVersionName")
        println("versionCode=$appVersionCode")
    }
}

dependencies {
    // 内嵌 tailnet 节点的 gomobile AAR：不入库，由 scripts/build-android-tstunnel.mts
    // 从 android/tstunnel 的 Go 源码本机构建（tech/android-client 的
    // aar-script-built-not-committed 规则）。克隆后先跑构建入口再 Gradle 构建；
    // AAR 缺失时本模块构建失败是显式信号。
    implementation(files("libs/tstunnel.aar"))
    // 纯 JVM 逻辑层（android/core 独立构建，经 settings 的 includeBuild 替换）。
    implementation("work.keepwriting:core")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    // 输入区图标按钮（Add/Send/PlayArrow；icons-core 的基础集，不引入 extended）
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    // WebView 代理覆盖（android-proxy-routing）：把 WebView 指向进程内代理桥。
}
