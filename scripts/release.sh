#!/bin/sh
# Cut a release of native-metadata-maven-plugin.
#
# Usage: release.sh [major|minor|patch|x.y.z] [options]
#
#   --dry-run          say what would happen, and change nothing
#   --no-push          commit and tag locally, stop there
#   --no-branch-check  allow the run from a branch other than main (for checking this script)
#
# ---------------------------------------------------------------------------------------------
# Why this is a script and not three git commands
#
# One artifact, one version. The version is written in exactly two places:
#
#   pom.xml      <version>            the project's own version, and what Maven publishes
#   README.md    the <version> in the usage snippet
#
# The pom is the version. The README's copy is documentation that has to agree with it, because a
# usage snippet pinning a version that was never released is worse than no snippet: it looks
# authoritative and it does not resolve.
#
# This is the deliberate difference from the panamaview release script this is modelled on, where
# thirteen version strings exist and seven move. There is no reactor here, no parent pom, no template
# pins, no package.json -- so the code that walked modules and rewrote template pins is not simplified
# into this script, it is absent from it. A rule that has nothing to apply to is a rule that rots.
#
# What deliberately does NOT move:
#
#   pom.xml's plugin and dependency <version> elements. maven-surefire-plugin 3.5.2 is not a fact
#   about this project's version and must never be rewritten by a bump; it is why the pom bump below
#   is anchored to the project's own coordinates rather than to "the first <version> in the file".
#
# Both files are checked for agreement before anything moves. A release that fails after the tag
# exists is one that cannot be backed out of, since a pushed tag is a public claim that the version
# is real.
# ---------------------------------------------------------------------------------------------
set -eu
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

BUMP=""
DRY_RUN=0
NO_PUSH=0
BRANCH_CHECK=1

say() { printf '\n=== %s\n' "$1"; }
fail() { printf 'release.sh: %s\n' "$1" >&2; exit 1; }

# ------------------------------------------------------------------ arguments
#
# Options are recognised anywhere, and the version is found by elimination -- anything that is not an
# option. The other way round would let a mistyped option be silently used as a version string.
for arg in "$@"; do
    case "$arg" in
        --dry-run)         DRY_RUN=1 ;;
        --no-push)         NO_PUSH=1 ;;
        --no-branch-check) BRANCH_CHECK=0 ;;
        -h|--help)         sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*)                fail "unknown option: $arg" ;;
        *)
            [ -z "$BUMP" ] || fail "more than one version given: '$BUMP' and '$arg'"
            BUMP="$arg"
            ;;
    esac
done
[ -n "$BUMP" ] || fail "no version given; usage: release.sh [major|minor|patch|x.y.z] [--dry-run]"

# ------------------------------------------------------------------ a tree we can tag
say "checking the working tree"
if [ "$BRANCH_CHECK" -eq 1 ]; then
    branch="$(git rev-parse --abbrev-ref HEAD)"
    [ "$branch" = "main" ] || fail "on '$branch'; releases are cut from main (--no-branch-check overrides, for checking this script)"
    echo "  on main"
else
    echo "  branch check skipped (--no-branch-check), on $(git rev-parse --abbrev-ref HEAD)"
fi
[ -z "$(git status --porcelain)" ] || {
    git status --short >&2
    fail "uncommitted changes; commit them first, so the tag matches what was verified"
}
echo "  clean"

# ------------------------------------------------------------------ what we are releasing
#
# Read from the pom by the same header rule publish-central.sh uses, and for the same reason: this
# pom names a <version> for every plugin and dependency it declares, so "the first <version> in the
# file" is only the project's own while there is no <parent> above it. Asserted, not assumed.
CURRENT=$(python3 - "$ROOT/pom.xml" <<'PY'
import pathlib, re, sys

path = pathlib.Path(sys.argv[1])
text = re.sub(r"<!--.*?-->", "", path.read_text(encoding="utf-8"), flags=re.S)

if "<parent>" in text:
    sys.exit("release.sh: pom.xml has a <parent>; the first <version> is no longer the project's "
             "own, so the rule this script reads it with needs revisiting")

header = re.split(r"<(?:properties|dependencies|build|profiles|distributionManagement)>", text)[0]
match = re.search(r"<version>([^<]+)</version>", header)
if not match:
    sys.exit("release.sh: no <version> in the pom header")
print(match.group(1))
PY
) || exit 1

