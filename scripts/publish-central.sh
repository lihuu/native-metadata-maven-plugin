#!/bin/sh
# Publish this plugin to Maven Central.
#
# Usage:
#   ./scripts/publish-central.sh              # build, sign, assemble, validate; uploads, stops before publishing
#   ./scripts/publish-central.sh --dry-run    # the same, minus the upload: nothing leaves this machine
#   ./scripts/publish-central.sh --release    # ... and then publish it (irreversible)
#
# ---------------------------------------------------------------------------------------------
# Why this is a script of its own rather than `mvn -Pcentral deploy`
#
# The central-publishing-maven-plugin (Sonatype's own, and the only plugin that publishes to
# Central now) treats its staging directory as a Maven *local repository*. So the install mechanism
# runs against that directory and writes its bookkeeping files there:
#
#   io/github/lihuu/native-metadata-maven-plugin/maven-metadata-local.xml   <- artifactId level, no pom there
#   io/github/lihuu/native-metadata-maven-plugin/1.0.0/_remote.repositories <- "which repository this came from"
#   ...and a checksum for each of those
#
# Central then refuses the upload, because a directory in the bundle that holds files and no .pom is
# invalid:
#
#   "Bundle has content that does NOT have a .pom file: io/github/lihuu/native-metadata-maven-plugin"
#
# Measured rather than assumed, and not fixable from the outside: `-Dmaven.install.skip=true` does not
# stop it, a dedicated `-Dmaven.repo.local` does not stop it (the files are written into staging by
# the same build), deleting them beforehand does not survive (staging is wiped and repopulated each
# run), and the plugin exposes no file-level filter -- `excludeArtifacts` matches whole modules, not
# files. The same measurement is recorded in the panamaview repository, which hit it first.
#
# So this script does the one thing that is deterministic: it lets the build produce the signed
# artifacts, assembles the Central-shaped tree itself from target/, and uploads that through the same
# Portal API the plugin uses. The plugin never uploads.
#
# ---------------------------------------------------------------------------------------------
# The isolated local repository
#
# Every Maven invocation here runs with -Dmaven.repo.local=<a directory under target/>. Two reasons,
# and the second is the one that matters:
#
#   - nothing this script does can disturb the developer's own ~/.m2;
#   - it is what makes the run reproducible on a machine that has no ~/.m2 yet (CI), because the
#     build resolves this plugin's own dependencies from Central into a directory it owns.
#
# The cost is that the first run downloads the whole plugin toolchain again. That is the price of not
# depending on whatever state ~/.m2 happens to be in, which is exactly the state a release must not
# depend on.
# ---------------------------------------------------------------------------------------------
set -eu
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

DRY_RUN=0
RELEASE=0

say() { printf '\n=== %s\n' "$1"; }
fail() { printf 'publish-central.sh: %s\n' "$1" >&2; exit 1; }

for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        --release) RELEASE=1 ;;
        -h|--help) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) fail "unknown option: $arg" ;;
    esac
done
if [ "$DRY_RUN" -eq 1 ] && [ "$RELEASE" -eq 1 ]; then
    fail "--dry-run and --release contradict each other; a dry run uploads nothing, so there is nothing to release"
fi

# ------------------------------------------------------------------ the signing key
#
# Checked first, because it is the one prerequisite whose absence is not discovered cheaply: without
# a secret key the build runs to the verify phase, fails inside the gpg plugin, and the message is the
# plugin's rather than this script's. A release attempt costs a full build; this costs nothing.
#
# The key has to have no passphrase (docs/publishing.md says why: --pinentry-mode loopback cannot
# answer a prompt, so a protected key hangs a headless run until it times out). That is not checked
# here -- there is no way to ask gpg whether a key is protected without trying to use it -- only that
# some secret key exists.
if ! gpg --list-secret-keys >/dev/null 2>&1 || [ -z "$(gpg --list-secret-keys --with-colons 2>/dev/null | grep '^sec:')" ]; then
    fail "no GPG secret key on this machine; Central requires a signature on every artifact (see docs/publishing.md)"
