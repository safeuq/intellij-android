import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.kotlin.multiplatform.library")
}

val jetbrainsClientLib: String by rootProject.extra
val protocolJars: List<String> by rootProject.extra

val protocolJarFiles = protocolJars.map { name ->
    file("$jetbrainsClientLib/$name").also { jar -> check(jar.isFile) { "Missing $jar: is JETBRAINS_CLIENT_LIB the client's Contents/lib folder?" } }
}

kotlin {
    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_21 }
    }
    android {
        namespace = "intellijandroid.client.compose"
        compileSdk = 37
        // java.util.HexFormat (client certificate fingerprints) needs 34.
        minSdk = 34
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }

    sourceSets {
        commonMain.dependencies {
            compileOnly(files(protocolJarFiles))
            implementation("org.bouncycastle:bcpkix-jdk18on:1.82")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
        }
        jvmTest.dependencies {
            implementation(files(protocolJarFiles))
            implementation(kotlin("test"))
        }
    }
}

tasks.named<Test>("jvmTest") { useJUnitPlatform() }
