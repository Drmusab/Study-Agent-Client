# Study Agent — UI Design System

Study Agent is a **voice-first, dark, calm study companion**. The UI exists to tell the
learner what is happening (Speaking / Listening / Evaluating / Rating), to surface the
question, and to keep the few actions that matter large and reachable. Everything else
is quieter than that.

This document is the single reference for tokens, components and rules. Screens must
consume tokens through `ui/theme/App*` objects and components from `ui/components/`;
they must not import raw palette constants from `ui/theme/Color.kt`.

```
screen  →  semantic token (AppColors / AppSpacing / AppShape / AppTextStyle / AppMotion)
        →  raw palette (Color.kt)   ← only theme files may reference this layer
```

---

## 1. Principles

| Principle | What it means in code |
|---|---|
| **Calm by default** | Solid surfaces, no gradients / glass / glow. One accent per card at most. |
| **Voice state is never ambiguous** | Phase is always icon + text + colour (`StudyPhaseChip`). Never colour alone. |
| **The question is the hero** | `headlineSmall` in an `AppHeroCard`; nothing else on Study uses a larger size. |
| **Big where it matters** | PTT ≥ 72dp tall, rating buttons ≥ 56dp, all tap targets ≥ 48dp. |
| **Progressive disclosure** | Transcript, evaluation detail, advanced settings appear only when they have content or are asked for. |
| **Plain language outside Diagnostics** | Raw errors, codes and monospace live only in Diagnostics. |
| **Font scaling and RTL are first-class** | No fixed heights on text containers; `heightIn(min=…)` only; `AutoMirrored` icons; `weight(1f)` rows with `maxLines`. |

---

## 2. Colour tokens — `AppColors`

The palette (navy background, slate surfaces, blue primary, teal accent, green/amber/red
status, per-rating colours) is **unchanged**. It is exposed only through semantic names.

| Token | Use |
|---|---|
| `appBackground` | Window / Scaffold container |
| `surfacePrimary` | Standard cards, top bars, navigation bar |
| `surfaceElevated` | Secondary cards inside cards, dialogs, chips |
| `surfaceInteractive` | Selected chip / nav indicator / tonal buttons |
| `divider` | Hairlines |
| `contentPrimary` / `contentSecondary` / `contentMuted` | Text hierarchy. `contentMuted` is for decorative/tertiary text only — never for information the user must read (contrast ≈ 3:1). |
| `onAction` | Text/icon on a filled primary button |
| `actionPrimary` / `actionPrimaryStrong` | Primary buttons, selected states, links |
| `actionAccent` | Section labels, accents (teal) |
| `statusSuccess/Warning/Danger/Info` + `*Fill` | Status text/icons; `*Fill` is the 14 % tinted container behind them |
| `statusDangerStrong` | Filled destructive button |
| `statusNeutral` | Idle / disconnected / unknown |
| `voiceIdle` / `voiceListening` / `voiceSpeaking` / `voiceProcessing` | Voice phase colours (grey / green / teal / amber) |
| `rating{Again,Hard,Good,Easy}Text` / `rating*Fill` / `onRatingFill` | Rating buttons. Fills are the darker shades that pass AA with white text. |

Rules
* Status is never colour-only: pair with an icon and/or text.
* Do not create new `Color(0x…)` literals in screens. Add a token.

## 3. Spacing — `AppSpacing`

`XXS 4 · XS 8 · SM 12 · MD 16 · LG 20 · XL 24 · XXL 32`

Semantic: `contentGutter 20`, `screenSectionGap 16`, `cardPadding 16`,
`heroCardPadding 20`, `fieldGap 12`.

Layout limits: `dashboardMaxWidth 920`, `studyMaxWidth 760`,
`ratingGridBreakpoint 430` (below → 2×2 rating grid, above → 1×4 row).

## 4. Shape — `AppShape`

`chipShape` (pill) · `buttonShape 14` · `fieldShape 12` · `cardShape 16` ·
`heroCardShape 24` · `dialogShape 28` · `circleShape`.

## 5. Typography — `Type.kt` / `AppTextStyle`

Material 3 scale with tightened line heights. Additional roles:

| Style | Use |
|---|---|
| `headlineSmall` | Study question |
| `titleLarge` | Screen titles (`StudyAgentTopBar`) |
| `titleMedium` | Card titles, hero numbers' labels |
| `labelLarge` | Buttons, chips, phase text |
| `AppTextStyle.metricValue` / `metricLabel` | `MetricTile` numbers and captions |
| `AppTextStyle.monoValue` / `monoCaption` | Diagnostics only |

Never set `fontSize` inline in a screen; pick a style.

## 6. Motion — `AppMotion`

`quick 150ms · standard 220ms · slow 300ms`, FastOutSlowIn. `useReducedMotion()` returns
the system preference (API 30+) and all continuous animation (PTT pulse, wave bars)
is gated on it. Card changes use fade + 12dp vertical shift (§71). No animation is decorative: everything expresses a state change.

---

## 7. Components — `ui/components/`

