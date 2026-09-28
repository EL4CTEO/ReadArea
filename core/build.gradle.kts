import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-independent book model, parsers and reading logic shared by the Android and desktop apps.
// Pure Kotlin on the JVM with no third-party dependencies, compiled for Java 17 so Android can use it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    maxHeapSize = "1g"
}
