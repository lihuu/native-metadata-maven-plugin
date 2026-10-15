#!/usr/bin/env bash
#
# A/B/C verification for the plain-native sample.
#
#   A baseline   untouched source configuration, plugin pruning disabled, native build + run
#   B pruned     plugin pruning applied, native build + run, must match A and must have changed
#   C negative   a copy of B with one *required* registration removed from every effective native
#                input; the same run must now fail because of the missing registration
#
# The script never rewrites src/main/resources for A/B, and it enumerates every native-image
# metadata file in the native input when checking that C really removed the only copy.
#
# Usage:
#   verify.sh --java-home <graalvm-home> --maven-home <maven-dir> [options]
#
# Options:
#   --plugin-version <v>   plugin version to consume (default: 1.0.0)
#   --repo <dir>           use this Maven local repository (isolated staging)
#   --evidence <dir>       evidence directory (default: <project>/target/plain-native-evidence)
#   --native-image <path>  explicit native-image executable (default: <java-home>/bin/native-image)
#   --expected <string>    expected METHOD_RESULT value (default: hello-via-field:world)
#   --timeout-maven <sec>  per Maven invocation (default 300)
#   --timeout-native <sec> per native-image build (default 1200)
#   --timeout-run <sec>    per application run (default 60)
#   --cases <a,b,c>        which groups to run (default a,b,c)
#   --skip-native-build    only run the Maven side of A/B and skip native-image
#
# Exit codes: 0 all requested groups behaved as expected, 1 otherwise.

set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

JAVA_HOME_ARG=""
MAVEN_HOME_ARG=""
PLUGIN_VERSION="1.0.0"
REPO_ARG=""
EVIDENCE=""
NATIVE_IMAGE_ARG=""
EXPECTED="hello-via-field:world"
TIMEOUT_MAVEN=300
TIMEOUT_NATIVE=1200
TIMEOUT_RUN=60
CASES="a,b,c"
EXPECT_PLUGIN_SHA=""
SKIP_NATIVE="false"

while [ $# -gt 0 ]; do
  case "$1" in
    --java-home) JAVA_HOME_ARG="$2"; shift 2 ;;
    --maven-home) MAVEN_HOME_ARG="$2"; shift 2 ;;
    --plugin-version) PLUGIN_VERSION="$2"; shift 2 ;;
    --repo) REPO_ARG="$2"; shift 2 ;;
    --evidence) EVIDENCE="$2"; shift 2 ;;
    --native-image) NATIVE_IMAGE_ARG="$2"; shift 2 ;;
    --expected) EXPECTED="$2"; shift 2 ;;
    --timeout-maven) TIMEOUT_MAVEN="$2"; shift 2 ;;
    --timeout-native) TIMEOUT_NATIVE="$2"; shift 2 ;;
    --timeout-run) TIMEOUT_RUN="$2"; shift 2 ;;
    --cases) CASES="$2"; shift 2 ;;
    --skip-native-build) SKIP_NATIVE="true"; shift ;;
    --expect-plugin-sha256) EXPECT_PLUGIN_SHA="$2"; shift 2 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

[ -n "$JAVA_HOME_ARG" ] || { echo "ERROR: --java-home is required" >&2; exit 2; }
[ -d "$JAVA_HOME_ARG" ] || { echo "ERROR: --java-home does not exist: $JAVA_HOME_ARG" >&2; exit 2; }
export JAVA_HOME="$JAVA_HOME_ARG"

if [ -n "$MAVEN_HOME_ARG" ]; then
  MVN="$MAVEN_HOME_ARG/bin/mvn"
else
  MVN="$(command -v mvn)"
fi
[ -x "$MVN" ] || { echo "ERROR: mvn not found ($MVN)" >&2; exit 2; }