| Component | File | Purpose / rules |
|---|---|---|
| `StudyAgentTopBar(title, onBack, subtitle?, trailing?)` | StudyAgentTopBar.kt | The only top bar. `AutoMirrored` back arrow with `contentDescription = "Back"`. |
| `AppCard`, `AppHeroCard` | AppPrimitives.kt | Solid surface containers. Hero = larger radius + padding, used once per screen. |
| `SectionHeader(title, color?, action?)` | AppPrimitives.kt | Small-caps label; optional trailing action slot. |
| `MetricTile(label, value, …)` | AppPrimitives.kt | Compact readiness numbers. |
| `StatusBadge(label, status, color, icon?)` | AppPrimitives.kt | Icon + text + colour status pill. |
| `InfoBanner(message, tone, title?, actionLabel?, onAction?, dismissible?)` | AppPrimitives.kt | Inline notices. `BannerTone.NEUTRAL/INFO/SUCCESS/WARNING/DANGER`. Plain language only. |
| `EmptyState`, `SkeletonBox`, `SkeletonCard` | AppPrimitives.kt | Empty and loading placeholders (no spinners in content areas). |
| `PrimaryButton`, `SecondaryButton`, `DestructiveButton`, `InlineTextButton` | AppControls.kt | ≥ 48dp, `AppShape.buttonShape`, optional icon, `loading` on primary. |
| `SettingRow(title, checked, onCheckedChange, description?, switchModifier)` | AppControls.kt | Switch row; whole row toggles; the `switchModifier` carries test tags. |
| `ChoiceRow(title, selected, onSelect, description?)` | AppControls.kt | Radio row, ≥ 52dp. |
| `ExpandableSection(title, expanded, onToggle, summary?)` | AppControls.kt | Progressive disclosure with rotated chevron. |
| `KeyValueRow`, `AppDivider` | AppControls.kt | Dense label/value line; hairline. |
| `StudyPhaseChip(phase)`, `StudySessionHeader(...)`, `studyPhaseOf(state)` | StudyPhase.kt | The one mapping from `StudyState` to what the user should do. Animated fade on phase change (§72), icon + text + colour, never colour alone. Tag `study_phase_chip`. |
| `PushToTalkButton(isListening, …, isProcessing)` | PushToTalkButton.kt | 72dp, press-and-hold or tap-toggle, states Idle / Listening / Processing / Disabled, haptics, reduced-motion aware. Tag `PUSH_TO_TALK_TEST_TAG`. |
| `RatingButtonGroup(onRate, suggestedRating, enabled)` | RatingButtonGroup.kt | 2×2 below `ratingGridBreakpoint`, 1×4 above; ≥ 52dp; suggested rating filled with ★ + white text, others tinted; tags `ratingTestTag(rating)`. |
| `ConnectionBadge(state, onClick)` + `connectionVisualOf` | ConnectionBadge.kt | Tappable status chip, ≥ 48dp, icon + text + colour, `AutoMirrored` semantics. |
| `AudioRouteIndicator(route?)` | AudioRouteIndicator.kt | Neutral "Headphones / Phone audio" chip; phone is a normal route, not a warning. |
| `VoiceWaveVisualizer(isActive, color)` | VoiceWaveVisualizer.kt | Activity indicator driven by a boolean — the RMS level flow is **not** collected by screens. 7-bar wave, reduced-motion aware. |

Control-Center-only helpers (`control/components/ControlComponents.kt`): `ControlSection`,
`ChoiceChips`, `StepperRow`, `SwitchRow`, `SliderRow`, `ExpandableAdvanced` — thin
wrappers over the design system for the dense configuration form.

Dashboard-only helpers (`home/components/DashboardCards.kt`): `SectionCard`, hero and
readiness tiles.

---

## 8. Screen patterns

* **Every screen**: `Scaffold(containerColor = AppColors.appBackground)`, `StudyAgentTopBar`
  (except Study, whose bar is back + connection + audio route), content width capped
  with `BoxWithConstraints` + `widthIn/width(min(maxWidth, *MaxWidth))`, `LazyColumn`
  items with stable `key`s, `collectAsStateWithLifecycle()` for all flows.
* **Dashboard**: hero Start/Resume card → readiness strip (`MetricTile`s) → active deck →
  today/history → freshness footnote. Secondary destinations live in the top-bar actions.
* **Study**: session header with phase → notices (pending headset switch, headset lost,
  paused, error) → question hero → transcript / review / evaluation (summary +
  `ExpandableSection` detail) / hint / explanation / completion → pinned controls (PTT,
  quick tools, ratings, Pause/End). Bottom navigation is hidden while a session is
  active (`isImmersiveStudy`).
* **Study Control**: summary → active session → presets → sections → persistent Apply bar
  in `Scaffold.bottomBar`. "Applies next session" badge when a session is running.
* **Settings**: everyday sections first (Study audio, Study experience, Speech
  recognition basics, Voice output); a single **Show advanced settings** switch reveals
  recognition tuning, speech timing/capabilities and network options.
* **Connection**: status card first, then the action, then profiles / advanced.
* **Diagnostics**: dense `KeyValueRow` cards in monospace; timeline and logs keyed by
  `sequence`; severity filter chips.

## 9. Accessibility checklist

- [ ] Tap targets ≥ 48dp (`heightIn(min = 48.dp)`; never `height(…)` on text buttons)
- [ ] Every icon-only button has a `contentDescription`; decorative icons pass `null`
- [ ] Status = icon + text (+ colour)
- [ ] `contentMuted` never used for required information
- [ ] Layouts survive 200 % font scale (rows use `weight(1f)` + `maxLines`; no fixed heights)
- [ ] Back arrows are `Icons.AutoMirrored.Filled.ArrowBack`
- [ ] Arabic strings keep their natural direction inside LTR chrome (`Text` handles bidi)
- [ ] Continuous animation gated on `useReducedMotion()`

## 10. Performance rules

* Never collect `audioLevel` (RMS) in a screen; pass booleans to `VoiceWaveVisualizer`.
* Derive lists with `remember(keys)`; never filter/reverse inside item lambdas.
* Stable keys on every `LazyColumn` item.
* Local draft state for sliders; commit on `onValueChangeFinished` only.
* Previews use static fakes — no repositories, no ViewModels.
