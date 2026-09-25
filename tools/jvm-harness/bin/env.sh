# Shared settings. Override any of these in the environment.
HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$HARNESS_DIR/../.." && pwd)"
H="${HARNESS_WORK:-/tmp/h}"                                   # jars + build outputs (not in git)
JAVA="${HARNESS_JAVA:-/tmp/pp/jdk/jdk4py/java-runtime/bin/java}" # any JDK/JRE >= 17
K="${HARNESS_KOTLIN_JARS:-/tmp/pp/kk/run_kotlin_kernel/jars}"   # dir holding the Kotlin compiler jars
