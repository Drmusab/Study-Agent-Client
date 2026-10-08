// Harness stub — androidx.compose.ui.text. See Runtime.kt for the policy.
package androidx.compose.ui.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

open class TextStyle(
    val color: Color = Color.Unspecified,
    val fontSize: TextUnit = TextUnit.Unspecified,
    val lineHeight: TextUnit = TextUnit.Unspecified,
    val fontFamily: FontFamily? = null,
    val fontStyle: FontStyle? = null,
    val fontWeight: FontWeight? = null,
    val letterSpacing: TextUnit = TextUnit.Unspecified,
    val textDecoration: TextDecoration? = null,
    val textAlign: TextAlign? = null,
    val textDirection: TextDirection? = null,
    val platformStyle: Any? = null,
    val lineBreak: Any? = null,
    val lineHeightStyle: LineHeightStyle? = null
) {
    fun copy(
        color: Color = this.color,
        fontSize: TextUnit = this.fontSize,
        lineHeight: TextUnit = this.lineHeight,
        fontFamily: FontFamily? = this.fontFamily,
        fontStyle: FontStyle? = this.fontStyle,
        fontWeight: FontWeight? = this.fontWeight,
        letterSpacing: TextUnit = this.letterSpacing,
        textDecoration: TextDecoration? = this.textDecoration,
        textAlign: TextAlign? = this.textAlign,
        textDirection: TextDirection? = this.textDirection,
        platformStyle: Any? = this.platformStyle
    ): TextStyle = TextStyle(color, fontSize, lineHeight, fontFamily, fontStyle, fontWeight,
        letterSpacing, textDecoration, textAlign, textDirection, platformStyle)

    fun merge(other: TextStyle? = null): TextStyle = other ?: this

    companion object {
        val Default = TextStyle()
        val LocalTextStyle: TextStyle = TextStyle()
    }
}

class AnnotatedString(val text: String = "") {
    class Builder {
        fun append(value: String): Builder = this
        fun addStyle(style: Any?, start: Int, end: Int): Builder = this
        fun toAnnotatedString(): AnnotatedString = AnnotatedString()
    }

    companion object {
        val Empty = AnnotatedString()
    }
}

class PersistentTextStyle

interface TextLayoutResult {
    val lineCount: Int
}

fun buildAnnotatedString(block: AnnotatedString.Builder.() -> Unit): AnnotatedString = AnnotatedString()

class KeyboardOptions
