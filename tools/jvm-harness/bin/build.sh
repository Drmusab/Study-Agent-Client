#!/bin/bash
# Compile shims, app main (non-Compose), the Compose/activity layer, debug fixtures and JVM
# unit tests. Usage: build.sh [shims|main|compose|test|all]
set -u
. "$(dirname "$0")/env.sh"
R="$REPO/app/src"; what=${1:-all}
pure() { for f in "$@"; do grep -qE "^import (androidx|android)\." "$f" || echo "$f"; done; }
# The Compose/android layer: every main file that imports a framework symbol `pure()` handed to the
# shims cannot satisfy. `androidx.lifecycle.*`/`androidx.annotation.*` are excluded because
# shims/lifecycle already covers those and `vmlayer()` compiles them into main-out — the two sets
# must stay disjoint or the same declaration is compiled twice.
composeset() { for f in $(find com -name '*.kt' | sort); do
  [ -n "$(grep -E '^import (androidx|android)\.' "$f" | grep -vE '^import androidx\.(lifecycle|annotation)\.' || true)" ] && echo "$f"
done; }
# The ViewModel layer: UI files whose only framework imports are androidx.lifecycle ones, which
# shims/lifecycle stubs. Without this, every ViewModel (GATE 16 card details included) and every
# ViewModel test silently drops out of the compile and "the state machine is untested" is masked.
vmlayer() { for f in $(find com/studyagent/client/ui -name '*.kt' | sort); do
  [ -n "$(grep -E '^import (androidx|android)\.' "$f" | grep -vE '^import androidx\.lifecycle\.' || true)" ] || echo "$f"
done; }
# Pure UI files: no framework import at all (presentation models, mappers, routes). The README always
# claimed these are built; without this they silently drop out of the compile, which is exactly how an
# untested mapper survives. `vmlayer()` skips them because its test is "has only lifecycle imports".
# AnkiRenderDiagnostics.kt is excluded: it is already in the Compose scope below, and compiling the
# same declaration twice would report a phantom redeclaration.
pureui() { for f in $(find com/studyagent/client/ui -name '*.kt' | sort); do
  case "$f" in *AnkiRenderDiagnostics.kt) continue;; esac
  grep -qE '^import (androidx|android)\.' "$f" || echo "$f"
done; }
if [ "$what" = shims ]; then
  LCP="$(ls "$H"/libs/*.jar | tr '\n' ':')"
  rm -rf "$H/shims-out"
  "$HARNESS_DIR/bin/kc" "$H/shims-out" "$LCP" $(find "$HARNESS_DIR"/shims/{datastore,okhttp,security,junit,lifecycle} "$HARNESS_DIR/runner" -name '*.kt')
  # Kotlin cannot star-import members of an `object`; stripping @Metadata makes Assert look like a Java class.
  "$JAVA" -cp "$H/mockgen-out:$K/kotlin-jupyter-kernel-0.19.0-944-all.jar:$K/kotlin-stdlib-2.3.10-RC.jar" \
    mockgen.StripMetadata "$H/shims-out/org/junit/Assert.class"
  exit
fi
if [ "$what" = main ] || [ "$what" = all ]; then
  cd "$R/main/java"
  SRC="$(find com -name '*.kt' | grep -v "/ui/\|/service/\|MainActivity\|StudyAgentApp\|/di/") $(vmlayer) $(pureui) $(pure $(find "$R/debug/java" -name '*.kt')) ${HARNESS_EXTRA_MAIN_SRC:-}"
  LCP="$(ls "$H"/libs/*.jar | tr '\n' ':')$H/shims-out"
  rm -rf "$H/main-out"
  "$HARNESS_DIR/bin/kc" "$H/main-out" "$LCP" -opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi $SRC "$HARNESS_DIR/shims/buildconfig/BuildConfig.kt" 2>&1 | grep -E "error:" > "$H/main-errors.txt"
  echo "main errors: $(wc -l < "$H/main-errors.txt") (files: $(echo $SRC | wc -w))"; head -30 "$H/main-errors.txt"