fi

say "signing key"
# Field 5 of a `sec:` record is the long key id. Field 4 is the key *length*, which is also a
# number and reads as a plausible answer -- this printed "4096" as a key id until it was checked
# against `gpg --list-secret-keys`.
gpg --list-secret-keys --with-colons 2>/dev/null | sed -n 's/^sec:[^:]*:[^:]*:[^:]*:\([^:]*\):.*/  key \1/p' | sed 's/^/  /'
echo "  (a key can be listed here and still be unusable; only the signature check below proves it)"

# ------------------------------------------------------------------ coordinates
#
# Read from the pom that will actually be published, by a parser rather than by `grep <version>`,
# because this pom explains itself in comments and one of those comments is one line away from
# matching: the whole file is full of plugin <version> elements, and `grep -m1` reads whichever one
# comes first.
#
# The single artifact assumption is asserted rather than hoped for. If this repository ever grows a
# <parent> or a <modules> list, the first <version> stops being this project's own, and a script that
# kept reading it would publish a bundle whose pom says the wrong thing -- with no error anywhere.
COORDS=$(python3 - "$ROOT/pom.xml" <<'PY'
import pathlib, re, sys

path = pathlib.Path(sys.argv[1])
text = re.sub(r"<!--.*?-->", "", path.read_text(encoding="utf-8"), flags=re.S)

def die(message):
    sys.exit(f"publish-central.sh: {message}")

if "<parent>" in text:
    die("pom.xml has a <parent>; the first <version> is no longer this project's own, so the rule "
        "this script reads coordinates with needs revisiting")
if "<modules>" in text:
    die("pom.xml has a <modules> list; this script publishes exactly one artifact and would need "
        "the same per-module layout work panamaview's script does")

# The header only: everything before the first top-level section. groupId and artifactId also name
# dependencies and plugins further down, and those must not be read as this project's coordinates.
header = re.split(r"<(?:properties|dependencies|build|profiles|distributionManagement)>", text)[0]

group = re.search(r"<groupId>([^<]+)</groupId>", header)
artifact = re.search(r"<artifactId>([^<]+)</artifactId>", header)
version = re.search(r"<version>([^<]+)</version>", header)
if not (group and artifact and version):
    die("could not read groupId/artifactId/version from the pom header")

# Print for the shell to read back with `read`. The values are Maven coordinates; a coordinate with
# whitespace in it is not a coordinate, so this refuses rather than trimming one into validity.
for label, match in (("groupId", group), ("artifactId", artifact), ("version", version)):
    if re.search(r"\s", match.group(1)):
        die(f"{label} '{match.group(1)}' contains whitespace")
print(group.group(1), artifact.group(1), version.group(1))
PY
) || exit 2
read -r GROUP ARTIFACT VERSION <<EOF
$COORDS
EOF

# The path this artifact sits at inside a Maven repository root, derived rather than written down a
# second time: <group as directories>/<artifactId>/<version>/.
GROUP_PATH=$(printf '%s' "$GROUP" | tr '.' '/')
REPO_PATH="$GROUP_PATH/$ARTIFACT/$VERSION"

echo "  $GROUP:$ARTIFACT:$VERSION"

# ------------------------------------------------------------------ a tree that was reviewed
#
# What gets published has to be what was reviewed, and a signed jar built from an uncommitted edit is
# neither. Checked before the build rather than after it: a failure here costs nothing, and a failure
# after signing costs a full rebuild.
#
# A dry run is exempt, and only a dry run: it uploads nothing, so there is nothing to keep honest,
# and requiring a clean tree would make the one mode meant for checking the flow unusable while
# working on it.
if [ "$DRY_RUN" -eq 0 ]; then
    if [ -n "$(git status --porcelain)" ]; then
        git status --short >&2
        fail "uncommitted changes; what gets published has to be what was reviewed"
    fi
fi

