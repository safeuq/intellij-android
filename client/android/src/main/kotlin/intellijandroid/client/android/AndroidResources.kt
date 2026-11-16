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

    /** [IconJar] needs a file; the jar asset (all of the client's SVGs) is copied out on every start, so an app update brings new icons. */
    fun iconJar(context: Context): IconJar {
        val file = File(context.noBackupFilesDir, "icons.jar")
        context.assets.open(file.name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return IconJar(file)
    }

    /** Icons from [IconJar], drawn once into a bitmap at the screen's density with AndroidSVG. */
    class SvgIcons(private val jar: IconJar, private val density: Float) : IconProvider {
        private val cache = ConcurrentHashMap<String, Painter?>()

        override fun painter(path: String): Painter? = cache.computeIfAbsent(path) { (jar.svg(it) ?: EXTRA_ICONS[it])?.let(::render) }

        private companion object {
            /**
             * Icons that aren't in the platform icons jar. `markdown/link.svg`: the Markdown plugin's chain link
             * (`icons/expui/editorActions/link_dark.svg`, Apache 2.0, JetBrains), the icon Gateway shows for Link.
             */
            val EXTRA_ICONS = mapOf(
                "markdown/link.svg" to """<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
<path d="M7.09656 3.19658C8.67248 1.62069 11.2275 1.62067 12.8035 3.19653C14.3795 4.77245 14.3795 7.3276 12.8036 8.90359L11.8529 9.85434C11.6576 10.0496 11.341 10.0496 11.1458 9.85435C10.9505 9.6591 10.9505 9.34251 11.1458 9.14725L12.0965 8.1965C13.2819 7.01104 13.2819 5.08906 12.0964 3.90366C10.911 2.7183 8.98906 2.71832 7.80366 3.9037L6.85287 4.85446C6.65761 5.04972 6.34103 5.04972 6.14577 4.85445C5.95051 4.65919 5.95051 4.34261 6.14577 4.14735L7.09656 3.19658Z" fill="#C3C5CB"/>
<path d="M4.85288 6.14731C5.04814 6.34258 5.04814 6.65916 4.85287 6.85442L3.90376 7.80351C2.71827 8.98898 2.71828 10.911 3.90378 12.0965C5.08926 13.2819 7.01126 13.2819 8.19669 12.0964L9.14661 11.1465C9.34187 10.9512 9.65846 10.9512 9.85372 11.1465C10.049 11.3417 10.049 11.6583 9.85373 11.8536L8.90381 12.8035C7.32786 14.3795 4.77269 14.3796 3.19669 12.8036C1.62065 11.2276 1.62063 8.67239 3.19666 7.0964L4.14577 6.14731C4.34104 5.95205 4.65762 5.95205 4.85288 6.14731Z" fill="#C3C5CB"/>
<path d="M10.3536 6.35355C10.5488 6.15829 10.5488 5.84171 10.3536 5.64645C10.1583 5.45118 9.84171 5.45118 9.64645 5.64645L5.64645 9.64645C5.45118 9.84171 5.45118 10.1583 5.64645 10.3536C5.84171 10.5488 6.15829 10.5488 6.35355 10.3536L10.3536 6.35355Z" fill="#C3C5CB"/>
</svg>""",
            )
        }

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
