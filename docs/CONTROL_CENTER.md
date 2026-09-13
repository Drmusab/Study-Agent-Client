# Study Control Center

The Control Center answers **“How should the PC Study Agent study?”** — deck, study mode,
session target, evaluation strictness, feedback depth, Socratic follow-ups, hints, rating
automation and transcript privacy.

It is deliberately **not** device Settings:

| Control Center (agent behavior) | Settings (device/app) |
|---|---|
| Active deck, study mode | STT language / locales |
| Session target (cards/minutes/finish due) | TTS voices, rate, pitch |
| New/review limits (session-level) | Audio routing (phone/headset) |
| Evaluation strictness + advanced knobs | Partial transcript display |
| Feedback depth, Socratic mode, hints | Diagnostics, network behavior |
| Rating automation, transcript retention | |

Nothing is duplicated across the two surfaces. The only intentional link is the preset
adapter: applying a preset may set local hands-free behavior flags (e.g. Walking Mode),
while speech engine settings always stay in Settings.

## Configuration model

One coherent model — `StudyControlConfig` — instead of dozens of booleans. Presets
(`StudyPreset.applyTo`) produce explicit configs; `StudyPreset.matching` detects which
preset (if any) a config still equals, so editing one field after choosing a preset flips
the state to **Custom**.

Three truths, kept distinct:

- **serverConfig** — authoritative, loaded via `request_study_config` when the server
  advertises `study_config`.
- **draft** — the user’s unsaved edits, persisted locally so leaving the screen never
  loses work.
- **localConfig** — last-known fallback for display, draft recovery and v1 servers.

## Save flow: ACK, never optimistic

```
edit draft → validate (StudyControlConfig.validate) → update_study_config(messageId=X)
→ Saving → study_config_updated(message_id=X) → verify → commit serverConfig
```

- **ACK correlation:** the server must echo the request `message_id` (the mock agents do).
  An unrelated `study_config_updated` is treated as a server-side push, not an ACK.
- **Rejection:** an `error` frame keeps the draft and the authoritative config exactly as
  they were; the reason is shown with Retry/Reset options.
- **Timeout:** 8 s bounded wait → “Could not confirm settings.” — never a permanent
  “Saving…” spinner.
- **Rapid taps:** a save already in flight returns `AlreadySaving`; only one
  `update_study_config` is ever outstanding.
- **Server push (§115):** if the agent changes configuration while the draft has unsaved
  edits, a banner offers **Reload** or **Keep my draft** — edits are never silently
  overwritten.

On Protocol v1 (no `study_config` capability) the same screen persists choices locally;
`start_session` then carries just deck + mode.

## Sections

```
PRESET        Quick Review / Deep Study / Walking / Exam / Weakness Training (+ preview)
DECK & MODE   Active deck (live deck list, nested names) + study mode
SESSION       Target type + value, new-card limit, review limit (or Anki default),
              learning-card handling
EVALUATION    Strictness (Lenient/Balanced/Strict/Exam) + collapsible Advanced
              (semantic matching, key points, penalties, partial credit)
TEACHING      Feedback depth, Socratic mode (+follow-ups/reveal), hint policy
RATING        Manual / Suggest / Auto-confident (+threshold slider) / Fully automatic
              (with scheduling warning)
PRIVACY       Transcript retention with privacy explanation
```

- The session-level **new-card limit** is labelled as a Study Agent session limit — it is
  never misrepresented as changing Anki deck options.
- “Use Anki default” review limit sends `null`.
- During an active session the screen leads with **Resume / Pause / End** and marks
  config changes as *Applies next session* — it never becomes a second Study screen.
- The summary header (“Cardiology • Due + New • 45 minutes • Balanced • Normal • Suggest
  rating”) makes the configuration legible without reading every control.
- **Reset** restores model defaults (keeping the active deck) behind a confirmation.

## Start-session integration

Dashboard **Start** and voice “start study” both use
`StudyControlRepository.currentStartRequest()`:

```kotlin
StartStudyRequest(deck = config.activeDeck, mode = config.studyMode.wireValue,
                  config = if (v2) config.toSessionStartConfig() else null)
```

Protocol v1 servers receive only deck + mode (§76). Recommendations (“Use
recommendation”) only update the draft/deck — they never silently change configuration
or start a session.
