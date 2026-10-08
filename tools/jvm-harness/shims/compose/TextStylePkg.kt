// Harness stub — androidx.compose.ui.text.style, transcribed from the pinned api dump
// `compose__ui__ui-text.1.6.0-beta01.txt`. The real declarations are `@JvmInline value class`es with
// `Companion` properties; modelling them as `interface + companion object` keeps the same call sites
// type-correct without value-class machinery. See Runtime.kt for the policy.
package androidx.compose.ui.text.style

interface TextDirection {
    companion object {
        val Unspecified: TextDirection = object : TextDirection {}
        val Content: TextDirection = object : TextDirection {}
        val ContentOrLtr: TextDirection = object : TextDirection {}
        val ContentOrRtl: TextDirection = object : TextDirection {}
        val Ltr: TextDirection = object : TextDirection {}
        val Rtl: TextDirection = object : TextDirection {}
    }
}

interface TextDecoration {
    companion object {
        val None: TextDecoration = object : TextDecoration {}
        val Underline: TextDecoration = object : TextDecoration {}
        val LineThrough: TextDecoration = object : TextDecoration {}
        val Overline: TextDecoration = object : TextDecoration {}
    }
}

interface TextOverflow {
    companion object {
        val Clip: TextOverflow = object : TextOverflow {}
        val Visible: TextOverflow = object : TextOverflow {}
        val Ellipsis: TextOverflow = object : TextOverflow {}
        val MiddleEllipsis: TextOverflow = object : TextOverflow {}
    }
}

interface TextAlign {
    companion object {
        val Left: TextAlign = object : TextAlign {}
        val Right: TextAlign = object : TextAlign {}
        val Center: TextAlign = object : TextAlign {}
        val Justify: TextAlign = object : TextAlign {}
        val Start: TextAlign = object : TextAlign {}
        val End: TextAlign = object : TextAlign {}
    }
}

interface LineBreak {
    companion object {
        val Simple: LineBreak = object : LineBreak {}
        val Word: LineBreak = object : LineBreak {}
        val Heading: LineBreak = object : LineBreak {}
    }
}

interface Hyphens {
    companion object {
        val Auto: Hyphens = object : Hyphens {}
        val None: Hyphens = object : Hyphens {}
    }
}

class LineHeightStyle(val alignment: Alignment = Alignment.Proportional,
                      val trim: Trim = Trim.None) {
    enum class Alignment { Top, Center, Bottom, Proportional }
    enum class Trim { None, Both, Start, End, FirstLineOnly, LastLineOnly }
}

class TextIndent(val start: androidx.compose.ui.unit.TextUnit,
                 val end: androidx.compose.ui.unit.TextUnit)
