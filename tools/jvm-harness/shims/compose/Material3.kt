// Harness stub — androidx.compose.material3 (1.2.x). See Runtime.kt for the policy.
package androidx.compose.material3

import androidx.compose.ui.graphics.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope

@Stable
class ColorScheme(
    val primary: Color = Color.Unspecified,
    val onPrimary: Color = Color.Unspecified,
    val primaryContainer: Color = Color.Unspecified,
    val onPrimaryContainer: Color = Color.Unspecified,
    val inversePrimary: Color = Color.Unspecified,
    val secondary: Color = Color.Unspecified,
    val onSecondary: Color = Color.Unspecified,
    val secondaryContainer: Color = Color.Unspecified,
    val onSecondaryContainer: Color = Color.Unspecified,
    val tertiary: Color = Color.Unspecified,
    val onTertiary: Color = Color.Unspecified,
    val tertiaryContainer: Color = Color.Unspecified,
    val onTertiaryContainer: Color = Color.Unspecified,
    val error: Color = Color.Unspecified,
    val onError: Color = Color.Unspecified,
    val errorContainer: Color = Color.Unspecified,
    val onErrorContainer: Color = Color.Unspecified,
    val background: Color = Color.Unspecified,
    val onBackground: Color = Color.Unspecified,
    val surface: Color = Color.Unspecified,
    val onSurface: Color = Color.Unspecified,
    val surfaceVariant: Color = Color.Unspecified,
    val onSurfaceVariant: Color = Color.Unspecified,
    val surfaceInverse: Color = Color.Unspecified,
    val onSurfaceInverse: Color = Color.Unspecified,
    val outline: Color = Color.Unspecified,
    val outlineVariant: Color = Color.Unspecified,
    val inverseSurface: Color = Color.Unspecified,
    val onInverseSurface: Color = Color.Unspecified,
    val scrim: Color = Color.Unspecified,
    val surfaceTint: Color = Color.Unspecified
)

fun lightColorScheme(
    primary: Color = Color.Unspecified,
    onPrimary: Color = Color.Unspecified,
    secondary: Color = Color.Unspecified,
    onSecondary: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
    onBackground: Color = Color.Unspecified,
    surface: Color = Color.Unspecified,
    onSurface: Color = Color.Unspecified,
    error: Color = Color.Unspecified,
    onError: Color = Color.Unspecified
): ColorScheme = ColorScheme()

fun darkColorScheme(
    primary: Color = Color.Unspecified,
    onPrimary: Color = Color.Unspecified,
    primaryContainer: Color = Color.Unspecified,
    secondary: Color = Color.Unspecified,
    onSecondary: Color = Color.Unspecified,
    tertiary: Color = Color.Unspecified,
    onTertiary: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
    onBackground: Color = Color.Unspecified,
    surface: Color = Color.Unspecified,
    onSurface: Color = Color.Unspecified,
    surfaceVariant: Color = Color.Unspecified,
    onSurfaceVariant: Color = Color.Unspecified,
    error: Color = Color.Unspecified,
    onError: Color = Color.Unspecified,
    outline: Color = Color.Unspecified,
    inverseSurface: Color = Color.Unspecified,
    onInverseSurface: Color = Color.Unspecified,
    scrim: Color = Color.Unspecified
): ColorScheme = ColorScheme()

@Stable
class Typography(
    val displayLarge: TextStyle = TextStyle(),
    val displayMedium: TextStyle = TextStyle(),
    val displaySmall: TextStyle = TextStyle(),
    val headlineLarge: TextStyle = TextStyle(),
    val headlineMedium: TextStyle = TextStyle(),
    val headlineSmall: TextStyle = TextStyle(),
    val titleLarge: TextStyle = TextStyle(),
    val titleMedium: TextStyle = TextStyle(),
    val titleSmall: TextStyle = TextStyle(),
    val bodyLarge: TextStyle = TextStyle(),
    val bodyMedium: TextStyle = TextStyle(),
    val bodySmall: TextStyle = TextStyle(),
    val labelLarge: TextStyle = TextStyle(),
    val labelMedium: TextStyle = TextStyle(),
    val labelSmall: TextStyle = TextStyle()
)

