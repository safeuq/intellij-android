# IntelliJ's look: Islands Dark editor and Project tree

Reference values for `client/` (used by `client/compose/src/commonMain/kotlin/intellijandroid/client/ui/IslandsDark.kt` and `EditorView.kt`). Researched 2026-11-15. The client takes editor text colors from the backend's color scheme at run time; the tables below are for the chrome around it and for checking.


Legend: **Seen** = read in an installed file or in source at tag `idea/263.7307.200`. **Inferred** = computed from Seen code/values, or not readable without running the IDE.
All lengths are logical px (dp). On Retina (scale 2) multiply by 2 for device px.
Install root: `IDE=<IntelliJ IDEA.app>/Contents`. GH = github.com/JetBrains/intellij-community at tag `idea/263.7307.200`.
Values were read from the installed IDE's jars and from the IntelliJ Community sources at the tag above.

Things I could NOT read (user config is off limits) and that change numbers; confirm with the user:
1. Editor line spacing (default 1.2, `FontPreferences.DEFAULT_LINE_SPACING`). The user may have changed it.
2. Compact mode (default off). If on, row height 22, tree border 2,8,2,8, header height 31, etc. (values given below as "compact").
3. Whether line numbers / folding outline / git change markers / breadcrumbs are shown (assumed default: line numbers on, folding outline on, gutter icons on, left free painters (git markers) shown in a git project).

---------------------------------------------------------------------

## 0. How the theme resolves (Seen)

| Layer | File | Notes |
|---|---|---|
| LaF "Islands Dark" | `$IDE/lib/intellij.platform.resources.jar` `themes/islands/ManyIslandsDark.theme.json`; registered in `lib/intellij.platform.ide.impl.jar` plugin xml: `<themeProvider id="Islands Dark" path="/themes/islands/ManyIslandsDark.theme.json" targetUi="islands"/>` | `"parentTheme": "ExperimentalDark"`, `"editorScheme": "Islands Dark"`, `ui.Islands = 1` (many-islands mode on) |
| parent LaF | same jar `themes/expUI/expUI_dark.theme.json` (id ExperimentalDark) then `themes/darcula.theme.json` | provides keys the child does not override |
| color names | `colors` map in ManyIslandsDark.theme.json (gray-10..160, blue-.., etc.) and aliases such as `layer-0-bg` | resolved values in tables below |
| `ui."*"` wildcard | ManyIslandsDark.theme.json `ui["*"]` | rewrites EVERY existing key ending in `.background`, `.foreground`, `.selectionBackground`, `.selectionInactiveBackground`, `.hoverBackground`, `.infoForeground` ... (GH `platform/platform-impl/src/com/intellij/ide/ui/UITheme.kt` `applyTheme`: for key `*.x` all keys ending `.x` are overwritten, specific keys later in the file win). This is why `Tree.background` is NOT Darcula's `#3C3F41` |
| Editor scheme "Islands Dark" | `lib/intellij.platform.resources.jar` `themes/islands/IslandSchemeDark.xml` (registered `lib/intellij.platform.projectModel.impl.jar` `<bundledColorScheme id="Islands Dark" path="/themes/islands/IslandSchemeDark.xml"/>`) | `parent_scheme="Darcula"` |
| parent scheme "Darcula" | `lib/intellij.platform.resources.jar` `DefaultColorSchemesManager.xml` (second `<scheme name="Darcula" ... default_scheme="true">`, line 1562 on) | **Darcula does NOT inherit "Default"**: schemes with `default_scheme="true"` get `parentScheme = null` (GH `platform/editor-ui-ex/src/com/intellij/openapi/editor/colors/impl/AbstractColorsScheme.java` `readScheme`: `if (!isDefault) parentScheme = ...`). So the chain is Islands Dark -> Darcula only; the light-ish `Default` values (e.g. `INSTANCE_FIELD_ATTRIBUTES 660e7a`) are never used |
| attribute lookup rule | GH `.../colors/impl/EditorColorsSchemeImpl.java getAttributes` + `AbstractColorsScheme.getFallbackAttributes` | own scheme, then Darcula; entry with empty `<value>` = "inherit"; if no entry: follow `TextAttributesKey` fallback key (`DefaultLanguageHighlighterColors`, `JavaHighlightingColors` in GH `platform/core-api/.../DefaultLanguageHighlighterColors.java`, `java/java-frontback-psi-impl/.../JavaHighlightingColors.java`) |

Editor `FONT_TYPE`: 0 plain, 1 bold, 2 italic, 3 bold italic. Editor `EFFECT_TYPE` (GH `AttributesFlyweight.toEffectType`): 0 BOXED, 1 LINE_UNDERSCORE, 2 WAVE_UNDERSCORE, 3 STRIKEOUT, 4 BOLD_LINE_UNDERSCORE, 5 BOLD_DOTTED_LINE.

## 1. Fonts (Seen)

All inside the bundled JBR: `$IDE/jbr/Contents/Home/lib/fonts/`
| Use | File | Metrics (Seen, parsed from head/hhea/OS2) |
|---|---|---|
| Editor regular | `JetBrainsMono-Regular.ttf` | unitsPerEm 1000, ascent 1020, descent 300, lineGap 0, advance 600 (monospace, 0.6 em) |
| Editor bold (`FONT_TYPE` 1) | `JetBrainsMono-Bold.ttf` | |
| Editor italic (`FONT_TYPE` 2) | `JetBrainsMono-Italic.ttf` | JetBrains Mono has a true italic |
| Editor bold italic (3) | `JetBrainsMono-BoldItalic.ttf` | |
| other weights present | Thin, ExtraLight, Light, Medium, SemiBold, ExtraBold (+Italic each) | not used by the scheme |
| UI regular | `Inter-Regular.otf` | unitsPerEm 2816, ascent 2728, descent 680, lineGap 0 (0.969 / 0.241 em) |
| UI semibold | `Inter-SemiBold.otf` | used for "bold" UI text; there is no Inter Bold/Medium file |
| UI italic | `Inter-Italic.otf`, `Inter-SemiBoldItalic.otf` | |

Editor font size 12 -> char advance = 0.6 * 12 = **7.2 px** (fractional; fractional metrics are ON on macOS, see section 7). Line number font = editor font size + `editor.gutter.linenumber.font.size.delta` (default -1, `intellij.platform.ide.impl.xml` advancedSetting) = **11** -> advance 6.6 px.
Ligatures: **off** by default (`FontPreferencesImpl.myUseLigatures = false`, `AppEditorFontOptions.USE_LIGATURES = false`). Default family is JetBrains Mono; default size would be 13, user has 12 (GH `FontPreferences.java`).

