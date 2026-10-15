# Native Metadata Maven Plugin

A standalone Maven plugin for GraalVM Native Image reflection metadata static analysis, de-duplication, and rule-based pruning.

Runs on **Java 17 or newer** Maven JVMs and reads class files with a single **ASM** backend, so a Java 17 JVM can inspect Java 21/25 bytecode without loading it. See [Runtime and compatibility](#runtime-and-compatibility) for the support matrix and the [validation record](docs/validation/migration-results.md) for what has actually been executed.

## Runtime and compatibility

Three Java versions are easy to confuse, so they are listed separately:

| Role | What it is | What this project promises |
| --- | --- | --- |
| JDK running Maven | The JVM the plugin executes in | GraalVM/OpenJDK **17, 21, 25**. Ordinary OpenJDK 17 works for `analyze`/`prune` |
| JDK used to build the plugin | Only the maintainer's build | GraalVM 25 (the released classes are Java 17, major version 61) |
| `native-image` toolchain | What turns the application into a binary | The same GraalVM that runs Maven, unless a path is given explicitly |

Maven 3.9.9 and 3.10.0 are verified. Maven 4 and Java 8/11 are out of scope for this release.

An input class file the scanner cannot read is an **error**, not something to step over: a class file
that is too short or corrupt, or one whose class-file version is newer than the bundled ASM supports,
fails `analyze`/`prune` with the file named. Silently skipping it would report a class as
unresolvable or unused, and pruning decisions are made from that report. (1.0.0 skipped such files.)

For a native-image project, the recommended setup is to point the IDE project SDK **and** the Maven Runner JRE at the project's own GraalVM (17, 21 or 25). The plugin never requires a second JDK just for scanning, and it never starts a subprocess to inspect bytecode.

## Goals

### `native-metadata:analyze`
Performs static inspection of application bytecode and compares it against reflection registrations:
- Extracts classes, methods, fields, record components, member references, reflection call sites (`Class.forName`, `getMethod`, `getDeclaredField`, etc.), and annotations (`@JsonProperty`, `@JacksonXmlProperty`, etc.).
- Categorizes reflection registrations: self-owned (`src/main/resources/META-INF/native-image`) vs dependency vs generated; broad (`allDeclared*`, `queryAll*`) vs explicit members.
- Reads framework-generated metadata passed through `generatedMetadataDirectories` as a read-only source. With Spring Boot, point it at `${project.build.directory}/spring-aot/main/resources`.
- Identifies candidates for pruning (e.g. registered members not referenced in bytecode or dynamic traces).
- Outputs machine-readable `target/native-metadata/report.json`, human-readable Markdown `target/native-metadata/report.md`, and `target/native-metadata/environment.json` (the JVM, vendor and Maven version read inside the running Maven process).

### `native-metadata:prune`
Optimizes native metadata configurations in compilation outputs (`target/classes/META-INF/native-image/...`):
- **De-duplication**: Prunes exact duplicates and registrations fully covered by wider registrations within the same resource or external dependencies.
- **Explicit Member Narrowing**: Applies user-configured `rulesFile` to replace broad registrations with fine-grained field and method lists.
- **Safety Boundary**: Modifies only compilation outputs in `target/classes`, never modifying source files in `src/main/resources`. Supports `dryRun`.
- **Audit Trail**: Generates `target/native-metadata/changelog.json` documenting every changed entry and its rationale.

## Usage

```xml
<plugin>
    <groupId>io.github.lihuu</groupId>
    <artifactId>native-metadata-maven-plugin</artifactId>
    <version>1.2.0</version>
    <executions>
        <execution>
            <id>prune-metadata</id>
            <phase>process-classes</phase>
            <goals>
                <goal>prune</goal>
            </goals>
            <configuration>
                <rulesFile>${project.basedir}/rules.json</rulesFile>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Rules File Format (`rules.json`)

```json
[
  {
    "resource": "META-INF/native-image/com.example/app/reflect-config.json",
    "type": "com.example.app.MyModel",
    "replacement": {
      "name": "com.example.app.MyModel",
      "fields": [{"name": "id"}],
      "methods": [
        {"name": "<init>", "parameterTypes": []},
        {"name": "getId", "parameterTypes": []}
      ]
    }
  }
]
```

`generatedMetadataDirectories` takes one or more directories of framework-generated metadata (for
Spring Boot: `${project.build.directory}/spring-aot/main/resources`). They are read as read-only
sources, they participate in the report and in coverage decisions, and the plugin never writes into
them. A configured directory that does not exist fails the build rather than being silently ignored.

## Worked examples

Two standalone projects consume the plugin only through its Maven coordinates. They are not modules of this build; copy them anywhere and run them there.

- [`examples/plain-native`](examples/plain-native/README.md) - a small application whose reflection targets come from the command line. Runs baseline / pruned / missing-registration (`A`/`B`/`C`) against `native-image`.
- [`examples/spring-boot-native`](examples/spring-boot-native/README.md) - Spring Boot 3.5.16 with real `process-aot` output, a Jackson record, and a dynamic operation registered through `RuntimeHints`. Verifies AOT discovery, an explainable de-duplication, and identical HTTP behaviour before and after pruning.

`scripts/verify-compatibility.sh` stages a plugin JAR and POM into an isolated repository and runs one of those samples with a given Maven and JDK; it never rebuilds the plugin. `scripts/build-high-version-fixtures.sh` produces the Java 21/25 class-file fixtures used by the low-JVM tests.

## A note on versions

`1.0.0` is published on Maven Central and is the **Java 25** build (Class-File API). It cannot run on
a Java 17 or Java 21 Maven JVM. `1.1.0` is the unreleased candidate for the Java 17 runtime baseline and the
ASM backend. Until it is published, stage the candidate JAR/POM in a dedicated local repository and
pin it explicitly; it is not yet available as a public release.

This matters more than it sounds: if a build fails to place the candidate artifact in its local
repository, Maven will happily download the published `1.0.0` of the same coordinates and the result
looks like a passing run until something tries to load a Java 25 class on an older JVM. Both
`scripts/verify-compatibility.sh` and the sample verifiers therefore check the hash of the artifact
they consume and refuse to run against something they did not stage.

## Releases

A release is a git tag; publishing to Maven Central is a separate, deliberate step.

```sh
./scripts/release.sh --dry-run patch    # say what would happen; changes nothing
./scripts/release.sh patch              # bump, verify, commit, tag, push
./scripts/publish-central.sh --publish  # build, sign, check, upload, validate, publish (one command)
./scripts/publish-central.sh            # the same, stopping at VALIDATED so you can look first
```

See [docs/releasing.md](docs/releasing.md) and [docs/publishing.md](docs/publishing.md).

## License

Apache License 2.0.
