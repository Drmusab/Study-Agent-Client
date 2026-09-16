# UI Audit — Study-Agent-Client

Audit performed against `master @ 9e0d43b` before the design-system refactor. Scope: every
composable under `ui/` (6 screens, 6 shared components, 3 theme files, app shell).
Findings are grouped by the audit axes required for the polish pass.

## 1. Critical defects found (must fix, no design debate)

| # | Where | Problem |
|---|-------|---------|
| C1 | `SettingsScreen.kt:378` | **Compile error.** `EnginePicker(engines = engines, …)` references an `engines` value that is never declared or collected (`viewModel.engines` exists but is not subscribed). The app does not build. |
| C2 | `StudyScreen.kt` (4 flows) | `viewModel.studyAudioRoute?.collectAsState()?.value`, same for `audioRouteAttention`, `pendingAudioRoute`, `appSettings`. `collectAsState()` is called, its `State` discarded, and only `.value` read. Nothing in the composition reads the resulting snapshot state, so these flows **never trigger recomposition by themselves** — the headset-loss card, the "use headphones now" affordance and the phone-audio notice can stay stale until some *other* state change happens. Must use `by …collectAsStateWithLifecycle()`. |
| C3 | `ControlCenterScreen.kt` | Back icon is `Icons.Default.ArrowBack` (static, non-mirrored) while every other screen uses `Icons.AutoMirrored.Filled.ArrowBack` → wrong direction in Arabic (RTL). |

## 2. Colors

* **No semantic layer.** Screens import raw palette constants
  (`DarkBackground`, `DarkSurface`, `DarkSurfaceElevated`, `PrimaryBlue`, `AccentTeal`,
  `TextPrimary/Secondary/Muted`, `Status*`, `Rating*`) directly. `MaterialTheme.colorScheme` is
  used in exactly two places in the whole app (`SettingsScreen` persistence error card) — the
  direction is inverted from the goal `screen → semantic theme → palette`.
* **Contrast failures (WCAG AA, 4.5:1 for normal text):**
  * `TextMuted #64748B` on `DarkSurface #1E293B` ≈ **3.0:1** — used for small labels
    (`labelSmall`, `bodySmall`) across all screens. Fails.
  * White text on the filled rating buttons: `#F8FAFC` on `RatingAgain #EF4444` ≈ **3.6:1**,
    on `RatingGood #10B981` ≈ **3.4:1**. 14sp Medium is *not* large text → fails.
  * `TextSecondary #94A3B8` on `DarkSurface` ≈ 5.2:1 — passes, keep.
* **Status semantics drift.** The same concept gets different colors on different screens
  (e.g. "connected" green dot in `ConnectionBadge` is `StatusGreen`, the "Recall" value is
  `AccentTeal`, pace "on track" is `AccentTeal` but "ahead" is `StatusGreen`,
  `ConnectionState.Error` row in diagnostics is red but the badge dot is red — mostly consistent,
  but nothing enforces it).
* **Voice activity colors** (teal = speaking, blue = listening) exist only as an inline
  `if (isSpeaking)` in `StudyScreen`; they are not tokens, and the idle wave is `Color.Gray`
  (Material gray, off-palette).
* **`DarkSurfaceElevated` is overloaded** — dialogs, selected chips, selected rows, chips,
  banners, wave tracks… all the same color, so "elevation/selection" carries no hierarchy.

## 3. Spacing

* Screen horizontal padding: **20.dp** (Home, Control), **18.dp** (Study, Settings, Connection),
  **16.dp** (Diagnostics). No rationale.
* Item rhythm 16.dp everywhere (fine) but inner spacers are free-form: 2/4/6/8/10/12/14/16/20/24
  all appear; e.g. `StudyScreen` uses 10/14/16, `DashboardCards` uses 6/10/12/14, `SettingsScreen`
  uses 4/8/10/12/14.
* Card inner padding: 14/16/18/20.dp by whim; question card 20.dp, transcript 14.dp,
  evaluation 18.dp.

## 4. Shape

* Card corners: 16.dp (most), **18.dp** (evaluation card only), 20.dp (hero-ish cards, dialog),
  10.dp (diagnostics timeline cards).
* Button corners: 10/12/14.dp mixed within the *same* screen (Apply bar 14, inner buttons 12,
  error panel 10).
* Chip/row corners: 6/10/12.dp ad hoc.

## 5. Typography

* `Type.kt` customizes only **6 of 15** Material roles. `titleMedium`, `titleSmall`, `bodySmall`,
  `labelSmall`, `labelMedium`, `headlineSmall`, `display*` are all **un-customized Material
  defaults** yet are used heavily → two type systems in one app.
* Screen-local `.copy(fontSize = …)` overrides: PTT 17.sp, Study question
  `titleLarge.copy(20.sp/28.sp)`, diagnostics 11.sp/12.sp, filter chips 11.sp, rating 14.sp.