## 2. Editor

### 2.1 Colors (Islands Dark scheme resolved through Darcula)

| Item | Value | Key | Source |
|---|---|---|---|
| Editor background | `#191A1C` | `TEXT` attr BACKGROUND (= `layer-0-bg`) | Seen `IslandSchemeDark.xml` |
| Default foreground | `#BCBEC4` | `TEXT` attr FOREGROUND; `DEFAULT_IDENTIFIER` also `#BCBEC4` | Seen |
| Caret color | `#CED0D6` | `CARET_COLOR` | Seen |
| Caret width | **2 px** logical (4 device px), bar caret, blinking | registry `editor.caret.width=2` (GH `platform/util/resources/misc/registry.properties`), `EditorCaretPainter` uses `JBUIScale.scale(settings.getLineCursorWidth()) * editor scale` | Seen |
| Caret height | = font metrics height (17 px at JBM 12), centered in the line, i.e. 2 px shorter than the 21 px line at top and bottom (full-line-height caret is off by default) | `EditorView.createNewMetrics` (caretHeight = lineHeight + topOverhang + bottomOverhang) | Inferred (arithmetic in 2.3) |
| Caret row background | `#1F2024` (spans full width, also painted across the gutter) | `CARET_ROW_COLOR`; `EditorGutterComponentImpl.getCaretRowColor/paintBackground` | Seen |
| Selection background (focused) | `#214283` | `SELECTION_BACKGROUND` (Darcula; Islands does not override) | Seen `DefaultColorSchemesManager.xml` |
| Selection background (inactive editor) | `#4C4F56` | `SELECTION_BACKGROUND_INACTIVE` (Darcula) | Seen |
| Selection foreground | none (text keeps its own colors) | `SELECTION_FOREGROUND` empty | Seen |
| Line number | `#4B5059` | `LINE_NUMBERS_COLOR` | Seen |
| Current line number (caret line) | `#A1A3AB` | `LINE_NUMBER_ON_CARET_ROW_COLOR` | Seen |
| Gutter background | = editor background `#191A1C` | New UI uses `EDITOR_GUTTER_BACKGROUND`; Islands sets it empty -> null -> `editor.getBackgroundColor()` (GH `EditorGutterColor.getEditorGutterBackgroundColor`). Darcula `GUTTER_BACKGROUND 313335` is NOT used in New UI | Seen |
| Gutter / editor separator | 1 px vertical line at x = gutterWidth - 3 (+1 on Retina), color `INDENT_GUIDE_COLOR` `#323438`; the last 3 px of the gutter are editor background | `EditorGutterComponentImpl.paint` (`isNewUI` branch), `getWhitespaceSeparatorOffset` = width - 3 | Seen (very low contrast, verify against a screenshot) |
| Indent guide | `#323438`, 1 px solid vertical line at the indent column start; selected (caret inside that block) `#4E5157` | `INDENT_GUIDE`, `SELECTED_INDENT_GUIDE`; `IndentGuideRenderer` | Seen |
| Right margin line | `#323438` | `RIGHT_MARGIN_COLOR` | Seen |
| Whitespace symbols | `#6F737A` | `WHITESPACES` | Seen |
| Matched brace | background `#43454A`, bold | `MATCHED_BRACE_ATTRIBUTES` | Seen |
| Folded text | fg `#868991`, bg `#393B40`; border `#2B2D30` | `FOLDED_TEXT_ATTRIBUTES`, `FOLDED_TEXT_BORDER_COLOR` | Seen |
| Identifier under caret | bg `#373B39` | `IDENTIFIER_UNDER_CARET_ATTRIBUTES` | Seen |
| Search result | bg `#2D543F` | `SEARCH_RESULT_ATTRIBUTES` | Seen |
| Scrollbar (Mac overlay) | thumb `#FFFFFF26`, hover `#FFFFFF4D` | `ScrollBar.Mac.thumbColor` | Seen |
| VCS gutter stripes | added `#549159`, modified `#375FAD`, deleted `#868A91` | `ADDED_LINES_COLOR`, `MODIFIED_LINES_COLOR`, `DELETED_LINES_COLOR` | Seen |
| File status text (project tree + tabs) | modified `#70AEFF`, added `#73BD79`, unknown/untracked `#E88F89`, ignored `#D69A6B`, deleted `#6F737A`, merged `#CF84CF`, conflict `#DE6A66`, renamed `#70AEFF` | `FILESTATUS_*` | Seen |

### 2.2 Text attributes, Java, fully resolved

Columns: foreground / background / font style / effect (type + color). "inherits" = no own foreground, uses `#BCBEC4`. The last column shows the scheme and fallback chain that supplied the value (all Seen: resolved against the scheme XML plus the key fallback map from the GH sources).

