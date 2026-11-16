import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.ow2.asm:asm:9.9") }
}

plugins {
    id("com.android.application") version "9.5.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}

// Same inputs as the desktop client: JetBrains Client 263.7307.200's Contents/lib folder (protocol jars, icons) and
// its JBR (fonts). Example: ~/Library/Caches/JetBrains/JetBrainsClientDist/JetBrainsClient-263.7307.200-aarch64.sit-…/IntelliJ IDEA.app/Contents/lib
val jetbrainsClientLib: String = providers.gradleProperty("jetbrainsClientLib")
    .orElse(providers.environmentVariable("JETBRAINS_CLIENT_LIB"))
    .orNull
    ?: error("Set JETBRAINS_CLIENT_LIB (or -PjetbrainsClientLib=…) to JetBrains Client 263.7307.200's Contents/lib folder")

fun clientFile(path: String): File =
    file("$jetbrainsClientLib/$path").also { check(it.exists()) { "Missing $it: is JETBRAINS_CLIENT_LIB the client's Contents/lib folder?" } }

val protocolJars = listOf(
    "intellij.libraries.rd.core.jar",
    "intellij.libraries.rd.framework.jar",
    "intellij.libraries.rd.text.jar",
    "intellij.platform.split.protocol.jar",
    "intellij.rd.ide.model.generated.jar",
)

// ArtUnsafeShim uses sun.misc.Unsafe, which android.jar doesn't have, so it is compiled against the JDK, as rd is.
val compileArtShim by tasks.registering(JavaCompile::class) {
    source = fileTree("rd-patch")
    classpath = files()
    destinationDirectory = layout.buildDirectory.dir("art-shim")
    sourceCompatibility = "17"
    targetCompatibility = "17"
    options.release = 17
    // Every sun.misc.Unsafe use warns "internal proprietary API"; that use is the point of the shim.
    options.isWarnings = false
}

/**
 * Makes JetBrains Client's protocol jars loadable on ART (docs/split-protocol.md, "Running rd and the models on Android"):
 * - class files newer than Java 17 (version 61) get version 61, because D8 rejects 69 (Java 25);
 * - rd's UnsafeBuffer calls ArtUnsafeShim instead of the sun.misc.Unsafe methods ART lacks, and the shim goes into the jar.
 * Resource files (`splitMarshallers.txt`, `marshallers.txt`) are copied as they are.
 */
abstract class PrepareProtocolJars : DefaultTask() {
    @get:InputFiles abstract val jars: ConfigurableFileCollection
    @get:InputFiles abstract val shimClasses: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val output = outputDirectory.get().asFile.apply { deleteRecursively(); mkdirs() }
        for (jar in jars) {
            var rewrittenCalls = 0
            ZipFile(jar).use { input ->
                ZipOutputStream(output.resolve(jar.name).outputStream()).use { zip ->
                    for (entry in input.entries()) {
                        var data = input.getInputStream(entry).use { it.readBytes() }
                        if (entry.name.startsWith("com/jetbrains/rd/framework/UnsafeBuffer") && entry.name.endsWith(".class")) {
                            val (patched, count) = routeUnsafeToShim(data)
                            data = patched
                            rewrittenCalls += count
                        }
                        if (entry.name.endsWith(".class")) data = lowerClassVersion(data)
                        zip.putNextEntry(ZipEntry(entry.name))
                        zip.write(data)
                        zip.closeEntry()
                    }
                    if (rewrittenCalls > 0) {
                        val shimRoots = shimClasses.files
                        for (root in shimRoots) root.walkTopDown().filter { it.isFile }.forEach { classFile ->
                            zip.putNextEntry(ZipEntry(classFile.relativeTo(root).invariantSeparatorsPath))
                            zip.write(classFile.readBytes())
                            zip.closeEntry()
                        }
                    }
                }
            }
            if (jar.name == "intellij.libraries.rd.framework.jar") {
                // 105 in rd 263.7307.200; zero would mean rd changed and the shim no longer covers it.
                check(rewrittenCalls > 0) { "No sun.misc.Unsafe uses found in UnsafeBuffer of $jar" }
                logger.lifecycle("${jar.name}: routed $rewrittenCalls sun.misc.Unsafe uses to ArtUnsafeShim")
            }
        }
    }

    private fun lowerClassVersion(data: ByteArray): ByteArray {
        val major = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (major <= 61) return data
        return data.copyOf().also { it[6] = 0; it[7] = 61 }
    }

    private fun routeUnsafeToShim(data: ByteArray): Pair<ByteArray, Int> {
        val unsafe = "sun/misc/Unsafe"
        val shim = "com/jetbrains/rd/framework/ArtUnsafeShim"
        var count = 0
        val writer = ClassWriter(0)
        ClassReader(data).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    override fun visitFieldInsn(opcode: Int, owner: String, fieldName: String, fieldDescriptor: String) {
                        if (opcode == Opcodes.GETSTATIC && owner == unsafe && fieldName.startsWith("ARRAY_")) {
                            count++
                            super.visitFieldInsn(opcode, shim, fieldName, fieldDescriptor)
                        } else {
                            super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor)
                        }
                    }

                    override fun visitMethodInsn(opcode: Int, owner: String, methodName: String, methodDescriptor: String, isInterface: Boolean) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && owner == unsafe) {
                            // receiver.method(args) becomes ArtUnsafeShim.method(receiver, args)
                            count++
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, shim, methodName, "(L$unsafe;" + methodDescriptor.substring(1), false)
                        } else {
                            super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface)
                        }
                    }
                }
        }, 0)
        return writer.toByteArray() to count
    }
}