* No **metric typography** role: `StatItem` uses `headlineSmall` (un-customized 24sp regular)
  for the number and `bodySmall` for the label — the number does not visually dominate as it
  should (weight/size gap is weak, letter spacing absent).
* No **monospace** role although Diagnostics is a technical screen (all values in default sans).
* Section titles are `labelMedium` + `.uppercase()` + teal — repeated 5× inline with different
  colors (teal/amber/blue/purple per card), which reads as decoration rather than hierarchy.

## 6. Buttons & hierarchy

* **Multiple filled primaries per view.** Study bottom area: filled PTT + 4 filled-tonal tools +
  4 filled rating buttons + outlined pause/end. Control Apply bar: filled APPLY + outlined
  Discard/Reset (acceptable). Smart start card: giant filled button inside a card that also has
  an outlined reconnect button in the offline banner above.
* Rating buttons are **saturated Anki-style fills** — the loudest thing on the screen during
  rating, fighting the evaluation text.
* Disabled styling is inconsistent (M3 default vs `alpha(0.4f)` hand-rolled on ratings).
* **Touch targets < 48dp:** `ChoiceChips` FilterChip `min 40.dp`, diagnostics log-filter chips
  (default 32dp), `ConnectionBadge` row ≈ 28–32dp tall, quick-tool buttons (M3 default 40dp
  min with 12sp labels), deck picker "Change" text button.
* PTT: good (72dp, clear two-state) but states are **color + label only** — no icon change, no
  haptics, no "processing" or "disabled" state styling.

## 7. Cards & status chips

* Four parallel "card" components: `SectionCard` (home), `ControlSection` (control),
  `SettingsCard` (settings), `DiagnosticsRowsCard` (diagnostics) + ~15 inline `Card(...)`
  sites, each re-choosing shape/color/padding.
* `StatusBadge` concept exists twice and half-formed: `ConnectionBadge` (dot + text, clickable,
  tiny) and the health rows in `SystemReadinessCard` (icon + label + value).
* Freshness ("Live / Cached / Stale") is a single muted line under the title — fine, but the
  word "Stale" is presented at the same weight as "Live" (prompt: keep it subtle, secondary).

## 8. Top bars

* **Four different top bars:**
  * Home: hand-rolled `Row`, 12.dp vertical padding, title left + 3 icon buttons right.
  * Study: hand-rolled `Row`, back + connection badge + route chip (functional, good content).
  * Control: Material `TopAppBar` (56dp) — the only screen using the framework component.
  * Settings / Connection / Diagnostics: hand-rolled `Row`, back + title, varying trailing slots.
* Back icon: `AutoMirrored.ArrowBack` tinted `TextPrimary` (Study/Settings/Connection/Diagnostics)
  vs static `ArrowBack` tinted `TextSecondary` (Control).

## 9. Navigation

* Bottom nav (Dashboard / Study / Control) exists with correct immersive-study hiding — good.
* Duplicated Control entry: bottom nav *and* the recommendation "Use recommendation" navigation
  (legitimate, different intent — keep).
* Secondary screens (Connection/Settings/Diagnostics) reachable from the Dashboard header —
  good; Study screen also reaches Connection via its badge — good.
* No `widthIn(max=…)` anywhere → on tablet/foldable, dashboard/control rows stretch to full
  width (1000dp+ text rows). No landscape handling on Study.

## 10. Loading / error / empty states

* Dashboard has the best state handling (offline banner, error panel, unsupported panels,
  freshness labels) but renders **nothing** while `freshness == Loading` and no snapshot: the
  screen is blank except the header until data arrives. No skeletons.
* Control: no loading indicator for `state.loading` (capabilities negotiating) — the screen
  just shows defaults silently (mitigated by "Waiting for the Study Agent's configuration…"
  line in the summary).
* Study: `StudyState.Loading` only shows "⏳ message" in the header row.
* Error states: dashboard error panel is good; Study `Error` state shows a red header line only —
  the rest of the screen looks active.

## 11. Study screen (core experience)

* **10 controls always visible** in the bottom area (PTT, Repeat, Hint, Explain, Skip, 4 ratings,
  Pause, End) regardless of phase → cognitive overload; controls that are meaningless in the
  current phase (e.g. ratings during Listening) are enabled-but-wrong, or enabled but the
  repository silently ignores them.