@Stable
class Shapes(
    val extraSmall: Shape = RectangleShape,
    val small: Shape = RectangleShape,
    val medium: Shape = RectangleShape,
    val large: Shape = RectangleShape,
    val extraLarge: Shape = RectangleShape
)

// material3 does not redeclare shape factories; the defaults below are internal to this stub.
private val RectangleShape: Shape = object : Shape {}

object MaterialTheme {
    val colorScheme: ColorScheme get() = ColorScheme()
    val typography: Typography get() = Typography()
    val shapes: Shapes get() = Shapes()
}

@Composable
fun MaterialTheme(
    colorScheme: ColorScheme = ColorScheme(),
    typography: Typography = Typography(),
    shapes: Shapes = Shapes(),
    content: @Composable () -> Unit
) = content()

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (Any) -> Unit = {},
    style: TextStyle = TextStyle()
) = Unit

@Composable
fun Text(
    text: androidx.compose.ui.text.AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = TextStyle()
) = Unit

@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) = content()

@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) = content()

@Composable
fun ElevatedCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) = content()
@Composable
fun OutlinedCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) = content()

class CardColors(val container: Color, val content: Color)
class CardElevation

object CardDefaults {
    fun cardColors(containerColor: Color = Color.Unspecified, contentColor: Color = Color.Unspecified) =
        CardColors(containerColor, contentColor)

    fun cardElevation(defaultElevation: Dp = 0.dp, pressedElevation: Dp = 0.dp) = CardElevation()
    fun outlinedCardColors(containerColor: Color = Color.Unspecified, contentColor: Color = Color.Unspecified) =
        CardColors(containerColor, contentColor)
}

@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    content: @Composable (PaddingValues) -> Unit
) = Unit


enum class FabPosition { Start, Center, End, Custom }

class TopAppBarColors
class TopAppBarScrollBehavior

@Composable
fun TopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    expanded: Boolean = true,
    colors: TopAppBarColors = TopAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null
) = Unit

interface TopAppBarScope

class TopAppBarDefaults {
    companion object {
        fun topAppBarColors(containerColor: Color = Color.Unspecified,
                            titleContentColor: Color = Color.Unspecified): TopAppBarColors = TopAppBarColors()

        @Composable
        fun topAppBarScrollBehavior(): TopAppBarScrollBehavior = TopAppBarScrollBehavior()
    }
}

@Composable
fun CenterAlignedTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarColors()
) = Unit

@Composable
fun SmallTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarColors()
) = Unit

@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) = Unit

@Composable
fun Icon(
    painter: androidx.compose.ui.graphics.Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) = Unit

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) = content()

class IconButtonColors
object IconButtonDefaults {
    fun iconButtonColors(): ButtonColors = ButtonColors()
}

class ButtonColors(val container: Color = Color.Unspecified, val content: Color = Color.Unspecified)
class ButtonElevation
class TextFieldColors
class SwitchColors
class RadioButtonColors
class SliderColors
class TabRowDefaults {
    class SecondaryIndicator
}

object ButtonDefaults {
    // Parameter names/order follow `material3.1.2.0-beta02.txt`: every colour factory takes the
    // disabled variants too, and they all return `ButtonColors`.
    fun buttonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified
    ) = ButtonColors(containerColor, contentColor)

    fun buttonElevation(defaultElevation: Dp = 0.dp) = ButtonElevation()
    fun textButtonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified
    ) = ButtonColors(containerColor, contentColor)
    fun outlinedButtonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified
    ) = ButtonColors(containerColor, contentColor)
    fun filledTonalButtonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified
    ) = ButtonColors(containerColor, contentColor)
    fun elevatedButtonElevation() = ButtonElevation()
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = Unit

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = PaddingValues(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = Unit

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = Unit

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = PaddingValues(),
    content: @Composable RowScope.() -> Unit
) = Unit

@Composable
fun TabRow(
    selectedTabIndex: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    indicator: @Composable TabRowScope.() -> Unit = {},
    divider: @Composable () -> Unit = {},
    tabs: @Composable () -> Unit
) = Unit

