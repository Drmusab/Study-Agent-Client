// Harness stub — androidx.compose.ui.text.input + foundation.text. See Runtime.kt for the policy.
package androidx.compose.ui.text.input

import androidx.compose.ui.unit.TextRange

class TextFieldValue(
    val text: String = "",
    val selection: TextRange = TextRange(0),
    val composingRange: TextRange? = null
) {
    constructor(text: String, selection: TextRange) : this(text, selection, null)
    constructor(text: String, cursorPosition: Int) : this(text, TextRange(cursorPosition), null)
    val isValidSelection: Boolean get() = true
    fun copy(text: String = this.text, selection: TextRange = this.selection): TextFieldValue =
        TextFieldValue(text, selection)
}

class TextInputServiceStub

interface VisualTransformation {
    fun filter(text: androidx.compose.ui.text.AnnotatedString,
               offset: Int): TransformedText
}

class TransformedText(val text: androidx.compose.ui.text.AnnotatedString,
                      val offsetMapping: OffsetMapping)

interface OffsetMapping {
    fun originalToTransformed(offset: Int): Int
    fun transformedToOriginal(offset: Int): Int
}

object OffsetMappingCompat {
    val Identity: OffsetMapping = object : OffsetMapping {
        override fun originalToTransformed(offset: Int) = offset
        override fun transformedToOriginal(offset: Int) = offset
    }
}

enum class KeyboardType { Text, ASCII, Number, Phone, Uri, Email, Password, NumberPassword, Decimal }

class KeyboardOptions(
    val keyboardType: KeyboardType = KeyboardType.Text,
    val imeAction: Any? = null
)
