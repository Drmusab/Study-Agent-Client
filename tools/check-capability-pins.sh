#!/bin/bash
# GATE 21L — reproducible capability-pin check.
#
# WHY THIS EXISTS
#   The GATE 21L report (docs/GATE_21L_REPORT.md §5 "Backend Capability Matrix") claims what each
#   backend can and cannot do for Custom Study / filtered decks. Those claims must not rest on a
#   document quoting a document. This script re-derives every row from the *pinned upstream
#   sources*, so a reviewer can re-run the evidence instead of trusting it.
#
# WHAT IT IS NOT
#   It is source evidence only. It never talks to Anki, AnkiDroid, AnkiConnect or a collection, and
#   a PASS here does not mean "the capability works" — it means "the upstream source still says what
#   the capability matrix says it says". Device / disposable-collection runs (VER-21L-46) are a
#   different artifact and remain outstanding.
#
# USAGE
#   tools/check-capability-pins.sh [workdir]        default workdir: ${TMPDIR:-/tmp}/gate21l-pins
#
#   An existing workdir is reused as-is (delete it to force a fresh clone). Nothing is written
#   inside the repository.
#
# PINS
#   ankitects/anki          tag 25.09.2      Anki desktop / rslib backend: scheduler + proto
#   ankidroid/Anki-Android  tag v2.24.1      the pinned public provider contract (api module)
#   FooSoft/anki-connect    default branch   UNPINNED — its resolved SHA is printed on every run,
#                                            because no AnkiConnect version is pinned anywhere in
#                                            this repository (that absence is itself a GATE 20 item)
#
# EXIT CODE
#   0  every expected marker was found / every expected absence still holds
#   1  at least one marker disagreed with the matrix, or a source could not be fetched
#
# A fetch failure is reported as UNVERIFIED, never as a pass.

set -uo pipefail

WORK="${1:-${TMPDIR:-/tmp}/gate21l-pins}"
ANKI="$WORK/anki"
AD="$WORK/ad"
AC="$WORK/ac"

PASS=0
FAIL=0
UNVERIFIED=0

say() { printf '%s\n' "$*"; }

# check_present <id> <repo-dir> <pathspec> <ere> <expectation note>
check_present() {
    local id="$1" dir="$2" path="$3" pattern="$4" note="$5"
    if [ ! -d "$dir" ]; then
        say "UNVERIFIED  $id   (source not fetched: $dir)"
        UNVERIFIED=$((UNVERIFIED + 1))
        return
    fi
    local hit
    hit="$(git -C "$dir" grep -n -E "$pattern" -- "$path" 2>/dev/null | head -3)"
    if [ -n "$hit" ]; then
        say "PRESENT     $id   $note"
        printf '%s\n' "$hit" | sed 's/^/                 | /'
        PASS=$((PASS + 1))
    else
        say "MISSING     $id   expected: $note"
        say "                 | pattern '$pattern' not found in $path"
        FAIL=$((FAIL + 1))
    fi
}

# check_absent <id> <repo-dir> <pathspec> <ere> <expectation note>
check_absent() {
    local id="$1" dir="$2" path="$3" pattern="$4" note="$5"
    if [ ! -d "$dir" ]; then
        say "UNVERIFIED  $id   (source not fetched: $dir)"
        UNVERIFIED=$((UNVERIFIED + 1))
        return
    fi
    local hit
    hit="$(git -C "$dir" grep -in -E "$pattern" -- "$path" 2>/dev/null | head -3)"
    if [ -z "$hit" ]; then
        say "ABSENT      $id   $note"
        PASS=$((PASS + 1))
    else
        say "FOUND       $id   expected absent: $note"
        printf '%s\n' "$hit" | sed 's/^/                 | /'
        FAIL=$((FAIL + 1))
    fi
}

fetch_sparse() { # <dir> <url> <ref> <sparse paths...>
    local dir="$1" url="$2" ref="$3"; shift 3
    if [ -d "$dir/.git" ]; then
        say "reusing     $dir"
        return 0
    fi
    if ! git clone --depth 1 --filter=blob:none --sparse --branch "$ref" "$url" "$dir" >/dev/null 2>&1; then
        say "FETCH FAILED  $url @ $ref"
        return 1
    fi
    git -C "$dir" sparse-checkout set "$@" >/dev/null 2>&1
    say "cloned      $dir @ $ref"
}

say "GATE 21L capability-pin check — workdir $WORK"
say "Source evidence only: no backend, device or collection is touched."
say ""

