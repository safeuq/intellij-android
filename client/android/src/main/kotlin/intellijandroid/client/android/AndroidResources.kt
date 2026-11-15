package intellijandroid.client.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import intellijandroid.client.ui.ClientFonts
import intellijandroid.client.ui.IconJar
import intellijandroid.client.ui.IconProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/** Fonts and icons packaged as assets by the `clientAssets` Gradle task, from JetBrains Client's install. */
object AndroidResources {
    fun fonts(context: Context): ClientFonts {
        val assets = context.assets
        fun font(name: String, weight: FontWeight, style: FontStyle = FontStyle.Normal) = Font("fonts/$name", assets, weight, style)
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

    /** [IconJar] needs a file; the jar asset (5 MB) is copied out on every start, so an app update brings new icons. */
    fun iconJar(context: Context): IconJar {
        val file = File(context.noBackupFilesDir, "intellij.platform.icons.jar")
        context.assets.open(file.name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return IconJar(file)
    }

    /** Icons from [IconJar], drawn once into a bitmap at the screen's density with AndroidSVG. */
    class SvgIcons(private val jar: IconJar, private val density: Float) : IconProvider {
        private val cache = ConcurrentHashMap<String, Painter?>()

        override fun painter(path: String): Painter? = cache.computeIfAbsent(path) { jar.svg(it)?.let(::render) }

        private fun render(text: String): Painter {
            val svg = SVG.getFromString(text)
            // Width and height in the SVG are in logical px (16 for most icons); -1 when missing.
            val width = svg.documentWidth.takeIf { it > 0 } ?: 16f
            val height = svg.documentHeight.takeIf { it > 0 } ?: 16f
            // AndroidSVG scales to the viewport only when the size is relative: with width="16" it draws 16 px.
            if (svg.documentViewBox == null) svg.setDocumentViewBox(0f, 0f, width, height)
            svg.setDocumentWidth("100%")
            svg.setDocumentHeight("100%")
            val bitmap = Bitmap.createBitmap(ceil(width * density).toInt(), ceil(height * density).toInt(), Bitmap.Config.ARGB_8888)
            svg.renderToCanvas(Canvas(bitmap), RenderOptions().viewPort(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
            return BitmapPainter(bitmap.asImageBitmap())
        }
    }
}
