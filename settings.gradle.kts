pluginManagement {
    includeBuild("build-logic")
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
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Only Google's own artifacts come from Google Maven, so no other group can be served from there.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "ReadArea"

// The Android app needs the Android SDK. Machines and CI jobs without one still build the shared core
// and the desktop app. Force either way with -Preadarea.android=true|false.
val androidBuild: Boolean = providers.gradleProperty("readarea.android").orNull?.toBoolean() ?: run {
    val env = providers.environmentVariable("ANDROID_HOME").orNull ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
    val local = file("local.properties")
    env != null || (local.isFile && local.readLines().any { it.trimStart().startsWith("sdk.dir") })
}
gradle.extra["readarea.android"] = androidBuild

include(":core")
include(":desktop")
if (androidBuild) include(":app")
