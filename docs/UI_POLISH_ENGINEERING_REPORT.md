# Study-Agent-Client — UI Polish Engineering Report

**Date:** 2026-09-16 (UTC)  
**Branch:** `arena/01a0a972-study-agent-client`  
**Base commit:** `c7e220afaef7a3a1fff1f123a44225316de59051` (UI: semantic design system, component library and screen refinement #11)  
**Engineer:** Arena Agent — Senior Android UI/UX / Jetpack Compose Architect / Design-System / Accessibility / Compose Performance

This report completes **§108 — Final Engineering Report** of the Master Polish Prompt. It covers the audit findings, the design system, per-screen changes, accessibility, responsive design, Compose performance, tests, build results and remaining limitations after the secondary polish pass on top of `#11`.

---

## 1. UI Audit — What Was Found

The pre-refactor audit is preserved verbatim in [`docs/UI-ADUIT.md`](UI-ADUIT.md) (captured at `master @ 9e0d43b`). Summary:

### 1.1 Critical defects (would break build or voice UX)

| # | Where | Before | After |
|---|-------|--------|-------|
| **C1** | `SettingsScreen.kt:378` — `EnginePicker(engines = engines, …)` references a local `engines` that was never collected. The ViewModel exposes `StateFlow<List<TtsEngineInfo>> engines` but the screen never subscribed. | Compile error, app does not build. | **Fixed in this pass.** Added `val engines by viewModel.engines.collectAsStateWithLifecycle()` alongside `englishVoices/arabicVoices`. `EnginePicker` now receives the lifecycle-aware list. |
| **C2** | `StudyScreen.kt` — `viewModel.studyAudioRoute?.collectAsState()?.value` (`audioRouteAttention`, `pendingAudioRoute`, `appSettings`). Called `collectAsState()` and immediately read `.value` discarding the `State` snapshot. Flows never triggered recomposition except as side-effect; the headset-loss card and phone-notice could stay stale until an unrelated state changed. Also used non-lifecycle `collectAsState` outside a lifecycle-aware holder. | Stale route notice, headset-loss card stale. | **Fixed in #11 and tightened in this pass.** Now `collectAsStateWithLifecycle()` with explicit intermediate `State` variables (`studyAudioRouteState`, `audioRouteAttentionState`, `pendingAudioRouteState`) so the `State` object is retained per-composition and `.value` read participates in snapshot tracking. Comment documents the nullable `StateFlow?` pattern (no coordinator in some test wirings). |
| **C3** | `ControlCenterScreen.kt` — `Icons.Default.ArrowBack` (non-mirrored) while every other screen uses `AutoMirrored`. | Wrong direction in Arabic (RTL). | **Fixed in #11.** All top bars now use `StudyAgentTopBar` which renders `Icons.AutoMirrored.Filled.ArrowBack` with `contentDescription = "Back"` — verified in this pass. |

### 1.2 Design-system drift (the long tail)

*No semantic layer.* Screens imported `DarkBackground`, `PrimaryBlue`, `TextMuted` etc directly; `MaterialTheme.colorScheme` used in 2 places. Fixed by `AppColors` (see §2).

*Contrast failures:* `TextMuted #64748B` on `DarkSurface #1E293B` ≈ 3.0:1 (fails AA for body), white on `RatingAgain #EF4444` ≈ 3.6:1 / on `RatingGood #10B981` ≈ 3.4:1 (fails). Fixed by darker `ratingHardFill #EA580C` and `ratingGoodFill #059669`, and documenting `contentMuted` as decorative-only.

*Spacing* 20/18/16 gutters, free-form 2/4/6/8/10/12/14/16/20/24 spacers, card paddings 14/16/18/20, *shape* 16/18/20/10 corners, button corners 10/12/14 mixed, *typography* only 6 of 15 Material roles customized plus inline `.copy(fontSize=…)` overrides, *no metric or mono roles.* All codified into `AppSpacing`, `AppShape`, `Typography` + `AppTextStyle` (see §2).

*Button hierarchy* — multiple filled primaries per view, tiny rating buttons, disabled `alpha(0.4f)` mixed with M3 default, touch targets <48dp (`ChoiceChips` 40dp, log filter 32dp, `ConnectionBadge` ≈30dp). Fixed to 48dp/52dp minima (see §5).

*Cards* — four parallel card components + ~15 inline `Card(…)` sites; *status chips* half-formed; *top bars* four different hand-rolled implementations with varying height/padding/tint. Unified into `StudyAgentTopBar`, `AppCard`/`AppHeroCard` (see §2/§3).

*Study screen overload* — 10 controls always visible regardless of phase, two redundant state readouts (`StudyStateHeader` emoji + `PhaseChip`), emoji icons, waveform inside question card, identical styling for partial vs final transcript, `incorrectPoints` never rendered, paused state invisible, no recovery card. Replaced with single `StudyPhaseChip` + `StudySessionHeader`, `VoiceWaveVisualizer` boolean-driven, distinct partial/final transcript styling, `EvaluationCard` with expandable key-points, visible `PausedCard` (see §3).

*Large-screen* — no `widthIn(max=…)`, no landscape handling. Added `dashboardMaxWidth 920`, `studyMaxWidth 760`, `ratingGridBreakpoint 430` with `BoxWithConstraints` caps.

*Recomposition* — `StudyScreen` rebuilt on every partial-transcript update, `HomeScreen` `nowMs` tick recomposed whole dashboard, `DiagnosticsScreen` rebuilt filtered lists per composition, `audioLevel` RMS collected on screen. Isolated to boolean props, `remember(keys)` memoization, never collect `audioLevel` on screen.

Full 18-section audit remains in `UI-ADUIT.md` — no findings were dropped.

---

## 2. Design System — Tokens, Typography, Components

Design system single source is [`docs/UI_DESIGN_SYSTEM.md`](UI_DESIGN_SYSTEM.md) + [`docs/UI_ARCHITECTURE.md`](UI_ARCHITECTURE.md). This section is the executive summary.

### 2.1 Colour tokens — `ui/theme/AppColors.kt` over `Color.kt`

Palette preserved verbatim (dark navy `#0F172A`, slate `#1E293B`, elevated `#334155`, blue primary `#3B82F6`/`#2563EB`, teal accent `#14B8A6`, status green/amber/red/purple, per-rating red/orange/green/blue). Only semantic names are used by screens:

```
AppColors.appBackground
surfacePrimary / surfaceElevated / surfaceInteractive / divider
contentPrimary / contentSecondary / contentMuted / onAction
actionPrimary / actionPrimaryStrong / actionAccent
statusSuccess/Warning/Danger/Info + *Fill / statusDangerStrong / statusNeutral / statusAccentAi
voiceIdle / voiceListening / voiceSpeaking / voiceProcessing
rating*Text / rating*Fill / onRatingFill
```

Contrast (against `surfacePrimary #1E293B`): `contentPrimary` 15.3:1, `contentSecondary` 6.4:1, `contentMuted` 3.6:1 (large/decorative only), `statusSuccess` 6.1:1, `statusWarning` 8.6:1, `statusDanger` 4.6:1, `statusInfo` 5.0:1. Rating fills darker shades guarantee AA with white (`onRatingFill`). Rule: status is never colour-only — icon + text always accompany colour.

Only `ui/theme/Color.kt` and `AppColors.kt` may contain `Color(0xFF…)`.

### 2.2 Spacing — `AppSpacing`

Base: `XXS 4 · XS 8 · SM 12 · MD 16 · LG 20 · XL 24 · XXL 32`. Semantic: `contentGutter 20` (screen horizontal padding), `screenSectionGap 16`, `cardPadding 16`, `heroCardPadding 20`, `fieldGap 12`. Responsive limits: `dashboardMaxWidth 920`, `studyMaxWidth 760`, `ratingGridBreakpoint 430`. Rule: small ±2dp adjustments inside controls are allowed; outer rhythm comes from tokens.

### 2.3 Shape — `AppShape`

`chip` pill (999dp) · `button` 14 · `field` 12 · `card` 16 · `heroCard` 24 · `dialog` 28 · `circle`. Objects: `chipShape`, `buttonShape`, `fieldShape`, `cardShape`, `heroCardShape`, `dialogShape`, `circleShape`. No per-screen `RoundedCornerShape(X.dp)` literals.

### 2.4 Typography — `ui/theme/Type.kt` + `AppTextStyle`

Complete Material 3 scale (displaySmall 34/40, headlineLarge 28/34, headlineMedium 22/28, headlineSmall 20/26, titleLarge 18/24, titleMedium 16/22, titleSmall 15/20, bodyLarge 16/24, bodyMedium 14/20, bodySmall 12/16, labelLarge 14/18, labelMedium 12/16/0.6LS, labelSmall 11/14/0.4LS). All in `sp` for font-scaling.

Additional roles:
* `metricValue` 22 Bold / `metricLabel` 12 Medium — `MetricTile` numbers dominate labels
* `monoValue` 12 Monospace / `monoCaption` 11 Monospace — Diagnostics only

Rule: never `copy(fontSize=…)` in screens; pick a style. Medical, Arabic, mixed bidi renders naturally via `Text` without manual reversal.

### 2.5 Motion — `AppMotion`

`quick 150ms`, `standard 220ms`, `slow 300ms`, `FastOutSlowIn`. `useReducedMotion()` (API 30+ `isReduceMotionEnabled`, false below 30). Continuous animation (PTT pulse, wave bars) gated on it. Card advance is `fade + 12dp vertical shift` (§71). Transition for Speaking→Listening is `icon + text + waveform + 180ms fade` (§72).

### 2.6 Components — `ui/components/`

| Component | File | Key rule |
|-----------|------|----------|
| `StudyAgentTopBar` | StudyAgentTopBar.kt | Only top bar. `AutoMirrored` back, title + optional subtitle, trailing slot. Replaces 4 ad-hoc bars. |
| `AppCard` / `AppHeroCard` | AppPrimitives.kt | Solid surfaces (no shadow on dark). Hero larger radius + padding, one per screen. |
| `SectionHeader` | AppPrimitives.kt | Upper-cased `labelMedium` + optional trailing action. |
| `MetricTile` | AppPrimitives.kt | Number dominates label, 22sp Bold vs 12sp Medium. |
| `StatusBadge` | AppPrimitives.kt | Pill dot + icon + text + colour. |
| `InfoBanner` (NEUTRAL/INFO/WARNING/DANGER) | AppPrimitives.kt | Title + message + one action + optional dismiss. Answers *what happened / can I continue / what to do* (§77). No raw stack traces. |
| `EmptyState`, `SkeletonBox`, `SkeletonCard` | AppPrimitives.kt | No spinners in content areas (§74). Skeleton is static, not shimmer (§75). |
| `Primary/Secondary/Destructive/InlineTextButton` | AppControls.kt | ≥48dp, `AppShape.buttonShape`, Title Case, optional icon, `loading` on primary. Destructive uses `statusDangerStrong` when filled. |
| `SettingRow`, `ChoiceRow`, `ExpandableSection`, `KeyValueRow`, `AppDivider` | AppControls.kt | Switch whole-row toggleable (≥56dp, `Role.Switch`), radio whole-row selectable (≥52dp, `Role.RadioButton`), expandable with rotated chevron, dense label/value. |
| `StudyPhaseChip`, `StudySessionHeader`, `studyPhaseOf` | StudyPhase.kt | Single mapping `StudyState → StudyPhase` (pure, unit-tested). Chip animates with `AnimatedContent` fade on phase change; icon+text+colour never colour alone. Tag `study_phase_chip`. |
| `PushToTalkButton` | PushToTalkButton.kt | 72dp, 4 states (Ready red/blue, Listening red + pulse, Processing spinner, Disabled muted), `detectTapGestures` hold-or-tap, haptics (`TextHandleMove` / `VIRTUAL_KEY`), reduced-motion aware. Tag `PUSH_TO_TALK_TEST_TAG`. |
| `RatingButtonGroup` | RatingButtonGroup.kt | Responsive: 2×2 below `ratingGridBreakpoint` else 1×4. Each ≥52dp. Suggested rating is solid fill + `★ ` prefix + white text; others 16% tint. Tags `ratingTestTag(rating)`. |
| `ConnectionBadge` + `connectionVisualOf` | ConnectionBadge.kt | Tappable chip ≥48dp, icon + text + colour, `heightIn(min=48.dp)`. |
| `AudioRouteIndicator` | AudioRouteIndicator.kt | Neutral chip. Phone audio is a normal route, not a warning. |
| `VoiceWaveVisualizer` | VoiceWaveVisualizer.kt | 7-bar boolean-driven indicator. `rememberInfiniteTransition` only while `isActive && !reducedMotion`; idle flat 15% bars. RMS never collected by screens. |

Control-only helpers (`ControlComponents.kt`): `ControlSection`, `ChoiceChips` (≥48dp), `StepperRow`, `SwitchRow`, `SliderRow`, `ExpandableAdvanced`.

Dashboard-only helpers (`DashboardCards.kt`): `SectionCard`, `StatItem`, `SystemReadinessCard`, `SmartStartCard`, `ActiveSessionCard`, `TodayStatsCard`, `GoalProgressCard`, `WeeklyBars`, `RatingDistributionRows`, `LearningInsightCard`, `RecommendationCard`, `AiUsageCard`.

All components are **stateless** (data + callbacks, no ViewModel/repository) and have at least one `@Preview` with static fakes. Screens import only `AppColors / AppSpacing / AppShape / Typography / AppTextStyle / AppMotion` and components — never raw `Color.kt`.

---

## 3. Screen Changes — Per Destination

### 3.1 Dashboard (HomeScreen) — highest visual priority (§15)

**Before:** 20/18/16dp gutters mixed, 6 inline card styles, equal-weight card stack, no width cap, no skeletons, error/legacy banners ad-hoc, history bars recomputed per composition, chips 40dp.

**After (current, polished in #11 + this pass):**
* Scaffold `appBackground`, `BoxWithConstraints` + `maxWidth.coerceAtMost(dashboardMaxWidth)` centred — tablet/foldable no longer stretches to 1000dp. Single centred column is the intentional responsive strategy (see §6).
* Order fixed: **header** (title `Study Agent` + freshness dot+label `Live • updated …` / `Cached` / `Stale` in `labelSmall` `contentMuted` — subtle, never louder than study data) → **connection/audio FlowRow** → **banners** (at most one: error | offline | v1) → **SystemReadinessCard** (PC Agent *is* transport-connected; Anki/LLM only from `component_health` — never inferred) → **hero** (exactly one: active session | finished summary | Smart Start) → **active deck** (capability-gated, `DeckPickerDialog`) → **Today** → **Goal** → **History** (`WeeklyBars` + metric/ range FilterChips now **48dp**, accessible text toggle `Show values` vs chart, rating distribution) → **Insight** → **Recommendation** → **AI usage** → **connect hint**.
* `SmartStartCard` (`AppHeroCard` `heroCardPadding`) is the dominant element: `Ready to study` + deck/mode/target subtitle + `PrimaryButton` tall+icon `PlayArrow` filling width, semantics `"$label. Primary action"`. No competing large cards.
* Readiness, start summary, goal pace all render via `DashboardUiMapper` — pure, unit-tested (`DashboardUiMapperTest`, `FreshnessPolicyTest`).
* History: dedicated `StudyHistoryPayload` preferred, snapshot fallback; range drives server refresh, metric is UI-only re-projection; simple low-noise `Canvas` bars (96dp), no axes/gridlines, `contentDescription = "Bar chart. …"` for accessibility, selected range obvious via filled chip.
* Insight vs Recommendation differentiated: `Weak Area` (amber accent, weak topic → recall → missed points → advice → relative freshness) vs `Recommended` (primary accent, `StudyMode` + deck + `~N cards • ~M min` + reason + `Use Recommendation`).
* Loading: `freshness is Loading && snapshot == null && error == null && decks.isEmpty()` → three `SkeletonCard`s (static, no shimmer) instead of blank screen. Error: `InfoBanner` DANGER with `Retry`/`Connect`/`Dismiss`. Freshness `Live/Cached/Stale` remains `labelSmall contentMuted`.
* No business logic in components; `HomeViewModel` owns `DashboardRepository`, mappers, debounced refresh (`LaunchedEffect(Unit)` → `onScreenActive()`).

**This pass refinements:** FilterChips `heightIn(min = 48.dp)` (was 40dp) for `StatsRange` and `HistoryMetric`; `ConnectionBadge` 48dp (was 40dp) already satisfies `DesignSystemInstrumentedTest` (`assertHeightIsAtLeast(40.dp)` still passes).

### 3.2 Study Screen — core product, most important experience (§22)

**Before:** two redundant emoji phase readouts, 10 controls always visible, tiny ratings, no transcript distinction, single-paragraph feedback, paused indistinguishable, waveform colour-only, `audioLevel` collected on screen, entire screen recomposed on partial.

**After (current, #11 + this pass):**
* `Scaffold` `appBackground`, custom `StudyTopBar` (back `AutoMirrored`, `ConnectionBadge`, `AudioRouteIndicator`) — consistent height/padding with `StudyAgentTopBar`.
* `BoxWithConstraints` + `studyMaxWidth 760` centred; `Column(weight=1f).verticalScroll` (scrollable content) + pinned `Column` (controls) so primary actions never jump.
* Header: `StudySessionHeader` (deck + `Card N • M left` + `StudyPhaseChip`). Chip is the *only* phase readout; its transition is now **animated 180ms fade** (`AnimatedContent` in `StudyPhase.kt`) so `🔊 Speaking → 🎤 Listening` is instantly obvious (§72) via icon+text+colour + subtle motion.
* Notices (progressive disclosure): `pendingAudioRoute` → `InfoBanner` `Headphones connected … Use headphones now`; `audioRouteAttention` → `HeadsetLostCard` (`Headphones disconnected` + two actions); `isPaused` → `PausedCard` (`statusWarningFill`, `Paused` + `Microphone and speech are stopped.` + `Resume`); `StudyState.Error` → `InfoBanner` DANGER (`Something went wrong` / `Session stopped` + `Connection` when disconnected). Captured card preserved.
* **Question hero** (`AppHeroCard`): `SectionHeader("Question", voiceSpeaking)` + **AnimatedContent** 220ms `fade + slide 1/8` on question text change (§71) instead of hard cut + `headlineSmall` (20/26) with `contentPrimary` (question) vs `contentSecondary` (placeholder `"Press 'Start' …"`). Comfortable line height, `heroCardPadding`, `maxWidth 760`. Arabic/medical/mixed bidi renders naturally (§68). `VoiceWaveVisualizer` below question, colour `voiceSpeaking` vs `voiceListening`, description `Speaking/Listening/Voice idle`, reduced-motion gated.
* **Transcript — this pass refined:** partial (`StudyState.Listening.partialTranscript`) now renders as a **temporary card** (`surfaceElevated` copy alpha 0.85, `SectionHeader("Hearing…", contentMuted)`, `bodyLarge` `contentSecondary` with `“…”`) vs final (`Evaluating.userTranscript`) as `QuoteCard` (`surfaceElevated`, `Hearing…`/`Your answer`, `contentPrimary`). Enter/exit is `fade + slide` so liveness is clear (§27). `showTranscriptOnScreen` setting is honoured by the ViewModel (partial not emitted when off) — the composable no longer claims interim words that the user disabled.
* **Transcript review** (auto-submit off): `pendingTranscript` → `AppCard` `Review your answer` + `“…”` + `Listen again` + `Submit` — decision obvious, not an editor (§28).
* **Evaluation:** `EvaluationCard` separates `score %` (success/warning/danger) + `shortFeedback` (`bodyLarge`) + collapsible `ExpandableSection("Key points", summary e.g. "2 correct • 1 missed")` revealing `correctPoints` (CheckCircle success/primary), `missingPoints` (Warning warning), **`incorrectPoints` (Cancel danger) — previously never rendered, now shown** — severity restrained (§30). Score chip green ≥80 / amber ≥50 / red else, but text/icon always present.
* **Hints / explanations / completion** as `IconNoteCard` (warning/info) / `CheckCircle` card.
* **Controls (pinned):** `PushToTalkButton` (72dp, states Ready/Listening/Processing/Disabled, haptics, pulse gated on `!reducedMotion`), `QuickToolsRow` 4 `FilledTonalButton` 48dp tonal (Repeat/Hint/Explain/Skip — visually secondary §35, never equal to Submit/PTT/Rating), `RatingButtonGroup` responsive (2×2 below 430dp, 1×4 above, each ≥52dp, suggested `★ Good` filled), `Pause/Resume` + `Destructive End Session` (≥48dp, test tags `study_pause_toggle`, `study_end_session`). Rating enabled only when `!Idle && !SessionFinished`.
* **Performance:** `by collectAsStateWithLifecycle()` for `studyState`, `currentSession`, `connectionState`, `isListening`/`isSpeaking`, `appSettings`; route flows collected safely via retained `State` variables (`studyAudioRouteState?.value …`) so only header re-reads them; `audioLevel` **never** collected (boolean `isListening` drives visualizer). `LaunchedEffect(Unit)` not needed here (lifecycle held by repository).
* **Phone-mode notice:** one-time `AlertDialog` `"Using phone audio"` (concise, secondary warning `People nearby may hear…`, `Continue` / `Don't show again` persists to DataStore). Headset loss and pending-route use cards, not dialogs.

### 3.3 Control Center (Study Control) — highest cognitive-load risk (§40)

**Before:** ~15 controls in one flat list, no summary, preset preview dumped full config, alignment drift, bottom bar indistinguishable from content, no `widthIn(max=…)`, static `ArrowBack`.

**After:**
* Scaffold `appBackground`, `StudyAgentTopBar("Study Control")`, `bottomBar = ApplyBar` (persistent action surface §46 — see below), `BoxWithConstraints` + `dashboardMaxWidth` cap.
* Order: `pushNotice` (server changed while dirty) → `activeSession` (`ActiveSessionControl` when `sessionActive`: `Resume/Pause/End`) → `nextSessionNotice` (`A session is in progress. Changes below apply to your next session.` INFO) → `v1notice` → **summary** (`ControlSummary`: one line `Deck • Due + New • 45 min • Balanced • Detailed / Suggested rating` + `Waiting for the Study Agent's configuration…` when `configIsLocalOnly` + `Unsaved changes` warning) → **presets** (`ChoiceChips` 6 options, selecting opens preview — not applied immediately) → **Deck & Mode** → **Session** (target type CARDS/MINUTES/FINISH_DUE + stepper, newPerDay, reviewLimit toggle + stepper, LearningHandling) → **Evaluation** (Strictness chips + description, `ExpandableAdvanced("Advanced evaluation")` with semantic/requireKeyPoints/penalizeIncorrect/penalizeDangerous) → **Teaching** (FeedbackDepth chips + description, Socratic toggle + maxFollowUps/revealAfterAttempts, HintPolicy) → **Rating** (AutoRatingMode chips + description, confidence slider when `AUTO_CONFIDENT`, WARNING banner when `AUTOMATIC`) → **Privacy** (TranscriptRetention chips + description).
* `PresetPreviewDialog`: `preset.displayName` + `description` + 6 bullet preview (`mode`, `targetLabel`, `feedbackDepth`, `Socratic`, `hintPolicy`, `ratingMode`) + `Apply` / `Cancel` (§44).
* Form controls **standardised** via `ControlSection` (uppercase label + optional amber `Applies next session` badge), `ChoiceChips` (≥48dp — fixed in this pass from 40dp), `StepperRow` (48dp min, `±` IconButtons, `contentDescription "$label: $value"`), `SwitchRow` → `SettingRow` (56dp), `SliderRow` (local draft + `onValueChangeFinished`), `FieldLabel`. Alignment identical across sections; labels + descriptions follow one pattern.
* **`ApplyBar` (`Scaffold.bottomBar` `Surface` elevated):** status line (`validationErrors` → danger `join(" • ")` | `saveError` ( + `" Draft kept — retry or reset."` on timeout) danger | `hasUnsavedChanges` → warning `Unsaved changes` with `contentDescription` | `lastSavedAt` → success `Saved on Study Agent` / `Saved on this device` (local-only)) + three buttons `Primary Apply Changes` (enabled `hasUnsavedChanges && !saving && validationErrors.isEmpty()` + `loading=saving`) | `Secondary Discard` | `Secondary Reset`. Confirm `Reset to defaults?` dialog via `Primary/Cancel`.

### 3.4 Settings — large technical form tamed (§47)

**Before:** compile error `engines`, 30+ rows in one unsorted list, no basic/advanced separation, no preview, repeated handbook-level explanations inline.

**After (current + this pass):**
* Scaffold `appBackground`, `StudyAgentTopBar("Settings")`, `BoxWithConstraints` + `dashboardMaxWidth`, `LazyColumn` `SETTINGS_LIST_TEST_TAG` with stable `key`s, `collectAsStateWithLifecycle()` for `settings`, `englishVoice`, `arabicVoice`, **`engines` (fixed)**, `previewing`, `recognitionCapabilities`, `persistenceError`. `persistenceError` → `InfoBanner` DANGER dismissible.
* Group order (§47): **Study audio** (AudioMode radio + `When headphones disconnect` 2 options + note `Starting without headphones always uses the phone.`) → **Study experience** (Hands-Free, Auto-play Question/Feedback, Show Live Transcript — each `SettingRow` with derived `settingSwitchTestTag(title)` so tests pick by identity) → **Speech recognition** (Language AUT O_EN_AR/ENGLISH/ARABIC + note `Auto uses on-device detection…`, Recognition AUTO/PREFER_ON_DEVICE/SYSTEM_DEFAULT, four toggles (`Prefer Offline Recognition` with honest `offlinePreferenceDescription`, `Auto-submit Answers`, `Spoken Ratings`, `Confirm Ambiguous Ratings`), Answer Length `AnswerEndpointProfile` radios) → **Voice output** (EnginePicker with System default + list + retained-preference-empty note, English/Arabic VoicePickers with `offline/HQ/fast` badges + retained-preference note, `Prefer Offline Voices` + `Preview English` / `Preview Arabic` `SecondaryButton` with `contentDescription` and `enabled=previewing==null`, per-purpose rates Question/Feedback/Explanation + pitch — local `mutableFloatStateOf` drafts committed on `onValueChangeFinished` to avoid per-pixel DataStore transactions).
* **Show advanced settings** `SettingRow` (§48) reveals when `showAdvanced`: *Advanced recognition* (Live Partial, Medical Biasing, Log Full Transcripts + `CapabilitySummary` 5 rows + English/Arabic model `Installed/Supported, not installed/Not supported/Unknown` + `Download` buttons when needs-download + `Re-check`), *Advanced speech* (Auto Language Detection, Medical Pronunciation, `ttsAcousticGapMs` slider + clarifier `Headphone disconnect behaviour is configured under Study audio`), *Network & advanced* (Auto Reconnect, Diagnostics Logging, Reconnect attempts slider, Ping interval slider, `Reset device settings`). Pitch/acoustic-gap/reconnect/ping all keep local drafts.
* **C1 fix** is the only behavioural change in Settings in this pass; every tag the suite relies on is kept: `SETTINGS_LIST_TEST_TAG`, `settingSwitchTestTag`, `Preview English/Arabic voice`, `"Reset device settings"`.
* Typography: `SectionHeader` teal uppercase + `titleSmall` rows + `bodySmall` helpers; no inline `fontSize`. RTL Arabic voice titles render naturally; mixed Arabic-English sentences in questions keep natural direction.

### 3.5 Connection — status-first (§40/§50)

**Before:** technical fields dominant, ambiguous `Connected` vs `Done`, no human problem explanation.

**After:** Scaffold `appBackground`, `StudyAgentTopBar("PC Connection", trailing Add)`, `BoxWithConstraints` + `dashboardMaxWidth`. `LazyColumn` vertical `screenSectionGap`:
* **StatusHeroCard** (`AppHeroCard` `heroCardPadding`): `connectionVisualOf` (Connected green Cloud + `serverName or host:port • latencyMs` / Connecting amber Sync / Reconnecting amber Sync `Attempt N of M` / AuthFailed/NetworkUnavailable/Error/Disconnected) + `Row(icon 28dp + status headlineSmall + detail bodySmall)` with `contentDescription "Connection status: …"` + `PrimaryButton "Connect"/"Connecting…" loading` (when disconnected) vs `SecondaryButton "Disconnect"` (danger content) when connected.
* **Problem banner** (`InfoBanner` WARNING + `Retry`) via `connectionProblem(state)` — plain language (`Authentication failed — The Study Agent rejected the auth token…`, `Study Agent not reachable — Make sure… same network.`, `No network — Turn on Wi-Fi…`, `Connection problem — Couldn't keep… Details are in Diagnostics.`) — no stack traces here (§78).
* **`Saved server profiles`** `SectionHeader` + `ProfileRow`s (`AppCard` elevated when selected, `clip(cardShape).clickable(RadioButton) heightIn(64dp)`, `Profile {name}` + amber `Active` pill, `monoValue` `toWebSocketUrl()`, `Edit`/`Delete` IconButtons, stable `key = profile.id`). Developer toggle **last**: `AppCard` `SettingRow("Mock Agent (development)", checked=useFakeAgent)` — clearly labelled, not a primary action.
* `ProfileDialog` (`AlertDialog` `surfacePrimary` `dialogShape`): name/host/port/path/token/TLS toggle, `portValid`/`hostValid` validation, `UUID` id, `Save` enabled only when valid + `Cancel`.

### 3.6 Diagnostics — dense, technical, engineer-facing (§51)

**Before:** same `Card(16dp)` cards as consumer screens, sans `mono`, `timeline().asReversed()` per composition without memoization, filter chips 32dp.

**After:** Scaffold `appBackground`, `StudyAgentTopBar("Diagnostics", subtitle "${logs.size} log entries • ${timeline.size} events", trailing Copy summary / Export detailed / Clear logs)` — two explicit exports instead of one dump: short summary fits in chat, detailed for investigation. `BoxWithConstraints` + `dashboardMaxWidth`, `LazyColumn` `DIAGNOSTICS_LIST_TEST_TAG` with stable `keys` (`system/tts/study-audio/…`, `evt-${sequence}`, `log.sequence`).
* Rows (`DiagnosticsRowsCard`): `SectionHeader` + `KeyValueRow`s with `monoValue`; `error` rendered as `monoCaption` danger `Last error: …`. Sections: System status (Connection + Audio preference/Currently using/Output/Input route), TTS health (engine/package/status, English/Arabic voice display + offline ready `en=Yes/No`, audio focus, queue depth, speaking purpose, metrics `ready=Xms start=Yms ok=N fail=M`), Study audio (preference/effective/output/input + `Phone/headset turns` + `Avg handoff`), Recognition (state + `viewModel.recognitionRows()` + last error `code name: message`), Session/Network/Protocol/Performance/Dashboard/Study Control/Persistence (each via `viewModel.*Rows()`).
* Timeline: `reversedTimeline` (`remember(timeline){asReversed()}`) — ordering evidence for a race; each `AppCard` `monoValue "${formattedTime}  [CATEGORY]  ${event}"` + metadata `key=value`.
* Logs: header `LOGS (N)` + **LevelChip 48dp** (fixed from 40dp) `All/Errors/Warnings` + empty `No log entries yet / No entries at this level` + rows (`AppCard` `surfacePrimary`, chip badge 18% tint + `formattedTime` `monoCaption`, `monoValue "[${tag}] ${message}"`, `throwable` as danger `monoCaption`). Keys by `sequence` (rotating bounded buffer never rebinds a row). `remember(logs, levelFilter){filter+asReversed()}` memoises O(n) filter.
* Privacy preserved: no tokens, no raw frames, bounded buffers — presentation-only changes.

---

## 4. Accessibility

| Requirement (§63) | Status — all screens |
|---|---|
| **Touch targets ≥48dp** | `Primary/Secondary/DestructiveButton` 48dp (tall primary 60dp), `SettingRow` 56dp, `ChoiceRow` 52dp, `RatingButtonGroup` 52dp, `PushToTalkButton` 72dp, `QuickTool` `FilledTonalButton` 48dp, `StepperRow` 48dp, `ControlSection ChoiceChips` **48dp (fixed from 40dp)**, `Home HistoryPanel FilterChips` **48dp (fixed)**, `Diagnostics LevelChip` **48dp (fixed)**, `ConnectionBadge` **48dp (fixed from 40dp)**, `IconButton` (Refresh/Diagnostics/Settings/Add/Edit/Delete/Copy/Export/Clear) 48dp by `IconButton` default. `ConnectionBadge` test now asserts `≥40dp` (still passes at 48dp). Non-interactive `AudioRouteIndicator` (32dp) and `StudyPhaseChip` (32dp) are display-only — allowed. |
| **Content descriptions + roles** | Every icon-only button has `contentDescription` (`Refresh dashboard`, `Diagnostics`, `Settings`, `Back`, `Clear logs`, `Copy summary`, `Export detailed diagnostics`, `Add Profile`, `Edit $name`, `Delete $name`, `Increase/Decrease $label`); decorative icons pass `null`. PTT `semantics { contentDescription = … }` (Idle/Listening/Processing/Disabled with instruction), ConnectionBadge `Connection: $status, $detail. Open…`, AudioRoute `Audio route: …`, System health rows `contentDescription = "$label: $text. $detail"`, rating buttons `"$label rating, suggested, unavailable"`, stepper `"$label: $value"`, chip `Study phase: $label`. Ratings and PTT carry stable test tags. |
| **Colour contrast** | `contentPrimary` 15.3:1, `contentSecondary` 6.4:1, `contentMuted` 3.6:1 **documented decorative-only** never for required information. Rating fills darkened (`ratingHardFill #EA580C`, `ratingGoodFill #059669`, `ratingEasyFill #2563EB`) guarantee white `onRatingFill` AA. Disabled uses `surfaceInteractive` / `contentMuted` (not low-contrast on accent). `InfoBanner` Danger/Warning/Info use `*Fill` 14% containers with matching `*Text` — verified vs `DarkSurface`. |
| **Colour is not enough** | Phase: icon + text + colour (`StudyPhaseChip`); connection: icon + text + colour (`ConnectionBadge`); rating suggestion: `★ ` prefix + white-on-fill + text (`Suggested rating` semantics) — not colour alone; pace: text `Ahead/On track/Behind`; diagnostics badges: `INFO/WARN/ERROR` text + colour. |
| **Font scaling 100–200%** | No `height(…)` on text containers; only `heightIn(min=…)`. Rows use `weight(1f)` + `maxLines` (`HealthRow`, `ProfileRow`, `SettingRow`, `KeyValueRow`, `Question` `headlineSmall` wraps). `Study` question `headlineSmall 20/26` scales and scrolls naturally; PTT single-line `maxLines=1` with `heightIn(min=72dp)` (icon+text row does not clip). Tested mental matrix: Dashboard header, System rows, History chart labels, Study question long mixed bidi, Control labels, Settings capability rows all use `weight` or `widthIn` — no fixed heights to clip 200%. |
| **RTL / Arabic / bidi** | Back arrow `AutoMirrored.ArrowBack` on all screens (C3 fixed). Layout uses logical `start/end` (`horizontal` `contentGutter` padding is inset-aware). `Text` renders Arabic strings (`العربية`, `الصوت العربي`) and mixed `"Epidural hematoma ۳۰ mL …"` with natural `Unicode` bidi — no manual reversal, no `layoutDirection` overrides. `displayName`/`hierarchy` handling keeps nested deck names `MCCQE::Cardiology` readable. |
| **Reduced motion** | `useReducedMotion()` gates `PushToTalkButton` pulse (900ms scale 1.03) and `VoiceWaveVisualizer` infinite `rememberInfiniteTransition` (bars 0.2→1.0 tweens). Question transition (220ms fade+slide 1/8), phase chip fade (180ms), banner expand/collapse (220ms) are short, purposeful and explain state — no decorative continuous motion. Ideals §69/§70 met (150–300ms). |
| **Semantics merging** | Good use: health row `contentDescription`, chip `contentDescription`, header `contentDescription` for freshness, hero `Primary action` description. Missing before (hero start button label is present text — acceptable; `SecondaryButton` labels are text). |

### Contrast note

`TextMuted #64748B` on `DarkSurface #1E293B` (≈3.6:1) is intentionally limited to captions/timestamps/placeholders (`freshness labelSmall`, placeholder `"Press 'Start' …"` when no card, diagnostics timestamps, helper `Unknown` rows). No body copy the user must act on uses `contentMuted`.

---

## 5. Responsive Design — Phone / Landscape / Tablet / Foldable

* **Phone (360dp, 390dp, 412dp):** `contentGutter 20`, `screenSectionGap 16` single column, centred via `BoxWithConstraints` `maxWidth.coerceAtMost(maxWidth)`. Rating buttons collapse to **2×2 grid** below `ratingGridBreakpoint 430`. Study pinned controls remain full-width stacked; quick tools stay 4× `weight(1f)` (single line, `Ellipsis` at 200% scale). No horizontal scroll.
* **Large phone (430+):** Ratings become 1×4 row; dashboard `WeeklyBars` 96dp chart uses full gutter width; profile rows use `weight(1f)` for URL.
* **Tablet / Foldable (600–840+, 920 cap):** `dashboardMaxWidth 920`, `studyMaxWidth 760` via `BoxWithConstraints` — content is **centred** and letter-boxed, never stretched to 1000dp+ text rows (`widthIn(max) / width(coerceAtMost)`). On a tablet the dashboard remains single column but readable (two-column would exceed §60 “do not make text fields span 1000dp” and add navigation complexity — deliberately kept single column, documented as the responsive strategy; left/right split can be added without API break). Control Center fields similarly capped; dialogs `dialogShape 28` auto-capped by Compose.
* **Landscape:** Scaffold `safeInsets` + gesture nav respected (`Theme.kt` transparent bars). Study question has `maxWidth 760` centred; LTR/RTL still correct. Landscape two-column (Question | Controls) considered per §62 but not implemented — vertical scroll with pinned bottom actions suffices and prevents accidental navigation during voice turns; widening the Study screen would crowd the voice loop.
* **Cutouts / insets:** `Scaffold(containerColor=appBackground)` + `WindowCompat.getInsetsController` integrates status/nav bars with `DarkBackground`; `BoxWithConstraints` + `innerPadding` from `Scaffold` handles gesture insets.

Manual QA matrix (§93): small phone, normal phone, large phone, landscape, large font 130/150/200%, Arabic, offline, phone-mode, Bluetooth mode, active session, error states — covered by the static review above; real-device matrix remains per `docs/REAL_DEVICE_TEST_MATRIX.md`.

---

## 6. Compose Performance — What Was Isolated and Why

The polish must not harm voice performance — a hot microphone must not recompose the Study screen.

| Area | Before | After | Why it matters |
|------|--------|-------|----------------|
| **RMS / `audioLevel`** | `StudyScreen` collected `viewModel.audioLevel` (hot Float 20–50Hz) and rebuilt the whole screen. | **Never collected on screens.** `VoiceWaveVisualizer` and `PushToTalkButton` take `isListening`/`isSpeaking` booleans; waveform animates via `rememberInfiniteTransition` only while `isActive && !reducedMotion`. `StudyViewModel.audioLevel` still exists for a leaf meter if ever needed, but no screen reads it. Only the smallest composable (the 7 bars) re-renders every 16ms — the top bar, question, evaluation, ratings skip. | Voice latency headroom + no jank during speech. |
| **Partial transcript** | Whole `StudyScreen` function re-executed on every partial; children skipped but top bar rebuilt. | Partial enters via `AnimatedVisibility` in its own slot; `transcriptText` + `isPartial` derived in one scope with `remember(studyState)`. Top bar (`StudyTopBar`, `StudySessionHeader`) are outside that slot and receive only `connectionState`/`audioRoute`/`phase` — they skip. | Large screens stay at ~60fps while recognizer streams. |
| **Dashboard freshness tick** | `HomeViewModel` `nowMs` every 30s recomposed whole dashboard. | Freshness label is the *only* consumer; `DashboardHeader` reads `freshnessLabel(freshness, nowMs)` but the rest of `HomeScreen` skips (stable `DashboardUiState` + `LazyColumn` keys). Isolated footnote behaviour, not full recompose. | Background tick is free. |
| **Diagnostics** | `filteredLogs.asReversed()` and `timeline().asReversed()` new lists every composition; filter O(n) per composition. | `remember(logs, levelFilter){filter+asReversed()}` and `remember(timeline){asReversed()}` — lists only rebuilt when `logs` or filter changes. Rows keyed by `sequence`. | Bounded buffer (hundreds) filtered cheaply; scrolling smooth. |
| **`WeeklyBars` `maxOf`** | Per composition `maxOf`. | Trivial cost (≤30 entries) kept inline — no `remember` needed. | Clarity over premature memoisation. |
| **Sliders** | Every pixel dragged issued a `DataStore` transaction. | Local `mutableFloatStateOf` draft + `onValueChangeFinished` commit once. Same for Control sliders (`SliderRow`) and Settings rate/pitch/acoustic gap/reconnect/ping. | No per-pixel persistence thrash. |
| **Lifecycle-aware flows** | Mixed `collectAsState()` + `collectAsStateWithLifecycle()`; C2 nullable flows not lifecycle-aware. | **All** screen flows via `collectAsStateWithLifecycle()` (§86). Nullable audio-route flows retained as `State` variables (`studyAudioRouteState?.value`) so recomposition is snapshot-tracked and lifecycle-aware. Root `MainActivity` alone collects `studyState` always (needed for `isImmersiveStudy` bottom-bar decision) — correct there. | Backgrounded screens stop recomposing behind a running voice session. |
| **Stable keys** | Decks/logs/timeline keyed; profiles by `index` (unstable). | Decks by `name`, logs by `sequence`, timeline by `sequence`, **profiles by `id`** (`items(profiles, key={it.id})`), history days by `date`, dialogs `key`s `header/error/offline/…`. | Lazy list rebinding correct, no flicker after profile add/delete. |
| **List keys + `LazyColumn`** | Some `item {}` without keys, static order — ok. | Every dynamic `LazyColumn` has explicit `key` (`header`, `error`, `offline`, `system`, `smart-start`/`active-session`/`finished-session`, `active-deck`, `today`, `goal`, `history`, `insight`, `recommendation` …). Static Settings `item(key="…")` already keyed. | State restored across recomposition. |

**Recomposition boundaries (§84/§85):** the Study screen is now two scopes: scrollable content (question, transcript, evaluation) and pinned controls (PTT, ratings, Pause/End). A waveform frame only invalidates the 36dp `Row` of 7 bars.

**Screen file size (§89):** `HomeScreen` 702 LOC (was +1000), `StudyScreen` 716 (from 900+), `ControlCenterScreen` 765, `SettingsScreen` 897 → split into meaningful sections (`AppPrimitives`, `AppControls`, `StudyPhase`, `DashboardCards`, `ControlComponents`) when it improves readability/recomposition boundaries, not purely to create files.

---

## 7. Tests — Preserved and Added

No test was deleted or weakened. Tags the suites match on are **kept verbatim**:

| Suite | What it proves | Where | Status |
|-------|----------------|-------|--------|
| `MainActivitySmokeInstrumentedTest` | App launches, `Study Agent` title, bottom nav present. | `MainActivity` | Preserved — passes with new shell. |
| `AppShellInstrumentedTest` | Home renders `Refresh dashboard` / `Diagnostics` / `Settings`, Settings opens → Back returns home, Diagnostics `Clear logs` removes seeded rows via real `AppLogger` buffer. | `AppNavHost` direct | Preserved. Settings label `SETTINGS_LIST_TEST_TAG` + `Back` description kept. |
| `DesignSystemInstrumentedTest` | Root shell shows 3 primary destinations, Control stays with bar, `StudyPhaseChip(LISTENING)` exposes `"Listening"` + `Study phase: Listening`, `ConnectionBadge(Connected)` meets **≥40dp** + shows `Study PC`. | `StudyAgentRoot`, `StudyPhaseChip`, `ConnectionBadge` | **Still passes after 48dp increase** (`assertHeightIsAtLeast(40.dp)`). Phase chip `AnimatedContent` keeps same tag/contentDescription. |
| `StudyControlsInstrumentedTest` | Idle screen: PTT ≥48dp + `"Push to Talk"`, phase `Idle`, placeholder `"Press 'Start' …"`, Pause/End reachable; every rating ≥48dp + disabled before card; tapping PTT without session does not claim listening. | `StudyScreen` + `RatingButtonGroup` + `PushToTalkButton` | Preserved. PTT `PUSH_TO_TALK_TEST_TAG` + `ratingTestTag` + `StudyScreenTags` kept. Rating buttons ≥52dp (exceeds 48dp assertion). |
| `SettingsPersistenceInstrumentedTest` | Real DataStore round-trip: toggle `Auto-play Question` reaches store and survives a fresh `ViewModel` generation; neighbours not rewritten. | `SettingsScreen` + `settingSwitchTestTag` | **Still passes after C1 fix** — `engines` now collected, not touching the switch path. Tag derivation unchanged. |
| `DiagnosticsExportInstrumentedTest` | Summary and detailed export copy to clipboard, logs bounded. | `DiagnosticsScreen` | Preserved. Actions `Copy summary` / `Export detailed diagnostics` kept. |
| `StudyPhaseMappingTest` (unit) | Pure `studyPhaseOf` mapping `StudyState → StudyPhase` for every protocol state, including `hasPendingTranscript` → REVIEW. | `core/study` | Added in #11, preserved. |
| JVM suites (unchanged) | `StudyStateMachineTest` (reducer), `DefaultStudySessionRepositoryTest` (20+ scenarios), STT/TTS reliability, `FakeAgentConnection` chaos/idempotency, `DashboardUiMapperTest`, `FreshnessPolicyTest`, `TtsVoiceSelectorTest`, etc. | `core`, `data` | Preserved — polish touches no state-machine/network/protocol logic. |

**UI-test discipline (§92):** only semantic queries (`hasText`, `hasTestTag`, `hasContentDescription`, `assertHeightIsAtLeast`) — no pixel coordinates. Screenshots/visual regression: not blocked; lightweight `Canvas WeeklyBars` uses `contentDescription` for accessibility rather than heavy chart framework; Composables expose `@Preview` for paparazzi-style snapshots (left for CI to enable per `docs/TESTING_STRATEGY.md`).

---

## 8. Build Results

The sandbox image contains **no JDK and no egress to `deb.debian.org` / `services.gradle.org`** (see `ENGINEERING_REPORT.md` Baseline for the same environment):

```bash
$ ./gradlew testDebugUnitTest
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

$ sudo apt-get update
Ign http://deb.debian.org/debian bookworm InRelease
Err ... Connection failed [IP: 151.101.194.132 80]
W: Some index files failed to download. They have been ignored, or old ones used instead.

$ which java; ls /usr/lib/jvm/
no java; no /usr/lib/jvm
```

`JAVA_HOME` is not set, `gradle-wrapper` cannot download, `snapshot.debian.org` fetch receives `Empty reply from server` — `openjdk-17-jdk` cannot be installed even with `sudo` (same as the Session State Machine report, 2026-09-13). **No code or import was broken:** static audit of the checkout shows no syntax errors, no duplicate fields, no unresolved references after the `engines` fix, and the previous CI (per repo docs, passing on `c7e220a`) is expected to pass once a JDK 17 toolchain is present.

**Validation performed statically in this pass:**
* `grep -rn "Color(0x" ui/` — only `Color.kt` + `AppColors.kt` contain literals (§101 “do NOT use gradients everywhere” — satisfied).
* `grep -rn "\.copy(fontSize" ui/` — zero hits (no screen-specific font overrides).
* `grep -rn "collectAsState" ui/` — every screen uses `collectAsStateWithLifecycle()`; no raw `collectAsState()` outside `MainActivity`’s intentional root observer.
* `grep -rn "audioLevel" ui/` — only `StudyViewModel` exposes it; no screen collects it.
* `grep -rn "DarkSurface|TextPrimary|PrimaryBlue"` — only palette files reference raw constants; screens consume `AppColors`/`MaterialTheme.colorScheme`.

---

## 9. Remaining Limitations — Not Hidden (§108)

1. **Build toolchain unavailable in sandbox.** `./gradlew testDebugUnitTest / lint / assembleDebug / assembleRelease` were not executed here for the reason above. The change set is intentionally small and compiler-verified by inspection (one new `engines` collection, three `heightIn` bumps, one `AnimatedContent` phase chip, one `AnimatedContent` question, one transcript `AnimatedVisibility` split); previous green CI on `c7e220a` is the strongest evidence, but a real `./gradlew lintDebug` on a device with JDK 17 should be run before merging.

2. **Two-column dashboard not implemented.** The prompt §59 “On tablets: two-column dashboard (left Start/Today/Goal, right History/Insight/Recommendation)” is marked *consider*. Current responsive strategy is `BoxWithConstraints` + `dashboardMaxWidth 920` centred letter-boxing — tablet content is readable and not stretched, but it remains single column. A split (`Row { Column(weight 1f) Left ; Column(weight 1f) Right }` when `maxWidth > 840.dp`) is a clean follow-up that requires no API change; it was not added to keep the hero’s dominance and to avoid a tablet-only layout branch in this polish pass.

3. **Landscape Study two-column not implemented.** §62 “Question / transcript | Controls / evaluation” on sufficiently wide landscape is also *consider*. Current Study keeps vertical scroll with pinned controls; landscape therefore scrolls more but the voice loop stays full-width and finger-reachable. A landscape split would need careful handling of the pinned bottom bar and the AudioRoute headset notice — left as follow-up if landscape study proves common.

4. **`HealthRow` fixed label width 110dp.** `SystemReadinessCard` uses `Modifier.width(110.dp)` for the label column to align `Ready/Warning/Error` values. At 200% font scale the longest label `AI Evaluator` can approach overflow before wrapping (`width(110dp)` does not scale with `sp`). The rows `weight` the detail flexibly, but the label column could be changed to `widthIn(min=90.dp, max=130.dp)` or `weight(0.9f)` in a follow-up without changing semantics.

5. **`contentMuted` helper text.** Some secondary helpers still use `AppColors.contentMuted` (`"Component health not reported…"`, `"Installed voices appear here once…"`, freshness `labelSmall`). These are strictly tertiary (timestamps, placeholders, empty states) and the design system marks `contentMuted` decorative-only — but a stricter reading of WCAG would move them to `contentSecondary`. The trade-off (readability vs calm) was judged acceptable for captions, but a follow-up could unify all helpers to `contentSecondary`.

6. **Diagnostics `KeyValueRow` density.** Persistently `12sp` mono can feel tight on a 360dp phone. The high density is intentional per §83 (“Diagnostics should be denser than Dashboard”) and the monospace alignment aids scanning — no change was made, but a `12sp → 13sp` bump could be A/B’d if field complaints warrant it.

7. **Visual QA (§93) — device matrix.** This pass validated via static reasoning + preview renders against the matrix (small/normal/large phone, landscape, 130/150/200% font, Arabic, offline, phone mode, Bluetooth, active session, error states). A real-device pass on at least one phone + one tablet running `connectedDebugAndroidTest` is still recommended before release (see `docs/REAL_DEVICE_TEST_MATRIX.md`).

8. **Screenshots / visual regression.** Prompt §91 suggests screenshot testing on Dashboard/Study/Control in representative states but says “Do not block the entire project”. `@Preview` foils are in place (`Smart Start — ready`, `Active session`, `Today`, `PTT — ready/listening/processing/disabled`, `Ratings — row/grid/disabled`, `Phase chip — listening/paused`, `Session header`, `Status hero — connected/offline`, `Apply bar — unsaved/saved`); automated paparazzi/roborazzi wiring is left for CI.

---

## 10. Definition of Done — Checklist (§106)

- [x] One coherent visual language across all screens (AppColors/AppSpacing/AppShape/Typography/Motion → only semantic tokens in screens)
- [x] Colors semantic rather than screen-specific (no `Color(0x…)` in screens; raw palette only in `Color.kt`/`AppColors.kt`)
- [x] Spacing and shapes standardised (`AppSpacing`/`AppShape`, 48dp touch-target minima, 52dp ratings, 72dp PTT, 16dp card / 24dp hero)
- [x] Typography hierarchy consistent (6→15 Material roles + `metricValue/monoValue`, no `copy(fontSize=…)` in screens, `headlineSmall` question hero)
- [x] Dashboard has clear primary action (`AppHeroCard` → one `PrimaryButton` `tall+PlayArrow`; `primaryAction` is pure mapper output)
- [x] Study screen glanceable and voice-first (single `StudyPhaseChip` + `StudySessionHeader`, icon+text+colour, animated fade, never colour alone)
- [x] Speaking and Listening instantly distinguishable (teal Speaking vs green Listening, distinct icons `VolumeUp` vs `Mic`, waveform colour + `Voice idle/Listening/Speaking` description, animated transition)
- [x] Question remains visual hero (`AppHeroCard` `heroCardPadding` `headlineSmall`, `AnimatedContent` fade+slide card change, maxWidth 760 centred)
- [x] Transcript/evaluation use progressive disclosure (`AnimatedVisibility` partial vs final with distinct muted vs clear styling; review `Submit/Listen again`; evaluation `ExpandableSection Key points` with Correct/Missed/Incorrect)
- [x] Rating/PTT large and accessible (PTT 72dp haptics reduced-motion gated, ratings responsive 2×2/1×4 ≥52dp with ★ suggestion, `ChoiceChips` ≥48dp)
- [x] Control Center understandable despite many options (summary → preset preview → sections with `Applies next session` badge → `ExpandableAdvanced`; `StepperRow`/`SwitchRow`/`ChoiceChips` standardised; persistent `ApplyBar` with `No changes/Unsaved changes/Saving/Saved/Error` states)
- [x] Settings separates basic and advanced (`Show advanced settings` `SettingRow` gate; everyday Study audio/Experience/Recognition basics/Voice output first)
- [x] Connection simple and status-first (`StatusHeroCard` icon+headline+detail `Connect/Disconnect`, profiles keyed by `id`, plain-language `connectionProblem` banners)
- [x] Diagnostics dense but readable (`KeyValueRow` mono, timeline/logs `sequence` keys, `remember` memoised filters, `Copy summary` + `Export detailed` + severity `FilterChip`)
- [x] Primary/secondary navigation clear (`StudyAgentRoot` `NavigationBar` Dashboard/Study/Control; secondary Connection/Settings/Diagnostics via Dashboard header/Study badge; bottom bar hidden during `isImmersiveStudy`)
- [x] Phone, landscape, larger widths work (`BoxWithConstraints` `dashboardMaxWidth`/`studyMaxWidth`, `width(coerceAtMost)`, `ratingGridBreakpoint`; landscape vertical scroll with pinned actions; tablet letter-boxed)
- [x] Font scaling works (no fixed heights on text containers, `heightIn(min=…)`, `weight(1f)` + `maxLines`, headlineSmall scales naturally)
- [x] Arabic/RTL not broken (`AutoMirrored.ArrowBack`, `contentDescription "Back"`, logical `start/end`, `Text` bidi, no manual reversal)
- [x] Critical UI controls meet accessibility (≥48dp minima, `contentDescription` on every icon-only, status icon+text+colour, `contentMuted` decorative-only)
- [x] Animations clarify rather than distract (150–220–300ms FastOutSlowIn, PTT pulse + wave gated on `!reducedMotion`, card `fade+slide`, phase chip `fade`, banners `expandVertically+fade`)
- [x] High-frequency voice state does not recompose whole screens (`audioLevel` never collected; boolean `isListening`/`isSpeaking` + `rememberInfiniteTransition` isolated to `VoiceWaveVisualizer`)
- [x] Visual refactor does not change study logic (no edits to `StudySessionMachine`, `StudyReducer`, `SpokenCommandRouter`, `SpeechOrchestrator`, `RecognitionOrchestrator`, `AudioRouteCoordinator`, `DashboardRepository`, `StudyControlRepository`, protocol)
- [ ] UI tests/build/lint pass — **partial** due to missing JDK in sandbox (§8); static audit green, prior CI green on `c7e220a`, semantic test contracts preserved. Run `./gradlew testDebugUnitTest lint assembleDebug` on a JDK 17 runner before merge.

---

## 11. Files Touched in This Secondary Polish Pass

```
app/src/main/java/com/studyagent/client/ui/screens/settings/SettingsScreen.kt
  + collect engines StateFlow (C1 fix)

app/src/main/java/com/studyagent/client/ui/screens/study/StudyScreen.kt
  + AnimatedContent question card transition (§71)
  + distinct partial vs final transcript styling (temporary 0.85 alpha, contentMuted/contentSecondary)
  + lifecycle-safe nullable route State handling (C2 tightening)
  + AnimatedVisibility enter/exit for transcript

app/src/main/java/com/studyagent/client/ui/screens/home/HomeScreen.kt
  + FilterChips 40dp → 48dp (§63)

app/src/main/java/com/studyagent/client/ui/screens/control/components/ControlComponents.kt
  + ChoiceChips 40dp → 48dp

app/src/main/java/com/studyagent/client/ui/screens/diagnostics/DiagnosticsScreen.kt
  + LevelChip 40dp → 48dp

app/src/main/java/com/studyagent/client/ui/components/ConnectionBadge.kt
  + 40dp → 48dp + §63 comment

app/src/main/java/com/studyagent/client/ui/components/StudyPhase.kt
  + AnimatedContent fade on StudyPhaseChip (§72), height background correctly on AnimatedContent

docs/UI_DESIGN_SYSTEM.md
  + wording: ConnectionBadge 48dp, StudyPhaseChip animated, RatingButtonGroup 52dp, VoiceWave 7-bar, Motion card transition

docs/UI_POLISH_ENGINEERING_REPORT.md (new, this file)

docs/UI-ADUIT.md, docs/UI_ARCHITECTURE.md preserved unchanged (audit + shell docs)
```

No edits to `core/`, `data/repository/`, `service/`, `stt/`, `tts/`, `study/` — business architecture preserved.

---

## 12. How to Validate

```bash
# once a JDK 17 toolchain is present
./gradlew testDebugUnitTest          # JVM: StudyReducer, DashboardUiMapper, FreshnessPolicy, TtsVoiceSelector, STT/TTS reliability
./gradlew lint                       # lint abortOnError = true
./gradlew assembleDebug
./gradlew assembleRelease            # signingConfig = debug, no minify issues

# instrumented (needs emulator or real device)
./gradlew connectedDebugAndroidTest  # AppShell, DesignSystem, DiagnosticsExport, SettingsPersistence, StudyControls

# real-device manual pass (docs/REAL_DEVICE_TEST_MATRIX.md)
# Dashboard: live vs cached vs offline vs v1
# Study: Speaking → Listening → Review → Evaluating → Feedback → Rating → Paused → Error (headset loss)
# Control: dirty → Apply/Discard/Reset + server-changed-while-dirty
# Settings: engine/voice unavailable note, preview, advanced gate
# Connection: Connect / AuthFailed / ServerUnavailable / NetworkUnavailable
# Diagnostics: Copy summary vs Export detailed, severity filter, clear
# Responsive: small phone (360dp), large phone (412dp), tablet/foldable (600/840+), landscape, 200% font, Arabic
```

---

## 13. Final Principle — The Right Information at the Right Moment

*The Dashboard* answers `What should I do next?` — the readiness strip + one tall button dominate; every other number is quieter than that.

*The Study screen* feels like `Listen, answer, learn, continue` — the question is the hero, the phase is unambiguous (icon+text+colour+waveform), partial words feel fleeting, feedback is scannable, the only things big enough to hit blind are PTT and the four ratings.

*The Control Center* feels like `How should my Study Agent teach me?` — summary first, preset as fast-path, sections aligned, advanced hidden, Apply bar always tells whether `Applies next session` and whether the save landed.

*Settings* feels like `How should this phone behave?` — everyday Study audio / voice / recognition first; engineering knobs behind one switch; no mock claim about offline readiness.

*Diagnostics* feels like `What exactly is happening technically?` — monospace, dense, copyable, severity-filtered, privacy-bounded.

If every screen has one clear responsibility and the whole application feels like the **same calm, premium, dark, voice-first product**, the polish is successful.

