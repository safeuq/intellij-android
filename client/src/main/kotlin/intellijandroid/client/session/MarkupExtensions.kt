package intellijandroid.client.session

import com.jetbrains.rd.ide.model.BlockInlayComponentsModel
import com.jetbrains.rd.ide.model.CodeFoldingEventsModel
import com.jetbrains.rd.ide.model.DeclarativeInlayHintEventsModel
import com.jetbrains.rd.ide.model.DocRenderDocumentExtModel
import com.jetbrains.rd.ide.model.DocRenderExtModel
import com.jetbrains.rd.ide.model.GutterMarkActionModel
import com.jetbrains.rd.ide.model.GutterMarkDnDModel
import com.jetbrains.rd.ide.model.GutterMarkHoverModel
import com.jetbrains.rd.ide.model.HintInlayHighlighterInteractionModel
import com.jetbrains.rd.ide.model.HyperlinkNavigateModel
import com.jetbrains.rd.ide.model.InlayWithPresentationEventsModel
import com.jetbrains.rd.ide.model.InlineCompletionEventsModel
import com.jetbrains.rd.ide.model.InvisibleBraceHintProviderModel
import com.jetbrains.rd.ide.model.LineMarkerActionModel
import com.jetbrains.rd.ide.model.MarkupDiagnostics
import com.jetbrains.rd.ide.model.MarkupModelExtension
import com.jetbrains.rd.ide.model.MarkupRevalidation
import com.jetbrains.rd.ide.model.MouseTrackingInlayExtensionModel
import com.jetbrains.rd.ide.model.RdMarkupModel
import com.jetbrains.rd.ide.model.TooltipProviderModel

/**
 * The extensions JetBrains Client attaches to every markup model it creates (recorded session, 2026-11-15). The
 * backend looks them up by key and fails without them ("null cannot be cast … ProtocolMarkupExtensionsKt.getExtension",
 * "lineMarkerActionModel is null"), and then sends no highlighters. Each call returns fresh, unbound instances.
 */
object MarkupExtensions {
    /** For `textControls[…].extensions[MarkupTextControlExtension].markup`. */
    fun editorMarkup() = RdMarkupModel(
        listOf(
            TooltipProviderModel("TOOLTIP_PROVIDER_MODEL_KEY"),
            DeclarativeInlayHintEventsModel("DECLARATIVE_INLAY_EVENTS_MODEL_EXTENSION_KEY"),
            InlineCompletionEventsModel("COMPLETION_INLAY_EVENTS_MODEL_EXTENSION_KEY"),
            TooltipProviderModel("HINT_INLAY_HIGHLIGHTER_TOOLTIP_PROVIDER_MODEL_KEY"),
            MarkupRevalidation("MARKUP_REVALIDATION_KEY"),
            HyperlinkNavigateModel("HYPERLINK_NAVIGATE_EXTENSION_KEY"),
            MarkupDiagnostics("DIAGNOSTICS_KEY"),
            BlockInlayComponentsModel("BLOCK_INLAYS_MODEL_EXTENSION_KEY"),
            CodeFoldingEventsModel("CODE_FOLDING_EVENTS_MODEL_EXTENSION_KEY"),
            GutterMarkActionModel("GUTTER_MARK_ACTION_MODEL_KEY"),
            GutterMarkDnDModel("GUTTER_MARK_DND_MODEL_KEY"),
            LineMarkerActionModel("LINE_MARKER_ACTIONS_KEY"),
            TooltipProviderModel("GUTTER_MARK_TOOLTIPS_KEY"),
            HintInlayHighlighterInteractionModel("HINT_INLAY_HIGHLIGHTER_INTERACTION_MODEL_KEY"),
            TooltipProviderModel("LINE_MARKER_TOOLTIPS_KEY"),
            InvisibleBraceHintProviderModel("INVISIBLE_BRACE_HINT_PROVIDER_MODEL_KEY"),
            MouseTrackingInlayExtensionModel("INLAY_MOUSE_EVENTS_MODEL_EXTENSION_KEY"),
            DocRenderExtModel("DOC_RENDER_MODEL_EXTENSION_KEY"),
            GutterMarkHoverModel("GUTTER_MARK_HOVER_MODEL_KEY"),
            InlayWithPresentationEventsModel("INLAY_WITH_PRESENTATION_EVENTS_MODEL_EXTENSION_KEY"),
        ),
    )

    /** For `documents[…].extensions[MarkupDocumentExtension].markups[key]`, both the `null` and the per-project key. */
    fun documentMarkup() = RdMarkupModel(
        listOf<MarkupModelExtension>(
            GutterMarkDnDModel("GUTTER_MARK_DND_MODEL_KEY"),
            MarkupDiagnostics("DIAGNOSTICS_KEY"),
            GutterMarkActionModel("GUTTER_MARK_ACTION_MODEL_KEY"),
            GutterMarkHoverModel("GUTTER_MARK_HOVER_MODEL_KEY"),
            DocRenderDocumentExtModel("DOC_RENDER_DOCUMENT_MODEL_EXTENSION_KEY"),
            MarkupRevalidation("MARKUP_REVALIDATION_KEY"),
            InvisibleBraceHintProviderModel("INVISIBLE_BRACE_HINT_PROVIDER_MODEL_KEY"),
            LineMarkerActionModel("LINE_MARKER_ACTIONS_KEY"),
            TooltipProviderModel("HINT_INLAY_HIGHLIGHTER_TOOLTIP_PROVIDER_MODEL_KEY"),
            TooltipProviderModel("TOOLTIP_PROVIDER_MODEL_KEY"),
            HintInlayHighlighterInteractionModel("HINT_INLAY_HIGHLIGHTER_INTERACTION_MODEL_KEY"),
            TooltipProviderModel("GUTTER_MARK_TOOLTIPS_KEY"),
            TooltipProviderModel("LINE_MARKER_TOOLTIPS_KEY"),
        ),
    )
}
