package intellijandroid.client.ui

import java.io.File
import java.util.zip.ZipFile

/**
 * IntelliJ's own icons from `intellij.platform.icons.jar`, picked like a dark theme does: `<name>_dark.svg` first,
 * then `<name>.svg`; then the Islands Dark palette swap `#CED0D6` → `#C3C5CB`. Each platform turns the SVG text into a
 * painter (desktop: Skia; Android: AndroidSVG).
 */
class IconJar(file: File) {
    private val jar = ZipFile(file.also { check(it.isFile) { "Missing icons jar $it" } })

    /** The SVG text for [path], e.g. `expui/nodes/folder.svg`, or null when the jar has no such icon. */
    fun svg(path: String): String? {
        val name = path.removePrefix("/")
        val entry = jar.getEntry(name.removeSuffix(".svg") + "_dark.svg") ?: jar.getEntry(name) ?: return null
        return jar.getInputStream(entry).use { it.readBytes().decodeToString() }.replace("#CED0D6", "#C3C5CB", ignoreCase = true)
    }
}
