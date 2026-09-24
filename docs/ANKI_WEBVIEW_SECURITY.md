# GATE 09 — Anki rich-card compatibility and WebView security

**Status: BLOCKED for full rich-media PASS (2026-09-24).** The renderer hardening in this
checkout is implemented and tested at the policy/unit level, but the verified AnkiDroid public
contract does not provide a media-file read stream. A filename in `ReviewInfo.MEDIA_FILES` is not
converted into a private path. Real-device comparison and media endurance are therefore not
claimed.

## Boundaries

`AnkiRenderedCard.media` contains logical `AnkiMediaRef` values only. `AnkiMediaResolver` is a
read-only domain boundary. Android `ContentResolver`/streams are not exposed to core or the card
page. `AnkiDroidMediaResolver` currently returns `PROVIDER_UNAVAILABLE` for valid filenames,
because v2.24.1 exposes filenames but no public readable media URI/endpoint. This is an explicit
degraded outcome, not a sandbox bypass.

The WebView uses the existing single `AnkiCardWebView` architecture. The reserved
`https://card.studyagent.invalid/` base origin is controlled and non-public. No application asset
tree or filesystem is exposed. The resource policy blocks remote, `file:`, `content:`, intent and
unknown resource schemes. Missing subresources do not fail the main document.

## Actual policy

| Surface | Final policy | Compatibility/security impact |
|---|---|---|
| JavaScript | enabled by explicit `CARD_TEMPLATE_ONLY` policy | page JS only; no native authority |
| Native JS bridge | none; `addJavascriptInterface` is absent | card cannot call Study-Agent or AnkiDroid APIs |
| File/content access | `false` | no generic filesystem/provider access |
| Universal file URL access | untouched platform default (false) | never relaxed |
| Remote resources | blocked by `AnkiCardResourcePolicy`; images also block network loads | offline/privacy-first; remote-dependent cards degrade |
| Mixed content | platform strict default; `ALWAYS_ALLOW` absent | HTTP is not silently enabled |
| Safe Browsing | enabled on API 26+ | WebView protection where supported |
| DOM/database storage | disabled | no cross-card persistent storage |
| Cookies/third-party cookies | disabled for the card WebView | no app auth cookie sharing |
| Multiple windows/popups | disabled and `onCreateWindow` returns false | no child browser |
| Microphone/camera | permission requests denied | native voice remains outside card |
| Geolocation | denied without retention | no location capability |
| File chooser | callback cancelled | no device file picker |
| SSL errors | cancelled; never `proceed()` | invalid TLS cannot be accepted for compatibility |
| Debugging | `BuildConfig.DEBUG` only | no release remote inspection |

## URI/navigation policy

Same-document fragments are allowed. Top-level external HTTPS/HTTP links are classified and
reported to the existing external-link boundary; the card WebView does not navigate to them.
`file:`, `content:`, `intent:`, `tel:`, `sms:`, `geo:`, `market:`, `javascript:`, unknown schemes
are blocked. `data:` and `blob:` are not allowed as top-level/resource escapes by the resource
policy. No generic `Intent.parseUri` or card-driven `startActivity` exists.

## Compatibility status

| Capability | Status | Notes |
|---|---|---|
| HTML/CSS, cloze, RTL, tables, malformed markup | IMPLEMENTED / unit-tested | backend supplies rendered HTML |
| page JavaScript | PARTIAL | page-context scripts; AnkiDroid native JS API unsupported |
| images/audio/video/custom fonts | BLOCKED/DEGRADED for AnkiDroid | no verified public media read mechanism |
| Math/MathJax | NOT TESTED / PARTIAL | no second math engine or remote CDN dependency is added |
| remote scripts/resources | SECURITY_BLOCKED | explicit default policy |
| accessibility/selection | IMPLEMENTED | semantic card markup is not stripped; text selection remains enabled |

## Known risks and limitations

- WebView is a complex third-party runtime; this policy is defense-in-depth, not a claim of an
  absolute browser sandbox.
- JavaScript can still consume CPU/memory inside the renderer process. Existing renderer-process
  recovery preserves the presentation turn, but it cannot make a hostile DOM cheap.
- Full media compatibility requires a future documented AnkiDroid public stream/URI contract (or a
  user-authorized, app-owned import flow). It must not be solved by opening
  `/data/data/com.ichi2.anki`, `collection.media`, or `collection.anki2`.
- No native card-audio player is introduced in this gate; consequently there is no independent
  audio-focus owner or stale playback callback to leak into StudySession.

## Verification commands

The policy and resolver tests are JVM tests:

```text
./gradlew :app:testDebugUnitTest --tests '*AnkiMediaResolverPolicyTest'
./gradlew :app:testDebugUnitTest --tests '*AnkiCardLinkPolicyTest'
```

Instrumented WebView tests require a device/emulator with a WebView provider and were not run in
this environment. No 100-card or AnkiDroid real-device claim is made.
