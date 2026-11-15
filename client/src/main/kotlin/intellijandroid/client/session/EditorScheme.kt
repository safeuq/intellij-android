package intellijandroid.client.session

import com.jetbrains.ide.model.highlighterRegistration.ColorsSchemeModel
import com.jetbrains.ide.model.highlighterRegistration.IdeaTextAttributes
import com.jetbrains.ide.model.highlighterRegistration.IdeaTextAttributesKey
import com.jetbrains.ide.model.highlighterRegistration.TextAttributesKeyModel
import com.jetbrains.rd.ide.model.AbstractColor
import com.jetbrains.rd.ide.model.IdeaColorKey
import com.jetbrains.rd.ide.model.RgbColor
import com.jetbrains.rd.ide.model.RgbaColor

/** IntelliJ's `EffectType`, as sent in `IdeaTextAttributes.effectType`. */
enum class TextEffect { LINE_UNDERSCORE, WAVE_UNDERSCORE, BOXED, STRIKEOUT, BOLD_LINE_UNDERSCORE, BOLD_DOTTED_LINE }

/** Resolved text attributes. Colors are ARGB; null means "keep what is below" (the default foreground, no background). */
data class TextStyle(
    val foreground: Long? = null,
    val background: Long? = null,
    /** IntelliJ `Font` style bits: 1 bold, 2 italic. */
    val fontType: Int = 0,
    val effect: TextEffect? = null,
    val effectColor: Long? = null,
) {
    val isBold get() = fontType and 1 != 0
    val isItalic get() = fontType and 2 != 0
}

/**
 * One editor color scheme, resolved like IntelliJ's `EditorColorsSchemeImpl`: for a key, look in the scheme, then its
 * parent scheme; if neither has an entry, move to the key's fallback (`JAVA_KEYWORD` → `DEFAULT_KEYWORD`) and repeat.
 * The backend sends each scheme with only its own entries ("Islands Dark" has 154; Darcula supplies the rest).
 */
class EditorScheme(val name: String, private val chain: List<ColorsSchemeModel>) {
    private val attributes: List<Map<String, IdeaTextAttributes>> = chain.map { scheme ->
        scheme.attributes.filter { !it.isInherited && it.attributes != null }.associate { it.textAttributesKey.externalName to it.attributes!! }
    }
    private val colors: List<Map<String, Long>> = chain.map { scheme ->
        scheme.colors.filter { !it.isInherited }.mapNotNull { entry -> entry.color?.toArgb()?.let { entry.colorKey.externalName to it } }.toMap()
    }
    private val colorFallbacks: Map<String, String> = chain.flatMap { it.colors }
        .mapNotNull { entry -> (entry.colorKey as? IdeaColorKey)?.fallback?.let { entry.colorKey.externalName to it.externalName } }
        .toMap()

    val defaultText: TextStyle = style(listOf(listOf("TEXT")))
    val background: Long = defaultText.background ?: error("Scheme $name has no TEXT background")
    val foreground: Long = defaultText.foreground ?: error("Scheme $name has no TEXT foreground")

    /** [keyChains]: for each key of a highlighter, the key followed by its fallbacks. The first key that resolves wins. */
    fun style(keyChains: List<List<String>>): TextStyle {
        for (keyChain in keyChains) {
            for (key in keyChain) {
                for (level in attributes) {
                    level[key]?.let { return it.toStyle() }
                }
            }
        }
        return TextStyle()
    }

    /** A scheme color such as `CARET_ROW_COLOR`; follows color-key fallbacks too. */
    fun color(key: String): Long? {
        var current: String? = key
        while (current != null) {
            for (level in colors) level[current]?.let { return it }
            current = colorFallbacks[current]
        }
        return null
    }

    companion object {
        /** Parents of the bundled schemes. ponytail: hard-coded; the protocol doesn't send a scheme's parent. */
        private val PARENTS = mapOf("Islands Dark" to "Darcula", "Islands Light" to "Default")

        fun resolve(name: String, schemes: Map<String, ColorsSchemeModel>): EditorScheme? {
            val chain = generateSequence(name) { PARENTS[it] }.map(schemes::get).toList()
            return if (chain.all { it != null }) EditorScheme(name, chain.filterNotNull()) else null
        }

        /** The key followed by its fallbacks, for later resolution against whichever scheme is current. */
        fun keyChain(key: TextAttributesKeyModel): List<String> =
            generateSequence(key) { (it as? IdeaTextAttributesKey)?.fallback }.map { it.externalName }.toList()

        fun IdeaTextAttributes.toStyle() = TextStyle(
            foreground = foregroundColor?.toArgb(),
            background = backgroundColor?.toArgb(),
            fontType = fontType,
            // An effect without a color is not drawn in IntelliJ.
            effect = effectColor?.let { TextEffect.valueOf(effectType.name) },
            effectColor = effectColor?.toArgb(),
        )

        fun AbstractColor.toArgb(): Long? = when (this) {
            is RgbaColor -> (alpha.toLong() shl 24) or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
            is RgbColor -> (0xFFL shl 24) or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
            else -> null
        }
    }
}
