// core（纯 JVM 逻辑层）：独立 Gradle 构建，不是 android/settings.gradle.kts 的
// 子项目——纯 Kotlin/JVM，不依赖 Android SDK、NDK 与任何 android.* 框架类，只装
// JDK 17+ 的机器也能跑它的测试：
//   android/gradlew -p android/core test
// 该命令是 config.yaml 完整回归命令的尾段（tech/android-client 的
// core-jvm-tests-in-regression 规则）。:app 经 android/settings.gradle.kts 的
// includeBuild("core") 以 work.keepwriting:core 坐标依赖本构建。
// 后续变更把 URL 校验与 token 剥离、同源判定、tailnet 分流、代理协议等纯逻辑
// 下沉到这里（tech/android-client 的 pure-logic-in-core 规则）。

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "core"
