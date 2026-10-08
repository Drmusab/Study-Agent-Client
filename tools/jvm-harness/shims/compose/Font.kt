// Harness stub — androidx.compose.ui.text.font. See Runtime.kt for the policy.
package androidx.compose.ui.text.font

open class FontFamily(val name: String = "") {
    companion object {
        val Default: FontFamily = FontFamily("default")
        val Serif: FontFamily = FontFamily("serif")
        val SansSerif: FontFamily = FontFamily("sansSerif")
        val Monospace: FontFamily = FontFamily("monospace")
        val Cursive: FontFamily = FontFamily("cursive")
    }
}

class FontWeight(val weight: Int) {
    companion object {
        val Thin = FontWeight(100)
        val ExtraLight = FontWeight(200)
        val Light = FontWeight(300)
        val Normal = FontWeight(400)
        val Medium = FontWeight(500)
        val SemiBold = FontWeight(600)
        val Bold = FontWeight(700)
        val ExtraBold = FontWeight(800)
        val Black = FontWeight(900)
    }
}

interface FontStyle {
    companion object {
        val Normal: FontStyle = object : FontStyle {}
        val Italic: FontStyle = object : FontStyle {}
    }
}

class Font(val resId: Int = 0)

class FontProvider

fun Font(resourceId: Int, format: Any? = null): Font = Font(resourceId)
