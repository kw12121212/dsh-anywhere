// Android 应用是本仓库内一个自成一体的 Gradle 多模块构建（tech/android-client 的
// android-module-layout 规则）：:app 依赖 Android SDK 与签名 keystore，不进完整
// 回归命令；纯 JVM 的 core（URL 校验、同源判定、分流、代理协议等纯逻辑的家）是
// includeBuild 引入的独立构建（android/core），只装 JDK 17+ 的机器也能跑它的测试
//（android/gradlew -p android/core test）。
// 依赖解析必须完全由本文件钉死，不依赖开发机上的任何全局配置。

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dsh-anywhere"
// 纯 JVM 逻辑层：独立构建（见 core/settings.gradle.kts），:app 以 work.keepwriting:core
// 坐标依赖，Gradle 以依赖替换编译同一份源码。
includeBuild("core")
include(":app")
