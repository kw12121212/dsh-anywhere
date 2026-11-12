plugins {
    // 与 :app 所用 AGP 9.4 内置的 Kotlin 版本钉成同一个：App 用那个编译器读本库
    // 的 Kotlin 元数据，元数据版本越界即 App 构建失败。
    kotlin("jvm") version "2.2.10"
}

// 依赖替换的坐标：:app 以 work.keepwriting:core 依赖本构建。
group = "work.keepwriting"
version = "1"

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // 测试每次都真跑：避免 up-to-date 把「没跑」当「通过」。
    outputs.upToDateWhen { false }
}

// dsbot-api-spike 的取证原型：独立 spike source set（src/spike/kotlin），编译引用
// main 源码（复用 Socks5Dialer/TailnetRouting/DialProbe 等拨号与解析类），但不进
// main/test 产物、不进任何 jar 与回归命令；runSpike 经环境变量接收目标与凭据
// （tech 标准 secrets-env-gated：不打印、不入库）。无 DSH_SPIKE_TARGET 时
// runSpike 以用法错误退出，对仓库零影响。
sourceSets {
    create("spike") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

dependencies {
    add("spikeImplementation", kotlin("stdlib"))
}

tasks.register<JavaExec>("runSpike") {
    group = "spike"
    description = "对真实 dsh 运行 DS-Bot API spike（需 DSH_SPIKE_TARGET 环境变量，详见 SpikeMain 用法输出）"
    mainClass.set("work.keepwriting.core.spike.SpikeMainKt")
    classpath = sourceSets["spike"].runtimeClasspath
    // JavaExec 默认继承当前进程环境变量：DSH_SPIKE_* 直接透传，无需在此登记。
}
