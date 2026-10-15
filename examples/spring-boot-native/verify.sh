#!/usr/bin/env bash
#
# A/B/C verification for the Spring Boot native sample.
#
#   A baseline   Spring AOT + native build with the plugin disabled; real HTTP requests
#   B pruned     Spring AOT + native build with the plugin enabled; the same HTTP requests must
#                return the same payloads, the self-owned duplicate must have been removed, and the
#                sources plus the set of AOT-generated files must be identical to A
#   C negative   a copy of the project with the RuntimeHints registration and the self-owned
#                duplicate removed everywhere; the dynamic request must now fail
#
# The script inspects every native-image metadata file in the native input instead of trusting the
# boot JAR, and it starts and stops the server itself so HTTP behaviour is actually exercised.
#
# Usage:
#   verify.sh --java-home <graalvm-home> --maven-home <maven-dir> [options]
#
# Options:
#   --plugin-version <v>   plugin version to consume (default: 1.0.0)
#   --repo <dir>           isolated Maven local repository
#   --evidence <dir>       evidence directory (default: <project>/target/spring-native-evidence)
#   --port <port>          HTTP port for the sample server (default: 18081)
#   --timeout-maven <sec>  per Maven invocation (default: 1800)
#   --timeout-start <sec>  wait for the server to answer /api/health (default: 90)
#   --timeout-request <sec> per HTTP request (default 10)
#   --cases <a,b,c>        which groups to run (default a,b,c)
#
# Exit codes: 0 all requested groups behaved as expected, 1 otherwise.

set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

JAVA_HOME_ARG=""
MAVEN_HOME_ARG=""
PLUGIN_VERSION="1.0.0"
REPO_ARG=""
EVIDENCE=""
PORT=18081
TIMEOUT_MAVEN=1800
TIMEOUT_START=90
TIMEOUT_REQUEST=10
CASES="a,b,c"
EXPECT_PLUGIN_SHA=""

while [ $# -gt 0 ]; do
  case "$1" in
    --java-home) JAVA_HOME_ARG="$2"; shift 2 ;;
    --maven-home) MAVEN_HOME_ARG="$2"; shift 2 ;;
    --plugin-version) PLUGIN_VERSION="$2"; shift 2 ;;
    --repo) REPO_ARG="$2"; shift 2 ;;
    --evidence) EVIDENCE="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --timeout-maven) TIMEOUT_MAVEN="$2"; shift 2 ;;
    --timeout-start) TIMEOUT_START="$2"; shift 2 ;;
    --timeout-request) TIMEOUT_REQUEST="$2"; shift 2 ;;
    --cases) CASES="$2"; shift 2 ;;
    --expect-plugin-sha256) EXPECT_PLUGIN_SHA="$2"; shift 2 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

[ -n "$JAVA_HOME_ARG" ] && [ -d "$JAVA_HOME_ARG" ] || { echo "ERROR: --java-home is required" >&2; exit 2; }
export JAVA_HOME="$JAVA_HOME_ARG"
if [ -n "$MAVEN_HOME_ARG" ]; then MVN="$MAVEN_HOME_ARG/bin/mvn"; else MVN="$(command -v mvn)"; fi
[ -x "$MVN" ] || { echo "ERROR: mvn not found" >&2; exit 2; }

EVIDENCE="${EVIDENCE:-$PROJECT_DIR/target/spring-native-evidence}"
mkdir -p "$EVIDENCE"
# $EVIDENCE and everything derived from it are used inside `( cd <work> && ... )` subshells below,
# where a relative --evidence would resolve against the work directory instead. Same failure mode
# as scripts/verify-compatibility.sh; make it absolute once, here.
EVIDENCE="$(cd "$EVIDENCE" && pwd)"

PYTHON="$(command -v python3 || command -v python)"
[ -n "$PYTHON" ] || { echo "ERROR: python3 is required" >&2; exit 2; }

MVN_COMMON="-B -ntp -Dnative.metadata.version=$PLUGIN_VERSION -DskipTests"
if [ -n "$REPO_ARG" ]; then MVN_COMMON="$MVN_COMMON -Dmaven.repo.local=$REPO_ARG"; fi

failures=0
RESULTS=""
BINARY_NAME="spring-boot-native-sample"