* **Two redundant state readouts**: `StudyStateHeader` (emoji + long label, e.g. "🎧 Listening to
  Answer") *and* `PhaseChip` ("🎤 Listening") — same fact, two places, two emoji sets.
* Emoji used as icons (🔊🎧🧠⭐💡📖⏸️⏳🎉⚠️) — inconsistent glyph rendering across OEMs,
  no tint control, not accessible icon+text pairs.
* Question card: decent, but 20sp via ad-hoc `.copy`, no max width, waveform sits *inside* the
  question card (odd ownership).
* Transcript: partial and pending-review use identical styling; partial should feel temporary.
* Evaluation: single "AI FEEDBACK" block; no severity label (Correct / Mostly correct / Partial /
  Incorrect), score chip only green(≥80)/amber(<80), `incorrectPoints` **is never rendered**.
* Paused state: nothing changes except the header text — the screen still shows PTT, tools and
  ratings as if active (prompt §37 requires a visible transform).
* Error/disconnection: no in-screen recovery card.

## 12. Animations & motion

* Infinite animations are correctly scoped (wave + PTT pulse only while active) — keep.
* No `reduce motion` handling anywhere.
* No card-change transition (question swaps are a hard cut), no entrance for banners/cards
  (`AnimatedVisibility` used only for transcript/evaluation with default spec).
* `LinearProgressIndicator` progress jumps without animation on dashboard.

## 13. Accessibility

* **Touch targets** — see §6 (chips, connection badge, quick tools).
* **Contrast** — see §2 (TextMuted, rating fills).
* **Icon-only controls**: all icon buttons have content descriptions — good (tests rely on it).
* **Color-only state**: PTT (color + label, OK), rating suggestion (★ + label, OK), connection
  dot (dot + text, OK). Phase chip (icon + text, OK after emoji removal). Goal pace (text, OK).
  Mostly fine; the *filled rating button* encodes meaning (suggested) in a star that is the only
  differentiator — acceptable, keep + strengthen.
* **Semantics merging**: a few good `semantics { contentDescription }` uses (health rows,
  stepper rows, deck rows); missing on the hero start button (label is present, OK).
* **RTL**: layout is mostly direction-agnostic (`horizontal` paddings, `SpaceBetween`); the C3
  back-icon bug is the concrete RTL defect. Arabic strings exist in settings/voices and render
  naturally; no manual reversal anywhere (good).
* **Font scaling**: no fixed heights around multi-line text except PTT (single line, OK) and
  chips (single line, OK) → safe at 130–200%.

## 14. Recomposition / performance

* `StudyScreen` re-runs its whole function body on every partial-transcript update and every
  `audioLevel`-independent state change; children skip correctly, but the top bar (connection
  badge, route chip) is rebuilt each time for no reason. Extracting stable sub-trees with their
  own inputs makes this free.
* `HomeScreen` `nowMs` ticks every 30 s and re-composes the whole dashboard — acceptable, but the
  freshness label is the only consumer; isolating it keeps the rest skipped.
* `DiagnosticsScreen` recomputes `filteredLogs.asReversed()` and `timeline().asReversed()` into
  new lists every composition — fine at these sizes, but the filter is O(n) per composition;
  memoize with `remember(logs, levelFilter)`.
* `WeeklyBars` recomputes `maxOf` each composition — trivial cost, keep.
* `LaunchedEffect(Unit) { viewModel.onScreenActive() }` in Home is correctly lifecycle-driven.
* `collectAsStateWithLifecycle` used on the main screen states; the C2 exceptions are the only
  non-lifecycle collections outside `MainActivity`'s root (which must collect always for the
  nav bar — correct there).
* List keys: decks by name, logs by sequence, timeline by sequence, profiles by index (should be
  `profile.id`), settings rows are `item {}` (no keys needed, static order).

## 15. Copy / terminology

* Mostly consistent (Study Agent, Dashboard, Study Control, Session). Drift:
  "PC Agent" (System row) vs "Agent"; "Server Offline" vs "Disconnected" vs "No Network";
  "Push to Talk (Hold or Tap)" is long for a primary control; "YOUR TRANSCRIPT" vs
  "REVIEW YOUR ANSWER" vs "AI FEEDBACK" (three uppercase labels with different weights/colors);
  "Use Fake Agent (Mock Mode)" vs "Mock Agent (development)".
* Emojis in user-facing state strings (see §11) — replace with Material icons + plain text.

## 16. Large-screen behavior

* No `widthIn(max = …)`, no two-column dashboard, no landscape split for Study, no dialog
  width caps. On a tablet the single column stretches and metrics rows become absurdly wide.
  (Prompt §58–§62.)

## 17. What is good and must be preserved

* The repository/ViewModel/mapper architecture (`DashboardUiMapper` is pure and unit-tested —
  keep it untouched).
* The bottom-nav + immersive-study shell logic in `MainActivity`.
* Capability gating (v1/v2, unsupported panels) — do not make it cosmetic.
* Honest offline/unknown wording ("Cost unavailable", "Unknown" capability rows,
  "Saved engine is currently unavailable…").
* PTT test tags, rating test tags, settings switch tags, diagnostics log tags — all consumed by
  the instrumented suite; **keep every tag, every `contentDescription` the tests match on
  ("Refresh dashboard", "Diagnostics", "Settings", "Back", "Clear logs"), and the strings
  "Push to Talk", "Idle", "Press 'Start' to begin loading cards", "LOGS ("**.
* The scoped-infinite-animation policy (§58) and the `LaunchedEffect(Unit)` lifecycle refresh.
* Diagnostics privacy (no tokens, no raw frames, bounded timeline) — presentation-only changes.
