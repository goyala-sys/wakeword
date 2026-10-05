// Separate test app for the VoxRT wake-word SDK ("Hey Assistant" only; custom phrases are a paid
// VoxRT service). Installs alongside the QA app. Build:
//   ../fetch_voxrt.sh && ../gradlew :voxrt-demo:assembleDebug
// The SDK and model are fetched into vendor/ (gitignored): their licences forbid redistributing
// them on their own, only bundled unmodified in an app.
plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.findmyphone.wakeword.voxrt"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.findmyphone.wakeword.voxrt"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk {
            abiFilters += "arm64-v8a"
            if (project.hasProperty("emulatorAbi")) abiFilters += "x86_64"
        }
    }
    // Same committed debug key as :demo-app, so new builds install over old ones.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    sourceSets["main"].apply {
        java.srcDir("vendor/java")
        jniLibs.srcDir("vendor/jniLibs")
        assets.srcDir("vendor/assets")
    }
    packaging { jniLibs { useLegacyPackaging = true } } // as VoxRT's own module ships it
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("vxrt") }
}
