#!/usr/bin/env bash
#
# Consumes a *given* plugin artifact with a *given* Maven and JDK, in an isolated repository, and
# records the evidence.
#
# It cannot rebuild the plugin: it only accepts a JAR and its POM, installs them with install-file
# into a fresh repository, and runs one of the sample projects there. That is what makes
# "JDK 25 built it, JDK 17 consumed it" checkable - a low-version case must not be able to
# substitute an artifact it built itself.
#
# A run is only PASS when the plugin demonstrably executed: the in-process environment record, the
# analysis report and the pruning changelog must all be present, the sources must be byte-identical
# afterwards, and the configuration the native build reads must have changed. An exit code of 0 from
# Maven is not enough on its own.
#
# Usage:
#   verify-compatibility.sh --artifact <jar> --pom <pom> \
#       --maven-home <maven-dir> --java-home <jdk-home> --case <case-id> [options]
#
# Options:
#   --sample <name>            example project under examples/ (default: plain-native)
#   --goals "<goals>"          Maven goals for the consumption run (default: "clean package")
#   --plugin-version <v>       version passed as -Dnative.metadata.version (default: from the POM)
#   --required-java <range>    Maven version range asserted in-process (default: [17,))
#   --required-maven <range>   Maven version range asserted in-process (default: [3.9.9,))
#   --expect-sha256 <hash>     fail if the consumed JAR is not this hash (cross-job identity check)
#   --work-dir <dir>           working copy location; must be outside the plugin checkout.
#                              Default: a fresh mktemp directory.
#   --evidence <dir>           evidence root (default: <project>/target/compat)
#   --settings <file>          Maven settings for the child Maven (default: none)
#   --maven-args "<args>"      extra arguments for the consumption run (default: none)
#   --require-config-change    require the configuration read by the native build to differ from
#                              the source configuration (default: on for plain-native)
#   --allow-config-unchanged   accept a zero-change pruning result (use when the sample has no
#                              expected change, e.g. the read-only analysis-only cases)
#   --verify-native            additionally run examples/<sample>/verify.sh A/B/C
#   --native-image <path>      native-image for --verify-native (default: <java-home>/bin/native-image)
#   --timeout <sec>            timeout for the consumption run (default: 300)
#
# Exit codes: 0 PASS, 1 FAIL, 2 usage error. result.json is written in every case.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ARTIFACT=""
POM=""
MAVEN_HOME=""
JAVA_HOME_ARG=""
CASE_ID=""
SAMPLE="plain-native"
GOALS="clean package"
PLUGIN_VERSION=""
REQUIRED_JAVA="[17,)"
REQUIRED_MAVEN="[3.9.9,)"
EXPECT_SHA=""
WORK_DIR=""
EVIDENCE_ROOT="${PROJECT_DIR}/target/compat"
SETTINGS=""
MAVEN_ARGS=""
VERIFY_NATIVE="false"
NATIVE_IMAGE=""
TIMEOUT=300
REQUIRE_CONFIG_CHANGE=""

usage() { sed -n '2,46p' "$0"; exit 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --artifact) ARTIFACT="$2"; shift 2 ;;
    --pom) POM="$2"; shift 2 ;;
    --maven-home) MAVEN_HOME="$2"; shift 2 ;;
    --java-home) JAVA_HOME_ARG="$2"; shift 2 ;;
    --case) CASE_ID="$2"; shift 2 ;;
    --sample) SAMPLE="$2"; shift 2 ;;
    --goals) GOALS="$2"; shift 2 ;;
    --plugin-version) PLUGIN_VERSION="$2"; shift 2 ;;
    --required-java) REQUIRED_JAVA="$2"; shift 2 ;;
    --required-maven) REQUIRED_MAVEN="$2"; shift 2 ;;
    --expect-sha256) EXPECT_SHA="$2"; shift 2 ;;
    --work-dir) WORK_DIR="$2"; shift 2 ;;
    --evidence) EVIDENCE_ROOT="$2"; shift 2 ;;
    --settings) SETTINGS="$2"; shift 2 ;;
    --maven-args) MAVEN_ARGS="$2"; shift 2 ;;
    --verify-native) VERIFY_NATIVE="true"; shift ;;
    --native-image) NATIVE_IMAGE="$2"; shift 2 ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    --require-config-change) REQUIRE_CONFIG_CHANGE="true"; shift ;;
    --allow-config-unchanged) REQUIRE_CONFIG_CHANGE="false"; shift ;;
    -h|--help) usage ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

