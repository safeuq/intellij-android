package intellijandroid.client.android

import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Opens the soft keyboard while the editor has focus. The input type is `TYPE_NULL`, so keyboards send plain key
 * events (and [BaseInputConnection] turns committed text into key events), which reach the editor's key handler the
 * same way a hardware keyboard's do. No composing text, suggestions or autocorrect.
 */
val editorTextInput: Modifier = Modifier.then(EditorTextInputElement)

private object EditorTextInputElement : ModifierNodeElement<EditorTextInputNode>() {
    override fun create() = EditorTextInputNode()
    override fun update(node: EditorTextInputNode) = Unit
    override fun hashCode() = 0
    override fun equals(other: Any?) = other === this
}

private class EditorTextInputNode : Modifier.Node(), FocusEventModifierNode, PlatformTextInputModifierNode {
    private var session: Job? = null

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused == (session != null)) return
        session?.cancel()
        session = null
        if (focusState.isFocused) {
            session = coroutineScope.launch {
                establishTextInputSession {
                    val inputView = view
                    startInputMethod(object : PlatformTextInputMethodRequest {
                        override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                            outAttributes.inputType = InputType.TYPE_NULL
                            outAttributes.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                            return BaseInputConnection(inputView, false)
                        }
                    })
                }
            }
        }
    }
}
