// LiquidLyre（璃音）/ 单 module 工程
// 仓库优先级放在 settings 中统一管理，避免各 module 各自声明仓库造成解析冲突。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LiquidLyre"
include(":app")