# Portable SHA-256: GNU coreutils and BSD/macOS disagree about the tool name.
sha256() { # file
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

# A stable digest of a directory tree (per-file digests, then a digest of those lines).
tree_sha256() { # dir
  ( cd "$1" && find . -type f | sort | while IFS= read -r f; do
      printf '%s  %s\n' "$(sha256 "$f")" "$f"
    done ) | sha256 /dev/stdin
}

[ -n "$ARTIFACT" ] || { echo "ERROR: --artifact is required" >&2; exit 2; }
[ -n "$POM" ] || { echo "ERROR: --pom is required" >&2; exit 2; }
[ -n "$MAVEN_HOME" ] || { echo "ERROR: --maven-home is required" >&2; exit 2; }
[ -n "$JAVA_HOME_ARG" ] || { echo "ERROR: --java-home is required" >&2; exit 2; }
[ -n "$CASE_ID" ] || { echo "ERROR: --case is required" >&2; exit 2; }
[ -f "$ARTIFACT" ] || { echo "ERROR: --artifact does not exist: $ARTIFACT" >&2; exit 2; }
[ -f "$POM" ] || { echo "ERROR: --pom does not exist: $POM" >&2; exit 2; }
[ -x "$MAVEN_HOME/bin/mvn" ] || { echo "ERROR: --maven-home has no bin/mvn: $MAVEN_HOME" >&2; exit 2; }
[ -x "$JAVA_HOME_ARG/bin/java" ] || { echo "ERROR: --java-home has no bin/java: $JAVA_HOME_ARG" >&2; exit 2; }
[ -d "$PROJECT_DIR/examples/$SAMPLE" ] || { echo "ERROR: unknown sample: $SAMPLE" >&2; exit 2; }
if [ -n "$SETTINGS" ] && [ ! -f "$SETTINGS" ]; then echo "ERROR: --settings not found: $SETTINGS" >&2; exit 2; fi

MVN="$MAVEN_HOME/bin/mvn"
if [ -z "$PLUGIN_VERSION" ]; then
  PLUGIN_VERSION=$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$POM" | head -1)
fi
[ -n "$PLUGIN_VERSION" ] || { echo "ERROR: pass --plugin-version (could not read it from $POM)" >&2; exit 2; }
if [ -z "$REQUIRE_CONFIG_CHANGE" ]; then
  # The plain sample carries a rule that must narrow its broad registration; a run that changes
  # nothing there means pruning did not happen.
  if [ "$SAMPLE" = "plain-native" ]; then REQUIRE_CONFIG_CHANGE="true"; else REQUIRE_CONFIG_CHANGE="false"; fi
fi

EVIDENCE="$EVIDENCE_ROOT/$CASE_ID"
REPO="$EVIDENCE/repo"

STATUS="FAIL"
REASON=""
INSTALL_EXIT=""
RUN_EXIT=""
NATIVE_EXIT="NOT_RUN"
ARTIFACT_SHA=""
STAGED_SHA=""
READ_JAVA_HOME=""
READ_JAVA_FEATURE=""
READ_MAVEN=""
READ_VENDOR=""
READ_GOAL=""
CONFIG_CHANGED="unknown"
WORK=""

write_result() { # status reason
  local status="$1" reason="$2"
  [ -n "$EVIDENCE" ] || return 0
  mkdir -p "$EVIDENCE" 2>/dev/null || return 0
  local reason_json="null"
  if [ -n "$reason" ]; then
    reason_json="\"$(printf '%s' "$reason" | tr -d '\n' | sed 's/"/\\"/g')\""
  fi
  # Precomputed: a case statement inside $( ) inside a heredoc confuses the bash 3.2 parser.
  local work_outside="true"
  case "$WORK" in "$PROJECT_DIR"/*) work_outside="false" ;; esac
  local plugin_executed="false"
  if [ "$READ_GOAL" = "prune" ] || [ "$READ_GOAL" = "analyze" ]; then plugin_executed="true"; fi
  local staged_matches="false"
  if [ -n "$STAGED_SHA" ] && [ "$STAGED_SHA" = "$ARTIFACT_SHA" ]; then staged_matches="true"; fi
  cat > "$EVIDENCE/result.json" <<EOF
{
  "caseId": "$CASE_ID",
  "sample": "$SAMPLE",
  "status": "$status",
  "commit": "$(git -C "$PROJECT_DIR" rev-parse HEAD 2>/dev/null || echo unknown)",
  "pluginVersion": "$PLUGIN_VERSION",
  "pluginSha256": "$ARTIFACT_SHA",
  "stagedPluginSha256": "$STAGED_SHA",
  "mavenVersion": "$READ_MAVEN",
  "mavenJavaFeature": ${READ_JAVA_FEATURE:-null},
  "javaHome": "$READ_JAVA_HOME",
  "javaVendor": "$READ_VENDOR",
  "pluginGoal": "$READ_GOAL",
  "nativeImageVersion": null,
  "configChanged": "$CONFIG_CHANGED",
  "exitCodes": { "install": "$INSTALL_EXIT", "consume": "$RUN_EXIT", "native": "$NATIVE_EXIT" },
  "assertions": {
    "stagedArtifactMatchesInput": $staged_matches,
    "isolatedRepository": true,
    "workDirectoryOutsideCheckout": $work_outside,
    "pluginExecutedInMavenProcess": $plugin_executed,
    "enforcerJavaRange": "$REQUIRED_JAVA",
    "enforcerMavenRange": "$REQUIRED_MAVEN",
    "requireConfigChange": "$REQUIRE_CONFIG_CHANGE"
  },
  "reason": $reason_json
}
EOF
  cat > "$EVIDENCE/environment.json" <<EOF
{
  "caseId": "$CASE_ID",
  "sample": "$SAMPLE",
  "pluginVersion": "$PLUGIN_VERSION",
  "pluginSha256": "$ARTIFACT_SHA",
  "stagedPluginSha256": "$STAGED_SHA",
  "pomSha256": "$(sha256 "$POM")",
  "mavenHome": "$MAVEN_HOME",
  "javaHomeRequested": "$JAVA_HOME_ARG",
  "javaHomeInMavenProcess": "$READ_JAVA_HOME",
  "mavenVersionInMavenProcess": "$READ_MAVEN",
  "javaFeatureInMavenProcess": "$READ_JAVA_FEATURE",
  "javaVendorInMavenProcess": "$READ_VENDOR",
  "pluginGoalInMavenProcess": "$READ_GOAL",
  "goals": "$GOALS",
  "workDirectory": "$WORK",
  "osName": "$(uname -s)",
  "osArch": "$(uname -m)"
}
EOF
}

fail() { STATUS="FAIL"; REASON="$1"; echo "[compat:$CASE_ID] FAIL: $1"; write_result "FAIL" "$1"; exit 1; }

# ---------------------------------------------------------------------------------------------
# Validate the work directory *before* creating, cleaning or copying anything. The default is a
# fresh mktemp directory: a default that lands inside the plugin checkout and is then rejected would
# make the documented invocation fail, and an arbitrary existing --work-dir must never be deleted.
# ---------------------------------------------------------------------------------------------
WORK_WAS_PROVIDED="false"
if [ -n "$WORK_DIR" ]; then
  WORK_WAS_PROVIDED="true"
  mkdir -p "$(dirname "$WORK_DIR")" 2>/dev/null || true
  RESOLVED="$(cd "$(dirname "$WORK_DIR")" 2>/dev/null && pwd)" || RESOLVED=""
  [ -n "$RESOLVED" ] || { echo "ERROR: cannot resolve --work-dir: $WORK_DIR" >&2; exit 2; }
  WORK_DIR="$RESOLVED/$(basename "$WORK_DIR")"
else
  WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/native-metadata-compat.XXXXXXXX")"
fi
case "$WORK_DIR" in
  "$PROJECT_DIR"|"$PROJECT_DIR"/*)
    echo "ERROR: --work-dir must be outside the plugin checkout: $WORK_DIR" >&2
    echo "       (the point of the consumption run is that the sample is not resolved from this reactor)" >&2
    exit 2 ;;
esac

# Cleanup happens only for a directory this script created itself. An existing directory the user
# pointed at is never deleted: it might be a checkout of something.
if [ "$WORK_WAS_PROVIDED" = "true" ] && [ -e "$WORK_DIR" ]; then
  if [ -f "$WORK_DIR/.native-metadata-work" ]; then
    rm -rf "$WORK_DIR"
  elif [ -n "$(ls -A "$WORK_DIR" 2>/dev/null)" ]; then
    echo "ERROR: --work-dir exists and is not empty: $WORK_DIR" >&2
    echo "       refusing to delete a directory this script does not own; pass a fresh or empty one" >&2
    exit 2
  fi
fi

WORK="$WORK_DIR"
mkdir -p "$EVIDENCE" "$WORK" "$REPO" || { echo "ERROR: cannot create $EVIDENCE or $WORK" >&2; exit 2; }
: > "$WORK/.native-metadata-work"

ARTIFACT_SHA=$(sha256 "$ARTIFACT")
echo "[compat:$CASE_ID] artifact=$ARTIFACT sha256=$ARTIFACT_SHA"
echo "[compat:$CASE_ID] java-home=$JAVA_HOME_ARG maven-home=$MAVEN_HOME sample=$SAMPLE"
echo "[compat:$CASE_ID] evidence=$EVIDENCE work=$WORK"

if [ -n "$EXPECT_SHA" ] && [ "$ARTIFACT_SHA" != "$EXPECT_SHA" ]; then
  fail "artifact hash $ARTIFACT_SHA != expected $EXPECT_SHA"
fi

# Arguments are kept in arrays so that paths containing spaces stay one argument each.
MAVEN_BASE=( -B -ntp "-Dmaven.repo.local=$REPO" )
if [ -n "$SETTINGS" ]; then
  MAVEN_BASE+=( -s "$SETTINGS" )
fi
CONSUME_ARGS=()
if [ -n "$MAVEN_ARGS" ]; then
  # shellcheck disable=SC2206  # deliberate: --maven-args is a whitespace-separated argument list
  CONSUME_ARGS=( $MAVEN_ARGS )
fi
GOAL_ARGS=()
if [ -n "$GOALS" ]; then
  # shellcheck disable=SC2206
  GOAL_ARGS=( $GOALS )
fi

run_with_timeout() {
  local seconds="$1"; shift
  local logfile="$1"; shift
  "$@" > "$logfile" 2>&1 &
  local pid=$!
  local waited=0
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$waited" -ge "$seconds" ]; then
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
}

# ---------------------------------------------------------------------------------------------
# Working copy outside the plugin checkout, so no reactor or source path can leak in.
# ---------------------------------------------------------------------------------------------
(cd "$PROJECT_DIR/examples/$SAMPLE" && tar --exclude ./target -cf - .) | (cd "$WORK" && tar -xf -)
[ -f "$WORK/pom.xml" ] || fail "working copy has no pom.xml: $WORK"

SOURCE_HASH_BEFORE=$(tree_sha256 "$WORK/src")

# ---------------------------------------------------------------------------------------------
# Stage the artifact into the isolated repository; nothing else may supply this coordinate.
# ---------------------------------------------------------------------------------------------
export JAVA_HOME="$JAVA_HOME_ARG"
run_with_timeout 300 "$EVIDENCE/install.log" \
  "$MVN" "${MAVEN_BASE[@]}" install:install-file -Dfile="$ARTIFACT" -DpomFile="$POM"
INSTALL_EXIT=$?
[ "$INSTALL_EXIT" -eq 0 ] || fail "install-file failed with exit $INSTALL_EXIT (see $EVIDENCE/install.log)"

STAGED_JAR="$REPO/io/github/lihuu/native-metadata-maven-plugin/$PLUGIN_VERSION/native-metadata-maven-plugin-$PLUGIN_VERSION.jar"
[ -f "$STAGED_JAR" ] || fail "the artifact was not installed into the isolated repository at $STAGED_JAR"
STAGED_SHA=$(sha256 "$STAGED_JAR")
[ "$STAGED_SHA" = "$ARTIFACT_SHA" ] || fail "staged artifact hash $STAGED_SHA does not match $ARTIFACT_SHA"

# ---------------------------------------------------------------------------------------------
# Consume it.
# ---------------------------------------------------------------------------------------------
( cd "$WORK" && run_with_timeout "$TIMEOUT" "$EVIDENCE/consume.log" \
    "$MVN" "${MAVEN_BASE[@]}" \
    "-Dnative.metadata.version=$PLUGIN_VERSION" \
    "-Drequired.java.version=$REQUIRED_JAVA" \
    "-Drequired.maven.version=$REQUIRED_MAVEN" \
    ${CONSUME_ARGS[@]+"${CONSUME_ARGS[@]}"} "${GOAL_ARGS[@]}" )
RUN_EXIT=$?
[ "$RUN_EXIT" -eq 0 ] || fail "consumption run failed with exit $RUN_EXIT (see $EVIDENCE/consume.log)"

# ---------------------------------------------------------------------------------------------
# The plugin must have actually executed, in this Maven process.
# ---------------------------------------------------------------------------------------------
ENVIRONMENT_JSON="$WORK/target/native-metadata/environment.json"
[ -f "$ENVIRONMENT_JSON" ] || fail "no $ENVIRONMENT_JSON: the plugin did not run in this Maven process"
READ_JAVA_HOME=$(sed -n 's/.*"javaHome" *: *"\([^"]*\)".*/\1/p' "$ENVIRONMENT_JSON")
READ_JAVA_FEATURE=$(sed -n 's/.*"javaFeature" *: *\([0-9]*\).*/\1/p' "$ENVIRONMENT_JSON")
READ_MAVEN=$(sed -n 's/.*"mavenVersion" *: *"\([^"]*\)".*/\1/p' "$ENVIRONMENT_JSON")
READ_VENDOR=$(sed -n 's/.*"javaVendor" *: *"\([^"]*\)".*/\1/p' "$ENVIRONMENT_JSON")
READ_GOAL=$(sed -n 's/.*"goal" *: *"\([^"]*\)".*/\1/p' "$ENVIRONMENT_JSON")
echo "[compat:$CASE_ID] in-Maven: java=$READ_JAVA_FEATURE ($READ_VENDOR) home=$READ_JAVA_HOME maven=$READ_MAVEN goal=$READ_GOAL"
[ "$READ_GOAL" = "prune" ] || fail "expected the prune goal to record its environment, got '$READ_GOAL'"
[ -n "$READ_JAVA_FEATURE" ] || fail "could not read the Java feature version from $ENVIRONMENT_JSON"

REPORT_JSON="$WORK/target/native-metadata/report.json"
CHANGELOG_JSON="$WORK/target/native-metadata/changelog.json"
[ -s "$REPORT_JSON" ] || fail "no analysis report at $REPORT_JSON"
[ -s "$CHANGELOG_JSON" ] || fail "no pruning changelog at $CHANGELOG_JSON"
python3 -c "import json,sys; json.load(open(sys.argv[1])); json.load(open(sys.argv[2]))" \
  "$REPORT_JSON" "$CHANGELOG_JSON" || fail "report/changelog are not valid JSON"

# Analysis is read-only: the sample's sources must be byte-identical afterwards.
SOURCE_HASH_AFTER=$(tree_sha256 "$WORK/src")
[ "$SOURCE_HASH_BEFORE" = "$SOURCE_HASH_AFTER" ] || fail "the plugin modified files under src/"

# The configuration the native build reads must have changed, or pruning did nothing measurable.
CONSUMED=$(cd "$WORK/target/classes" 2>/dev/null && find . -path '*META-INF/native-image*' -name 'reflect-config.json' | sort)
if [ -n "$CONSUMED" ]; then
  CONFIG_CHANGED="no-source-counterpart"
  while IFS= read -r candidate; do
    RELATIVE=${candidate#./}
    SOURCE_CONFIG="$WORK/src/main/resources/$RELATIVE"
    # AOT may put its own metadata before the owned resource in lexical order. Only compare
    # resources that actually have a source counterpart, then require at least one to differ.
    [ -f "$SOURCE_CONFIG" ] || continue
    if [ "$CONFIG_CHANGED" = "no-source-counterpart" ]; then CONFIG_CHANGED="false"; fi
    CONSUMED_SHA=$(sha256 "$WORK/target/classes/$RELATIVE")
    SOURCE_CONFIG_SHA=$(sha256 "$SOURCE_CONFIG")
    if [ "$CONSUMED_SHA" != "$SOURCE_CONFIG_SHA" ]; then CONFIG_CHANGED="true"; fi
  done <<< "$CONSUMED"
fi

echo "[compat:$CASE_ID] consumed configuration changed: $CONFIG_CHANGED"
if [ "$REQUIRE_CONFIG_CHANGE" = "true" ] && [ "$CONFIG_CHANGED" != "true" ]; then
  fail "expected the configuration read by the native build to change, but it did not ($CONFIG_CHANGED)"
fi

# ---------------------------------------------------------------------------------------------
# Optional native A/B/C, driven by the sample itself.
# ---------------------------------------------------------------------------------------------
if [ "$VERIFY_NATIVE" = "true" ]; then
  NATIVE_BIN="${NATIVE_IMAGE:-$JAVA_HOME_ARG/bin/native-image}"
  if [ ! -x "$NATIVE_BIN" ]; then
    NATIVE_EXIT="FAIL_MISSING_TOOL"
    fail "native-image not found at $NATIVE_BIN"
  fi
  NATIVE_ARGS=( --java-home "$JAVA_HOME_ARG" --maven-home "$MAVEN_HOME"
                --plugin-version "$PLUGIN_VERSION" --repo "$REPO"
                --expect-plugin-sha256 "$ARTIFACT_SHA"
                --evidence "$EVIDENCE/native" )
  if [ -n "$NATIVE_IMAGE" ]; then NATIVE_ARGS+=( --native-image "$NATIVE_IMAGE" ); fi
  ( cd "$WORK" && "$WORK/verify.sh" "${NATIVE_ARGS[@]}" )
  NATIVE_EXIT=$?
  [ "$NATIVE_EXIT" -eq 0 ] || fail "native A/B/C failed with exit $NATIVE_EXIT"
fi

STATUS="PASS"
write_result "PASS" ""
echo "[compat:$CASE_ID] PASS"
exit 0
