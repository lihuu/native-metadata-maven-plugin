#!/usr/bin/env bash
#
# Semantic assertions on a *built* plain-native project. No native toolchain required.
#
# The plugin's own `mvn verify` runs this against the Invoker clone (target/it/plain-native) after the
# integration test, because "Maven exited 0" says nothing about what pruning did:
#
#   1. the changelog names exactly the type the rule names - pruning did not help itself to anything else
#   2. the consumed configuration carries the members the rule lists, and the members the application
#      actually reflects on are among them
#   3. entries no rule mentions are identical to the input
#   4. the sources were not rewritten in place
#
# A broad registration is not *wrong* - it registers more than needed and the image still works - so
# "the file must not contain allDeclaredMethods" is deliberately not asserted here: that is a property
# of this fixture's rule, not a property of the plugin. What is asserted is that the rule's effect is
# exactly the rule's effect.
#
# Usage:
#   verify-pruning.sh [--project <built-project-dir>] [--source <pristine-source-dir>]
#
# Exit codes: 0 all assertions pass, 1 otherwise.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT=""
SOURCE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --project) PROJECT="$2"; shift 2 ;;
    --source) SOURCE="$2"; shift 2 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

PROJECT="${PROJECT:-$SCRIPT_DIR}"
if [ ! -d "$PROJECT" ]; then
  echo "ERROR: --project is not a directory: $PROJECT" >&2
  echo "       (the built project comes from the Invoker integration test, which runs in the" >&2
  echo "        integration-test phase; -Dinvoker.skip=true skips it and leaves nothing to assert)" >&2
  exit 2
fi
if [ -n "$SOURCE" ] && [ ! -d "$SOURCE" ]; then
  echo "ERROR: --source is not a directory: $SOURCE" >&2; exit 2
fi

PYTHON="$(command -v python3 || command -v python)"
[ -n "$PYTHON" ] || { echo "ERROR: python3 is required" >&2; exit 2; }

"$PYTHON" - "$PROJECT" "$SOURCE" <<'PY'
import hashlib
import json
import pathlib
import sys

project = pathlib.Path(sys.argv[1]).resolve()
source = pathlib.Path(sys.argv[2]).resolve() if sys.argv[2] else None

RESOURCE = "META-INF/native-image/com.example.plain/reflect-config.json"
RULE_TYPE = "com.example.plain.Greeting"
# The rule narrows Greeting to these; describe(String[]) and label() are what the sample actually
# reflects on at run time, so losing either of them is a correctness failure, not a hygiene issue.
REQUIRED_MEMBERS = {
    ("<init>", ()),
    ("<init>", ("java.lang.String",)),
    ("label", ()),
    ("describe", ("java.lang.String[]",)),
}

failures = []


def check(ok, message):
    print(f"[verify-pruning] {'PASS' if ok else 'FAIL'}: {message}")
    if not ok:
        failures.append(message)


def read_json(path):
    try:
        return json.loads(path.read_text())
    except Exception as exc:  # noqa: BLE001 - the message is the assertion output
        check(False, f"{path} is valid JSON ({exc})")
        return None


def normalize(entries):
    if isinstance(entries, dict):  # reachability-metadata wraps the array
        entries = entries.get("reflection", [])
    return [e for e in entries if isinstance(e, dict) and "name" in e]


# 1. The changelog scope: the rule's type, and nothing else.
changelog_path = project / "target/native-metadata/changelog.json"
check(changelog_path.is_file(), f"changelog written: {changelog_path}")
changelog = read_json(changelog_path) if changelog_path.is_file() else []
changelog = changelog if isinstance(changelog, list) else []
touched = sorted({c.get("typeName") for c in changelog})
actions = sorted({(c.get("typeName"), c.get("action")) for c in changelog})
check(touched == [RULE_TYPE], f"only {RULE_TYPE} was changed, nothing else (touched={touched})")
check((RULE_TYPE, "NARROWED") in actions, f"the rule was applied (actions={actions})")
check((RULE_TYPE, "DELETED") in actions, f"the duplicate broad entry was removed (actions={actions})")

# 2. The consumed configuration: the rule's members, plus everything the app reflects on.
consumed_path = project / "target/classes" / RESOURCE
check(consumed_path.is_file(), f"the configuration the native build consumes exists: {consumed_path}")
consumed = normalize(read_json(consumed_path)) if consumed_path.is_file() else []
by_name = {}
for entry in consumed:
    by_name.setdefault(entry["name"], []).append(entry)

greeting_entries = by_name.get(RULE_TYPE, [])
check(len(greeting_entries) == 1, f"exactly one {RULE_TYPE} entry remains (found {len(greeting_entries)})")
greeting = greeting_entries[0] if greeting_entries else {}
broad = sorted(k for k in ("allDeclaredFields", "allDeclaredMethods", "allDeclaredConstructors")
               if greeting.get(k))
check(not broad, f"the narrow entry carries no broad flag (found {broad})")
members = {(m.get("name"), tuple(m.get("parameterTypes", []))) for m in greeting.get("methods", [])}
missing = sorted(REQUIRED_MEMBERS - members)
check(not missing, f"every member the app reflects on is still registered (missing={missing})")
fields = sorted(f.get("name") for f in greeting.get("fields", []))
check(fields == ["message"], f"the field list matches the rule (found {fields})")

# 3. Entries no rule mentions are untouched.
for rel in ("report.json", "report.md", "environment.json"):
    path = project / "target/native-metadata" / rel
    check(path.is_file(), f"{rel} was written: {path}")
environment = read_json(project / "target/native-metadata/environment.json")
if isinstance(environment, dict):
    check(isinstance(environment.get("javaFeature"), int),
          f"the environment record names the JVM that ran the goal (javaFeature={environment.get('javaFeature')})")
    check(environment.get("goal") == "prune", f"the prune goal recorded itself (goal={environment.get('goal')})")

if source is not None:
    source_config = source / "src/main/resources" / RESOURCE
    check(source_config.is_file(), f"the pristine input configuration exists: {source_config}")
    pristine = normalize(read_json(source_config)) if source_config.is_file() else []
    # The input carries the broad entry twice; the rule narrows the first and deletes the duplicate.
    untouched = sorted({e["name"] for e in pristine} - {RULE_TYPE})
    for name in untouched:
        expected = [e for e in pristine if e["name"] == name]
        actual = by_name.get(name, [])
        check(expected == actual, f"{name} is registered exactly as before ({len(actual)} entr(ies))")

    # 4. The sources were not rewritten in place.
    differences = []
    for path in sorted((source / "src").rglob("*")):
        if not path.is_file():
            continue
        counterpart = project / path.relative_to(source)
        if not counterpart.is_file():
            differences.append(f"missing {counterpart}")
        elif hashlib.sha256(path.read_bytes()).digest() != hashlib.sha256(counterpart.read_bytes()).digest():
            differences.append(str(counterpart))
    check(not differences, f"src/ is byte-identical to the input (differences={differences[:5]})")

if failures:
    print(f"[verify-pruning] FAILED ({len(failures)} assertion(s))")
    sys.exit(1)
print("[verify-pruning] PASS")
PY
