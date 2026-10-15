# Migration results and acceptance record

Date: 2026-10-15
Worktree: `/Users/lihu/.codex/worktrees/jdk17-asm/native-metadata-maven-plugin`, branch `codex/jdk17-asm`, base `109ceaf`.
Artifact under test: `io.github.lihuu:native-metadata-maven-plugin:1.1.0`,
sha256 `3881f13ceab5f47fbe200b0b66f4cc168986bdcbf8e75b0b18084d9e0d22ff18`,
built once with GraalVM CE 25.0.2 / Maven 3.9.9 at `--release 17` (class-file major 61).

Source candidate commit: `c20b952726128af25b757ce7325db7039f84f2ce`. The production sources and POM in that commit
match the source manifest of the frozen artifact verified below. Documentation-only follow-ups do
not change the tested implementation. `1.1.0` remains an unpublished candidate; no release has been cut.

Evidence paths below are relative to `/private/tmp/native-metadata-pi-asm/`.

Statuses are `PASS`, `FAIL` or `NOT_RUN`. `NOT_RUN` is never a synonym for "probably fine".

## Support matrix actually exercised on this machine

| Role | Versions |
| --- | --- |
| Maven JVM | GraalVM CE 17.0.9, Oracle GraalVM 21.0.12, GraalVM CE 25.0.2, Eclipse Temurin 17.0.20.1 |
| Maven | 3.9.9, 3.10.0 |
| `native-image` | the `native-image` of the same GraalVM that runs Maven |
| Host | macOS 27.2, arm64 |