val prepareProtocolJars by tasks.registering(PrepareProtocolJars::class) {
    jars.from(protocolJars.map(::clientFile))
    shimClasses.from(compileArtShim)
    outputDirectory = layout.buildDirectory.dir("protocol-jars")
}

/**
 * Fonts (JetBrains Mono, Inter) from JetBrains Client's JBR, and `icons.jar`: every SVG from the client's jars, platform
 * and plugins. The backend names icons by path (plus a plugin we ignore), e.g. Kotlin's file icons live in
 * `intellij.kotlin.base.resources.jar`, not in the platform icons jar. About 6,100 SVGs, 7 MB before compression.
 */
abstract class ClientAssets : DefaultTask() {
    @get:InputFiles abstract val fonts: ConfigurableFileCollection
    /** The first jar with a given path wins, so the platform icons jar goes first. */
    @get:InputFiles abstract val iconJars: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val output = outputDirectory.get().asFile.apply { deleteRecursively(); mkdirs() }
        fonts.forEach { it.copyTo(output.resolve("fonts/${it.name}")) }
        val written = mutableSetOf<String>()
        ZipOutputStream(output.resolve("icons.jar").outputStream()).use { icons ->
            iconJars.forEach { jar ->
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence().filter { it.name.endsWith(".svg") && written.add(it.name) }.forEach { entry ->
                        icons.putNextEntry(ZipEntry(entry.name))
                        zip.getInputStream(entry).use { it.copyTo(icons) }
                        icons.closeEntry()
                    }
                }
            }
        }
    }
}

val clientAssets by tasks.registering(ClientAssets::class) {
    val fontDirectory = clientFile("../jbr/Contents/Home/lib/fonts")
    fonts.from(listOf("JetBrainsMono-Regular.ttf", "JetBrainsMono-Bold.ttf", "JetBrainsMono-Italic.ttf", "JetBrainsMono-BoldItalic.ttf", "Inter-Regular.otf", "Inter-SemiBold.otf")
        .map { fontDirectory.resolve(it).also { font -> check(font.isFile) { "Missing font $font" } } })
    // ponytail: one path space for all plugins; two plugins with the same icon path get the first one. Key by plugin if that shows up.
    iconJars.from(clientFile("intellij.platform.icons.jar"), fileTree(clientFile("..")) { include("lib/**/*.jar", "plugins/**/*.jar") })
    outputDirectory = layout.buildDirectory.dir("generated/client-assets")
}

android {
    namespace = "intellijandroid.client.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "intellijandroid.client.android"
        // java.util.HexFormat (client certificate fingerprints) needs 34.
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The connection, session and UI code is shared with the desktop client.
    sourceSets.named("main") { kotlin.srcDir("../src/main/kotlin") }

    packaging {
        resources.excludes += listOf("META-INF/versions/**", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    }
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(clientAssets, ClientAssets::outputDirectory) }
}

dependencies {
    implementation(files(protocolJars.map { name -> prepareProtocolJars.flatMap { it.outputDirectory.file(name) } }))
    implementation("org.bouncycastle:bcpkix-jdk18on:1.82")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.caverock:androidsvg-aar:1.4")
}
