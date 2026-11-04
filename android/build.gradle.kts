// AGP 9 起 Kotlin 支持内置于 Android 插件，不再单独声明 org.jetbrains.kotlin.android
// ——声明了会被 AGP 直接拒绝（https://kotl.in/gradle/agp-built-in-kotlin）。
// Compose 编译器插件的版本必须等于 AGP 内置的 Kotlin 版本（当前 2.2.10，
// `./gradlew -p android buildEnvironment` 可查）：Compose 编译器随 Kotlin 编译器
// 同版本发布，不一致时构建直接失败。升级 AGP 时与 core 的 kotlin("jvm") 版本
// 一起改（:app 用那个编译器读本库的 Kotlin 元数据，元数据版本越界即失败）。
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
