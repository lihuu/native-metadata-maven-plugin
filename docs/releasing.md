# Releasing

How a version of `native-metadata-maven-plugin` is cut, and what has to agree before it is.

Releasing and publishing are two separate steps here. A release is a git tag; publishing is a bundle
on Maven Central. Only the second one is permanent, so only the second one can go wrong.

```sh
./scripts/release.sh --dry-run patch    # say what would happen; changes nothing
./scripts/release.sh patch              # bump, verify, commit, tag, push
./scripts/publish-central.sh            # build, sign, check the layout, upload
./scripts/publish-central.sh --release  # ... and publish it (irreversible)
```

---

## 1. Version fields

There is **one artifact and one version**, written in exactly two files:

| File | Field | Moves on release? | Meaning |
| :--- | :--- | :---: | :--- |
| `pom.xml` | `<version>` | **Yes** | The project's version. The single source of truth; this is what Maven publishes. |
| `README.md` | `<version>` in the usage snippet | **Yes** | The version a reader copies into their own pom. |
| `pom.xml` | every plugin and dependency `<version>` | No | `maven-surefire-plugin` 3.5.2 is not a fact about this project. |
| `docs/*.md` | past release notes | No | A released version's notes are a record, not a pin. Editing them turns evidence into fiction. |

`scripts/release.sh` rewrites the first two and **asserts exactly one match in each**. Anchoring is
why: this pom declares a `<version>` for every plugin and dependency it has, so "the first `<version>`
in the file" is the project's own only while there is no `<parent>` above it. Both scripts assert that
there is no `<parent>`, and both refuse to run rather than guess if one appears.

This is the deliberate simplification from the sibling `panamaview` repository, where thirteen version
strings exist and seven move across a reactor, five templates and an npm package. There is no reactor,
no parent pom, no template pin and no `package.json` here, so that machinery is not simplified into
this repository — it is absent from it.

## 2. What `release.sh` does

```sh
./scripts/release.sh [major|minor|patch|x.y.z] [--dry-run] [--no-push] [--no-branch-check]
```

In order:

1. Refuses unless the tree is clean and (unless `--no-branch-check`) the branch is `main`. A tag cut
   over uncommitted edits is a tag on code nobody reviewed.
2. Reads the current version from the pom header.
3. Computes the next version: `major`, `minor`, `patch`, or an explicit `x.y.z`.
4. Refuses if the tag `vX.Y.Z` already exists. A version number on Central can never be used twice, so
   reusing a tag name is exactly the mistake that cannot be undone later.
5. Checks that `pom.xml` and `README.md` currently agree. If they already disagree, bumping them keeps
   the disagreement and the release fails after the tag exists — cheap to check first.
6. Bumps both files.
7. Runs `mvn -B -Pcentral verify` on the **bumped** tree. The ordering is the point: what gets tagged
   is the bumped tree, and a build that was only ever green at the old version has been verified for a
   state that no longer exists. If it fails, the version fields are put back, so a failed release
   never leaves a half-release sitting in the working tree.
8. Commits `chore(release): bump version to X.Y.Z`, creates the annotated tag `vX.Y.Z`, pushes `main`
   and the tag.

`--dry-run` prints all of that as a numbered plan, checks that the next version computes and that the
tag name is free, and **writes nothing** — no file is modified, nothing is committed, nothing is
tagged. It does not run the build; use `scripts/publish-central.sh --dry-run` for a build that
proves the artifact set, since that is the same build and it uploads nothing either.

`--no-push` commits and tags locally and stops.

## 3. CI on the tag

`.github/workflows/release.yml` runs on `v*` tags and checks one thing: that the tag matches both
version fields and that the tagged revision builds. It exists because a pushed tag is the last moment
to notice a mismatch — the tag is public and cannot be quietly taken back, and a hand-made
`git tag` never went through `release.sh`.

```sh
gh run list --workflow=release.yml --limit 1
```

## 4. After the tag

The tag is the release. Publishing it to Maven Central is the next step and is not automatic —
see [publishing.md](publishing.md).
