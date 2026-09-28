buildscript {
    // The Android and KSP plugins are only needed (and only downloaded) when settings.gradle.kts includes
    // the Android app. They share the root classloader with the Kotlin plugins below, like `apply false`.
    if (gradle.extra.has("readarea.android") && gradle.extra["readarea.android"] == true) {
        repositories {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            maven("https://maven-central.storage-download.googleapis.com/maven2/")
            mavenCentral()
            gradlePluginPortal()
        }
        dependencies {
            classpath(libs.android.gradlePlugin)
            classpath(libs.ksp.gradlePlugin)
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