# ------------------------------------------------------------------ what the next version is
if printf '%s' "$BUMP" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    NEXT="$BUMP"
else
    NEXT=$(python3 - "$CURRENT" "$BUMP" <<'PY'
import sys

current, bump = sys.argv[1], sys.argv[2]
parts = current.split(".")
if len(parts) != 3 or not all(p.isdigit() for p in parts):
    sys.exit(f"cannot bump '{current}': not x.y.z")
major, minor, patch = (int(p) for p in parts)

if bump == "major":
    major, minor, patch = major + 1, 0, 0
elif bump == "minor":
    minor, patch = minor + 1, 0
elif bump == "patch":
    patch += 1
else:
    sys.exit(f"unknown bump '{bump}'; expected major, minor, patch or x.y.z")
print(f"{major}.{minor}.{patch}")
PY
) || exit 1
fi

say "releasing $CURRENT -> $NEXT"
git rev-parse -q --verify "refs/tags/v$NEXT" >/dev/null && fail "tag v$NEXT already exists"

# ------------------------------------------------------------------ everything agrees before we move it
#
# If the two files already disagree, bumping them would keep the disagreement and the release would
# fail after the tag exists. Cheap to check first, and it is also the check that catches a README
# snippet someone edited by hand.
say "checking the version fields currently agree"
python3 - "$ROOT" "$CURRENT" <<'PY'
import pathlib, re, sys

root, current = pathlib.Path(sys.argv[1]), sys.argv[2]
problems = []

pom = root / "pom.xml"
header = re.split(r"<(?:properties|dependencies|build|profiles|distributionManagement)>",
                  re.sub(r"<!--.*?-->", "", pom.read_text(encoding="utf-8"), flags=re.S))[0]
found = re.search(r"<version>([^<]+)</version>", header)
if not found or found.group(1) != current:
    problems.append(f"pom.xml is {found.group(1) if found else 'unreadable'}")

readme = root / "README.md"
# The plugin's own <version> inside the usage snippet, not the maven-metadata-maven-plugin
# <artifactId> line above it. Anchored on <version> so an unrelated mention of the number in prose
# cannot be counted as one of these.
pins = re.findall(r"<version>([^<]+)</version>", readme.read_text(encoding="utf-8"))
if len(pins) != 1:
    problems.append(f"README.md has {len(pins)} <version> element(s), expected exactly 1 "
                    f"(the usage snippet); a second one is a copy that will drift")
elif pins[0] != current:
    problems.append(f"README.md is {pins[0]}")

if problems:
    print("\n".join("  " + p for p in problems), file=sys.stderr)
    sys.exit(f"the version fields disagree with {current}; fix them before releasing")
print(f"  pom.xml and README.md both at {current}")
PY

# ------------------------------------------------------------------ dry run
#
# A dry run does not write, does not commit and does not tag -- it prints the plan. It reports the
# three things that decide whether the real run would work: the next version, whether the tag name is
# still free, and whether the files to be rewritten are the ones this script thinks they are.
#
# It deliberately does not run the build. `mvn -Pcentral verify` is what the release will run, and
# running it here would make --dry-run an expensive way to print a plan; the acceptance evidence for
# the build is publish-central.sh --dry-run, which does exactly that and uploads nothing.
if [ "$DRY_RUN" -eq 1 ]; then
    say "dry run: this is what would happen; nothing has been changed"
    echo "  1. rewrite pom.xml            <version>$CURRENT</version> -> <version>$NEXT</version>"
    echo "  2. rewrite README.md          <version>$CURRENT</version> -> <version>$NEXT</version>  (usage snippet)"
    echo "  3. mvn -B -Pcentral verify    the full build, tests, sources/javadoc jars and signatures"
    echo "  4. git commit                 chore(release): bump version to $NEXT"
    echo "  5. git tag -a v$NEXT"
    if [ "$NO_PUSH" -eq 1 ]; then
        echo "  6. (not) git push origin main && git push origin v$NEXT   [--no-push]"
    else
        echo "  6. git push origin main && git push origin v$NEXT"
    fi
    echo
    echo "  Not touched by this script: pom.xml's plugin and dependency <version> elements, and the"
    echo "  historical version numbers in docs/. A released version's notes are a record, not a pin."
    echo
    echo "  Publishing is a separate step, after the tag, and it is one command:"
    echo "    ./scripts/publish-central.sh --publish  # build, sign, check, upload, validate, publish"
    echo "    ./scripts/publish-central.sh --dry-run  # the same, but uploads nothing"
    exit 0
