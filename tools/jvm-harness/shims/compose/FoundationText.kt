// Harness stub — androidx.compose.foundation.text. See Runtime.kt for the policy.
// The real `BasicTextField` has a legacy `TextFieldValue` overload and a `TextFieldState` one; the
// app uses the former, which is what is declared here.
package androidx.compose.foundation.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation

@Composable
fun BasicTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions(),
    onKeyboardAction: ((Any?) -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformationIdentity,
    onTextLayout: ((Any?) -> Unit)? = null,
    cursorBrush: Brush = SolidColorBrush,
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit = { it() }
) = Unit

@Composable
fun BasicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions(),
    cursorBrush: Brush = SolidColorBrush,
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit = { it() }
) = Unit

internal val VisualTransformationIdentity: VisualTransformation = object : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString, offset: Int) =
        androidx.compose.ui.text.input.TransformedText(text, OffsetMappingIdentity)
}

internal val OffsetMappingIdentity: androidx.compose.ui.text.input.OffsetMapping =
    object : androidx.compose.ui.text.input.OffsetMapping {
        override fun originalToTransformed(offset: Int) = offset
        override fun transformedToOriginal(offset: Int) = offset
    }

internal val SolidColorBrush: Brush = SolidColor(Color.Unspecified)