log() { echo "[verify-spring] $*"; }
record() { if [ -z "$RESULTS" ]; then RESULTS="$1"; else RESULTS="$RESULTS,$1"; fi; }

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
  "$@" > "$logfile" 2>&1 &
  local pid=$!
  local waited=0
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$waited" -ge "$seconds" ]; then
      pkill -TERM -P "$pid" 2>/dev/null; kill -TERM "$pid" 2>/dev/null; sleep 2
      pkill -KILL -P "$pid" 2>/dev/null; kill -KILL "$pid" 2>/dev/null
      wait "$pid" 2>/dev/null
      echo "TIMEOUT: exceeded ${seconds}s" >> "$logfile"
      return 124
    fi
    sleep 1; waited=$((waited + 1))
  done
  wait "$pid"
}

# Starts the native binary, exercises HTTP, always stops it again.
run_http_suite() {
  local work="$1" dir="$2" label="$3"
  local binary="$work/target/$BINARY_NAME"
  if [ ! -x "$binary" ]; then
    echo "missing native binary $binary" > "$dir/http.log"
    return 1
  fi
  "$binary" "--server.port=$PORT" "--spring.profiles.active=" > "$dir/server.log" 2>&1 &
  local server_pid=$!
  local waited=0
  local up="false"
  while [ "$waited" -lt "$TIMEOUT_START" ]; do
    if curl -fsS --max-time "$TIMEOUT_REQUEST" "http://127.0.0.1:$PORT/api/health" > /dev/null 2>&1; then
      up="true"; break
    fi
    kill -0 "$server_pid" 2>/dev/null || break
    sleep 1; waited=$((waited + 1))
  done
  if [ "$up" != "true" ]; then
    echo "server did not become healthy within ${TIMEOUT_START}s" > "$dir/http.log"
    kill -TERM "$server_pid" 2>/dev/null; sleep 1; kill -KILL "$server_pid" 2>/dev/null
    wait "$server_pid" 2>/dev/null
    return 1
  fi

  local status=0
  {
    echo "== GET /api/greeting?name=Ada"
    curl -fsS --max-time "$TIMEOUT_REQUEST" "http://127.0.0.1:$PORT/api/greeting?name=Ada" || status=1
    echo
    echo "== POST /api/greeting {name:Ada}"
    curl -fsS --max-time "$TIMEOUT_REQUEST" -H 'Content-Type: application/json' \
      -d '{"name":"Ada"}' "http://127.0.0.1:$PORT/api/greeting" || status=1
    echo
    echo "== GET /api/dynamic?method=describe&argument=ok"
    curl -fsS --max-time "$TIMEOUT_REQUEST" \
      "http://127.0.0.1:$PORT/api/dynamic?method=describe&argument=ok" || status=1
    echo
  } > "$dir/http-body.txt" 2> "$dir/http.log"

  kill -TERM "$server_pid" 2>/dev/null
  sleep 2
  kill -KILL "$server_pid" 2>/dev/null
  wait "$server_pid" 2>/dev/null

  # Normalise: strip the health payload and keep the three JSON bodies in order.
  "$PYTHON" - "$dir/http-body.txt" "$dir/http-normalised.json" <<'PY'
import json, sys
lines = [l.strip() for l in open(sys.argv[1]) if l.strip() and not l.startswith("==")]
assert len(lines) == 3, f"expected 3 JSON responses, got {len(lines)}: {lines}"
bodies = [json.loads(l) for l in lines]
json.dump(bodies, open(sys.argv[2], "w"), sort_keys=True, indent=1)
print(json.dumps(bodies, sort_keys=True))
PY
  local py_status=$?
  [ "$py_status" -eq 0 ] || status=1
  return $status
}

snapshot_metadata() {
  # Two things the plugin promises not to touch, checked in two different ways.
  #
  # src/ is hashed by content: "the plugin does not rewrite the project" is a byte-level promise.
  #
  # target/spring-aot/ is only listed. Spring AOT regenerates it on every Maven run, and its
  # Application__BeanFactoryRegistrations output is not byte-stable across runs of the *same*
  # configuration - in CI, one GraalVM 21 job produced two different hashes for it across three runs
  # while the untouched src/ stayed identical - so comparing those bytes between A and B reports the
  # AOT's own variance as a plugin regression. The file set is stable, and the plugin must not add
  # or remove anything in a directory it only reads.
  local work="$1" out="$2"
  local src="$out/source-hashes.txt" aot="$out/aot-file-list.txt"
  : > "$src"
  : > "$aot"
  find "$work/src" -type f 2>/dev/null | sort | while read -r f; do
    echo "$(sha256 "$f")  ${f#$work/}" >> "$src"
  done
  find "$work/target/spring-aot" -type f 2>/dev/null | sort | while read -r f; do
    echo "${f#$work/}" >> "$aot"
  done
}