fi

# ------------------------------------------------------------------ bump
#
# Anchored to the project's own coordinates rather than to the first <version> in the file. The pom
# declares maven-surefire-plugin 3.5.2 and maven-compiler-plugin 3.14.0; an unanchored rewrite is one
# edit away from turning those into the project's version, and it would do it silently.
say "bumping the version"
python3 - "$ROOT" "$CURRENT" "$NEXT" <<'PY'
import pathlib, re, sys

root, current, next_ = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]

def rewrite(path, anchor_pattern, replacement):
    text = path.read_text(encoding="utf-8")
    new, count = re.subn(anchor_pattern, replacement, text, count=1)
    if count != 1:
        sys.exit(f"{path.relative_to(root)}: matched {count} times, expected exactly 1; the file "
                 f"has changed shape and this script's anchor for it is stale")
    path.write_text(new, encoding="utf-8")
    print(f"  {str(path.relative_to(root)):12} {current} -> {next_}")

# The artifactId is the anchor: it is directly above the project's own <version>, and no plugin or
# dependency in this pom declares <artifactId>native-metadata-maven-plugin</artifactId>.
rewrite(root / "pom.xml",
        r"(<artifactId>native-metadata-maven-plugin</artifactId>\s*<version>)" + re.escape(current) + r"(</version>)",
        r"\g<1>" + next_ + r"\g<2>")

# The README's usage snippet, anchored the same way -- on the artifactId that precedes the pin.
rewrite(root / "README.md",
        r"(<artifactId>native-metadata-maven-plugin</artifactId>\s*<version>)" + re.escape(current) + r"(</version>)",
        r"\g<1>" + next_ + r"\g<2>")
PY

# ------------------------------------------------------------------ prove it, then commit it
#
# Verification runs after the bump rather than before it, and that ordering is the point: what gets
# tagged is the tree at the new version, and the build has to be green on that tree -- a pom whose
# version changed and whose build was only ever checked at the old one has been verified for a state
# that no longer exists.
#
# Failure here leaves the tree edited, so it is put back. A bump that stays after a failed build is a
# half-release sitting in the working tree, and the next attempt would find a dirty tree and refuse.
say "building and testing the bumped tree"
if ! mvn -B -Pcentral verify; then
    echo >&2
    echo "release.sh: the build failed; putting the version fields back" >&2
    git checkout -- pom.xml README.md
    exit 1
fi

# ------------------------------------------------------------------ commit + tag
say "committing"
git commit -q -m "chore(release): bump version to $NEXT" -- pom.xml README.md
echo "  chore(release): bump version to $NEXT"

# Annotated rather than lightweight: a lightweight tag is a bare pointer, and a release is a claim
# about what the code is, which deserves a message and a date of its own.
git tag -a "v$NEXT" -m "native-metadata-maven-plugin v$NEXT"
echo "  tagged v$NEXT"

if [ "$NO_PUSH" -eq 1 ]; then
    say "not pushing (--no-push)"
    echo "  when ready:  git push origin main && git push origin v$NEXT"
    exit 0
fi

say "pushing"
git push -q origin main
git push -q origin "v$NEXT"
echo "  main and v$NEXT pushed"

say "v$NEXT is tagged"
echo "  The workflow on the tag checks that it agrees with the pom:  gh run list --workflow=release.yml --limit 1"
echo "  To publish it:  ./scripts/publish-central.sh --publish   (one command, irreversible)"
