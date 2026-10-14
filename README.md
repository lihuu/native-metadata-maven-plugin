# Native Metadata Maven Plugin

A standalone Maven plugin for GraalVM Native Image reflection metadata static analysis, de-duplication, and rule-based pruning.

Built on **Java 25** using the Java 25 **Class-File API** (`java.lang.classfile.*`) for native bytecode inspection without runtime reflection or classpath loading.

## Goals

### `native-metadata:analyze`
Performs static inspection of application bytecode and compares it against reflection registrations:
- Extracts classes, methods, fields, record components, member references, reflection call sites (`Class.forName`, `getMethod`, `getDeclaredField`, etc.), and annotations (`@JsonProperty`, `@JacksonXmlProperty`, etc.).
- Categorizes reflection registrations: self-owned vs dependency vs generated; broad (`allDeclared*`, `queryAll*`) vs explicit members.
- Identifies candidates for pruning (e.g. registered members not referenced in bytecode or dynamic traces).
- Outputs machine-readable `target/native-metadata/report.json` and human-readable Markdown summary `target/native-metadata/report.md`.

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
    <version>1.0.0</version>
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

## Releases

A release is a git tag; publishing to Maven Central is a separate, deliberate step.

```sh
./scripts/release.sh --dry-run patch    # say what would happen; changes nothing
./scripts/release.sh patch              # bump, verify, commit, tag, push
./scripts/publish-central.sh --dry-run  # build, sign, and check the bundle; uploads nothing
./scripts/publish-central.sh            # ... then upload it, stopping before publishing
```

See [docs/releasing.md](docs/releasing.md) and [docs/publishing.md](docs/publishing.md).

## License

Apache License 2.0.
