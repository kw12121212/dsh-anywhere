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