NATIVE_IMAGE="${NATIVE_IMAGE_ARG:-$JAVA_HOME/bin/native-image}"
EVIDENCE="${EVIDENCE:-$PROJECT_DIR/target/plain-native-evidence}"
mkdir -p "$EVIDENCE"

# Kept as plain strings: /bin/bash on macOS is 3.2, where expanding an empty array under
# `set -u` aborts the script.
MVN_COMMON="-B -ntp -Dnative.metadata.version=$PLUGIN_VERSION"
if [ -n "$REPO_ARG" ]; then
  MVN_COMMON="$MVN_COMMON -Dmaven.repo.local=$REPO_ARG"
fi

failures=0
RESULTS=""
PYTHON="$(command -v python3 || command -v python)"
[ -n "$PYTHON" ] || { echo "ERROR: python3 is required to inspect metadata JSON" >&2; exit 2; }

log() { echo "[verify-plain] $*"; }

# Portable timeout that kills the whole process tree on expiry.
# Portable SHA-256: GNU coreutils and BSD/macOS disagree about the tool name.
sha256() { # file
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

# The plugin under test must already be in the repository the child Maven uses. If it is not, Maven
# would download the *published* artifact with the same coordinates, and a run against a different
# binary would look exactly like a run against this one.
require_staged_plugin() {
  if [ -z "$REPO_ARG" ]; then
    echo "WARNING: no --repo given: the child Maven may resolve the plugin from the network." >&2
    echo "         Pass --repo <dir> containing the artifact under test to make this meaningful." >&2
    return 0
  fi
  local staged="$REPO_ARG/io/github/lihuu/native-metadata-maven-plugin/$PLUGIN_VERSION/native-metadata-maven-plugin-$PLUGIN_VERSION.jar"
  if [ ! -f "$staged" ]; then
    log "FAILED: the plugin is not staged in $REPO_ARG; refusing to build, because Maven would download the published artifact of the same coordinates"
    failures=$((failures + 1)); record "plugin-not-staged-FAIL"
    return 1
  fi
  local staged_sha
  staged_sha=$(sha256 "$staged")
  echo "[verify] staged plugin $staged sha256=$staged_sha"
  if [ -n "$EXPECT_PLUGIN_SHA" ] && [ "$staged_sha" != "$EXPECT_PLUGIN_SHA" ]; then
    log "FAILED: staged plugin hash $staged_sha != expected $EXPECT_PLUGIN_SHA"
    failures=$((failures + 1)); record "plugin-hash-mismatch-FAIL"
    return 1
  fi
  return 0
}

run_with_timeout() {
  local seconds="$1"; shift
  local logfile="$1"; shift
  local status=0
  "$@" > "$logfile" 2>&1 &
  local pid=$!
  local waited=0
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$waited" -ge "$seconds" ]; then
      log "TIMEOUT after ${seconds}s: $*"
      pkill -TERM -P "$pid" 2>/dev/null
      kill -TERM "$pid" 2>/dev/null
      sleep 2
      pkill -KILL -P "$pid" 2>/dev/null
      kill -KILL "$pid" 2>/dev/null
      wait "$pid" 2>/dev/null
      echo "TIMEOUT: exceeded ${seconds}s" >> "$logfile"
      return 124
    fi
    sleep 1
    waited=$((waited + 1))
  done
  wait "$pid"
  status=$?
  return $status
}

native_input_configs() {
  # Every native-image metadata file the native build will read: the classes directory only,
  # because this sample has no runtime dependencies.
  find "$1" -path '*META-INF/native-image*' -name '*.json' -type f | sort
}

build_native() {
  local work="$1" name="$2" logfile="$3"
  if [ "$SKIP_NATIVE" = "true" ]; then
    echo "SKIPPED (--skip-native-build)" > "$logfile"
    return 0
  fi
  if [ ! -x "$NATIVE_IMAGE" ]; then
    echo "MISSING native-image at $NATIVE_IMAGE" > "$logfile"
    return 127
  fi
  run_with_timeout "$TIMEOUT_NATIVE" "$logfile" \
    "$NATIVE_IMAGE" --no-fallback -cp "$work/target/classes" -o "$work/target/$name" com.example.plain.App
}

run_app() {
  local work="$1" name="$2" logfile="$3"
  shift 3
  if [ "$SKIP_NATIVE" = "true" ]; then
    echo "SKIPPED (--skip-native-build)" > "$logfile"
    return 0
  fi
  run_with_timeout "$TIMEOUT_RUN" "$logfile" "$work/target/$name" "$@"
}

# Checks the pruned Greeting entry in isolation; other entries legitimately keep broad flags.
check_pruned_greeting() {
  local config="$1"
  "$PYTHON" - "$config" <<'PY'
import json, sys
entries = json.load(open(sys.argv[1]))
greeting = [e for e in entries if e.get("name") == "com.example.plain.Greeting"]
assert len(greeting) == 1, f"expected exactly one Greeting entry, got {len(greeting)}"
g = greeting[0]
assert not any(k.startswith("allDeclared") or k.startswith("allPublic") for k in g), g
methods = {(m["name"], tuple(m.get("parameterTypes", []))) for m in g.get("methods", [])}
assert ("describe", ("java.lang.String[]",)) in methods, methods
assert ("<init>", ("java.lang.String",)) in methods, methods
assert [f["name"] for f in g.get("fields", [])] == ["message"], g.get("fields")
PY
}

expected_result_line() {
  echo "METHOD_RESULT=$EXPECTED"
}

contains() { grep -qF "$2" "$1"; }

record() {
  if [ -z "$RESULTS" ]; then RESULTS="$1"; else RESULTS="$RESULTS,$1"; fi
}

# ---------------------------------------------------------------- A: baseline
run_case_a() {
  local dir="$EVIDENCE/A-baseline"
  mkdir -p "$dir"
  log "A: untouched configuration, pruning disabled"
  (
    cd "$PROJECT_DIR" || exit 1
    run_with_timeout "$TIMEOUT_MAVEN" "$dir/maven-baseline.log" \
      "$MVN" $MVN_COMMON -Pbaseline clean package
  )
  local status=$?
  if [ "$status" -ne 0 ]; then
    log "A FAILED: Maven baseline build exited $status"
    failures=$((failures + 1)); record "A-baseline-maven-FAIL"; return
  fi
  cp "$PROJECT_DIR/target/classes/META-INF/native-image/com.example.plain/reflect-config.json" "$dir/consumed-config.json"
  if build_native "$PROJECT_DIR" plain-native-a "$dir/native-build.log"; then
    if run_app "$PROJECT_DIR" plain-native-a "$dir/run.log"; then
      cp "$PROJECT_DIR/target/plain-native-a" "$dir/binary" 2>/dev/null
      if contains "$dir/run.log" "APP_OK" && contains "$dir/run.log" "$(expected_result_line)"; then
        record "A-baseline-PASS"; log "A PASS"
      else
        failures=$((failures + 1)); record "A-baseline-run-FAIL"; log "A FAILED: unexpected output"
      fi
    else
      failures=$((failures + 1)); record "A-baseline-run-FAIL"; log "A FAILED: native run failed"
    fi
  else
    failures=$((failures + 1)); record "A-baseline-native-FAIL"; log "A FAILED: native-image build failed"
  fi
}

# ---------------------------------------------------------------- B: pruned
run_case_b() {
  local dir="$EVIDENCE/B-pruned"
  mkdir -p "$dir"
  log "B: plugin pruning applied"
  rm -rf "$PROJECT_DIR/target/native-metadata"
  (
    cd "$PROJECT_DIR" || exit 1
    run_with_timeout "$TIMEOUT_MAVEN" "$dir/maven-prune.log" \
      "$MVN" $MVN_COMMON clean package
  )
  local status=$?
  if [ "$status" -ne 0 ]; then
    log "B FAILED: Maven build exited $status"
    failures=$((failures + 1)); record "B-pruned-maven-FAIL"; return
  fi
  cp "$PROJECT_DIR/target/classes/META-INF/native-image/com.example.plain/reflect-config.json" "$dir/consumed-config.json"
  cp "$PROJECT_DIR/target/native-metadata/changelog.json" "$dir/changelog.json" 2>/dev/null
  cp "$PROJECT_DIR/target/native-metadata/report.json" "$dir/report.json" 2>/dev/null

  if ! contains "$dir/consumed-config.json" '"describe"'; then
    failures=$((failures + 1)); record "B-pruned-no-narrowing-FAIL"; log "B FAILED: pruned config lost the describe method"
    return
  fi
  if ! check_pruned_greeting "$dir/consumed-config.json"; then
    failures=$((failures + 1)); record "B-pruned-still-broad-FAIL"; log "B FAILED: Greeting entry was not narrowed as the rule requires"
    return
  fi
  if ! contains "$dir/consumed-config.json" 'com.example.plain.Unused'; then
    failures=$((failures + 1)); record "B-pruned-overreach-FAIL"; log "B FAILED: unrelated registration was removed"
    return
  fi
  if ! contains "$dir/changelog.json" '"NARROWED"' || ! contains "$dir/changelog.json" '"DELETED"'; then
    failures=$((failures + 1)); record "B-pruned-changelog-FAIL"; log "B FAILED: expected both a narrowing and a duplicate deletion"
    return
  fi

  if build_native "$PROJECT_DIR" plain-native-b "$dir/native-build.log"; then
    if run_app "$PROJECT_DIR" plain-native-b "$dir/run.log"; then
      cp "$PROJECT_DIR/target/plain-native-b" "$dir/binary" 2>/dev/null
      if contains "$dir/run.log" "APP_OK" && contains "$dir/run.log" "$(expected_result_line)"; then
        record "B-pruned-PASS"; log "B PASS"
      else
        failures=$((failures + 1)); record "B-pruned-run-FAIL"; log "B FAILED: unexpected output"
      fi
    else
      failures=$((failures + 1)); record "B-pruned-run-FAIL"; log "B FAILED: native run failed"
    fi
  else
    failures=$((failures + 1)); record "B-pruned-native-FAIL"; log "B FAILED: native-image build failed"
  fi
}

# ---------------------------------------------------------------- C: negative
run_case_c() {
  local dir="$EVIDENCE/C-missing-registration"
  local work="$dir/work"
  mkdir -p "$dir"
  log "C: copy of B with one required registration removed everywhere"
  rm -rf "$work"
  mkdir -p "$work"
  # Copy the project outside the plugin checkout so nothing else can supply the registration.
  (cd "$PROJECT_DIR" && tar --exclude ./target -cf - .) | (cd "$work" && tar -xf -)

  (
    cd "$work" || exit 1
    run_with_timeout "$TIMEOUT_MAVEN" "$dir/maven-prune.log" \
      "$MVN" $MVN_COMMON clean package
  )
  if [ $? -ne 0 ]; then
    log "C FAILED: setup Maven build exited non-zero"
    failures=$((failures + 1)); record "C-copy-maven-FAIL"; return
  fi

  # Remove the required "describe" registration from every effective native input, and drop the
  # source copy as well so that no other copy of the permission exists anywhere in the project.
  rm -rf "$work/src/main/resources/META-INF/native-image"
  "$PYTHON" - "$work/target/classes" <<'PY' || { failures=$((failures + 1)); record "C-remove-FAIL"; log "C FAILED: could not remove registration"; return; }
import json, sys, pathlib
root = pathlib.Path(sys.argv[1])
removed = 0
for path in list(root.rglob("reflect-config.json")) + list(root.rglob("reachability-metadata.json")):
    data = json.loads(path.read_text())
    entries = data.get("reflection", []) if isinstance(data, dict) else data
    for entry in entries:
        if "methods" in entry:
            entry["methods"] = [
                m for m in entry["methods"]
                if not (m.get("name") == "describe"
                        and m.get("parameterTypes") == ["java.lang.String[]"])
            ]
    if isinstance(data, dict):
        data["reflection"] = entries
    path.write_text(json.dumps(data, indent=2) + "\n")
    removed += 1
print(f"rewrote {removed} metadata files")
PY

  # No copy may remain in the native input or in the sources. Only metadata files are scanned:
  # the sample's own Java sources legitimately contain the string "describe".
  local leftovers
  leftovers=$(find "$work/target/classes" "$work/src" -type f \( -name 'reflect-config.json' -o -name 'reachability-metadata.json' -o -name 'reflection-config.json' \) 2>/dev/null | xargs grep -l '"describe"' 2>/dev/null)
  if [ -n "$leftovers" ]; then
    log "C FAILED: a copy of the describe registration survived in the native input:"
    echo "$leftovers" | tee -a "$dir/leftovers.txt"
    failures=$((failures + 1)); record "C-leftover-FAIL"; return
  fi
  native_input_configs "$work/target" | while read -r f; do
    echo "--- $f" >> "$dir/native-input-configs.txt"
    cat "$f" >> "$dir/native-input-configs.txt"
  done

  if build_native "$work" plain-native-c "$dir/native-build.log"; then
    if run_app "$work" plain-native-c "$dir/run.log"; then
      log "C FAILED: the dynamic operation still succeeded without the registration"
      failures=$((failures + 1)); record "C-negative-FAIL"; return
    fi
    if grep -qiE "MissingReflectionRegistration|NoSuchMethodException|not registered|missing registration|reflect-config" "$dir/run.log"; then
      record "C-negative-PASS"; log "C PASS (failed for a registration-related reason)"
    else
      log "C FAILED: run failed, but not for a registration-related reason"
      failures=$((failures + 1)); record "C-negative-wrong-reason-FAIL"
    fi
  else
    if grep -qiE "MissingReflectionRegistration|NoSuchMethodException|not registered|missing registration|reflect-config" "$dir/native-build.log"; then
      record "C-negative-PASS"; log "C PASS (native build refused the missing registration)"
    else
      log "C FAILED: native build failed for an unrelated reason"
      failures=$((failures + 1)); record "C-native-wrong-reason-FAIL"
    fi
  fi
}

if [ "$SKIP_NATIVE" = "true" ]; then
  log "WARNING: --skip-native-build set: native groups are NOT executed"
fi

if [ -n "${REPO_ARG:-}" ] || [ -n "${EXPECT_PLUGIN_SHA:-}" ]; then
  require_staged_plugin || exit 1
fi

case ",$CASES," in *,a,*) run_case_a ;; esac
case ",$CASES," in *,b,*) run_case_b ;; esac
case ",$CASES," in *,c,*) run_case_c ;; esac

{
  echo "{"
  echo "  \"case\": \"plain-native\","
  echo "  \"pluginVersion\": \"$PLUGIN_VERSION\","
  echo "  \"javaHome\": \"$JAVA_HOME\","
  echo "  \"maven\": \"$MVN\","
  echo "  \"nativeImage\": \"$NATIVE_IMAGE\","
  echo "  \"skipNativeBuild\": $SKIP_NATIVE,"
  echo "  \"groups\": \"$CASES\","
  echo "  \"outcomes\": \"$RESULTS\","
  echo "  \"failures\": $failures"
  echo "}"
} > "$EVIDENCE/result.json"

log "evidence: $EVIDENCE"
log "outcomes: ${RESULTS:-<none>}"
if [ "$failures" -ne 0 ]; then
  log "FAILED ($failures)"
  exit 1
fi
log "PASS"
exit 0