# ------------------------------------------------------------------ the Portal token
#
# Read from ~/.m2/settings.xml rather than from the environment, so there is one place the credential
# lives. Server id `central`, which is what publishingServerId names in the -Pcentral profile.
#
# A dry run does not need it and does not look for it, so the flow can be verified on a machine that
# has never been configured. The token is never printed, never echoed, never passed as an argument --
# only its presence is reported.
SETTINGS="${SETTINGS:-$HOME/.m2/settings.xml}"
if [ "$DRY_RUN" -eq 1 ]; then
    say "skipping the Portal token (--dry-run uploads nothing)"
    echo "  settings file: $SETTINGS"
else
    say "reading the Portal token"
    [ -f "$SETTINGS" ] || fail "no settings file at $SETTINGS (see docs/publishing.md)"
    TOKEN=$(python3 - "$SETTINGS" <<'PY'
import base64, sys, xml.etree.ElementTree as ET

# Parsed, not pattern-matched: a settings file is XML, and a regex over it is one comment or one
# attribute away from reading the wrong value.
#
# Namespace-aware by necessity. A settings file declares the Maven SETTINGS namespace on <settings>,
# so every element below it carries that URI; matching a bare 'server' finds nothing at all, and
# matching the parent while reading children by unqualified name finds the server and then an empty
# id -- which reads as "no such entry" rather than as the bug it is.
tree = ET.parse(sys.argv[1])
root = tree.getroot()
ns = ''
if root.tag.startswith('{'):
    ns = root.tag[:root.tag.index('}') + 1]

def text(element, name):
    found = element.find(ns + name)
    return (found.text or '').strip() if found is not None else ''

for server in root.iter(ns + 'server'):
    if text(server, 'id') != 'central':
        continue
    u, p = text(server, 'username'), text(server, 'password')
    if not u or not p:
        sys.exit('the central server entry has no username/password')
    # The base64 of "username:password", produced here rather than by the shell.
    #
    # Print the two values and let the shell join them and encode the result, and the separator
    # becomes part of what is encoded: the Portal wants a colon between them, and encoding it away
    # from the values is how it stops being a byte that silently breaks every upload.
    print(base64.b64encode(f'{u}:{p}'.encode()).decode())
    sys.exit(0)
sys.exit('no <server> with id central in the settings file; see docs/publishing.md')
PY
    ) || exit 2
    [ -n "$TOKEN" ] || fail "the central server entry produced an empty token"
    echo "  token: read (not printed)"
fi

# ------------------------------------------------------------------ build + sign
say "building and signing (-Pcentral)"
# An isolated local repository, deliberately: see the header. Kept between runs, because the point is
# to not depend on ~/.m2, not to re-download the toolchain every time.
REPO="$ROOT/target/central-repo"
mkdir -p "$REPO"

# `verify` runs everything up to and including the signing phase, and stops there. That is the whole
# artifact set: pom, jar, sources jar, javadoc jar, each with its .asc.
#
# The publishing step is not `deploy`: `central-publishing-maven-plugin` runs at deploy, and letting
# it run -- with or without -DskipPublishing=true -- either leaves a failed deployment record on the
# Portal for every attempt or wipes the staging tree this script assembles. Its bundle is not used at
# all, so nothing activates it.
mvn -B -Pcentral -Dmaven.repo.local="$REPO" verify

# ------------------------------------------------------------------ assemble the bundle
#
# Assembled here from target/ rather than taken from the plugin's staging tree. The plugin's tree
# would be the natural source, except that it only exists when the plugin is allowed to upload, and
# letting it upload means a failed deployment record for every attempt. What that tree held -- and
# did not -- is why this script exists at all: see the header.
#
# The layout is Central's: <groupId as path>/<artifactId>/<version>/, holding the pom and the jar
# with its sources and javadoc jars. One artifact, one directory -- which is the whole difference
# from the panamaview script this is modelled on, where a parent pom sits *beside* the framework and
# a directory-swap that looks obvious produces a path no Maven repository uses.
say "assembling the bundle"
STAGE="$ROOT/target/central-publish/staging"
rm -rf "$ROOT/target/central-publish"
mkdir -p "$STAGE/$REPO_PATH"

