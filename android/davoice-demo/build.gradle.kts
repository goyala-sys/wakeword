// Test app for the DaVoice wake-word SDK: fixed-phrase models trained by DaVoice, for comparison
// with the open-vocabulary sherpa engine in :demo-app. Separate app (installs side by side) because
// both SDKs bundle their own ONNX Runtime native libraries.
//   ../fetch_davoice.sh && ../gradlew :davoice-demo:assembleDebug
// Licence key: paste it in the app, or bake a default in with -PdavoiceLicense=... / $DAVOICE_LICENSE.
plugins {
    id("com.android.application")
    kotlin("android")
}

val davoiceLicense = (findProperty("davoiceLicense") as String?) ?: System.getenv("DAVOICE_LICENSE") ?: ""

android {
    namespace = "com.findmyphone.wakeword.davoice"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.findmyphone.wakeword.davoice"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" } // the only phone ABI the DaVoice AAR ships
        buildConfigField("String", "DAVOICE_LICENSE", "\"${davoiceLicense.replace("\"", "")}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("dm") }
}

dependencies {
    implementation("com.davoice:keyworddetection:1.0.0") // local-maven, from ../fetch_davoice.sh
    implementation("ai.picovoice:android-voice-processor:1.0.2") // declared by DaVoice's own wrapper
}
