# GATE 21M — Capability-Aware UI

**Date:** 2026-10-18 (UTC)
**Source HEAD:** `19c8a0d76169b18e4f2bf1787d107fbc96c41069`
**Primary files:**
`ui/components/anki/AnkiBackendSectionUi.kt`,
`ui/screens/settings/SettingsScreen.kt`,
`ui/screens/settings/SettingsViewModel.kt`,
`ui/screens/settings/AnkiBackendSelectionController.kt`
**Production changes in this run:** two wiring corrections: `DiagnosticsScreen.kt` (observe the
asynchronous read) and `AnkiBackendSectionUi.kt` (withhold a dead launcher action). Presentation logic otherwise unchanged.

---

## 1. Capability authority

The UI has no capability vocabulary of its own. It consumes:

```kotlin
// core/anki/AnkiBackendState.kt
fun capability(required: AnkiRequiredCapability): AnkiCapabilityStatus
```

`AnkiCapabilityStatus` has two independent dimensions:

| Dimension | Type | Meaning |
|---|---|---|
| Implementation | `AnkiImplementedSupport` | `SUPPORTED` / `UNSUPPORTED` / `UNKNOWN` — does the **wired application** implement it? |
| Readiness | `AnkiCapabilityReadiness` | `READY` / `NOT_READY` / `UNKNOWN` — can it be used **right now**? |

The constructor invariant is enforced in the domain type:

```kotlin
require(
  operational != READY || implemented == SUPPORTED
) { "A capability that is not known to be implemented cannot be ready" }
```

The UI exposes both dimensions for every requirement:

| UI label | Domain value |
|---|---|
| Supported | `SUPPORTED` |
| Not implemented | `UNSUPPORTED` |
| Unknown | `UNKNOWN` |
| Available now | `READY` |
| Not available now | `NOT_READY` |
| Not checked yet | `UNKNOWN` |

`AnkiBackendCapabilityRowUi.usableNow` is true only for `operational == READY`. A static implementation
claim alone never renders “Available now”.

### Capability groups

Rows are grouped for reading order only. The group heading is **not** a second capability vocabulary
and is never a policy input.

```text
Review
Library
Browser
Details
Reviewer actions
Note editing
Note creation
Media
```

`AnkiRequiredCapability.entries` is mapped totally; a new capability cannot silently disappear. The
test `every required capability has a title and a display group, and no row is lost by grouping`
asserts the partition.

### Rendered requirements

The full existing requirement set is displayed, including:

`DECKS`, `DECK_COUNTS`, `SCHEDULED_REVIEW`, `REVIEW`, `RENDERED_CARDS`, `CARD_BROWSER`,
`CARD_DETAILS`, `FLAGS`, `BURY`, `SUSPEND_CARDS`, `EDIT_NOTE_FIELDS`, `EDIT_NOTE_TAGS`,
`CHANGE_CARD_DECK`, `NOTE_MODEL_LISTING`, `NOTE_MODEL_SCHEMA`, `NOTE_TEMPLATE_LISTING`,
`MEDIA_READ`, `STORE_MEDIA`, `CREATE_NOTE`.

No capability is invented for Custom Study. GATE 20/21L are represented by a separate disclosure,
not by a fabricated requirement enum value.

---

## 2. Capability ≠ readiness

The central rule (BACKEND_SELECTION_POLICY §2.1) is rendered literally:

```text
Note editing
  Implementation: Supported
  Current state:  Backend unavailable      ← SUPPORTED / NOT_READY
```

and:

```text
Card browser
  Implementation: Not implemented
  Current state:  Not available now        ← UNSUPPORTED / NOT_READY
```

and:

```text
Review (unchecked backend)
  Implementation: Unknown
  Current state:  Not checked yet          ← UNKNOWN / UNKNOWN
```

Consequences enforced in tests:

