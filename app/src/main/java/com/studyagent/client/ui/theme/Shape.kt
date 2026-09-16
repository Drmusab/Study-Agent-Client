package com.studyagent.client.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner-radius system. Pick by *role*, not by number:
 *
 *  [chip]      — chips, badges, status pills
 *  [button]    — buttons of all styles
 *  [field]     — text fields, search boxes, stepper boxes
 *  [card]      — standard cards
 *  [heroCard]  — the one dominant card per screen (Smart Start, status hero)
 *  [dialog]    — dialogs / sheets
 *  [circle]    — avatars, dots, waveform-less circles
 */
object AppShape {

    val chip: Dp = 999.dp
    val button: Dp = 14.dp
    val field: Dp = 12.dp
    val card: Dp = 16.dp
    val heroCard: Dp = 24.dp
    val dialog: Dp = 28.dp

    val chipShape = RoundedCornerShape(chip)
    val buttonShape = RoundedCornerShape(button)
    val fieldShape = RoundedCornerShape(field)
    val cardShape = RoundedCornerShape(card)
    val heroCardShape = RoundedCornerShape(heroCard)
    val dialogShape = RoundedCornerShape(dialog)
    val circleShape: RoundedCornerShape = CircleShape
}