place() {
    src=$1; dest=$2
    [ -f "$src" ] || fail "missing $src -- was the build run?"
    mkdir -p "$(dirname "$dest")"
    cp "$src" "$dest"
}

# The suffixes are classifiers, so they sit between the version and the extension.
for base in "$ARTIFACT-$VERSION.pom" "$ARTIFACT-$VERSION.jar" \
            "$ARTIFACT-$VERSION-sources.jar" "$ARTIFACT-$VERSION-javadoc.jar"; do
    for suffix in "" ".asc"; do
        place "$ROOT/target/$base$suffix" "$STAGE/$REPO_PATH/$base$suffix"
    done
done

# Checksums, written here rather than by the plugin. Central requires md5 and sha1 for every file;
# sha256 and sha512 are accepted and are what the plugin's own bundles carried, so they are written
# too. Not for the .asc files: the plugin produced none for those, and a bundle that differs from a
# known-good one in a way nobody asked for is a risk taken for no gain.
say "checksumming"
find "$STAGE" -type f ! -name '*.asc' | while read -r f; do
    ( cd "$(dirname "$f")" && b=$(basename "$f") && {
        md5 -q "$b" | tr -d '\n' > "$b.md5"
        shasum -a 1   "$b" | cut -d' ' -f1 | tr -d '\n' > "$b.sha1"
        shasum -a 256 "$b" | cut -d' ' -f1 | tr -d '\n' > "$b.sha256"
        shasum -a 512 "$b" | cut -d' ' -f1 | tr -d '\n' > "$b.sha512"
    } )
done

# ------------------------------------------------------------------ layout check
#
# The layout is the thing Central validates, so assert it here rather than learning it from a
# rejected upload. Two rules, both of them things a rejected bundle has actually failed on:
#
#   - no directory may hold files without a .pom (the maven-metadata-local.xml failure, exactly);
#   - every artifact needs its .asc and its md5 and sha1.
#
# This is the check that would have caught the first attempt before it was uploaded, and it is the
# reason a --dry-run is worth running at all: it is the whole validation, minus the network.
say "checking the layout"
python3 - "$STAGE" <<'PYCHECK'
import pathlib, sys

stage = pathlib.Path(sys.argv[1])
problems = []

for d in sorted(p for p in stage.rglob('*') if p.is_dir()):
    files = [f for f in d.iterdir() if f.is_file()]
    if files and not any(f.name.endswith('.pom') for f in files):
        problems.append(f"{d.relative_to(stage)}/ holds {len(files)} file(s) and no .pom")

for artifact in sorted(stage.rglob('*.pom')) + sorted(stage.rglob('*.jar')):
    for suffix in ('.asc', '.md5', '.sha1'):
        if not artifact.with_name(artifact.name + suffix).is_file():
            problems.append(f"{artifact.relative_to(stage)} has no {suffix}")

if problems:
    print('the bundle would be rejected:', file=sys.stderr)
    for p in problems:
        print('  ' + p, file=sys.stderr)
    sys.exit(1)

files = sorted(f for f in stage.rglob('*') if f.is_file())
print(f"  {len(files)} files")
for f in files:
    print(f"    {f.relative_to(stage)}")
print('  layout checked: every directory with files has a .pom, every artifact has .asc + .md5 + .sha1')
PYCHECK

# ------------------------------------------------------------------ sign off on the signatures
#
# The layout check proves a .asc exists; only gpg proves it is a signature of the file beside it. One
# file is enough -- they are all produced by the same execution -- and verifying every one would add
# nothing but time.
#
# Checked here, before the upload, for the same reason as the layout: a signature Central rejects is
# a rejected bundle, and the upload is the whole version.
say "verifying a signature"
gpg --verify "$STAGE/$REPO_PATH/$ARTIFACT-$VERSION.pom.asc" "$STAGE/$REPO_PATH/$ARTIFACT-$VERSION.pom"