- A disconnected backend with a static claim still reports `SUPPORTED` for the operations its adapter
  implements, and `NOT_READY` for readiness.
- A ready backend still reports `UNSUPPORTED` for operations it does not implement.
- `Checking` is never reported as ready and never grants a capability.
- A backend with no static claim and no current probe reports `UNKNOWN`, never “supported”.

Evidence: `Gate21mCapabilityAvailabilityUiTest` (14 tests).

---

## 3. Custom Study disclosure (GATE 20 / GATE 21L boundary)

**GATE 20:** design-only / blocked. **GATE 21L:** blocked. **No filtered-session lifecycle exists.**

The section therefore renders a **non-interactive disclosure**, never a control:

```kotlin
data class AnkiCustomStudyStatusUi(
    val token: String,                  // CUSTOM_STUDY_NOT_IMPLEMENTED
                                        // or CUSTOM_STUDY_LIFECYCLE_NOT_IMPLEMENTED
    val headline: String,
    val explanation: String,
    val supportsAnyCustomStudy: Boolean,
    val canCreateFilteredSession: Boolean,
    val canReviewFilteredDeck: Boolean
)
```

### Production claim

`AnkiDroidBackend.customStudyCapabilities` is the audited GATE 20 claim:

```kotlin
// data/anki/ankidroid/AnkiDroidBackend.kt:166
override val customStudyCapabilities = AnkiDroidCustomStudyCapabilities.AUDITED
// AnkiDroidCustomStudyCapabilities.AUDITED = AnkiCustomStudyCapabilities.NONE
```

Result:

```text
Custom Study: not implemented
token: CUSTOM_STUDY_NOT_IMPLEMENTED
```

### If an adapter claims part of the vocabulary

The disclosure becomes:

```text
Custom Study: lifecycle not implemented
token: CUSTOM_STUDY_LIFECYCLE_NOT_IMPLEMENTED
```

The explanation states that create / rebuild / end are not implemented in this build. A capability
claim by an adapter is not an implemented Study-Agent lifecycle.

### Forbidden, and absent

- No Start / Rebuild / End / Empty control.
- No fabricated active filtered session.
- No progress indicator.
- No inferred support from ordinary deck browsing.
- No `FilteredSession` transaction family; diagnostics reports it as
  `NOT_APPLICABLE (no filtered-session lifecycle exists in this build; GATE 21L BLOCKED)`.
- No PC Custom Study claim.

Evidence: `Gate21mCapabilityAvailabilityUiTest` custom-study cases;
`Gate21mR20_2BoundaryTest`; `AUDIT-21M-R2-12`.

---

## 4. Empty inventory and unavailable instances

### Empty registry

The section renders:

```text
No Anki backend is configured
This build has no configured Anki backend instance, so no backend can be selected and no Anki
operation can start. Automatic (AUTO) will report the same refusal.
```

plus the disabled informational kind rows (because this composition wires no adapter for those kinds)
and no Refresh action. It does not invent an AnkiDroid or PC instance to fill the card.

### Unavailable configured instance

A registered but unavailable backend stays selectable. This is intentional:

- picking it must produce a **typed refusal** from the existing admission/resolution path;
- silently redirecting to a sibling would be forbidden substitution;
- the row shows the availability status text and the stable identity;
- capability rows still show implementation truth separately from readiness.

An unregistered kind is informational and disabled; a registered unavailable instance is selectable.
The two states are not interchangeable.

---

## 5. Presentation model (immutable, Android-free)

`AnkiBackendSectionUiState` contains only presentation data:

```text
rows: List<AnkiBackendRowUi>
selectedKey: String?
preferenceSummary: String
preferenceToken: String
saveFeedback: AnkiBackendSaveFeedbackUi
refresh: AnkiBackendRefreshUi
activeSession: ActiveStudySessionUi
scopeNotice: String
switchEffectNotice: String?
emptyInventory: AnkiBackendEmptyInventoryUi?
observationNotice: String
actions: List<AnkiBackendUiAction>
```