The Linux x86_64 and macOS jobs have since been executed on the pull request; see
[CI run on the pull request](#ci-run-on-the-pull-request) and AC-11. Everything else below is the
local record.

## Acceptance items

| ID | Requirement | Status | Evidence |
| --- | --- | --- | --- |
| AC-01 | GraalVM 17 runs both goals and the native flow; plain OpenJDK 17 covers analysis/pruning | PASS | `evidence/frozen/plain-native-n17` (A/B/C, `native-image 17.0.9`), `evidence/frozen/matrix/O17-plain-openjdk-17` (Temurin 17.0.20.1: `analyze`, `prune`, `changelog.json`, consumed config changed) |
| AC-02 | Six consumption runs against one JDK-25-built JAR, with version and SHA-256 | PASS | All six `R{17,21,25}-maven-{3.9.9,3.10.0}` cases PASS; every `result.json` has `pluginSha256 == stagedPluginSha256 == 3881f13c...` and the in-Maven `mavenJavaFeature`/`mavenVersion` matching the job |
| AC-03 | JDK 17 compiles and runs the core tests; plugin classes are major 61; dependencies usable on 17 | PASS | `evidence/frozen/build25.log` (53 tests, 0 skipped), `U17` (53), `U21` (53), plus independent clean-source builds with actual JDK17/21 (`evidence/clean-source-jdk17.log`, `clean-source-jdk21.log`, each 53/53); all 49 published classes checked as major 61; ASM 9.11 + Jackson 2.18.3 are Java 8+ |
| AC-04 | JDK 17 reads real 21/25 class files and does not load application classes | PASS | `HighVersionInputTest` with pre-generated fixtures; marker file absent; fixtures proven unloadable |
| AC-05 | MR-JAR selection, source, overloads/annotations/records and reflection call sites match the sample's semantics | PASS | `MrJarMetadataSelectionTest` (versioned class selection; nested `META-INF` metadata ignored), `ClassfileScannerTest`, `MetadataAnalyzerTest` |
| AC-06 | Read-only analysis, dry-run, write boundaries, sources preserved, no partial application | PASS | `PruningApplierTest` (7 tests incl. symlinked output, symlinked candidate root, path traversal), `verify-compatibility.sh` asserts a `src/` tree hash before/after, and the Spring sample asserts A/B `src/` hash equality plus an unchanged set of Spring-AOT-generated files |
| AC-07 | Existing public/declared and constructor query permission regressions still pass | PASS | `PruningEngineTest` (25 tests), `UnknownMemberCoverageTest` |
| AC-08 | Native A/B/C passes; the negative control fails for the right reason | PASS | `evidence/frozen/plain-native-n17` and `plain-native-n25`: A and B produce `hello-via-field:world` and `APP_OK`, B narrows the consumed config (changelog `NARROWED` + `DELETED`) and keeps the untouched `Unused` entry, C fails with `NoSuchMethodException: com.example.plain.Greeting.describe([Ljava/lang/String;)` |
| AC-09 | Real Spring AOT output reaches the report and its consumed native path is checked | PASS | `evidence/frozen/s17`, `s21`: `spring-boot:process-aot` runs before `native-metadata:analyze`/`prune`, the report classifies the AOT directory as `GENERATED` (`generatedMetadataDirectories`), and the recorded `-cp` shows `target/classes` first |
| AC-10 | Spring baseline vs pruned HTTP/dynamic behaviour equivalent, de-duplication effective, negative control valid | PASS | `evidence/frozen/s17`, `s21`: `http-normalised.json` identical between A and B for all three requests; the changelog deletes the self-owned duplicate and names the generated source; A/B `src/` hashes are identical and the set of files Spring AOT generated is unchanged (AOT *content* is deliberately not compared between runs: `Application__BeanFactoryRegistrations` is regenerated on every run and is not byte-stable for the same configuration, see the comment in `examples/spring-boot-native/verify.sh`); C removes the `RuntimeHints` registrar and fails on `/api/dynamic` |
| AC-11 | No required native scenario silently skipped; commands, exit codes and logs archived | PASS | Local runs assert the staged hash and fail when `native-image` is missing. The GitHub Actions matrix has now been executed on the pull request: run `38684961127` (head `d0f1f82`), **19/19 jobs green**, including `acceptance summary`, which fails unless every required job succeeded. Key lines from that run: `vendor: Eclipse Adoptium feature: 17` (O17), `consumed configuration changed: true` for each of the six consume cases, `outcomes: A-baseline-PASS,B-pruned-PASS,C-negative-PASS` (S21); see the CI section below |
| AC-12 | Maintainer terminal/IDEA walkthrough and at least one real project trial | PARTIAL | Real project trial: `java-pi` at `a4381a2c`, isolated copy, against this exact frozen hash - baseline native build + MCP loop, dry-run 102/102 owned types resolved with 0 changes, apply + native build + MCP loop; see [java-pi-trial.md](java-pi-trial.md). IDEA walkthrough: `NOT_RUN` - no IntelliJ installation exists on this machine and no computer-use tool is exposed, so the IDE gate cannot be performed here; the runbook is in [idea-walkthrough.md](idea-walkthrough.md) |
| AC-13 | README separates Maven JVM, build JDK, business target and native toolchain | PASS | `README.md` "Runtime and compatibility" |
| AC-14 | Every result belongs to the candidate artifact; release uses a new version number | PARTIAL | Every result is pinned to the 1.1.0 hash above and the harness refuses to run otherwise. The version number is new, so the published Java 25 `1.0.0` is not replaced. The tested sources are now committed as `c20b952726128af25b757ce7325db7039f84f2ce`. Tagging and publishing have not been performed; the release step remains open |
| AC-15 | Two independent Maven projects outside the checkout, consumed by coordinates only | PASS | Both actually executed from a kit containing no plugin source/classes/reactor; Spring included AOT, native build and real HTTP. See [detached-consumers.md](detached-consumers.md) for exact evidence and the corrected postcondition rerun |

## Runs against the frozen artifact

| Case | Maven JVM | Maven | Result |
| --- | --- | --- | --- |
| B25 (build + unit + non-native Invoker) | GraalVM CE 25.0.2 | 3.9.9 | 53 tests, 0 skipped |
| U17 / U21 | GraalVM CE 17.0.9 / Oracle GraalVM 21.0.12 | 3.9.9 | 53 tests, 0 skipped each |
| X17 explicit empty fixture dir | GraalVM CE 17.0.9 | 3.9.9 | FAIL as required |
| R17 x {3.9.9, 3.10.0} | GraalVM CE 17.0.9 | both | PASS |
| R21 x {3.9.9, 3.10.0} | Oracle GraalVM 21.0.12 | both | PASS |
| R25 x {3.9.9, 3.10.0} | GraalVM CE 25.0.2 | both | PASS |
| O17 (analysis/pruning only) | Eclipse Temurin 17.0.20.1 | 3.9.9 | PASS |
| N17 / N25 (plain native A/B/C) | GraalVM CE 17.0.9 / 25.0.2 | 3.9.9 | PASS |
| S17 / S21 (Spring AOT + native + HTTP A/B/C) | GraalVM CE 17.0.9 / Oracle GraalVM 21.0.12 | 3.9.9 | PASS |

Additional N25 case: `MAVEN_ARGS=-Dbusiness.release=25` with the detached plain sample, final plugin
hash, GraalVM25 and Maven3.9.9: **A/B/C PASS**, actual `App.class` major **69** confirmed.
Evidence: `evidence/plain-business25/{result.json,A-baseline,B-pruned,C-missing-registration}` and
`evidence/plain-business25.log`. The original N25 run used the sample's default release17; this extra
case proves the plan's separate Java25 business-bytecode requirement.

## CI run on the pull request

Run [`38684961127`](https://github.com/lihuu/native-metadata-maven-plugin/actions/runs/38684961127)
on head `d0f1f82` (implementation `c20b952` plus the harness fixes described below): **19 of 19
jobs PASS**, `acceptance summary` included. That job is the merge gate - it fails unless every
required job succeeded - so "19/19" is not a count of green checkmarks, it is one run in which
nothing was skipped.

The workflow could not pass at all before three harness defects were fixed. None of them was in
the plugin:

1. `scripts/verify-compatibility.sh` kept `--evidence` relative but used it inside `( cd "$WORK" && ... )`.
   CI passes `--evidence target/compat`, so the consumption log redirect failed before Maven ever
   started, and `-Dmaven.repo.local` would have pointed into the work copy - an empty repository
   instead of the one the candidate was staged into. All six consume jobs, the plain-OpenJDK-17 job
   and the macOS job died on this. `--evidence` is now resolved to an absolute path up front.
2. The Spring sample compared `target/spring-aot` byte for byte between two separate Maven runs.
   Spring AOT regenerates that directory on every run and `Application__BeanFactoryRegistrations` is
   not byte-stable for the same configuration: the GraalVM 21 job produced two different hashes for
   it across three runs while the untouched `src/` stayed identical. `src/` is still compared byte for
   byte; the generated directory is now checked by file set, which still catches the plugin adding or
   removing anything there.
3. The plain-OpenJDK-17 job asserted on `environment.json["javaVendor"]`, a key that file does not
   have. The in-Maven record is `javaVendorInMavenProcess`; `javaVendor` is the key in `result.json`.
   The step had never executed that far before, so it only surfaced as `KeyError: 'javaVendor'` once
   item 1 was fixed. It now reads the in-Maven record, asserts the feature version is 17, and
   cross-checks the vendor against `result.json`.

Which artifact CI consumes differs from the frozen one above: the matrix consumes the JAR the `build`
job produces in the same run (`sha256 c394f4639157b65f37c818942b64306cd404935999651a323d02c649045a9d23`
for run 38684961127, where the previous run produced a different hash from the same sources). JARs
are not bit-reproducible - timestamps - so the hash is per-run, and each consume case asserts the
staged hash equals the input hash before it runs. The property the matrix proves is unaffected: one
artifact, built once on JDK 25, consumed by all six JDK/Maven combinations, both native suites and
the macOS job.

## What the false-green search found

1. The old Surefire integration test reused a stale `~/.m2` 1.0.0 JAR, skipped `native-image` when
   absent, and reflected on constant names. Removed; replaced by Invoker + isolated repository.
2. `JarFile.versionedStream()` was assumed to resolve `META-INF/versions/N/...` for every path. It does
   not do so for resources under `META-INF` — the JAR specification forbids versioning them. A first
   attempt to "fix" this invented versioned metadata overrides, which would have let a file no JVM
   reads justify deleting a real registration. Reverted; the test now asserts the specification's
   behaviour instead.
3. `io.github.lihuu:native-metadata-maven-plugin:1.0.0` **is published on Maven Central** and is the
   Java 25 build. A consumption run that failed to stage the candidate artifact silently downloaded
   the published one: on JDK 25 it looked like a pass, and on JDK 17/21 it failed with
   `class file version 69.0`. Every harness now stages the artifact, asserts the staged hash, and (in
   the sample verifiers) refuses to build when the artifact is not staged. The candidate version is
   `1.1.0` so the published `1.0.0` can never be mistaken for it.
4. `PASS` no longer follows from Maven exiting 0: the harness requires the in-process environment
   record, the analysis report, the changelog, unchanged sources and a changed consumed configuration.

## Harness-only changes made by the parent after the freeze

These changed no production code and no artifact byte; the frozen hash stayed the same:

- `.github/workflows/ci.yml` and `scripts/ci-install-toolchain.sh`: fixture download moved out of
  `target/`, flattened staged-artifact upload, correct plain-JDK vendor key, native/Spring jobs now
  consume the build job's artifact instead of rebuilding the plugin on their own JDK, macOS job
  consumes the same staged artifact, and the installer no longer relies on a macOS Homebrew path.
  The installer was exercised with the real GraalVM 17 and Maven 3.9.9/3.10.0 archives.
- `docs/validation/idea-walkthrough.md`: the IDEA runbook, `NOT_RUN`.
- A detached-kit proof (no plugin source, classes or reactor) with a working directory containing
  spaces, real GraalVM 17 / Maven 3.9.9 and the frozen artifact: `evidence/detached-kit/space-path/`.
- Per-case dependency caches under `evidence/frozen/matrix/*/repo`; each case still installs the
  frozen artifact itself and verifies its hash, so no case can silently borrow another's plugin.

## Deliberate non-goals

- No second bytecode backend, no Class-File API fallback, no JDK 25 scanner subprocess.
- No automatic "delete what nothing references" rule.
- No claim that pruning makes binaries smaller or faster; no measurement of that was made.
- No support claim beyond GraalVM/OpenJDK 17, 21, 25 and Maven 3.9.9/3.10.0.
