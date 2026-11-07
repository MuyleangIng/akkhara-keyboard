// Akkhara keyboard app module.
//   ./gradlew assembleDebug     → app/build/outputs/apk/debug/app-debug.apk  (for testing)
//   ./gradlew assembleRelease   → dist/Akkhara-<version>.apk                 (signed, to share/install)
// Release signing reads keystore.properties (git-ignored; keep a backup of it + keystore/:
// every future update must be signed with the SAME key, or phones refuse to update).
// The model (AKKHARA-MINI, ONNX int8, ~18.5 MB) is copied from ../models/akkhara-mini/onnx
// into the app's assets before every build (task copyModel), so it ships inside the APK.

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.akkhara.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.akkhara.keyboard"
        minSdk = 26          // Android 8.0+
        targetSdk = 35       // Android 15 (Galaxy S25 runs Android 15/16)
        versionCode = 14
        versionName = "0.10.0"
        // phone CPUs only: arm64 (all new phones, e.g. Galaxy S25) + 32-bit arm (older phones)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // ONNX Runtime uses reflection/JNI: keep classes as they are
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // .onnx files are already compact; don't compress them again (faster loading)
    androidResources { noCompress += listOf("onnx") }
}

dependencies {
    // ONNX Runtime for Android: runs encoder.onnx / decoder.onnx on the phone CPU
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// Copy the int8 model + tokenizer into assets/akkhara-mini/ before building.
val copyModel by tasks.registering(Copy::class) {
    from(rootProject.file("../models/akkhara-mini/onnx")) {
        include("encoder.int8.onnx", "decoder.int8.onnx", "tokenizer.json")
    }
    into(file("src/main/assets/akkhara-mini"))
}
tasks.named("preBuild") { dependsOn(copyModel) }

// After a release build, copy the APK to dist/Akkhara-<version>.apk (easy to find and share).
tasks.register<Copy>("distApk") {
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.file("dist"))
    rename { "Akkhara-${android.defaultConfig.versionName}.apk" }
}
afterEvaluate { tasks.named("assembleRelease") { finalizedBy("distApk") } }