check_native_input_has_registration() {
  local work="$1"
  "$PYTHON" - "$work/target" <<'PY'
import json, sys, pathlib
root = pathlib.Path(sys.argv[1])
found = []
for path in root.rglob("reflect-config.json"):
    try:
        data = json.loads(path.read_text())
    except Exception:
        continue
    entries = data.get("reflection", []) if isinstance(data, dict) else data
    for entry in entries:
        if entry.get("name") == "com.example.boot.DynamicTarget":
            found.append((str(path), entry))
for path, entry in found:
    print(f"{path}: {json.dumps(entry)}")
sys.exit(0 if found else 1)
PY
}

run_case_a() {
  local dir="$EVIDENCE/A-baseline"
  mkdir -p "$dir"
  log "A: Spring AOT + native, plugin pruning disabled"
  ( cd "$PROJECT_DIR" && run_with_timeout "$TIMEOUT_MAVEN" "$dir/maven.log" \
      "$MVN" $MVN_COMMON -Pbaseline -Pnative clean package )
  if [ $? -ne 0 ]; then failures=$((failures+1)); record "A-maven-FAIL"; log "A FAILED: maven"; return; fi
  if check_native_input_has_registration "$PROJECT_DIR" > "$dir/native-input-registration.txt"; then
    log "A: AOT registration present in the native input"
  else
    failures=$((failures+1)); record "A-aot-missing-FAIL"; log "A FAILED: no AOT registration in the native input"; return
  fi
  snapshot_metadata "$PROJECT_DIR" "$dir"
  cp "$PROJECT_DIR/target/classes/META-INF/native-image/com.example.boot/reflect-config.json" "$dir/self-owned-config.json" 2>/dev/null
  if run_http_suite "$PROJECT_DIR" "$dir" A; then
    record "A-baseline-PASS"; log "A PASS"
  else
    failures=$((failures+1)); record "A-http-FAIL"; log "A FAILED: HTTP suite"
  fi
}

run_case_b() {
  local dir="$EVIDENCE/B-pruned"
  mkdir -p "$dir"
  log "B: Spring AOT + native, plugin pruning enabled"
  ( cd "$PROJECT_DIR" && run_with_timeout "$TIMEOUT_MAVEN" "$dir/maven.log" \
      "$MVN" $MVN_COMMON -Pnative clean package )
  if [ $? -ne 0 ]; then failures=$((failures+1)); record "B-maven-FAIL"; log "B FAILED: maven"; return; fi
  cp "$PROJECT_DIR/target/native-metadata/changelog.json" "$dir/changelog.json" 2>/dev/null
  cp "$PROJECT_DIR/target/native-metadata/report.json" "$dir/report.json" 2>/dev/null
  cp "$PROJECT_DIR/target/classes/META-INF/native-image/com.example.boot/reflect-config.json" "$dir/self-owned-config.json" 2>/dev/null
  snapshot_metadata "$PROJECT_DIR" "$dir"

  if ! grep -q '"DELETED"' "$dir/changelog.json" 2>/dev/null; then
    failures=$((failures+1)); record "B-no-dedup-FAIL"; log "B FAILED: expected the self-owned duplicate to be removed"; return
  fi
  if ! grep -qi 'generated' "$dir/changelog.json" 2>/dev/null; then
    failures=$((failures+1)); record "B-dedup-source-FAIL"; log "B FAILED: dedup reason does not name a generated source"; return
  fi
  # The sources and the generated directory must be untouched by the plugin.
  if ! cmp -s "$dir/source-hashes.txt" "$EVIDENCE/A-baseline/source-hashes.txt"; then
    failures=$((failures+1)); record "B-source-change-FAIL"
    log "B FAILED: source files differ from A; inspect source-diff.txt"
    diff "$EVIDENCE/A-baseline/source-hashes.txt" "$dir/source-hashes.txt" | head -20 > "$dir/source-diff.txt"
    return
  fi
  if ! cmp -s "$dir/aot-file-list.txt" "$EVIDENCE/A-baseline/aot-file-list.txt"; then
    failures=$((failures+1)); record "B-generated-set-change-FAIL"
    log "B FAILED: the set of Spring AOT generated files changed; inspect aot-diff.txt"
    diff "$EVIDENCE/A-baseline/aot-file-list.txt" "$dir/aot-file-list.txt" | head -20 > "$dir/aot-diff.txt"
    return
  fi
  if ! check_native_input_has_registration "$PROJECT_DIR" > "$dir/native-input-registration.txt"; then
    failures=$((failures+1)); record "B-lost-registration-FAIL"; log "B FAILED: the effective native input lost the registration"; return
  fi
  if run_http_suite "$PROJECT_DIR" "$dir" B; then
    if cmp -s "$dir/http-normalised.json" "$EVIDENCE/A-baseline/http-normalised.json"; then
      record "B-pruned-PASS"; log "B PASS (HTTP identical to A)"
    else
      failures=$((failures+1)); record "B-http-diff-FAIL"; log "B FAILED: HTTP payloads differ from A"
      diff "$EVIDENCE/A-baseline/http-normalised.json" "$dir/http-normalised.json" | head -40 > "$dir/http-diff.txt"
    fi
  else
    failures=$((failures+1)); record "B-http-FAIL"; log "B FAILED: HTTP suite"
  fi
}

