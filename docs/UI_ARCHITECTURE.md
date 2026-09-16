# Study Agent — UI Architecture

Companion to [UI_DESIGN_SYSTEM.md](UI_DESIGN_SYSTEM.md). This describes how the Compose
layer is put together after the UI refinement pass; it does not describe voice, protocol
or repository internals (see `ARCHITECTURE.md`, `VOICE_FLOW.md`, `SESSION_STATE_MACHINE.md`).

## Layers

```
MainActivity                     thin: permissions, theme, StudyAgentRoot(container)
└── ui/navigation/StudyAgentRoot primary Scaffold + NavigationBar (Dashboard / Study / Control)
    └── ui/navigation/AppNavHost NavHost: home, study, control, connection, settings, diagnostics
        └── ui/screens/*         one package per destination: Screen.kt + ViewModel.kt (+ components/)
            └── ui/components/   design-system composables (stateless, preview-able)
                └── ui/theme/    AppColors / AppSpacing / AppShape / AppTextStyle / AppMotion → Color.kt
```

* **ViewModels are unchanged in behaviour.** Screens read `StateFlow`s via
  `collectAsStateWithLifecycle()` and call the same intent functions as before. The only
  ViewModel edit in this pass was a type fix (`StudyViewModel.pendingAudioRoute` is
  `StateFlow<EffectiveStudyAudioRoute?>?`, matching the coordinator).
* **Components are stateless.** They take data + callbacks; they never hold a ViewModel
  or repository. Every component has at least one `@Preview` with static data.
* **Screens own layout and composition of components.** They do not define colours,
  sizes or typography inline.

## Navigation

| Route | Screen | Primary? | Bottom bar |
|---|---|---|---|
| `home` | Dashboard | yes | shown |
| `study` | Study Session | yes | hidden while a session is active (`isImmersiveStudy`) |
| `control` | Study Control | yes | shown |
| `connection` | Connection | no (from Dashboard status / Study badge) | hidden |
| `settings` | Settings | no (Dashboard top bar) | hidden |
| `diagnostics` | Diagnostics | no (Dashboard top bar) | hidden |

`StudyAgentRoot` observes `studySessionRepository.studyState` only to decide bar
visibility; it does not participate in any study logic. `AppNavHost` is still hostable
on its own (the instrumented suite does this) so tests are not blocked by the
permission prompts in `MainActivity`.

## State → UI mapping

* `StudyState` → `StudyPhase` via `studyPhaseOf()` (pure, unit tested in
  `StudyPhaseMappingTest`). `PhaseVisual.of(phase)` yields icon + label + colour. The
  Study screen never derives phase copy inline.
* `ConnectionState` → `ConnectionVisual` via `connectionVisualOf()`; used by the badge on
  Study and the status card on Connection so both agree.
* `EffectiveStudyAudioRoute` → `AudioRouteIndicator`; phone audio is rendered as a
  neutral route (design rule §33), headset loss is a card with two actions, not a dialog.

## Recomposition boundaries

* High-frequency `audioLevel` is **not** collected by any screen. `VoiceWaveVisualizer`
  and `PushToTalkButton` animate from booleans (`isListening`, `isSpeaking`,
  `isProcessing`) with `rememberInfiniteTransition`, gated by `useReducedMotion()`.
* Diagnostics memoises `timeline()` and the filtered/reversed log list with
  `remember(logs, …)` and keys rows by `sequence`.
* Study Control keeps a draft in the ViewModel; the screen only renders `draftConfig` and
  the Apply bar reads `hasUnsavedChanges/validationErrors/saving` — no per-keystroke
  network activity.
* Sliders keep local `mutableFloatStateOf` drafts and commit on `onValueChangeFinished`.

## Test hooks (stable contracts)

| Hook | Where | Used by |
|---|---|---|
| `"Study Agent"` title text | Dashboard | smoke + shell tests |
| `Refresh dashboard` / `Diagnostics` / `Settings` / `Back` content descriptions | top bars | AppShellInstrumentedTest |
| `active_deck_card` | Dashboard | shell test |
| `SETTINGS_LIST_TEST_TAG`, `settingSwitchTestTag(title)` | Settings | SettingsPersistenceInstrumentedTest |
| `DIAGNOSTICS_LIST_TEST_TAG`, `Copy summary` / `Export detailed diagnostics` / `Clear logs` | Diagnostics | DiagnosticsExportInstrumentedTest |
| `PUSH_TO_TALK_TEST_TAG`, `StudyScreenTags.*`, `ratingTestTag(rating)` | Study | StudyControlsInstrumentedTest |
| `BOTTOM_NAV_TEST_TAG`, `STUDY_PHASE_CHIP_TEST_TAG` | shell / Study | DesignSystemInstrumentedTest |

## Adding a screen or component

1. Tokens first: if a new colour/size is needed, add it to `AppColors`/`AppSpacing` with a
   semantic name. Never a literal in a screen.
2. Reuse `StudyAgentTopBar`, `AppCard`, buttons, rows. Only add a component when the same
   arrangement appears on ≥ 2 screens or encodes a rule (e.g. phase chip).
3. Cap content width with `BoxWithConstraints` and the appropriate `*MaxWidth`.
4. Key every lazy item; collect flows with lifecycle awareness.
5. Add a `@Preview` with static data and, for behaviour the tests rely on, a semantics tag
   constant in the components/screen package.
