// Standalone QA app: the open-vocabulary engine plus the DaVoice SDK, switchable. Install on a phone:
//   ../fetch_models.sh && ../fetch_davoice.sh && ../gradlew :demo-app:installDebug
// DaVoice licence key: paste it in the app, or bake a default in with -PdavoiceLicense=... / $DAVOICE_LICENSE.
plugins {
    id("com.android.application")
    kotlin("android")
}

val davoiceLicense = (findProperty("davoiceLicense") as String?) ?: System.getenv("DAVOICE_LICENSE") ?: ""

android {
    namespace = "com.findmyphone.wakeword.demo"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.findmyphone.wakeword.demo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            // CI emulator only (./gradlew ... -PemulatorAbi): phones never need x86.
            if (project.hasProperty("emulatorAbi")) abiFilters += "x86_64"
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DAVOICE_LICENSE", "\"${davoiceLicense.replace("\"", "")}\"")
    }
    // Committed debug key (not a secret): every CI build gets the same signature, so testers can
    // install a new build over the old one instead of uninstalling first.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("onnx", "dm") }
}

dependencies {
    implementation(project(":wakeword-android"))
    implementation("com.findmyphone.wakeword:wakeword-core:0.1.0")
    // DaVoice SDK, repackaged by ../fetch_davoice.sh to share sherpa-onnx's libonnxruntime.so.
    implementation("com.davoice:keyworddetection:1.0.0")
    implementation("ai.picovoice:android-voice-processor:1.0.2") // declared by DaVoice's own wrapper

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
