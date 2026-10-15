#!/usr/bin/env bash
#
# Builds the pre-generated class-file fixtures that let a Java 17 JVM inspect Java 21/25 bytecode.
#
# The point of pre-generating them is that the low-JVM test job must not compile them itself:
# a job that compiles the input with its own JDK cannot prove anything about reading other
# versions. The build job runs this on the newest JDK and uploads the result; the 17/21 jobs
# download it and point the tests at it with
#   -Dnative.metadata.test.highVersionFixtures=<downloaded-dir>
#
# Not named ".class" artifacts are committed: fixtures are build output, produced from tracked
# sources under src/test/fixtures/highversion.
#
# Usage:
#   scripts/build-high-version-fixtures.sh [--java-home <jdk>] [--output <dir>]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

JAVA_HOME_OVERRIDE=""
OUTPUT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --java-home) JAVA_HOME_OVERRIDE="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -n "$JAVA_HOME_OVERRIDE" ]; then
  JAVA_HOME="$JAVA_HOME_OVERRIDE"
fi
if [ -z "${JAVA_HOME:-}" ]; then
  echo "ERROR: set JAVA_HOME or pass --java-home" >&2
  exit 1
fi

JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
for tool in "$JAVAC" "$JAR"; do
  [ -x "$tool" ] || { echo "ERROR: $tool is not executable" >&2; exit 1; }
done

OUTPUT="${OUTPUT:-${PROJECT_DIR}/target/high-version-fixtures}"
FIXTURE_SRC="${PROJECT_DIR}/src/test/fixtures/highversion"

echo "== building high-version fixtures =="
echo "java.home = $JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | sed 's/^/  /'
echo "output    = $OUTPUT"

rm -rf "$OUTPUT"
mkdir -p "$OUTPUT"

# 21/25 sources use APIs introduced after 17 (List.getFirst), so a --release 17 compile of the same
# file would not be honest input; HighVersionProbe is excluded from the 17 artifact.
ALL_SOURCES=()
while IFS= read -r f; do ALL_SOURCES+=("$f"); done < <(find "$FIXTURE_SRC" -name '*.java' | sort)
RELEASE17_SOURCES=()
for f in "${ALL_SOURCES[@]}"; do
  case "$(basename "$f")" in
    HighVersion21.java) ;;
    *) RELEASE17_SOURCES+=("$f") ;;
  esac
done

build_release() {
  local release="$1"; shift
  local classes="$OUTPUT/classes-${release}"
  local jar="$OUTPUT/highversion-${release}.jar"
  mkdir -p "$classes"
  echo "-- javac --release ${release} (${#} sources)"
  "$JAVAC" --release "$release" -d "$classes" "$@"
  "$JAR" --create --file "$jar" -C "$classes" .
  local major
  major=$(od -An -t u1 -j 6 -N 2 "$classes/example/HighVersionRecord.class" | awk '{print $1*256+$2}')
  echo "   ${jar} major=${major}"
}

support_release() {
  local release="$1"
  local probe="$OUTPUT/.probe-${release}"
  rm -rf "$probe"
  mkdir -p "$probe/src" "$probe/out"
  printf 'class Probe {}\n' > "$probe/src/Probe.java"
  if "$JAVAC" --release "$release" -d "$probe/out" "$probe/src/Probe.java" > "$probe/javac.log" 2>&1; then
    rm -rf "$probe"
    return 0
  fi
  rm -rf "$probe"
  return 1
}

if support_release 17; then
  build_release 17 "${RELEASE17_SOURCES[@]}"
else
  echo "ERROR: this JDK cannot compile --release 17" >&2
  exit 1
fi

for release in 21 25; do
  if support_release "$release"; then
    build_release "$release" "${ALL_SOURCES[@]}"
  else
    echo "NOT_RUN: $(basename "$JAVA_HOME") cannot compile --release ${release}; no fixture produced"
  fi
done

echo "done"