# ------------------------------------------------------------------ pack
# Zipped from inside the staging directory, so the archive's entries are the repository paths and not
# a wrapping directory: Central reads the bundle as a repository tree.
say "packing the bundle"
BUNDLE="$ROOT/target/central-publish/central-bundle.zip"
( cd "$STAGE" && zip -q -r -X "$BUNDLE" . )
echo "  central-bundle.zip ($(wc -c < "$BUNDLE" | tr -d ' ') bytes)"

if [ "$DRY_RUN" -eq 1 ]; then
    say "dry run -- nothing was uploaded"
    echo "  the bundle above is what would be sent to the Central Portal, and it is at"
    echo "    $BUNDLE"
    echo "  nothing has touched the network and no deployment record exists."
    exit 0
fi

# ------------------------------------------------------------------ upload
#
# The Portal validates asynchronously. Note POST, not GET: the GET form of this endpoint answers 500
# for every deployment.
say "uploading to the Central Portal"
ID=$(curl -sS -X POST -H "Authorization: Bearer $TOKEN" \
        -F "bundle=@$BUNDLE" \
        "https://central.sonatype.com/api/v1/publisher/upload?name=$ARTIFACT-$VERSION")
echo "  deployment id: $ID"

say "waiting for validation"
state=""
i=0
while [ "$i" -lt 40 ]; do
    i=$((i + 1))
    sleep 5
    RESP=$(curl -sS -X POST -H "Authorization: Bearer $TOKEN" \
             "https://central.sonatype.com/api/v1/publisher/status?id=$ID")
    state=$(printf '%s' "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["deploymentState"])' 2>/dev/null || echo "?")
    [ "$state" = "VALIDATED" ] && break
    case "$state" in FAILED) break ;; esac
done

if [ "$state" != "VALIDATED" ]; then
    echo >&2
    echo "deployment $ID is $state, not VALIDATED. The Portal's own reasons:" >&2
    printf '%s' "$RESP" | python3 -c '
import json, sys
d = json.load(sys.stdin)
for group, messages in (d.get("errors") or {}).items():
    for m in messages:
        print(f"  [{group}] {m}")
' >&2
    echo >&2
    echo "Nothing was published. The bundle is at $BUNDLE if it is worth inspecting." >&2
    exit 1
fi
echo "  VALIDATED"

# ------------------------------------------------------------------ publish
#
# Deliberately two steps. Uploading is reversible -- a validated deployment that is never published
# simply expires -- and publishing is not: a version on Central cannot be withdrawn, and a version
# number that has been used can never be used again.
if [ "$RELEASE" -eq 0 ]; then
    say "validated, not published"
    echo "  Publishing is permanent: a version cannot be withdrawn from Central once it is out."
    echo "  To publish this deployment:"
    echo "    ./scripts/publish-central.sh --release   (rebuilds and uploads a fresh one)"
    echo "  or release the one that is already validated:"
    echo "    curl -X POST -H \"Authorization: Bearer ...\" \\"
    echo "      https://central.sonatype.com/api/v1/publisher/deployment/$ID"
    exit 0
fi

say "publishing"
curl -sS -X POST -H "Authorization: Bearer $TOKEN" \
     "https://central.sonatype.com/api/v1/publisher/deployment/$ID" -o /dev/null -w '  HTTP %{http_code}\n'

say "waiting for it to reach Maven Central"
i=0
while [ "$i" -lt 60 ]; do
    i=$((i + 1))
    sleep 20
    code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 \
             "https://repo1.maven.org/maven2/$REPO_PATH/$ARTIFACT-$VERSION.pom" || true)
    if [ "$code" = "200" ]; then
        echo "  live: https://repo1.maven.org/maven2/$REPO_PATH/"
        exit 0
    fi
    printf '  [%s/60] repo1 answers %s\n' "$i" "$code"
done

echo "  still not on repo1.maven.org after 20 minutes. That is not necessarily a failure --"
echo "  Central's own sync can take up to a couple of hours -- check the deployment:"
echo "    https://central.sonatype.com/publishing/deployments"