run_case_c() {
  local dir="$EVIDENCE/C-missing-registration"
  local work="$dir/work"
  mkdir -p "$dir"
  log "C: copy of B with the RuntimeHints registration and its self-owned duplicate removed"
  rm -rf "$work"; mkdir -p "$work"
  ( cd "$PROJECT_DIR" && tar --exclude ./target -cf - . ) | ( cd "$work" && tar -xf - )

  # Remove the *only* sources of the registration: the hint that AOT compiles from, and the
  # hand-written metadata. Nothing else in the project mentions DynamicTarget for reflection.
  rm -f "$work/src/main/resources/META-INF/native-image/com.example.boot/reflect-config.json"
  "$PYTHON" - "$work/src/main/java/com/example/boot/Application.java" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
text = p.read_text()
text = text.replace("import org.springframework.context.annotation.ImportRuntimeHints;\n", "")
text = text.replace("@ImportRuntimeHints(DynamicHints.class)\n", "")
p.write_text(text)
PY
  rm -f "$work/src/main/java/com/example/boot/DynamicHints.java"

  ( cd "$work" && run_with_timeout "$TIMEOUT_MAVEN" "$dir/maven.log" \
      "$MVN" $MVN_COMMON -Pnative clean package )
  if [ $? -ne 0 ]; then
    failures=$((failures+1)); record "C-maven-FAIL"; log "C FAILED: maven"; return
  fi
  if check_native_input_has_registration "$work" > "$dir/native-input-registration.txt"; then
    failures=$((failures+1)); record "C-residue-FAIL"; log "C FAILED: a registration for DynamicTarget survived in the native input"
    return
  fi
  if [ -x "$work/target/$BINARY_NAME" ]; then
    if run_http_suite "$work" "$dir" C; then
      failures=$((failures+1)); record "C-negative-FAIL"; log "C FAILED: the dynamic request succeeded without the registration"
    else
      if grep -qiE "MissingReflectionRegistration|NoSuchMethod|ClassNotFound|does not exist|reflect" "$dir/server.log" "$dir/http.log" 2>/dev/null; then
        record "C-negative-PASS"; log "C PASS (dynamic request failed for a registration-related reason)"
      else
        failures=$((failures+1)); record "C-wrong-reason-FAIL"; log "C FAILED: failed, but not clearly for a missing registration"
      fi
    fi
  else
    # Missing class at image build time is also a legitimate, registration-related failure.
    if grep -qiE "DynamicTarget|MissingReflectionRegistration|ClassNotFound" "$dir/maven.log"; then
      record "C-negative-PASS"; log "C PASS (native build refused the missing registration)"
    else
      failures=$((failures+1)); record "C-wrong-reason-FAIL"; log "C FAILED: no binary and no registration-related reason in the log"
    fi
  fi
}

if [ -n "${REPO_ARG:-}" ] || [ -n "${EXPECT_PLUGIN_SHA:-}" ]; then
  require_staged_plugin || exit 1
fi

case ",$CASES," in *,a,*) run_case_a ;; esac
case ",$CASES," in *,b,*) run_case_b ;; esac
case ",$CASES," in *,c,*) run_case_c ;; esac

{
  echo "{"
  echo "  \"case\": \"spring-boot-native\","
  echo "  \"pluginVersion\": \"$PLUGIN_VERSION\","
  echo "  \"javaHome\": \"$JAVA_HOME\","
  echo "  \"maven\": \"$MVN\","
  echo "  \"groups\": \"$CASES\","
  echo "  \"outcomes\": \"$RESULTS\","
  echo "  \"failures\": $failures"
  echo "}"
} > "$EVIDENCE/result.json"

log "evidence: $EVIDENCE"
log "outcomes: ${RESULTS:-<none>}"
if [ "$failures" -ne 0 ]; then log "FAILED ($failures)"; exit 1; fi
log "PASS"
exit 0
