import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.12.2"
}

repositories {
    mavenCentral()
    google()
}

// The rd library and the generated protocol models are taken from JetBrains Client itself. They must be
// the same build as the backend (263.7307.200): both sides compare a schema hash per model.
// Example: ~/Library/Caches/JetBrains/JetBrainsClientDist/JetBrainsClient-263.7307.200-aarch64.sit-…/IntelliJ IDEA.app/Contents/lib
val jetbrainsClientLib: String = providers.gradleProperty("jetbrainsClientLib")
    .orElse(providers.environmentVariable("JETBRAINS_CLIENT_LIB"))
    .orNull
    ?: error("Set JETBRAINS_CLIENT_LIB (or -PjetbrainsClientLib=…) to JetBrains Client 263.7307.200's Contents/lib folder")

val protocolJars = listOf(
    "intellij.libraries.rd.core.jar",
    "intellij.libraries.rd.framework.jar",
    "intellij.libraries.rd.text.jar",
    "intellij.platform.split.protocol.jar",
    "intellij.rd.ide.model.generated.jar",
).map { name ->
    file("$jetbrainsClientLib/$name").also { jar -> check(jar.isFile) { "Missing $jar: is JETBRAINS_CLIENT_LIB the client's Contents/lib folder?" } }
}

dependencies {
    implementation(files(protocolJars))
    implementation("org.bouncycastle:bcpkix-jdk18on:1.82")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(kotlin("test"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_21 }
}

tasks.test { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "intellijandroid.client.MainKt"
        // Fonts and icons are read from the same JetBrains Client install as the protocol jars.
        jvmArgs("-Djetbrains.client.lib=$jetbrainsClientLib")
    }
}