The model:

- has no backend handle, ledger, registry, resolver or writer;
- has no Android/Compose import;
- is a `data class` of immutable values;
- carries stable keys for Compose item identity;
- exposes `hasDuplicateKeys` for test/audit use.

This is what makes the selection rules deterministically testable in the JVM harness without a device.
Evidence: `Gate21mBackendSelectionUiTest` (21 tests), `Gate21mCapabilityAvailabilityUiTest`.

---

## 6. Design-system integration

The card reuses existing primitives:

| UI need | Existing primitive |
|---|---|
| Card | `SettingsCard` / `AppCard` |
| Section heading | `SectionHeader` |
| Selectable row | `ChoiceRow` (`Role.RadioButton`) |
| Warning / info | `InfoBanner` |
| Empty state | `EmptyState` |
| Label/value | `KeyValueRow` |
| Capability expansion | `ExpandableSection` |
| Buttons | `SecondaryButton` |
| Spacing / type / colour | `AppSpacing`, `AppTypography`, `AppColors` |

No new design system was created. The section is one item in the existing `LazyColumn` Settings list
(`SettingsScreen.kt:525`), so it participates in the existing scrolling and state preservation.

---

## 7. Accessibility

Source-level properties:

- `ChoiceRow` uses `Modifier.selectable(selected, enabled, role = Role.RadioButton)`; TalkBack can
  announce selected/disabled and the radio role.
- Every row has a **text** title and a **text** status label. Colour is never the only indicator.
- Disabled informational rows carry an explanation in the row description, so a user hears why the
  option is unavailable.
- Rows have minimum 52 dp touch height; expandable headers have 48 dp.
- Capability values are text (“Supported · Available now”), not icons.
- Warning/error copy is text and survives text scaling by wrapping in a `Column`; no fixed-height
  clipping container was introduced.
- Compose test tags are present for future instrumentation:
  `settings_anki_backend_<key>`, `settings_anki_backend_refresh`,
  `settings_anki_backend_active_session`, `settings_anki_backend_save_feedback`,
  `settings_anki_backend_capabilities_<key>`, `diagnostics_anki_backends`.

**Not executed:** TalkBack, keyboard traversal and large-text behavior require a device/emulator.
They are recorded as BLOCKED in the verification report, not as PASS. See
`GATE_21M_ANDROID_VALIDATION_STATUS.md`.

---

## 8. Localization and RTL

The repository's established UI convention is **hard-coded English copy in Compose**, not string
resources. There is no `values-ar/strings.xml`; only notification-channel strings use resources
(`app/src/main/res/values/strings.xml`). GATE 21M follows that existing convention rather than
introducing a partial localization layer for one card.

Properties preserved:

- No `LayoutDirection.Ltr` / `Rtl` override.
- No `absolutePadding` and no `padding(left = …)` / `padding(right = …)` in the gate's screens.
- `Row`, `Column`, `Arrangement.spacedBy`, and logical `start`/`end` padding are used.
- `BackendId.stableId` is rendered verbatim and is never translated.
- Capability enum tokens in diagnostics are not localized (they are technical vocabulary).
- User-facing labels are English strings; Arabic UI localization remains a repository-wide gap, not a
  GATE 21M regression.

**Not executed:** RTL layout rendering and text truncation on a device. Recorded BLOCKED.

---

## 9. What this checkpoint does not claim

- It does not claim Compose rendering was executed. The JVM harness compiles the GATE 16 Compose
  closure, not the Settings screen; the all-framework-importing diagnostic sweep reports a pre-existing
  `SettingsScreen.kt:778` error in `NavigationBarItemDefaults.colors(containerColor = …)`, unrelated to
  the GATE 21M section. Gradle verification is blocked by the environment.
- It does not claim TalkBack, RTL or large-text execution.
- It does not claim a PC Anki UI exists.
