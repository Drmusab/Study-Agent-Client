#!/bin/bash
# Run compiled JUnit4 tests. usage: run.sh [classRegex]   (HARNESS_JAVA_OPTS=-Dharness.timeoutMs=15000)
. "$(dirname "$0")/env.sh"
CP="$H/test-out:$H/main-out:$H/shims-out:$(ls "$H"/libs/*.jar | grep -v android | tr '\n' ':')$H/android-34-mockable.jar"
# Gradle runs unit tests with the module directory as working directory; source-scanning tests rely on it.
cd "$REPO/app" && exec "$JAVA" -Xmx2g -XX:+UseSerialGC ${HARNESS_JAVA_OPTS:-} -cp "$CP" harness.RunnerKt "$H/test-out" "${1:-}"
