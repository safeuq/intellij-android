plugins {
    kotlin("multiplatform") version "2.3.21" apply false
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
    id("com.android.application") version "9.5.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.5.1" apply false
}

// The rd library and the generated protocol models are taken from JetBrains Client itself. They must be
// the same build as the backend (263.7307.200): both sides compare a schema hash per model.
// Example: ~/Library/Caches/JetBrains/JetBrainsClientDist/JetBrainsClient-263.7307.200-aarch64.sit-…/IntelliJ IDEA.app/Contents/lib
val jetbrainsClientLib by extra(
    providers.gradleProperty("jetbrainsClientLib")
        .orElse(providers.environmentVariable("JETBRAINS_CLIENT_LIB"))
        .orNull
        ?: error("Set JETBRAINS_CLIENT_LIB (or -PjetbrainsClientLib=…) to JetBrains Client 263.7307.200's Contents/lib folder")
)

/** File names in [jetbrainsClientLib]. compose compiles against them; each app adds them at run time (android: patched for ART). */
val protocolJars by extra(
    listOf(
        "intellij.libraries.rd.core.jar",
        "intellij.libraries.rd.framework.jar",
        "intellij.libraries.rd.text.jar",
        "intellij.platform.split.protocol.jar",
        "intellij.rd.ide.model.generated.jar",
    )
)
