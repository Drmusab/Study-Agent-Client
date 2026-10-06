#!/bin/bash
# Bootstrap the Gradle-free JVM harness in a sandbox where Maven Central and dl.google.com are
# unreachable but PyPI and GitHub are not. It is a *bootstrap*, not CI evidence: the results it
# produces are equivalence evidence (see README.md "Deviations").
#
#   HARNESS_WORK=/tmp/h HARNESS_JAVA=/path/to/java HARNESS_KOTLIN_JARS=/path/to/kotlin/jars \
#     tools/jvm-harness/bin/bootstrap-sandbox.sh
#
# Inputs it expects to exist (they are not committed; ~120 MB on the first run):
#   /tmp/pp/kk/run_kotlin_kernel/jars  Kotlin compiler + stdlib, from the PyPI wheel
#                                      `kotlin-jupyter-kernel` (it also ships
#                                      kotlinx-coroutines-core 1.10.2 and the serialization plugin)
#   /tmp/ap/android-34/android.jar     android.jar, from the GitHub repo `Sable/android-platforms`
#   /tmp/corp                          checkout of `Kotlin/kotlinx.coroutines` at tag 1.10.2
#
# To fetch those three inputs from a bare sandbox:
#   pip download --no-deps -d /tmp/pp jdk4py kotlin-jupyter-kernel
#   (cd /tmp/pp && mkdir -p jdk kk && (cd jdk && unzip -q ../jdk4py-*.whl) \
#      && (cd kk && unzip -q ../kotlin_jupyter_kernel-*.whl))
#   git clone --depth 1 --filter=blob:none --sparse https://github.com/Sable/android-platforms /tmp/ap
#   (cd /tmp/ap && git sparse-checkout set android-34)
#   git clone --depth 1 --branch 1.10.2 --filter=blob:none --sparse \
#     https://github.com/Kotlin/kotlinx.coroutines /tmp/corp
#   (cd /tmp/corp && git sparse-checkout set kotlinx-coroutines-test)
set -uo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$HARNESS_DIR/../.." && pwd)"
H="${HARNESS_WORK:-/tmp/h}"
JAVA="${HARNESS_JAVA:-/tmp/pp/jdk/jdk4py/java-runtime/bin/java}"
K="${HARNESS_KOTLIN_JARS:-/tmp/pp/kk/run_kotlin_kernel/jars}"
ANDROID_JAR="${HARNESS_ANDROID_JAR:-/tmp/ap/android-34/android.jar}"
COROUTINES_SRC="${HARNESS_COROUTINES_SRC:-/tmp/corp/kotlinx-coroutines-test}"
FAT="$K/kotlin-jupyter-kernel-0.19.0-944-all.jar"
export HARNESS_WORK="$H" HARNESS_JAVA="$JAVA" HARNESS_KOTLIN_JARS="$K"
mkdir -p "$H/libs" || exit 1

echo "== 1/7 plain jars"
cp "$ANDROID_JAR" "$H/libs/android-34.jar" || exit 1
cp "$K/kotlin-stdlib-2.3.10-RC.jar" "$K/annotations-13.0.jar" "$K/kotlin-reflect-2.3.10-RC.jar" \
   "$K/kotlin-script-runtime-2.4.0-dev-6891.jar" "$K/kotlinx-serialization-core-jvm-1.9.0.jar" \
   "$K/kotlinx-serialization-json-jvm-1.9.0.jar" "$H/libs/"

echo "== 2/7 kotlinx-coroutines-core $(unzip -p "$FAT" META-INF/kotlinx_coroutines_core.version 2>/dev/null)"
# The `.kotlin_module` entry is mandatory: without it the compiler cannot resolve the top-level
# factory functions (`MutableStateFlow`, `Mutex`, `withLock`, ...) and every shim fails to build.
python3 - "$FAT" "$H/libs/kotlinx-coroutines-core-jvm-1.10.2.jar" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
keep = ('kotlinx/coroutines/', 'META-INF/kotlinx_coroutines_core.version',
        'META-INF/kotlinx-coroutines-core.kotlin_module', 'META-INF/proguard/coroutines.pro')
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
    for e in zin.infolist():
        if e.filename.startswith(keep) and not e.is_dir():
            zout.writestr(e.filename, zin.read(e.filename))
