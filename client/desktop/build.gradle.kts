import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

val jetbrainsClientLib: String by rootProject.extra
val protocolJars: List<String> by rootProject.extra

dependencies {
    implementation(project(":compose"))
    implementation(files(protocolJars.map { "$jetbrainsClientLib/$it" }))
    implementation(compose.desktop.currentOs)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_21 }
}

compose.desktop {
    application {
        mainClass = "intellijandroid.client.MainKt"
        // Fonts and icons are read from the same JetBrains Client install as the protocol jars.
        jvmArgs("-Djetbrains.client.lib=$jetbrainsClientLib")
    }
}
