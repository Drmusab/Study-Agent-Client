#!/bin/bash
# One-shot bootstrap for the Gradle-free JVM harness in this sandbox (GATE 20 local checks).
# Not committed as part of the gate deliverable beyond its own file: it only wraps the documented
# recipe in bin/bootstrap-sandbox.sh with the fetch commands from that script's header.
set -uo pipefail
H=/tmp/h
mkdir -p /tmp/pp/jdk /tmp/pp/kk

echo "== fetch jdk4py + kotlin-jupyter-kernel (PyPI)"
pip download --no-deps -d /tmp/pp jdk4py kotlin-jupyter-kernel >/tmp/pp/pip.log 2>&1 || { tail -5 /tmp/pp/pip.log; exit 1; }
(cd /tmp/pp/jdk && unzip -qo ../jdk4py-*.whl) || exit 1
(cd /tmp/pp/kk && unzip -qo ../kotlin_jupyter_kernel-*.whl) || exit 1

echo "== fetch android-34/android.jar (GitHub)"
[ -f /tmp/ap/android-34/android.jar ] || {
  rm -rf /tmp/ap
  git clone --depth 1 --filter=blob:none --sparse https://github.com/Sable/android-platforms /tmp/ap || exit 1
  (cd /tmp/ap && git sparse-checkout set android-34) || exit 1
}

echo "== fetch kotlinx-coroutines-test 1.10.2 (GitHub)"
[ -d /tmp/corp/kotlinx-coroutines-test ] || {
  rm -rf /tmp/corp
  git clone --depth 1 --branch 1.10.2 --filter=blob:none --sparse https://github.com/Kotlin/kotlinx.coroutines /tmp/corp || exit 1
  (cd /tmp/corp && git sparse-checkout set kotlinx-coroutines-test) || exit 1
}

echo "== bootstrap-sandbox.sh"
HARNESS_WORK=$H \
HARNESS_JAVA=/tmp/pp/jdk/jdk4py/java-runtime/bin/java \
HARNESS_KOTLIN_JARS=/tmp/pp/kk/run_kotlin_kernel/jars \
HARNESS_ANDROID_JAR=/tmp/ap/android-34/android.jar \
HARNESS_COROUTINES_SRC=/tmp/corp/kotlinx-coroutines-test \
  /home/user/Study-Agent-Client/tools/jvm-harness/bin/bootstrap-sandbox.sh
echo "BOOTSTRAP EXIT: $?"
