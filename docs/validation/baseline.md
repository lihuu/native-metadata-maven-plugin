# Baseline and migration record

Date: 2026-10-15
Worktree: `/Users/lihu/.codex/worktrees/jdk17-asm/native-metadata-maven-plugin`
Branch: `codex/jdk17-asm`, base `109ceaf` (`main`).

This file records what was actually executed, with the commands and the observed results. Anything
that was not executed on this machine is marked `NOT_RUN`; see
[migration-results.md](migration-results.md) for the per-acceptance-item status.

## 1. State before the migration

| Observation | Evidence |
| --- | --- |
| `maven.compiler.release = 25` and a duplicated `<release>25</release>` in `maven-compiler-plugin` | `pom.xml` at `109ceaf` |
| The scanner used `java.lang.classfile.*` (JDK 22+ preview, final in 24) | `ClassfileScanner.java` at `109ceaf` |
| `mvn -B -ntp clean verify` on GraalVM 25 / Maven 3.10.0: BUILD SUCCESS, 35 tests | `target/compat/baseline/gv25-verify.log` |
| The one integration test reused `~/.m2/.../native-metadata-maven-plugin/1.0.0` when present, skipped `native-image` when absent, and reflected on constant class and method names | `src/test/java/.../it/SampleProjectIntegrationTest.java` at `109ceaf` |
| A stale 1.0.0 JAR was in fact present in the local repository (built 09:10 the same day) | `~/.m2/repository/io/github/lihuu/native-metadata-maven-plugin/1.0.0/`, hash recorded below |

Pre-migration artifact kept as evidence (not committed):

```
/private/tmp/native-metadata-pi-asm/artifacts/pre-migration/native-metadata-maven-plugin-1.0.0.jar
sha256 cac5d0da518f547c2e25b55180c62cd08f767514c61b6b74474aa3b395ec31ce
MANIFEST: Java-Version: 25, Build-Jdk-Spec: 25
```

## 2. Negative control: the un-migrated plugin on a real JDK 17

Probe project (plain Java, `--release 17`) declaring `native-metadata:analyze` and
`native-metadata:prune`, consuming the pre-migration JAR from an **isolated** repository:

```
JAVA_HOME=<graalvm-community-jdk-17.0.9> mvn -Dmaven.repo.local=<probe>/repo \
    install:install-file -Dfile=<pre-migration jar> -DpomFile=<pre-migration pom>
JAVA_HOME=<graalvm-community-jdk-17.0.9> mvn -Dmaven.repo.local=<probe>/repo clean package
```

Result (`target/compat/baseline/pre-migration-jdk17/`):

```
Failed to execute goal io.github.lihuu:native-metadata-maven-plugin:1.0.0:analyze (a) on project probe:
Unable to load the mojo 'analyze' ... due to an API incompatibility:
io/github/lihuu/nativemetadata/AnalyzeMojo has been compiled by a more recent version of the Java
Runtime (class file version 69.0), this version of the Java Runtime only recognizes class file
versions up to 61.0
```

The same artifact and the same isolated repository with GraalVM 25 succeeds
(`Native metadata analysis summary: 1 owned types, ...`, `BUILD SUCCESS`), which shows the failure is
the plugin's bytecode level and not the harness.

## 3. Post-migration state

| Item | Value |
| --- | --- |
| Single bytecode backend | ASM 9.11, streaming `ClassVisitor`; only `org.ow2.asm:asm` is a runtime dependency |
| Production and test compilation target | `maven.compiler.release=17` (single source of truth in `<properties>`) |
| Plugin descriptor | `requiredJavaVersion=17`, `requiredMavenVersion=3.9.9` |
| Plugin class-file major version | 61 |
| Unit tests on GraalVM 17 | 53/53 pass on the final artifact source, including reading Java 21 (65) and Java 25 (69) class files |
| Pre-generated fixtures | `scripts/build-high-version-fixtures.sh` emits `highversion-{17,21,25}.jar` (major 61/65/69) |

Fixes made because a test would otherwise have been able to pass without proving anything:

1. The self-installing Surefire integration test was removed. Integration testing moved to
   `maven-invoker-plugin` 3.10.1 with `localRepositoryPath=target/it-repo`, so the sample resolves the
   plugin built *by this run* and cannot pick up a stale same-coordinate JAR.
2. `native-image` being absent no longer turns the native path into a silent pass: the native groups
   are separate, gated profile runs that fail when the tool is missing.
3. The sample application derives the class name, constructor parameter type, field name, method
   name and method parameter types from run-time input, so the compiler cannot fold the reflection
   target into a constant.
4. `JarFile.versionedStream()` correctly selects versioned class entries for the running JVM.
   Resources under `META-INF` cannot be versioned under the JAR specification. The final scanner
   retains the standard JDK selection; `MrJarMetadataSelectionTest` confirms both class selection and
   ignoring nested versioned metadata. The temporary custom `MultiReleaseJarSupport` was removed.


## 4. Toolchains

See [toolchains.json](toolchains.json) for URLs and digests. Summary of the decision about the
spec's pinned GraalVM 22.3.3:

- The `vm-22.3.3` release publishes no `darwin-aarch64` asset (the URL for
  `graalvm-ce-java17-darwin-aarch64-22.3.3.tar.gz` returns 404; the release only lists
  `darwin-amd64`), so it cannot run on this development machine.
- GraalVM CE 22.3.x does not bundle `native-image`; it must be added with `gu install native-image`,
  which would make CI differ from what is verified locally.
- GraalVM CE for JDK **17.0.9** bundles `native-image 17.0.9` and is used for both local runs and CI.

This is a recorded toolchain revision, not a skipped verification.
