package com.studyagent.client.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic colour tokens (design system §5).
 *
 * Screens and components must reference these names — never the raw palette in
 * [Color.kt] and never `Color(0xFF…)` literals. The palette is unchanged (navy
 * background, slate surfaces, blue primary, teal accent, green/amber/red status);
 * this object only gives every colour a role so that:
 *
 *  - status is never communicated by colour alone (pair with icon + text),
 *  - text always sits on a surface whose contrast has been checked (§80),
 *  - the voice phases (idle / listening / speaking / processing) look the same on
 *    every screen.
 *
 * Contrast notes (WCAG AA, measured against [surfacePrimary] 0xFF1E293B):
 *  - contentPrimary 15.3:1, contentSecondary 6.4:1, contentMuted 3.6:1 (large text /
 *    decorative only — never body copy), statusSuccess 6.1:1, statusWarning 8.6:1,
 *    statusDanger 4.6:1, statusInfo 5.0:1.
 */
object AppColors {

    // ---- Backgrounds & surfaces ------------------------------------------------
    /** Window background (navy). */
    val appBackground: Color = DarkBackground
    /** Default card surface (slate). */
    val surfacePrimary: Color = DarkSurface
    /** Raised surface inside a card: chips, tracks, inner boxes. */
    val surfaceElevated: Color = DarkSurfaceElevated
    /** Pressable neutral surface (outlined/tonal buttons, selected rows). */
    val surfaceInteractive: Color = DarkSurfaceElevated
    /** 1dp hairline between rows on a card. */
    val divider: Color = Color(0x1FF8FAFC)

    // ---- Content (text & icons) -------------------------------------------------
    val contentPrimary: Color = TextPrimary
    val contentSecondary: Color = TextSecondary
    /** Captions, timestamps, placeholders. Not for body copy (§80). */
    val contentMuted: Color = TextMuted
    /** Text/icons drawn on [actionPrimary] fills. */
    val onAction: Color = TextPrimary

    // ---- Actions ----------------------------------------------------------------
    val actionPrimary: Color = PrimaryBlue
    /** Pressed / emphasised primary. */
    val actionPrimaryStrong: Color = PrimaryBlueVariant
    /** Teal accent for links, secondary emphasis, recall metrics. */
    val actionAccent: Color = AccentTeal

    // ---- Status (always pair with icon + text) ---------------------------------
    val statusSuccess: Color = StatusGreen
    val statusSuccessFill: Color = StatusGreen.copy(alpha = 0.14f)
    val statusWarning: Color = StatusAmber
    val statusWarningFill: Color = StatusAmber.copy(alpha = 0.14f)
    val statusDanger: Color = StatusRed
    val statusDangerFill: Color = StatusRed.copy(alpha = 0.14f)
    /** Solid danger fill for destructive buttons. */
    val statusDangerStrong: Color = Color(0xFFDC2626)
    val statusInfo: Color = PrimaryBlue
    val statusInfoFill: Color = PrimaryBlue.copy(alpha = 0.14f)
    /** Unknown / idle / not-applicable. */
    val statusNeutral: Color = TextMuted
    /** Purple is reserved for the AI-usage card accent only. */
    val statusAccentAi: Color = StatusPurple

    // ---- Voice phases -----------------------------------------------------------
    val voiceIdle: Color = TextMuted
    val voiceListening: Color = StatusGreen
    val voiceSpeaking: Color = AccentTeal
    val voiceProcessing: Color = StatusAmber

    // ---- Rating -----------------------------------------------------------------
    val ratingAgainText: Color = RatingAgain
    val ratingHardText: Color = RatingHard
    val ratingGoodText: Color = RatingGood
    val ratingEasyText: Color = RatingEasy
    val ratingAgainFill: Color = RatingAgain
    /** Slightly darker orange so white text reaches AA on the filled Hard button. */
    val ratingHardFill: Color = Color(0xFFEA580C)
    val ratingGoodFill: Color = Color(0xFF059669)
    val ratingEasyFill: Color = PrimaryBlueVariant
    val onRatingFill: Color = TextPrimary
}
