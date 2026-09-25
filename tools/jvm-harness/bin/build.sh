#!/bin/bash
# Compile shims, app main (non-Compose), debug fixtures and JVM unit tests. Usage: build.sh [shims|main|test|all]
set -u
. "$(dirname "$0")/env.sh"
R="$REPO/app/src"; what=${1:-all}
pure() { for f in "$@"; do grep -qE "^import (androidx|android)\." "$f" || echo "$f"; done; }
if [ "$what" = shims ]; then
  LCP="$(ls "$H"/libs/*.jar | tr '\n' ':')"
  rm -rf "$H/shims-out"
  "$HARNESS_DIR/bin/kc" "$H/shims-out" "$LCP" $(find "$HARNESS_DIR"/shims/{datastore,okhttp,security,junit} "$HARNESS_DIR/runner" -name '*.kt')
  # Kotlin cannot star-import members of an `object`; stripping @Metadata makes Assert look like a Java class.
  "$JAVA" -cp "$H/mockgen-out:$K/kotlin-jupyter-kernel-0.19.0-944-all.jar:$K/kotlin-stdlib-2.3.10-RC.jar" \
    mockgen.StripMetadata "$H/shims-out/org/junit/Assert.class"
  exit
fi
if [ "$what" = main ] || [ "$what" = all ]; then
  cd "$R/main/java"
  SRC="$(find com -name '*.kt' | grep -v "/ui/\|/service/\|MainActivity\|StudyAgentApp\|/di/") $(pure $(find com/studyagent/client/ui -name '*.kt')) $(pure $(find "$R/debug/java" -name '*.kt'))"
  LCP="$(ls "$H"/libs/*.jar | tr '\n' ':')$H/shims-out"
  rm -rf "$H/main-out"
  "$HARNESS_DIR/bin/kc" "$H/main-out" "$LCP" -opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi $SRC "$HARNESS_DIR/shims/buildconfig/BuildConfig.kt" 2>&1 | grep -E "error:" > "$H/main-errors.txt"
  echo "main errors: $(wc -l < "$H/main-errors.txt") (files: $(echo $SRC | wc -w))"; head -30 "$H/main-errors.txt"
fi
if [ "$what" = test ] || [ "$what" = all ]; then
  cd "$R/test/java"
  TSRC=$(find com -name '*.kt' | grep -v "FakeAgentConnectionTest\|WebSocketIntegrationTest\|RatingControlsPolicyTest\|StudyPhaseMappingTest")
  LCP="$(ls "$H"/libs/*.jar | grep -v android | tr '\n' ':')$H/libs/android-34.jar:$H/shims-out:$H/main-out"
  rm -rf "$H/test-out"
  "$HARNESS_DIR/bin/kc" "$H/test-out" "$LCP" -Xfriend-paths="$H/main-out" -opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi $TSRC 2>&1 | grep -E "error:" > "$H/test-errors.txt"
  echo "test errors: $(wc -l < "$H/test-errors.txt") (files: $(echo $TSRC | wc -w))"; head -30 "$H/test-errors.txt"
fi