| Item | Key | Foreground | Background | Font | Effect | Resolved via |
|---|---|---|---|---|---|---|
| Keyword | `JAVA_KEYWORD` | #CF8E6D |  | plain |  | Islands Dark via JAVA_KEYWORD>DEFAULT_KEYWORD |
| String | `JAVA_STRING` | #6AAB73 |  | plain |  | Islands Dark via JAVA_STRING>DEFAULT_STRING |
| Number | `JAVA_NUMBER` | #2AACB8 |  | plain |  | Islands Dark via JAVA_NUMBER>DEFAULT_NUMBER |
| Line comment | `JAVA_LINE_COMMENT` | #7A7E85 |  | plain |  | Islands Dark via JAVA_LINE_COMMENT>DEFAULT_LINE_COMMENT |
| Block comment | `JAVA_BLOCK_COMMENT` | #7A7E85 |  | plain |  | Islands Dark via JAVA_BLOCK_COMMENT>DEFAULT_BLOCK_COMMENT |
| Doc comment | `JAVA_DOC_COMMENT` | #5F826B |  | italic |  | Islands Dark via JAVA_DOC_COMMENT>DEFAULT_DOC_COMMENT |
| Doc tag (@param) | `JAVA_DOC_TAG` | #67A37C |  | plain | LINE_UNDERSCORE (no color: not drawn) | Islands Dark via JAVA_DOC_TAG>DEFAULT_DOC_COMMENT_TAG |
| Doc tag value | `DOC_COMMENT_TAG_VALUE` | #ABADB3 |  | plain |  | Islands Dark via DOC_COMMENT_TAG_VALUE>DEFAULT_DOC_COMMENT_TAG_VALUE |
| Doc markup (<p>) | `JAVA_DOC_MARKUP` | #68A67E |  | plain |  | Islands Dark via JAVA_DOC_MARKUP>DEFAULT_DOC_MARKUP |
| Valid string escape | `JAVA_VALID_STRING_ESCAPE` | #CF8E6D |  | plain |  | Islands Dark via JAVA_VALID_STRING_ESCAPE>DEFAULT_VALID_STRING_ESCAPE |
| Invalid string escape | `JAVA_INVALID_STRING_ESCAPE` | #CF8E6D |  | plain | WAVE_UNDERSCORE #FA6675 | Islands Dark via JAVA_INVALID_STRING_ESCAPE>DEFAULT_INVALID_STRING_ESCAPE |
| Operator | `JAVA_OPERATION_SIGN` | #BCBEC4 |  | plain |  | Islands Dark via JAVA_OPERATION_SIGN>DEFAULT_OPERATION_SIGN |
| Parentheses | `JAVA_PARENTH` | #BCBEC4 |  | plain |  | Islands Dark via JAVA_PARENTH>DEFAULT_PARENTHS |
| Brackets | `JAVA_BRACKETS` | #BCBEC4 |  | plain |  | Islands Dark via JAVA_BRACKETS>DEFAULT_BRACKETS |
| Braces | `JAVA_BRACES` | #BCBEC4 |  | plain |  | Islands Dark via JAVA_BRACES>DEFAULT_BRACES |
| Comma | `JAVA_COMMA` | #BCBEC4 |  | plain |  | Islands Dark via JAVA_COMMA>DEFAULT_COMMA |
| Dot | `JAVA_DOT` | #BCBEC4 |  | plain |  | Islands Dark via JAVA_DOT>DEFAULT_DOT |
| Semicolon | `JAVA_SEMICOLON` | #BCBEC4 |  | plain |  | Islands Dark via JAVA_SEMICOLON>DEFAULT_SEMICOLON |
| Local variable | `LOCAL_VARIABLE_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via LOCAL_VARIABLE_ATTRIBUTES>DEFAULT_LOCAL_VARIABLE>DEFAULT_IDENTIFIER |
| Parameter | `PARAMETER_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via PARAMETER_ATTRIBUTES>DEFAULT_PARAMETER>DEFAULT_IDENTIFIER |
| Lambda parameter | `LAMBDA_PARAMETER_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via LAMBDA_PARAMETER_ATTRIBUTES>PARAMETER_ATTRIBUTES>DEFAULT_PARAMETER>DEFAULT_IDENTIFIER |
| Reassigned local | `REASSIGNED_LOCAL_VARIABLE_ATTRIBUTES` | #BCBEC4 |  | plain | LINE_UNDERSCORE #84868C | Islands Dark via REASSIGNED_LOCAL_VARIABLE_ATTRIBUTES>DEFAULT_REASSIGNED_LOCAL_VARIABLE |
| Reassigned parameter | `REASSIGNED_PARAMETER_ATTRIBUTES` | #BCBEC4 |  | plain | LINE_UNDERSCORE #84868C | Islands Dark via REASSIGNED_PARAMETER_ATTRIBUTES>DEFAULT_REASSIGNED_PARAMETER |
| Instance field | `INSTANCE_FIELD_ATTRIBUTES` | #C77DBB |  | plain |  | Islands Dark via INSTANCE_FIELD_ATTRIBUTES>DEFAULT_INSTANCE_FIELD |
| Instance final field | `INSTANCE_FINAL_FIELD_ATTRIBUTES` | #C77DBB |  | plain |  | Islands Dark via INSTANCE_FINAL_FIELD_ATTRIBUTES>INSTANCE_FIELD_ATTRIBUTES>DEFAULT_INSTANCE_FIELD |
| Static field | `STATIC_FIELD_ATTRIBUTES` | #C77DBB |  | italic |  | Islands Dark via STATIC_FIELD_ATTRIBUTES>DEFAULT_STATIC_FIELD |
| Static final field (constant) | `STATIC_FINAL_FIELD_ATTRIBUTES` | #C77DBB |  | italic |  | Islands Dark via STATIC_FINAL_FIELD_ATTRIBUTES>STATIC_FIELD_ATTRIBUTES>DEFAULT_STATIC_FIELD |
| Class name | `CLASS_NAME_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via CLASS_NAME_ATTRIBUTES>DEFAULT_CLASS_NAME>DEFAULT_IDENTIFIER |
| Interface name | `INTERFACE_NAME_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via INTERFACE_NAME_ATTRIBUTES>DEFAULT_INTERFACE_NAME>DEFAULT_IDENTIFIER |
| Enum name | `ENUM_NAME_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via ENUM_NAME_ATTRIBUTES>CLASS_NAME_ATTRIBUTES>DEFAULT_CLASS_NAME>DEFAULT_IDENTIFIER |
| Abstract class name | `ABSTRACT_CLASS_NAME_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via ABSTRACT_CLASS_NAME_ATTRIBUTES>CLASS_NAME_ATTRIBUTES>DEFAULT_CLASS_NAME>DEFAULT_IDENTIFIER |
| Type parameter | `TYPE_PARAMETER_NAME_ATTRIBUTES` | #16BAAC |  | plain |  | Islands Dark |
| Method call | `METHOD_CALL_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via METHOD_CALL_ATTRIBUTES>DEFAULT_FUNCTION_CALL>DEFAULT_IDENTIFIER |
| Static method call | `STATIC_METHOD_ATTRIBUTES` | (inherits text #BCBEC4) |  | italic |  | Darcula |
| Method declaration | `METHOD_DECLARATION_ATTRIBUTES` | #56A8F5 |  | plain |  | Islands Dark via METHOD_DECLARATION_ATTRIBUTES>DEFAULT_FUNCTION_DECLARATION |
| Constructor call | `CONSTRUCTOR_CALL_ATTRIBUTES` | #BCBEC4 |  | plain |  | Islands Dark via CONSTRUCTOR_CALL_ATTRIBUTES>DEFAULT_FUNCTION_CALL>DEFAULT_IDENTIFIER |
| Constructor declaration | `CONSTRUCTOR_DECLARATION_ATTRIBUTES` | #56A8F5 |  | plain |  | Islands Dark via CONSTRUCTOR_DECLARATION_ATTRIBUTES>DEFAULT_FUNCTION_DECLARATION |
| Annotation name | `ANNOTATION_NAME_ATTRIBUTES` | #BBB529 |  | plain | LINE_UNDERSCORE (no color: not drawn) | Darcula |
| Annotation attribute name | `ANNOTATION_ATTRIBUTE_NAME_ATTRIBUTES` | (inherits text #BCBEC4) |  | plain | LINE_UNDERSCORE (no color: not drawn) | Islands Dark |
| Default text / identifier | `TEXT` | #BCBEC4 | #191A1C | plain |  | Islands Dark |
| Error (ERRORS_ATTRIBUTES) | `ERRORS_ATTRIBUTES` | (inherits text #BCBEC4) |  | plain | WAVE_UNDERSCORE #FA6675 | Islands Dark |
| Warning (WARNING_ATTRIBUTES) | `WARNING_ATTRIBUTES` | (inherits text #BCBEC4) |  | plain | WAVE_UNDERSCORE #F2C55C | Islands Dark |
| Unused (NOT_USED_ELEMENT_ATTRIBUTES) | `NOT_USED_ELEMENT_ATTRIBUTES` | #6F737A |  | plain |  | Islands Dark |
| Matched brace | `MATCHED_BRACE_ATTRIBUTES` | (inherits text #BCBEC4) | #43454A | bold |  | Islands Dark |
| Unmatched brace | `UNMATCHED_BRACE_ATTRIBUTES` | #F75464 |  | plain |  | Islands Dark |
| Deprecated | `DEPRECATED_ATTRIBUTES` | (inherits text #BCBEC4) |  | plain | STRIKEOUT #BCBEC4 | Islands Dark |
| Bad character | `BAD_CHARACTER` | #F75464 |  | plain | WAVE_UNDERSCORE (no color: not drawn) | Islands Dark |
| TODO | `TODO_DEFAULT_ATTRIBUTES` | #8BB33D |  | italic |  | Islands Dark |
| Folded text | `FOLDED_TEXT_ATTRIBUTES` | #868991 | #393B40 | plain |  | Islands Dark |
| Identifier under caret | `IDENTIFIER_UNDER_CARET_ATTRIBUTES` | (inherits text #BCBEC4) | #373B39 | plain | LINE_UNDERSCORE (no color: not drawn) | Islands Dark |
| Write identifier under caret | `WRITE_IDENTIFIER_UNDER_CARET_ATTRIBUTES` | (inherits text #BCBEC4) | #402F33 | plain | LINE_UNDERSCORE (no color: not drawn) | Islands Dark |
| Search result | `SEARCH_RESULT_ATTRIBUTES` | (inherits text #BCBEC4) | #2D543F | plain |  | Islands Dark |

Notes on the table:
- Error and warning use `EFFECT_TYPE 2` = **WAVE_UNDERSCORE** (error `#FA6675`, warning `#F2C55C`; error stripe `#D64D5B` / `#C29E4A`). No foreground change: the text keeps its syntax color and the wave is drawn under it.
- Unused element (grey variable, `NOT_USED_ELEMENT_ATTRIBUTES`): foreground `#6F737A`, no effect (Islands Dark has no strike/underline there).
- Deprecated: `EFFECT_TYPE 3` = STRIKEOUT in `#BCBEC4`.
- `ANNOTATION_NAME_ATTRIBUTES`, `JAVA_DOC_TAG` have `EFFECT_TYPE 1` with no `EFFECT_COLOR`: nothing is drawn (effect needs a color).
- `STATIC_METHOD_ATTRIBUTES` is an explicit Darcula entry that only sets italic, so the static call is italic in the default text color (the `DEFAULT_STATIC_METHOD #57AAF7` fallback is not reached, because the explicit entry has a non-empty value). Inferred from the lookup rule.
- Parameters, locals, class names, interface names, method calls all end at `DEFAULT_IDENTIFIER #BCBEC4`. Only declarations (`#56A8F5`), fields (`#C77DBB`), constants/static fields (`#C77DBB` italic), type parameters (`#16BAAC`) and annotations (`#BBB529`) are colored. Semantic highlighting (rainbow) is off by default.
- Java class/interface/enum/record icons in the Project tree use the node icons in section 4, not text colors.

Wave painting (Inferred from GH `platform/platform-api/src/com/intellij/ui/paint/EffectPainter2D.java`, registry `ide.text.effect.new=true`): drawn from baseline y, available height = font descent of the line (6 px with the metrics in 2.3); height is capped to 3 px (`getMaxHeight`: descent <= 7 -> 3), period = 2 px zig-zag, amplitude 2 px, stroke width 0.7 px (`THIN_STROKE` when height < 6), antialiased; occupies y+3 .. y+6 below the baseline.

### 2.3 Line height (Seen code, Inferred numbers)

Rule (GH `platform/platform-impl/src/com/intellij/openapi/editor/impl/view/EditorView.java createNewMetrics/getLineHeight`; registries `editor.text.xcode.vertical.spacing=false`, `editor.text.vertical.spacing.correct.rounding=false`):

```
fontMetricsHeight = FontMetrics.getHeight()     // int: ascent + descent + leading, each rounded up by Java2D
lineHeight        = ceil(fontMetricsHeight * lineSpacing)       // lineSpacing default 1.2 (FontPreferences.DEFAULT_LINE_SPACING)
descent'          = descent + (lineHeight - fontMetricsHeight) / 2   // integer division
ascent'           = lineHeight - descent'                         // baseline = lineTop + ascent'
```
JetBrains Mono 12: ascent 1.02*12 = 12.24 -> 13, descent 0.30*12 = 3.6 -> 4, leading 0 -> fontMetricsHeight **17**.
- spacing 1.2: lineHeight = ceil(20.4) = **21**; descent' = 4 + (21-17)/2 = 6; ascent' = 15; caret height 17 (2 px margin above and below).
- spacing 1.0: lineHeight 17, ascent' 13, descent' 4.
Verify the FontMetrics rounding on a real run (JBR on macOS can differ by 1 px); the formulas are exact, the integer 17 is computed.
Text x: char i at x0 + i * 7.2 (fractional advances; do not round to ints).

### 2.4 Gutter geometry (New UI layout, logical px; GH `EditorGutterLayout.createNewUILayout` + `EditorGutterComponentImpl`)

Left to right, defaults (non-compact; compact values in parentheses come from `darcula.theme.json` `Editor.Gutter.*.compact`):

| # | Area | Width | Source |
|---|---|---|---|
| 1 | empty annotation area | 4 (compact 0) | `JBUI.CurrentTheme.Editor.Gutter.emptyAnnotationAreaWidth` default 4 |
| 2 | gap before line numbers (`gapAfterVcsMarkersWidth`) | 4 (2) | JBUI.java default 4 |
| 3 | line numbers | `max(14, ceil(stringWidth(maxLineNumber at 11 px JBM)))` -> 14 for up to 2 digits, 20 for 3 digits (6.6 px per digit), 27 for 4 digits. 14 = width of the breakpoint placeholder icon `expui/breakpoints/breakpoint.svg` (14x14) | `getInitialLineNumberWidth`, `calcLineNumbersAreaWidth` |
| 4 | gap after line numbers (`gapAfterLineNumbersWidth`) | 4 (2) | |
| 5 | left free painters (git change stripe lives here) | 10 when shown (8 + 2), else 0 | `getLeftFreePaintersAreaWidth`, `FREE_PAINTERS_LEFT_AREA_WIDTH 8` |
| 6 | icons area (breakpoints, run/override markers) | 17 (`START_ICON_AREA_WIDTH`), grows with wider icons | `myStartIconAreaWidth` |
| 7 | gap after icons (`gapAfterIconsWidth`) | 4 (2) | |
| 8 | right free painters | 0 | New UI: `myRightFreePaintersAreaReserveWidth` |
| 9 | folding area | 11 = fold icon width 9 (`expui/gutter/fold.svg`, 9x9) + 2 | `getFoldingAreaWidth` |
| 10 | extra right free painters + gap | 8 + 1 = 9 | `FREE_PAINTERS_EXTRA_RIGHT_AREA_WIDTH 8`, `areaGap(1)` |

Total (git project, markers shown) = 4+4+L+4+10+17+4+0+11+9 = **63 + L**: 77 with L=14, 83 with L=20, 90 with L=27. Without git markers (item 5 = 0): 67 + L - 4.
Line numbers are **right-aligned**: right edge x = 4 + 4 + L (left of "gap after line numbers"), font 11 px JBM plain, baseline = line top + the editor's ascent' (not the 11 px font's), color per 2.1. Text area starts at gutter width (separate component, no extra padding; the 3 px strip before the separator is inside the gutter). Fold chevron icon `expui/gutter/fold(_dark).svg` is only drawn on hover (alpha context) in New UI.
Line numbers area on a non-real editor without numbers: 14 wide.

## 3. Project tool window (Islands Dark colors resolved)

### 3.1 Colors (Seen, aliases resolved from `colors` in ManyIslandsDark.theme.json)

| Item | Value | Key / alias | Source |
|---|---|---|---|
| Tree background | `#191A1C` | `Tree.background` <- wildcard `*.background` = `tool-window-bg` = `layer-0-bg` | Seen (wildcard rule in `UITheme.kt`) |
| Tree foreground | `#D1D3D9` | `*.foreground` = `text-default` = `gray-130` | Seen |
| Selection background, tree focused | `#2A4371` | `selection-bg-active` = `blue-50` | Seen |
| Selection background, tree not focused | `#33353B` | `selection-bg-inactive` = `gray-40` (`selectionInactiveBackground`) | Seen |
| Selection foreground (focused and not) | `#D1D3D9` | `selectionForeground`/`selectionInactiveForeground` = `text-default`; `Tree.forceFocusedSelectionForeground=false` (Islands) | Seen |
| Hover background (unselected row) | `#FFFFFF10` (white, alpha 0x10 = 6.3%) | `Tree.hoverBackground` = `selection-bg-hovered`; same for inactive tree | Seen |
| Indent guides | off (`Tree.paintLines=false`); if enabled `#33353B` | `Tree.hash` = `tree-indent-guide-border` | Seen |
| Grayed text (e.g. path after module name) | `#73767C` | `SimpleTextAttributes.GRAYED_ATTRIBUTES` -> `Component.infoForeground` <- `*.infoForeground` = `text-secondary` = `gray-80` | Inferred from `NamedColorUtil.getInactiveTextColor` + wildcard |
| Disabled text | `#4C4F56` | `text-disabled` | Seen |
| Directory / module / package / excluded node text | same `#D1D3D9`, no special color, regular weight (excluded folders only change the icon to orange); VCS file status colors apply (section 2.1) | | Inferred (no special attributes found in `ProjectViewRenderer`, `NodeRenderer`) |
| Font | Inter Regular 13 (`Tree.font` = default label font; ide font size 13) | | Seen setting, Inferred mapping |
| Scrollbar | macOS overlay, same as editor | | |

### 3.2 Metrics (logical px)

| Item | Value | Source |
|---|---|---|
| Row height | **24** (compact 22) | `Tree.rowHeight` in ManyIslandsDark.theme.json (`rowHeight`, `rowHeight.compact`) |
| Tree insets (`Tree.border`) | top 4, left 12, bottom 4, right 12 (compact 2,8,2,8) | ManyIslandsDark.theme.json `Tree.border`; applied as Swing component border (Inferred; check `ProjectViewTree` does not override) |
| Indent per level | **19** = `max(controlWidth/2 = 8, Tree.leftChildIndent 7)` + `Tree.rightChildIndent 11` | GH `ClassicPainter.getRendererOffset/getLeftIndent`, `darcula.theme.json` `leftChildIndent 7`, `rightChildIndent 11` |
| Chevron (control) | 16x16 icon box; x = 12 + (depth-1)*19; vertically centered in the row; shown only for non-leaf rows; depth 1 = first visible node (project root; root is hidden, root handles shown) | `ClassicPainter.getControlOffset`, `TreeUtil.getNodeDepth` |
| Renderer origin (icon+text cell) | x = 12 + depth*19 (leaf and non-leaf; for depth d: (d-1)*19 + 19) | `ClassicPainter.getRendererOffset` |
| Node icon | 16x16, at renderer.x + `ipad.left` (2) (+/-1 from the 1 px component border, Inferred) | `SimpleColoredComponent` ipad `(1,2)`, border `empty(1)` |
| Icon-to-text gap | **2** (`JBUIScale.scale(2)`) | `SimpleColoredComponent.myIconTextGap` |
| Resulting text x for depth d | 12 + 19*d + 2 + 16 + 2 = 19*d + 32 (depth 1 -> 51, depth 2 -> 70, depth 3 -> 89) | Inferred |
| Selection / hover highlight | rounded rect from x = 12 (`HORIZONTAL_SELECTION_OFFSET`, = tree left inset) to width - 12, full row height (24); not per-indent, always the same left edge | GH `DefaultTreeUI.paint` (`borderOffset = 12`, `min(helper.getX()+12, insets.left + controlOffset)`); registry `ide.experimental.ui.tree.selection=true` |
| Selection corner | `Tree.Selection.arc = 8` (JBUI default 8, no theme override) is passed as RoundRectangle2D arcWidth/arcHeight, so corner **radius = 4**. Adjacent selected rows merge: top corners squared if the row above is selected, bottom if the row below is | `JBUI.CurrentTheme.Tree.ARC`, `RectanglePainter2D.FILL` (arc diameter), `DefaultTreeUI` lines 361-400. Inferred conclusion (radius vs diameter) |
| Chevron colors | `expui/general/chevronRight_dark.svg` / `chevronDown_dark.svg`: stroke `#B4B8BF`, 1 px, round caps, same icon when selected (`collapsedSelectedIcon` = same path) | Seen |

### 3.3 Tool window header (the "Project" title row)

| Item | Value | Source |
|---|---|---|
| Height | **41** (compact 31); 1 px of it is the bottom line when drawn | `JBUI.CurrentTheme.ToolWindow.defaultHeaderHeight` 41, `darcula.theme.json` `ToolWindow.Header.height.compact 31` |
| Background | `#191A1C` (`ToolWindow.Header.inactiveBackground` = `tool-window-bg`; New UI always uses the "inactive" color) | ManyIslandsDark.theme.json `ToolWindow.Header` |
| Bottom line | only drawn when the content is scrolled / tabbed / has a top toolbar; color `ToolWindow.Header.borderColor` = `tool-window-border` = `#26282C` | `ToolWindowHeader.paintComponent` |
| Title font | Inter Regular 13 (`ToolWindow.Header.font.size.offset = 0`, `JBFont.label()`); color `#D1D3D9` | Inferred from `JBUI.CurrentTheme.ToolWindow.headerFont`, `defaultHeaderFont` |
| Title label insets | left 12, right 16 (`ToolWindow.Header.labelLeftRightInsets` default `0,12,0,16`) | JBUI.java |
| Toolbar insets (right side actions) | left 12, right 8; component border `empty(2,0)` | JBUI.java, `ToolWindowHeader.kt` |

### 3.4 Islands (Seen)

| Item | Value | Source |
|---|---|---|
| Mode | many islands: `ui.Islands = 1` | ManyIslandsDark.theme.json |
| Window background behind islands (`MainWindow.background`, toolbar, stripes, status bar) | `#26282C` (`main-window-bg` = `layer-1-bg`) | Seen |
| Island body (tool windows, editor area) | `#191A1C` (`layer-0-bg`) | Seen |
| Island border color (`Island.borderColor`) | `#191A1C` (`tool-window-bg`); the `alt` variant `#212326` is only for the alternate look | Seen |
| Island corner | `Island.arc = 20` -> corner radius **10** (`arcSizeF = scale(arc / 2)`); compact `arc.compact = 16` -> 8 | `IslandsUICustomization.paintIslandAreaRaw` |
| Gap between islands | `Island.borderWidth = 6` (compact 4): each island leaves `borderWidth/2 = 3` px of window background on every side, so adjacent islands are 6 px apart. Component border insets: tool window `Island.ToolWindow.insets` default 3, editor `Island.Editor.insets` default 2 (not overridden in the theme) | `paintIslandAreaRaw` (`offset = borderWidth / 2`), `createToolWindowBorderPainter`, `createEditorBorderPainter`. Inferred: visual gap ~6, verify with screenshot |
| Outer gap when a stripe is hidden | `Islands.emptyGap` default 4 on the side without stripe | `configureToolWindowPane` |
| Corner fillets | the 4 corner pieces are painted in window background `#26282C` with quadratic curves of size 10 on top of the island rect, plus a border line of `Island.borderColor` with `borderArcLength = 14` (compact 10) | `paintIslandAreaRaw` |
| Inactive window | islands are dimmed with `Island.inactiveAlpha = 0.56` | ManyIslandsDark.theme.json, `islandsInactiveAlpha` |
| Island gradient | window gradient background image `images/gradientBackground-islands-dark.svg` in `lib/intellij.platform.ide.impl.jar` is only used when the islands gradient setting is on (default off for the default Islands themes: Inferred) | |
| Editor tab bar background | `#191A1C` (`EditorTabs.background` = `editor-bg`) | Seen |

## 4. Icons (Seen unless marked)

All in `$IDE/lib/intellij.platform.icons.jar`. In New UI the loader asks for old `AllIcons` paths and `PlatformIconMappings.json` (same jar) redirects to `expui/...`. In a dark theme the loader first tries `<name>_dark.svg`, then `<name>.svg`. Nodes have `_dark` for nearly all; only ~15 nodes (e.g. `folder.svg`) differ in color. Then `icons.ColorPalette` of the theme recolors: ManyIslandsDark maps SVG color `#CED0D6` -> `icon-default-stroke` = `#C3C5CB` (this changes the grey strokes/fills in `_dark` icons: folder stroke, package, text lines). Colors below are the `_dark` file values before that remap.

| Node | Jar path (dark file; light file is the same name without `_dark`) | Main colors in `_dark` | Size |
|---|---|---|---|
| Folder, `.idea` folder, plain dir | `expui/nodes/folder_dark.svg` | fill `#43454A`, stroke `#CED0D6` (-> `#C3C5CB`) | 16x16 |
| Source root (blue folder) `src/main/java` | `expui/nodes/sourceRoot_dark.svg` (mapped from `modules/sourceRoot.svg`) | fill `#25324D`, stroke `#548AF7` | 16 |
| Test root (green folder) | `expui/nodes/testRoot_dark.svg` | fill `#253627`, stroke `#57965C` | 16 |
| Resources root | `expui/nodes/resourcesRoot_dark.svg` | folder + three `#F2C55C` bars | 16 |
| Test resources root | `expui/nodes/testResourcesRoot_dark.svg` | green + `#DB5C5C`/`#F2C55C` bars | 16 |
| Excluded folder (`out`, `target`) | `expui/nodes/excludeRoot_dark.svg` (from `modules/excludeRoot.svg`) | fill `#45322B`, stroke `#C77D55` (orange-brown) | 16 |
| Generated sources root | `expui/nodes/generatedSource_dark.svg` | `#25324D`/`#3574F0` + gear | 16 |
| Package | `expui/nodes/package_dark.svg` | folder outline with dot, `#43454A` fill, `#CED0D6` outline | 16 |
| Module / project root node | `expui/nodes/module_dark.svg` (folder with small blue rounded square, bottom right: `#25324D` fill, `#548AF7` stroke); IDEA project icon `expui/nodes/ideaProject_dark.svg`; Java module `expui/nodes/moduleJava_dark.svg` (blue rounded square) | | 16. Which of the three the root row uses is Inferred (module node: `module_dark`) |
| Java class (also used for a `.java` file that declares a public class, which is what the Project tree shows) | `expui/nodes/class_dark.svg` | circle fill `#25324D`, stroke `#548AF7`, "C" glyph `#548AF7` | 16 |
| Interface | `expui/nodes/interface_dark.svg` | circle `#253627`/`#57965C`, "I" glyph | 16 |
| Enum | `expui/nodes/enum_dark.svg` | `#2F2936`/`#A571E6` | 16 |
| Record | `expui/nodes/record_dark.svg` | `#25324D`/`#548AF7` | 16 |
| Annotation | `expui/nodes/annotation_dark.svg` | `#253627`/`#57965C` | 16 |
| Abstract class | `expui/nodes/classAbstract_dark.svg` | `#25324D`/`#548AF7` + dashed | 16 |
| Java file type (when not shown as a class, e.g. outside sources) | `expui/fileTypes/java_dark.svg` | fill `#45322B`, stroke `#C77D55`, base bar `#C77D55` (a mug) | 16 |
| `.class` file | `expui/fileTypes/javaClass_dark.svg` | `#CED0D6` | 16 |
| `.iml` module file | `expui/fileTypes/ideaModule_dark.svg` (mapping: `fileTypes/ideaModule.svg -> nodes/ideaModule.svg` means this new file replaces the old `nodes/ideaModule`) | fill `#43454A`, stroke `#CED0D6` + small bar | 16 |
| XML | `expui/fileTypes/xml_dark.svg` | fill `#C77D55` (angle brackets) | 16 |
| Text / `.txt` / `.md` fallback | `expui/fileTypes/text_dark.svg` (4 lines, `#CED0D6`); markdown `expui/fileTypes/markdown_dark.svg` (`#548AF7`) | | 16 |
| `.properties` | `expui/fileTypes/properties_dark.svg` | `#CED0D6` | 16 |
| JSON / YAML | `expui/fileTypes/json_dark.svg` (`#B589EC`), `expui/fileTypes/yaml_dark.svg` | | 16 |
| Unknown file | `expui/fileTypes/unknown_dark.svg` | `#43454A`/`#CED0D6` + "?" | 16 |
| Library / JDK | `expui/nodes/library_dark.svg`, `expui/nodes/jdk_dark.svg`, `expui/nodes/libraryFolder_dark.svg` | | 16 |
| Chevron right (collapsed) | `expui/general/chevronRight_dark.svg`: `<path d="M6 11.5L9.5 8L6 4.5" stroke="#B4B8BF" stroke-linecap="round"/>` in 16x16 | | 16 |
| Chevron down (expanded) | `expui/general/chevronDown_dark.svg`: `<path d="M11.5 6.25L8 9.75L4.5 6.25" stroke="#B4B8BF" stroke-linecap="round"/>` | | 16 |
| Tree icon keys | `Tree.collapsedIcon` / `Tree.collapsedSelectedIcon` = `/expui/general/chevronRight.svg`, `Tree.expandedIcon` / `Tree.expandedSelectedIcon` = `/expui/general/chevronDown.svg` | | Seen in ManyIslandsDark/expUI_dark.theme.json |
| Gutter fold chevron | `expui/gutter/fold_dark.svg` 9x9, stroke `#6F737A` (`foldBottom_dark.svg` for the end marker) | | 9 |
| Breakpoint (gutter) | `expui/breakpoints/breakpoint.svg` 14x14 fill `#E55765` | | 14 |

Variant markers on node icons (visibility lock, final, static, test/run mark) are overlay layers drawn on top; use `expui/nodes/finalMark_dark.svg`, `staticMark_dark.svg`, `junitTestMark_dark.svg`, `runnableMark_dark.svg`, `locked_dark.svg` if needed. 

## 5. Rendering details that affect "looks identical"

| Item | Value | Source |
|---|---|---|
| Fractional metrics | ON on macOS Catalina or later (editor and IDE text): glyph advances are not rounded, so JBM 12 advance is 7.2 px, not 7 | `UISettings.getPreferredFractionalMetricsValue` (`isMacOSCatalina`), registry `*.text.fractional.metrics=Auto` | 
| Antialiasing | `UISettingsState.editorAAType/ideAAType` default SUBPIXEL; macOS has no LCD smoothing, so effectively grayscale antialiasing with gamma of Core Text/JBR. Use grayscale AA, no hinting | Seen default, Inferred effect |
| Ligatures | off | section 1 |
| Line spacing | 1.2 default; line height 21 px at JBM 12 (section 2.3) | |
| Caret | 2 px bar, blinks (default 500 ms), color `#CED0D6` | section 2.1 |
| Editor text left edge | char grid starts at gutter width; no extra text padding | section 2.4 |
| Line numbers | right aligned, 11 px, ascent shared with the editor baseline | section 2.4 |
| Tree selection | rounded 4 px radius, inset 12 px left/right, not full width | section 3.2 |
| HiDPI | all values logical, Retina doubles them; separators are `alignToInt` (1 px logical = 2 device px); gutter separator moves +1 logical px on scale > 1 | |
| Window layout | 26282C window, 191A1C islands with 10 px radius and ~6 px gaps | section 3.4 |
| Dark icons | pick `_dark.svg`, then remap `#CED0D6 -> #C3C5CB` | section 4 |
| Text colors with no key | fall back to the default foreground `#BCBEC4` (editor) / `#D1D3D9` (UI) | |
| Static, doc-comment and constant styles | italic comes from real `JetBrainsMono-Italic.ttf` (not synthesized) | section 1 |

## 6. Open items worth one screenshot check
1. Real FontMetrics height for JBM 12 on JBR macOS (expected 17 -> line height 21 at spacing 1.2).
2. Whether the 1 px gutter separator line is visible in 2026.3 Islands Dark (code paints it with `#323438`).
3. Whether the user's Project tree uses compact mode (row 22 vs 24) and the gutter has git markers (width +10).
4. Selection corner radius 4 vs 8 (code passes arc 8 as RoundRectangle2D arc width).
5. Icon x inside the renderer (2 or 3 px) from ipad/border.

## 7. Measured against the real JetBrains Client (2026-11-15)

Pixel measurements of the real client (263.7307.200, Islands Dark, default settings, `smoke` project without git) on the Mac. Where these differ from the sections above, these win; `IslandsDark.kt` and `EditorView.kt` use them.

| Item | Code reading above | Measured |
|---|---|---|
| Project tree row height | 24 (compact 22) | **22** |
| Tree selection | inset 12, radius 4 | inset 12, radius 4, fill `#2A4371` (focused) / `#2B4371` |
| Gutter width, 10-line file (L = 14) | 63 + L = 77 with git markers | **56** = line numbers 14 + 4 + icons 17 + 4 + folding 11 + 6; no git markers, no annotation gap before the numbers |
| Text start | right after the gutter | right after the gutter |
| Gutter icon (run triangle) | 17 px icon area | 14 px icon centred in the 17 px area |
| Editor tab | (not covered) | a pill: height 24, fill `#233657`, 1 px border `#2C4A82`, radius 6, in a 32 px row |
| Line height | 21 | 21 (JetBrains Mono 12, char width 7.2) |

Open item 3 of section 6 is answered: rows are 22 and there are no git markers in a non-git project.


## 8. Completion popup, measured (2026-11-15)

From a screenshot of the real client's popup at 2× (`client/compose/src/commonMain/kotlin/intellijandroid/client/ui/LookupPopup.kt` uses these):

| Item | Value |
|---|---|
| Background, border | `#26282C` (`Popup.background` = `layer-1-bg`), 1 px `#33353B` (`layer-1-border`), radius 6 (arc 12) |
| Row | height 22; item text in the editor font (JetBrains Mono 12), `#BCBEC4` (`CompletionPopup.foreground`) |
| Typed letters in the item text | `#538AF9` (`CompletionPopup.matchForeground`) |
| Tail ("(String s)"), type on the right, group header, footer | `#73767C` (`text-secondary`); header and footer in Inter |
| Selection | inset 3 from the border; `#33353B` grey while the autopopup has not been navigated (SEMI_FOCUSED), `#2A4371` once it has (FOCUSED) |
| Horizontal | icon 16 at 11 from the border, item text at 32, type text ends 17 before the right border |
| Vertical | 2 between border and first row; groups ("Commands") get a 1 px separator 4 below the previous row, then a 22 high header |
| Footer tip | 12 from the left border (`Popup.Advertiser borderInsets 6,12,6,12`) |
| Position | just under the caret line; the item text starts 6.5 to the right of the typed prefix |

## 9. Folder chooser, measured (2026-11-16)

From a 2x screenshot of IntelliJ's "Open File or Project" dialog (`FileChooserDialogImpl`, Islands Dark, macOS), browsing Termux's home. Values in pt (= dp in the app). Used by the Android app's project folder picker (`client/android/.../FolderChooser.kt`).

| Part | Value |
|---|---|
| Dialog | 475 × 482, background `#191A1C` (same as the islands) |
| Toolbar | icons 16, one every 26, first box 21 from the left; separator `#363738`; "Hide path" link on the right, 13.5 from the edge |
| Path field | 14.5 from both sides, 68 below the dialog's top, 24 high; focused ring `#4670DA`; a drop-down (path history) at its right end |
| Tree panel | 12.5 from both sides; rows 22, indent 19 (as the Project tree); selection `#33353A`, inset 12 from both panel sides |
| Icons | `expui/nodes/folder.svg`; a folder with `.idea` gets `expui/nodes/ideaProject.svg` (folder with a small badge); toolbar `expui/nodes/homeFolder.svg`, `expui/general/refresh.svg`, `expui/actions/toggleVisibility.svg` |
| Buttons | OK `#4670DA` filled, Cancel outlined `#414349`; each 72 × 24, 12 apart, 15 from the right, 11 from the bottom |
| Browse icon in a text field (`TextFieldWithBrowseButton`) | `expui/inline/browse.svg` |

Left out in the app: Desktop, New Folder, Delete, the path history drop-down, the help button, and the drag-and-drop hint.

## 10. Connect screen: Gateway's welcome window

The connect screen copies JetBrains Gateway 2026.3's "Welcome to JetBrains Gateway" window and its project ⋮ menu,
measured on 2x screenshots (pt = dp). Code: `client/android/.../ConnectScreen.kt`, object `Gateway`.

| Part | Gateway | Ours |
|---|---|---|
| Sidebar | 224 wide; "Connections" label, rows 33 high, icon 17 in; selected row full width `#2E436E` | Same; 32 % of the width, at least 132, on a narrow screen. Rows: This phone (`expui/nodes/ssh.svg`), divider, Link (Markdown plugin's chain icon) |
| Header | Title 25 in, centred 37.5 down, divider at 63.5; New Project 133 × 27, 27.5 from the right | "Recent Termux Projects" / "Connect by Link"; New Project without the drop-down |
| Search | 44 high, `searchHistory` icon 7.5 in, text at 32 | Same; filters the rows by path |
| Host group | Bold host 25 in, centred 31 below the search divider; icons every 25.5, last centred 44.5 from the right | "This phone" with + (New Project) and Refresh; link hosts as `host:port` |
| Project row | 39 high; product icon 25 in, path in link blue at 50; status 18 after the path: "Running" green with a red stop square, or "Last active: 17 minutes ago"; ⋮ | Same, `expui/nodes/ideaProject.svg`; paths under Termux's home shortened to `~/…` |
| ⋮ menu | 237 wide, radius 8, `#2B2D30`, rows 24, text 20 in; Open With, Remove from Recent Projects, Collect Host and Client Logs | Remove from Recent Projects only |

Left out: the logo and version, All Providers, the settings gear, New Project's drop-down, Open With and log collection.

**Portrait** (window taller than wide). Gateway has no portrait layout, and the sidebar took a third of a phone's
411 dp for two entries, squeezing the paths. Upright, the sidebar becomes a tab bar at the bottom, like the main
window's tool window bar ([`mobile-layout.md`](mobile-layout.md)): 40 high, two equal tabs (icon and label), the
selected one on a 32-high `#393B40` pill with radius 6. Project rows put the status under the path (rows 48 high).
Landscape keeps the sidebar, now always 224 wide.
