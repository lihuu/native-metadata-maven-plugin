#!/usr/bin/env bash
#
# Installs a pinned toolchain described in docs/validation/toolchains.json, verifying the digest
# before the archive is used.
#
# Usage:
#   scripts/ci-install-toolchain.sh --graalvm <id> [--maven <id>] --dest <dir>
#   scripts/ci-install-toolchain.sh --maven <id> --dest <dir>
#   scripts/ci-install-toolchain.sh --plain-jdk17 <id> --dest <dir>
#
# Every <id> must exist in docs/validation/toolchains.json. Nothing is downloaded without a digest
# to check it against: a lock file whose hashes are not enforced is documentation, not a lock file.
#
# Prints KEY=VALUE lines on stdout so a workflow can consume them, for example:
#   eval "$(scripts/ci-install-toolchain.sh --graalvm graalvm-ce-jdk17-linux --dest /opt/toolchains)"

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOCK_FILE="$PROJECT_DIR/docs/validation/toolchains.json"

GRAALVM_ID=""
MAVEN_ID=""
PLAIN_ID=""
DEST=""

while [ $# -gt 0 ]; do
  case "$1" in
    --graalvm) GRAALVM_ID="$2"; shift 2 ;;
    --maven) MAVEN_ID="$2"; shift 2 ;;
    --plain-jdk17) PLAIN_ID="$2"; shift 2 ;;
    --dest) DEST="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

[ -n "$DEST" ] || { echo "ERROR: --dest is required" >&2; exit 2; }
[ -f "$LOCK_FILE" ] || { echo "ERROR: lock file not found: $LOCK_FILE" >&2; exit 2; }
mkdir -p "$DEST"

PYTHON="$(command -v python3 || command -v python)"
[ -n "$PYTHON" ] || { echo "ERROR: python3 is required" >&2; exit 2; }

lookup() { # section id
  "$PYTHON" - "$LOCK_FILE" "$1" "$2" <<'PY'
import json, sys
lock = json.load(open(sys.argv[1]))
section, wanted = sys.argv[2], sys.argv[3]
for entry in lock.get(section, []):
    if entry.get("id") == wanted:
        print(json.dumps(entry))
        sys.exit(0)
sys.exit(f"no entry {wanted!r} in section {section!r} of {sys.argv[1]}")
PY
}

verify_and_extract() {
  local entry="$1" dest="$2" label="$3"
  local url sha key base archive
  url=$("$PYTHON" -c 'import json,sys; print(json.loads(sys.argv[1])["url"])' "$entry")
  sha=$("$PYTHON" -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("sha256",""))' "$entry")
  key="sha256"
  if [ -z "$sha" ]; then
    sha=$("$PYTHON" -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("sha512",""))' "$entry")
    key="sha512"
  fi
  [ -n "$sha" ] || { echo "ERROR: $label has no digest in the lock file" >&2; exit 1; }

  base="$(basename "$url")"
  archive="$dest/$base"
  if [ ! -s "$archive" ]; then
    echo "downloading $url" >&2
    curl -fL --retry 3 --connect-timeout 30 --max-time 1800 -o "$archive.part" "$url"
    mv "$archive.part" "$archive"
  fi

  if [ "$key" = "sha256" ]; then
    actual=$(shasum -a 256 "$archive" | awk '{print $1}')
  else
    actual=$(shasum -a 512 "$archive" | awk '{print $1}')
  fi
  if [ "$actual" != "$sha" ]; then
    echo "ERROR: $label digest mismatch" >&2
    echo "  expected $key=$sha" >&2
    echo "  actual   $key=$actual" >&2
    exit 1
  fi
  echo "$label $key ok: $actual" >&2

  local extract="$dest/${base%.tar.gz}"
  rm -rf "$extract"
  mkdir -p "$extract"
  case "$base" in
    *.tar.gz) tar xzf "$archive" -C "$extract" ;;
    *.zip) unzip -q "$archive" -d "$extract" ;;
    *) echo "ERROR: unsupported archive $base" >&2; exit 1 ;;
  esac
  # Vendor archives contain a top-level product directory, not bin/ at the
  # extraction root. Resolve exactly one JVM/Maven home and verify it.
  local product_home
  product_home=$("$PYTHON" - "$extract" <<'PYHOME'
from pathlib import Path
import sys
root = Path(sys.argv[1])
candidates = []
for base in [root, *root.iterdir()]:
    for home in [base, base / "Contents/Home"]:
        if (home / "bin/java").is_file() or (home / "bin/mvn").is_file():
            candidates.append(home)
if len(candidates) != 1:
    sys.exit(f"expected exactly one toolchain home in {root}, got {candidates}")
print(candidates[0])
PYHOME
  ) || return 1
  echo "$product_home"
}

if [ -n "$GRAALVM_ID" ]; then
  entry=$(lookup graalvm "$GRAALVM_ID")
  home=$(verify_and_extract "$entry" "$DEST" "graalvm:$GRAALVM_ID")
  if [ -d "$home/Contents/Home" ]; then home="$home/Contents/Home"; fi
  echo "JAVA_HOME=$home"
  echo "GRAALVM_HOME=$home"
fi

if [ -n "$PLAIN_ID" ]; then
  entry=$(lookup plainJdk17 "$PLAIN_ID")
  home=$(verify_and_extract "$entry" "$DEST" "plain-jdk17:$PLAIN_ID")
  if [ -d "$home/Contents/Home" ]; then home="$home/Contents/Home"; fi
  echo "PLAIN_JAVA_HOME=$home"
fi

if [ -n "$MAVEN_ID" ]; then
  entry=$(lookup maven "$MAVEN_ID")
  if [ -n "$("$PYTHON" -c 'import json,sys; print(json.loads(sys.argv[1]).get("path",""))' "$entry")" ]; then
    echo "MAVEN_HOME=$("$PYTHON" -c 'import json,sys; print(json.loads(sys.argv[1])["path"])' "$entry")"
  else
    home=$(verify_and_extract "$entry" "$DEST" "maven:$MAVEN_ID")
    echo "MAVEN_HOME=$home"
  fi
fi
