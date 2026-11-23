import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.liquidlyre.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.liquidlyre.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 14
        versionName = "1.6.5"
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
        getByName("release") {
            isMinifyEnabled = false
            // 产出 unsigned 包，由交付脚本用自建 keystore 正式签名。
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    sourceSets {
        getByName("main") { java.srcDirs("src/main/kotlin") }
    }

    kotlin {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    packaging {
        resources { excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // 液态玻璃：官方 AndroidLiquidGlass 本地 AAR（Apache-2.0）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.11.0")
    implementation("androidx.compose.ui:ui-graphics:1.11.0")
    implementation("androidx.compose.foundation:foundation:1.11.0")
    implementation("androidx.compose.animation:animation:1.11.0")
    implementation("androidx.compose.runtime:runtime:1.11.0")

    // ViewModel + Flow + 生命周期
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")

    // DataStore（偏好 / 目录授权持久化）
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
    // JVM 单测需要真实 org.json（android.jar 里的是返回默认值的 stub）
    testImplementation("org.json:json:20240303")
}
