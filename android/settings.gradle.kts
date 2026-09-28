pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.library") version "8.7.3"
        id("com.android.application") version "8.7.3"
        kotlin("android") version "2.0.21"
    }
}
dependencyResolutionManagement {
    repositories {
        maven(rootDir.resolve("local-maven")) { content { includeGroup("com.k2fsa.sherpa.onnx"); includeGroup("com.davoice") } } // fetch_models.sh, fetch_davoice.sh
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
    }
}
rootProject.name = "wakeword"
includeBuild("wakeword-core")
include(":wakeword-android")
include(":demo-app")
include(":davoice-demo")