fi
if [ "$what" = compose ] || [ "$what" = all ]; then
  # STEP A — the API-shaped stubs themselves. A stub that does not compile cannot judge app code, so
  # stub errors are reported as a harness failure, separately from app errors.
  SCP="$(ls "$H"/libs/*.jar | tr '\n' ':')$H/shims-out"
  rm -rf "$H/compose-shims-out"
  python3 "$HARNESS_DIR/bin/gen-r.py" "$R/main/res" "$H/gen/R.kt" >/dev/null
  "$HARNESS_DIR/bin/kc" "$H/compose-shims-out" "$SCP" -opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi \
    $(find "$HARNESS_DIR"/shims/compose "$HARNESS_DIR"/shims/activity "$HARNESS_DIR"/shims/navigation -name '*.kt') "$H/gen/R.kt" \
    2>&1 | grep -E "error:" > "$H/compose-stub-errors.txt"
  if [ -s "$H/compose-stub-errors.txt" ]; then
    echo "COMPOSE STUBS BROKEN (harness defect, not app): $(wc -l < "$H/compose-stub-errors.txt")"
    head -20 "$H/compose-stub-errors.txt"
    exit 1
  fi
  # STEP B — the app layer that needs Compose/Activity/Navigation, compiled against main-out.
  # Default scope = the GATE 16 card/note-details UI closure plus the GATE 17 note-editor closure,
  # both of which must be error-free. Set GATE16_COMPOSE_ALL=1 to sweep every main file that imports a
  # framework symbol; that sweep is a diagnostic, not a gate (see
  # docs/GATE_16_CARD_NOTE_DETAILS.md, "Compose verification").
  cd "$R/main/java"
  GATE16_SET="com/studyagent/client/ui/screens/carddetails/CardDetailsScreen.kt
com/studyagent/client/ui/components/anki/AnkiCardRenderer.kt
com/studyagent/client/ui/components/anki/AnkiCardWebView.kt
com/studyagent/client/ui/components/anki/AnkiExternalLinkHandler.kt
com/studyagent/client/ui/components/anki/AnkiRenderDiagnostics.kt
com/studyagent/client/ui/components/anki/CleanAnkiCardView.kt
com/studyagent/client/ui/components/anki/CardMetadataSection.kt
com/studyagent/client/ui/components/anki/CardSchedulingSection.kt
com/studyagent/client/ui/components/anki/NoteFieldsSection.kt
com/studyagent/client/ui/components/StudyAgentTopBar.kt
com/studyagent/client/ui/components/AppPrimitives.kt
com/studyagent/client/ui/components/AppControls.kt
com/studyagent/client/ui/components/anki/NoteFieldEditor.kt
com/studyagent/client/ui/components/anki/TagsEditor.kt
com/studyagent/client/ui/components/anki/DeckSelector.kt
com/studyagent/client/ui/screens/editnote/EditNoteScreen.kt
$(find com/studyagent/client/ui/theme -name '*.kt')"
  if [ "${GATE16_COMPOSE_ALL:-0}" = 1 ]; then
    CSRC=$(composeset); SCOPE="all-framework-importing main files"
  else
    CSRC=$(echo "$GATE16_SET" | tr ' \n' '  '); SCOPE="GATE 16 UI closure"
  fi
  LCP="$SCP:$H/compose-shims-out:$H/main-out"
  rm -rf "$H/compose-out"
  # `-Xfriend-paths` is what makes the split honest: under Gradle the whole `app/src/main/java` tree
  # is one module, so `internal` declarations are visible across it. Without this the compose step
  # would report phantom "cannot access internal" errors for perfectly good app code.
  "$HARNESS_DIR/bin/kc" "$H/compose-out" "$LCP" -Xfriend-paths="$H/main-out" \
      -opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi $CSRC \
    2>&1 | grep -E "error:" > "$H/compose-errors.txt"
  echo "compose errors: $(wc -l < "$H/compose-errors.txt") [scope: $SCOPE] (files: $(echo $CSRC | wc -w), stub files: $(find "$HARNESS_DIR"/shims/{compose,activity,navigation} -name '*.kt' | wc -l))"
  head -30 "$H/compose-errors.txt"
fi
if [ "$what" = test ] || [ "$what" = all ]; then
  cd "$R/test/java"
  TSRC=$(find com -name '*.kt' | grep -v "FakeAgentConnectionTest\|WebSocketIntegrationTest\|RatingControlsPolicyTest\|StudyPhaseMappingTest")
  LCP="$(ls "$H"/libs/*.jar | grep -v android | tr '\n' ':')$H/libs/android-34.jar:$H/shims-out:$H/main-out"
  rm -rf "$H/test-out"
  "$HARNESS_DIR/bin/kc" "$H/test-out" "$LCP" -Xfriend-paths="$H/main-out" -opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi $TSRC 2>&1 | grep -E "error:" > "$H/test-errors.txt"
  echo "test errors: $(wc -l < "$H/test-errors.txt") (files: $(echo $TSRC | wc -w))"; head -30 "$H/test-errors.txt"
fi
