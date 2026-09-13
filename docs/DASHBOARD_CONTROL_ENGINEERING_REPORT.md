# Engineering Report — Operational Dashboard & Study Control Center (Protocol v2)

**Date:** 2026-09-13 · **Branch:** `arena/01a09910-study-agent-client`
**Scope:** 40 files changed, +7,625 / −267 across 7 commits on top of `master@9774636`.

## 1. What was delivered

### 1.1 Home = live operational dashboard (§1–§31)
- `DashboardRepository` requests `request_dashboard` (+ dedicated deck / health / history /
  insight / AI-usage requests) with **single-flight coalescing**, 8 s bounded timeouts,
  `message_id` correlation, an out-of-order **generation guard** (stale/unsolicited
  snapshots only apply when `generated_at` is newer), and **panel-scoped merges** for
  live pushes (`session_progress`, `session_stats`, `session_started/paused/resumed`,
  `session_finished` → coalesced refresh).
- **No polling.** Refresh triggers: negotiation completion, screen-active-if-stale,
  manual refresh, session end, config change, server push. Opening Home never fires an
  LLM/insight generation.
- **Honest freshness:** `FreshnessPolicy` maps to `Loading / Live / Cached / Stale /
  Unavailable` and is rendered in the header (`Live • updated now`, `Cached • 12 min
  ago`). The last snapshot + deck list persist offline (JSON in DataStore) behind an
  Offline banner; the server remains the only source of truth.
- Capability-gated panels — system readiness, smart Start/Resume, active deck, today
  stats, goal progress, weekly performance (Canvas chart + text values), rating
  distribution with ranges, learning insight, recommendation, AI usage — render only
  what the server provides. **Every hardcoded demo metric ("Toronto Notes", 183 due,
  92 studied, 81% recall, 28m, topic chips, AI cost $0.00) was removed**; nothing is
  computed on the device.
- Start is **idempotent** (state machine rejects duplicate starts; button disables in
  STARTING) and Resume is navigation-only.

### 1.2 Study Control Center (new screen, §32–§123)
- One `StudyControlConfig` model end to end; presets via `StudyPreset.applyTo` with
  `matching` → **Custom** detection when a field is edited.
- Three-way config truth: `serverConfig` (authoritative), `draft` (persisted
  locally, survives navigation), `localConfig` (fallback / v1).
- Save flow: `update_study_config` → **ACK correlated by echoed `message_id`** →
  commit; `error` frame or 8 s timeout keeps the draft and authoritative config;
  rapid taps dedupe (`AlreadySaving`); invalid configs (`validate()`) never leave the
  device; unsolicited server config pushes surface a **Reload / Keep my draft** banner.
- Sections: Preset (+ preview), Deck & Mode, Session target/limits (session-level,
  never misrepresented as Anki options), Evaluation strictness + advanced knobs,
  Teaching (feedback/Socratic/hints), Rating automation, Privacy. Active-session
  surface leads with Resume/Pause/End and marks changes as *Applies next session*.
- `start_session` carries `config.toSessionStartConfig()` on v2 and deck+mode only on v1.

### 1.3 Navigation & capability authority (§124–§147)
- Bottom nav **Dashboard / Study / Control** (hidden during immersive study turns);
  Settings stays device-only; connection/settings/diagnostics move to the header.
- `CapabilityStore` is the single negotiation authority: `capabilities` frame →
  `NEGOTIATED_V2`; silence for 4 s → `LEGACY_V1` (basic study keeps working, panels
  gate off). WebSocket-connected never implies Anki/LLM readiness — only
  `component_health` counts, else **Unknown**.

### 1.4 Simulation targets
- `FakeAgentConnection`: full v2 management surface (7 demo decks, snapshots,
  config store with ACK echo + rejection sentinel, pushes) and
  `FakeCapabilityMode FULL / PARTIAL / V1_ONLY`.
- `server/mock_pc_agent.py`: `--v1-only` / `--partial-capabilities`, ACK echo,
  rejection on `session_target_value == 666`.

### 1.5 Tests written (JVM unit, 11 files)
`data/`: DashboardModels deserialization, FreshnessPolicy matrix, CapabilityStore
(v1 timeout fallback, v2 negotiation, reset), DashboardRepository (8 scenarios),
StudyControlRepository (8 scenarios), StudyPreset, SessionStartConfig, DashboardUiMapper;
`FakeAgentManagementTest` (8 scenarios over the fake agent).

### 1.6 Docs
New: `docs/DASHBOARD.md`, `docs/CONTROL_CENTER.md`. Updated: `docs/PROTOCOL.md`
(v2 wire spec §4–§8), `docs/ARCHITECTURE.md` (management layer), `README.md`.

## 2. Verification status — read before merging

**⚠️ Gradle validation could NOT be executed in the authoring sandbox.** The
environment has no JDK, no Android SDK, and no network access to provision either, so
`./gradlew testDebugUnitTest`, `assembleDebug`, `assembleRelease` and `lint` were never
run here. No claim is made that the build passes.

Compensation performed instead of a build:

1. Full self-review pass of every new/modified repository, mapper, ViewModel and screen.
2. Automated brace/paren/bracket balance check over all 33 touched Kotlin files (all balanced).
3. Symbol cross-checks of every consumer against definitions (imports, extension
   functions, constructor parameters, enum members, component signatures).
4. `python3 -m py_compile server/mock_pc_agent.py` — passes.

**Required validation before merge (run in CI):**

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew lint            # if configured in CI
```

Known areas with the highest residual risk, to watch first if compilation fails:
`HomeScreen.kt` and `ControlCenterScreen.kt` (largest Compose surfaces, Material3
lambda-overloads such as `LinearProgressIndicator(progress = { … })`), the
`combine` chains in both ViewModels, and test coroutines
(`UnconfinedTestDispatcher(scheduler)` eager-subscription pattern in
`FakeAgentManagementTest`).

## 3. Design decisions worth remembering

| Decision | Rationale |
|---|---|
| Repositories own all networking; ViewModels only combine StateFlows | Compose recomposition never triggers I/O; dedup/timeout live in one place |
| Generation counter + `generated_at` ordering | Out-of-order snapshots can never regress the UI |
| ACK must echo `message_id` | Unrelated `study_config_updated` is a server push, never a false commit |
| Draft persists across navigation | Users never lose edits; server stays authoritative |
| Capability strings preserved when unknown | Newer agents advertise features without breaking old clients |
| Preset adapter is the *only* Control→Settings link | No duplicated device settings; §74 explicit adapter |
| Fake agent + Python mock both implement full v2 | Gating/fallback paths are testable without a live agent |

## 4. Suggested follow-ups

- Run the gradle validation above; fix any Kotlin/Material3 signature drift.
- Espresso/Robolectric UI tests once `androidTest` infrastructure exists (none today).
- Consider snapshot tests for the weekly/rating charts if visual regressions appear.