interface TabRowScope {
    @Composable
    fun LinearProgressIndicator(modifier: Modifier = Modifier, color: Color = Color.Unspecified) = Unit
}

@Composable
fun ScrollableTabRow(
    selectedTabIndex: Int,
    modifier: Modifier = Modifier,
    edgePadding: Dp = 0.dp,
    tabs: @Composable () -> Unit
) = Unit

@Composable
fun Tab(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    selectedContentColor: Color = Color.Unspecified,
    unselectedContentColor: Color = Color.Unspecified
) = Unit

@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = 0.dp,
    trackColor: Color = Color.Unspecified,
    strokeCap: Any = Unit
) = Unit

@Composable
fun CircularProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = 0.dp,
    trackColor: Color = Color.Unspecified
) = Unit

@Composable
fun LinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified
) = Unit

@Composable
fun LinearProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified
) = Unit

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null
) = Unit

object SwitchDefaults {
    fun colors(checkedThumbColor: Color = Color.Unspecified, checkedTrackColor: Color = Color.Unspecified,
               uncheckedThumbColor: Color = Color.Unspecified, uncheckedTrackColor: Color = Color.Unspecified) =
        SwitchColors()
}

@Composable
fun RadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: RadioButtonColors = RadioButtonDefaults.colors()
) = Unit

object RadioButtonDefaults {
    fun colors(
        selectedColor: Color = Color.Unspecified,
        unselectedColor: Color = Color.Unspecified,
        disabledSelectedColor: Color = Color.Unspecified,
        disabledUnselectedColor: Color = Color.Unspecified
    ) = RadioButtonColors()
}

@Composable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(),
    interactionSource: MutableInteractionSource? = null
) = Unit

class CheckboxColors

object CheckboxDefaults {
    fun colors(checkedColor: Color = Color.Unspecified, uncheckedColor: Color = Color.Unspecified,
               checkmarkColor: Color = Color.Unspecified,
               disabledCheckedColor: Color = Color.Unspecified,
               disabledUncheckedColor: Color = Color.Unspecified,
               disabledIndeterminateColor: Color = Color.Unspecified) = CheckboxColors()
}

@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    colors: FilterChipColors = FilterChipDefaults.filterChipColors(),
    border: BorderStroke? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) = Unit

class FilterChipColors
object FilterChipDefaults {
    fun filterChipColors(containerColor: Color = Color.Unspecified, selectedContainerColor: Color = Color.Unspecified) =
        FilterChipColors()

    fun filterChipBorder(enabled: Boolean = true, selected: Boolean = false): BorderStroke? = null
}

// Real Material3 chip colour types: `SelectableChipColors` covers selected chips (filter/input),
// `ChipColors` the assist/suggestion ones, and `AssistChipDefaults` supplies their defaults.
abstract class SelectableChipColors
abstract class ChipColors

object AssistChipDefaults {
    val shape: Shape = RectangleShape

    fun assistChipColors(containerColor: Color = Color.Unspecified, labelColor: Color = Color.Unspecified): ChipColors =
        object : ChipColors() {}

    fun assistChipBorder(enabled: Boolean = true, borderWidth: Dp = Dp.Unspecified): BorderStroke? = null
}

@Composable
fun AssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = AssistChipDefaults.shape,
    colors: ChipColors = AssistChipDefaults.assistChipColors(),
    border: BorderStroke? = AssistChipDefaults.assistChipBorder(),
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) = Unit

@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors()
) = Unit

@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    steps: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SliderColors = SliderDefaults.colors()
) = Unit

object SliderDefaults {
    fun colors(thumbColor: Color = Color.Unspecified, activeTrackColor: Color = Color.Unspecified,
               inactiveTrackColor: Color = Color.Unspecified) = SliderColors()
}

