// 顶层构建脚本。
// v1.1：官方 AndroidLiquidGlass 的 backdrop 2.0.0 / shapes 1.2.0 以本地 AAR（app/libs）引入，
// 与 AndroidX Compose 1.11 配套；Kotlin 2.4.10 + 官方 Compose 编译器插件。
// 不用官方 2.0.1：它把整条 Compose 1.12 依赖链抬到 compileSdk 37 + AGP 9.1+，
// 而 Google 公开 SDK 平台最高只有 API 36（详见 README §0）。
// AGP 8.13.0 + Gradle 8.13 + JDK 17 编译；compileSdk/targetSdk 36。
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
