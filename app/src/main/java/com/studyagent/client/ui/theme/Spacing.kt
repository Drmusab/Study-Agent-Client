package com.studyagent.client.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing system.
 *
 * Use these for *layout rhythm* — screen gutters, card padding, gaps between rows. Do not
 * mechanically replace every historical value; small adjustments (±2dp) inside a control are
 * fine as long as the control's outer rhythm comes from here.
 */
object AppSpacing {

    val XXS: Dp = 4.dp
    val XS: Dp = 8.dp
    val SM: Dp = 12.dp
    val MD: Dp = 16.dp
    val LG: Dp = 20.dp
    val XL: Dp = 24.dp
    val XXL: Dp = 32.dp

    /** Horizontal padding of a scrolling screen. */
    val contentGutter: Dp = 20.dp

    /** Vertical gap between top-level cards/sections in a scrolling screen. */
    val screenSectionGap: Dp = 16.dp

    /** Inner padding of a standard card. */
    val cardPadding: Dp = 16.dp
    /** Inner padding of the hero card. */
    val heroCardPadding: Dp = 20.dp

    /** Gap between related fields inside a card. */
    val fieldGap: Dp = 12.dp

    // --- Responsive content widths ------------------------------------------
    /** Max width of dashboard/control content on tablet/foldable. */
    val dashboardMaxWidth: Dp = 920.dp
    /** Max width of study question/transcript/evaluation content. */
    val studyMaxWidth: Dp = 760.dp
    /** Below this width, multi-button rows collapse into a 2×2 grid. */
    val ratingGridBreakpoint: Dp = 430.dp
}