@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle(),
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    error: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    isError: Boolean = false,
    shape: Shape = RectangleShape,
    colors: TextFieldColors = TextFieldDefaults.colors(),
    border: Any? = null,
    contentPadding: PaddingValues = PaddingValues(),
    keyboardOptions: androidx.compose.ui.text.input.KeyboardOptions =
        androidx.compose.ui.text.input.KeyboardOptions(),
    keyboardAction: ((Any?) -> Unit)? = null,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation? = null,
    interactionSource: MutableInteractionSource? = null,
    onVisualTransformation: androidx.compose.ui.text.input.VisualTransformation? = null
) = Unit

@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    isError: Boolean = false,
    colors: TextFieldColors = TextFieldDefaults.colors()
) = Unit

object TextFieldDefaults {
    fun textFieldColors(): TextFieldColors = TextFieldColors()
    fun outlinedTextFieldColors(): TextFieldColors = TextFieldColors()

    /** `TextFieldDefaults.colors(...)` is the 1.2-era factory the app uses. */
    fun colors(
        focusedTextColor: Color = Color.Unspecified,
        unfocusedTextColor: Color = Color.Unspecified,
        disabledTextColor: Color = Color.Unspecified,
        errorColor: Color = Color.Unspecified,
        cursorColor: Color = Color.Unspecified,
        focusedIndicatorColor: Color = Color.Unspecified,
        unfocusedIndicatorColor: Color = Color.Unspecified,
        disabledIndicatorColor: Color = Color.Unspecified,
        errorIndicatorColor: Color = Color.Unspecified,
        focusedContainerColor: Color = Color.Unspecified,
        unfocusedContainerColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        errorContainerColor: Color = Color.Unspecified,
        focusedPrefixColor: Color = Color.Unspecified,
        unfocusedPrefixColor: Color = Color.Unspecified,
        focusedSuffixColor: Color = Color.Unspecified,
        unfocusedSuffixColor: Color = Color.Unspecified,
        focusedLabelTextColors: Color = Color.Unspecified,
        unfocusedLabelTextColors: Color = Color.Unspecified,
        focusedSupportingTextColor: Color = Color.Unspecified,
        unfocusedSupportingTextColor: Color = Color.Unspecified,
        errorSupportingTextColor: Color = Color.Unspecified
    ) = TextFieldColors()

    fun textFieldBorder(): Any = Any()
    fun outlinedTextFieldBorder(): Any = Any()
    fun textFieldPadding(start: Dp = 0.dp, end: Dp = 0.dp, top: Dp = 0.dp, bottom: Dp = 0.dp) =
        PaddingValues(start, top, end, bottom)
    fun singleLineTextFieldPadding(start: Dp = 0.dp, end: Dp = 0.dp, top: Dp = 0.dp, bottom: Dp = 0.dp) =
        PaddingValues(start, top, end, bottom)
    fun decorationBox(): Any = Any()
}

class MenuItemColors

object MenuItemDefaults {
    /** `MenuItemDefaults.colors(...)` per `material3.1.2.0-beta02.txt` (six colour slots). */
    fun colors(textColor: Color = Color.Unspecified, leadingIconColor: Color = Color.Unspecified,
               trailingIconColor: Color = Color.Unspecified,
               disabledTextColor: Color = Color.Unspecified,
               disabledLeadingIconColor: Color = Color.Unspecified,
               disabledTrailingIconColor: Color = Color.Unspecified) = MenuItemColors()
}

@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) = Unit

@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    colors: MenuItemColors = MenuItemDefaults.colors(),
    shape: Shape = RectangleShape,
    contentPadding: PaddingValues = PaddingValues(),
    interactionSource: MutableInteractionSource? = null
) = Unit



@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = RectangleShape
) = Unit

@Composable
fun BasicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle(),
    singleLine: Boolean = false
) = Unit

@Composable
fun Divider(modifier: Modifier = Modifier, thickness: Dp = 0.dp, color: Color = Color.Unspecified) = Unit

@Composable
fun HorizontalDivider(modifier: Modifier = Modifier, thickness: Dp = 0.dp, color: Color = Color.Unspecified) = Unit

@Composable
fun VerticalDivider(modifier: Modifier = Modifier, thickness: Dp = 0.dp, color: Color = Color.Unspecified) = Unit

@Composable
fun ProgressIndicator(modifier: Modifier = Modifier) = Unit