fetch_sparse "$ANKI" https://github.com/ankitects/anki 25.09.2 proto rslib/src/scheduler rslib/src/decks
say "  anki       $(git -C "$ANKI" rev-parse --short HEAD 2>/dev/null || echo '?')  (tag 25.09.2)"
fetch_sparse "$AD" https://github.com/ankidroid/Anki-Android v2.24.1 api/src/main/java/com/ichi2/anki
say "  ankidroid  $(git -C "$AD" rev-parse HEAD 2>/dev/null || echo '?')  (tag v2.24.1)"
fetch_sparse "$AC" https://github.com/FooSoft/anki-connect master plugin
if [ -d "$AC/.git" ]; then
    say "  anki-connect $(git -C "$AC" rev-parse HEAD 2>/dev/null || echo '?')  <-- UNPINNED (default branch)"
fi
say ""

say "== Anki backend (ankitects/anki 25.09.2) =="
check_present "A1" "$ANKI" proto/anki/decks.proto \
    'rpc AddOrUpdateFilteredDeck|OpChangesWithId' \
    "AddOrUpdateFilteredDeck returns OpChangesWithId -> an id exists on the raw path"
check_present "A2" "$ANKI" proto/anki/scheduler.proto \
    'rpc CustomStudy\(CustomStudyRequest\) returns \(collection.OpChanges\)' \
    "preset CustomStudy returns OpChanges -> no id (identity undefined on the preset path)"
check_present "A3" "$ANKI" proto/anki/scheduler.proto \
    'rpc EmptyFilteredDeck|rpc RebuildFilteredDeck' \
    "empty + rebuild exist as separate RPCs (different result types)"
check_present "A4" "$ANKI" proto/anki/scheduler.proto \
    'rpc GetQueuedCards' \
    "the backend queue that would serve a filtered deck exists"
check_present "A5" "$ANKI" rslib/src/scheduler/filtered/custom_study.rs \
    'custom_study_custom_study_session' \
    "the reserved custom-study deck name comes from a translation (localized, not a stable key)"
check_present "A6" "$ANKI" rslib/src/scheduler/filtered/custom_study.rs \
    'allow_empty: false|NoMatchingCards' \
    "no-cards is an error (NoMatchingCards), not a successfully created empty deck"
check_present "A7" "$ANKI" rslib/src/scheduler/filtered/mod.rs \
    'fn empty_filtered_deck|fn rebuild_filtered_deck' \
    "backend-side empty/rebuild implementations exist (reachability from this repo is a separate question)"
check_present "A8" "$ANKI" proto/anki/scheduler.proto \
    'CustomStudyRequest|CramKind' \
    "the custom-study request shape is backend-defined (cram kinds, limits, tags)"
say ""

say "== AnkiDroid public provider (ankidroid/Anki-Android v2.24.1, api module) =="
AD_CONTRACT="api/src/main/java/com/ichi2/anki/FlashCardsContract.kt"
if [ -d "$AD/.git" ]; then
    check_present "D1" "$AD" "$AD_CONTRACT" \
        'DECK_DYN' \
        "filtered decks are only *observable* (read-only DECK_DYN column)"
    check_present "D2" "$AD" "$AD_CONTRACT" \
        'CONTENT_SELECTED_URI' \
        "deck selection is the only deck mutation the contract documents"
    check_absent "D3" "$AD" "$AD_CONTRACT" \
        'filtered[ _-]?deck.*(insert|create|rebuild|empty)|(insert|create|rebuild|empty).*filtered[ _-]?deck' \
        "no filtered-deck create/rebuild/empty endpoint exists in the public contract"
    check_absent "D4" "$AD" "$AD_CONTRACT" \
        'CONTENT_URI: Uri = Uri.withAppendedPath\(AUTHORITY_URI, "(decks_v2|filtered|dyn|schedule_v2)"\)' \
        "no additional hidden content URIs beyond the documented set"
else
    say "UNVERIFIED  D1-D4 (source not fetched)"
    UNVERIFIED=$((UNVERIFIED + 4))
fi
say ""

say "== AnkiConnect (the documented PC route, docs/ARCHITECTURE.md) =="
if [ -d "$AC/.git" ]; then
    ACTION_COUNT="$(git -C "$AC" grep -c '@util.api' -- plugin/__init__.py 2>/dev/null)"
    say "  @util.api actions exposed: ${ACTION_COUNT:-?}"
    check_absent "C1" "$AC" plugin/__init__.py \
        'def (customStudy|createFilteredDeck|rebuildFilteredDeck|emptyFilteredDeck|getQueuedCards)' \
        "no AnkiConnect action for custom study / filtered deck / queue"
else
    say "UNVERIFIED  C1 (source not fetched)"
    UNVERIFIED=$((UNVERIFIED + 1))
fi
say ""

say "== Summary =="
say "checks that still hold : $PASS"
say "checks that disagree  : $FAIL"
say "checks not verifiable  : $UNVERIFIED   (source missing — not a pass)"
say ""
say "Reminder: this is source evidence for docs/GATE_21L_REPORT.md §5, not a behavioural test."
say "GATE 20 remains the owner of every decision that would turn these rows into an implementation."

[ "$FAIL" -eq 0 ] && [ "$UNVERIFIED" -eq 0 ]