print('  ->', dst)
PY

echo "== 3/7 serialization compiler plugin"
python3 - "$FAT" "$H/serplugin.jar" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as zin:
    wanted = [n for n in zin.namelist() if n.startswith('META-INF/services/')
              and 'serialization' in zin.read(n).decode('utf-8', 'replace')]
    with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
        for n in zin.namelist():
            if n.startswith('org/jetbrains/kotlinx/serialization/') and not n.endswith('/'):
                zout.writestr(n, zin.read(n))
        for n in wanted:
            zout.writestr(n, zin.read(n))
print('  -> services', wanted)
PY

echo "== 4/7 atomicfu shim (coroutines-test needs it at compile and run time)"
LCP="$(ls "$H"/libs/*.jar | grep -v android | tr '\n' ':')"
rm -rf "$H/atomicfu-out"
"$HARNESS_DIR/bin/kc" "$H/atomicfu-out" "$LCP" "$HARNESS_DIR/shims/atomicfu/AtomicFu.kt" 2>&1 | grep -E "error:" | head -5
python3 - "$H/atomicfu-out" "$H/libs/kotlinx-atomicfu-0.27.0-shim.jar" <<'PY'
import sys, os, zipfile
root, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(root):
        for f in files:
            p = os.path.join(base, f); z.write(p, os.path.relpath(p, root))
print('  ->', dst)
PY

echo "== 5/7 kotlinx-coroutines-test 1.10.2 from source"
python3 "$HARNESS_DIR/bin/prep-coroutines-test.py" "$COROUTINES_SRC" /tmp/ct-src || exit 1
LCP="$(ls "$H"/libs/*.jar | grep -v android | tr '\n' ':')"
rm -rf "$H/coroutines-test-out"
"$HARNESS_DIR/bin/kc" "$H/coroutines-test-out" "$LCP" \
  -opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi -opt-in=kotlinx.coroutines.InternalCoroutinesApi \
  $(find /tmp/ct-src -name '*.kt') 2>&1 | grep -E "error:" | head -20
# `runTest` resolves its exception handler through a ServiceLoader entry; without these resource
# files every runTest fails with "Exception handler was not found via a ServiceLoader".
mkdir -p "$H/coroutines-test-out/META-INF"
cp -r "$COROUTINES_SRC/jvm/resources/META-INF/." "$H/coroutines-test-out/META-INF/"
python3 - "$H/coroutines-test-out" "$H/libs/kotlinx-coroutines-test-jvm-1.10.2.jar" <<'PY'
import sys, os, zipfile
root, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(root):
        for f in files:
            p = os.path.join(base, f); z.write(p, os.path.relpath(p, root))
print('  ->', dst)
PY

echo "== 6/7 mockable android.jar"
rm -rf "$H/mockgen-out"
"$HARNESS_DIR/bin/kc" "$H/mockgen-out" "$FAT:$K/kotlin-stdlib-2.3.10-RC.jar" "$HARNESS_DIR/mockgen/MockGen.kt" 2>&1 | grep -E "error:" | head -5
"$JAVA" -cp "$H/mockgen-out:$FAT:$K/kotlin-stdlib-2.3.10-RC.jar" mockgen.MockGenKt \
  "$H/libs/android-34.jar" "$H/android-34-mockable.jar"

echo "== 7/7 shims + main + tests"
"$HARNESS_DIR/bin/build.sh" shims
"$HARNESS_DIR/bin/build.sh" all
echo
echo "run:  HARNESS_WORK=$H HARNESS_JAVA=$JAVA HARNESS_KOTLIN_JARS=$K $HARNESS_DIR/bin/run.sh [classRegex]"
