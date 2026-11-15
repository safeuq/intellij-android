package intellijandroid.client.desktop

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import intellijandroid.client.ui.ClientFonts
import intellijandroid.client.ui.IconProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Desktop sources for fonts and icons: both ship with JetBrains Client, next to the protocol jars.
 * Example: `…/IntelliJ IDEA.app/Contents/lib` (icons jar) and `…/Contents/jbr/Contents/Home/lib/fonts` (fonts).
 */
object DesktopResources {
    fun fonts(clientLib: File): ClientFonts {
        val fontDirectory = clientLib.resolveSibling("jbr/Contents/Home/lib/fonts")
        fun font(name: String, weight: FontWeight, style: FontStyle = FontStyle.Normal) =
            Font(fontDirectory.resolve(name).also { check(it.isFile) { "Missing font $it" } }, weight, style)
        return ClientFonts(
            editor = FontFamily(
                font("JetBrainsMono-Regular.ttf", FontWeight.Normal),
                font("JetBrainsMono-Bold.ttf", FontWeight.Bold),
                font("JetBrainsMono-Italic.ttf", FontWeight.Normal, FontStyle.Italic),
                font("JetBrainsMono-BoldItalic.ttf", FontWeight.Bold, FontStyle.Italic),
            ),
            ui = FontFamily(
                font("Inter-Regular.otf", FontWeight.Normal),
                font("Inter-SemiBold.otf", FontWeight.SemiBold),
            ),
        )
    }

    /**
     * IntelliJ's own icons from `intellij.platform.icons.jar`, picked like a dark theme does: `<name>_dark.svg` first,
     * then `<name>.svg`; then the Islands Dark palette swap `#CED0D6` → `#C3C5CB`.
     */
    class JarIcons(clientLib: File, private val density: Density) : IconProvider {
        private val jar = ZipFile(clientLib.resolve("intellij.platform.icons.jar").also { check(it.isFile) { "Missing $it" } })
        private val cache = ConcurrentHashMap<String, Painter?>()

        override fun painter(path: String): Painter? = cache.computeIfAbsent(path, ::load)

        private fun load(path: String): Painter? {
            val name = path.removePrefix("/")
            val entry = jar.getEntry(name.removeSuffix(".svg") + "_dark.svg") ?: jar.getEntry(name) ?: return null
            val svg = jar.getInputStream(entry).use { it.readBytes().decodeToString() }.replace("#CED0D6", "#C3C5CB", ignoreCase = true)
            return loadSvgPainter(svg.byteInputStream(), density)
        }
    }
}
