# spring-boot-native: AOT generated metadata and pruning

A standalone Spring Boot 3.5.16 Web application. It does **not** inherit from the plugin's POM, is not
part of the plugin's reactor, and consumes the plugin only through its Maven coordinates.

## What it proves

The application exposes a REST API, uses a Jackson record for the request and response bodies, and
has one operation that cannot be expressed statically: `GET /api/dynamic?method=...` looks up a method
by a name that arrives in the query string. That operation is registered through a
`RuntimeHintsRegistrar`, so `spring-boot:process-aot` turns it into generated reflection metadata.

| Group | Configuration | Expected |
| --- | --- | --- |
| A baseline | Spring AOT + native build, plugin disabled | server answers all three requests |
| B pruned | Spring AOT + native build, plugin enabled | identical HTTP payloads; the self-owned duplicate is deleted and the changelog names the generated source; sources and generated metadata are byte-identical to A |
| C negative | a copy of B with the `RuntimeHints` registrar and the hand-written duplicate removed | the dynamic request fails because the registration is gone |

`verify.sh` runs all three, starts and stops the native server itself, and inspects every metadata
file in the native input rather than only the boot JAR.

## Prerequisites

- A GraalVM JDK with `native-image`, and Maven 3.9.9+.
- A C toolchain (Spring Boot native builds compile C code).
- Native Build Tools 0.10.6 and Spring Boot 3.5.16 (pinned in `pom.xml`).

## Why `prepare-package`

`spring-boot:process-aot` and this plugin both run at `prepare-package`. The build order is not
assumed; it is checked in the Maven log:

```
--- spring-boot:3.5.16:process-aot (process-aot) @ spring-boot-native-sample ---
--- native-metadata:...:analyze (analyze-native-metadata) ---
--- native-metadata:...:prune (prune-native-metadata) ---
--- graalvm-native:...:compile-no-fork (build-native) ---
```

`process-aot` writes generated metadata to `target/spring-aot/main/resources` and to
`target/classes/META-INF/native-image/<group>/<artifact>/`. The `-cp` argument the native build passes
to `native-image` contains `target/classes` first, which is how the generated metadata reaches the
image. `verify.sh` records that `-cp` line.

`generatedMetadataDirectories` points the plugin at `target/spring-aot/main/resources` as well, so the
report distinguishes the framework-generated source from the copy already in `target/classes`. The generated sources stay read-only; the plugin rewrites only the packaged copy of the
self-owned configuration under `target/classes/META-INF/native-image`. The original files under
`src/main/resources` are preserved.

## Using the sample by hand

```sh
export JAVA_HOME=/path/to/graalvm
MVN=/path/to/maven/bin/mvn

# --- A: baseline, plugin disabled -------------------------------------------------
$MVN -Pbaseline -Pnative -DskipTests clean package
./target/spring-boot-native-sample --server.port=18081 &
curl -s 'http://127.0.0.1:18081/api/greeting?name=Ada'
curl -s -H 'Content-Type: application/json' -d '{"name":"Ada"}' http://127.0.0.1:18081/api/greeting
curl -s 'http://127.0.0.1:18081/api/dynamic?method=describe&argument=ok'
kill %1

# --- B: pruned --------------------------------------------------------------------
$MVN -Pnative -DskipTests clean package
cat target/native-metadata/changelog.json     # one DELETED, reason names the generated source
./target/spring-boot-native-sample --server.port=18081 &
curl -s 'http://127.0.0.1:18081/api/dynamic?method=describe&argument=ok'   # same answer as A
kill %1
```

For C, copy the project elsewhere, delete
`src/main/java/com/example/boot/DynamicHints.java`, remove `@ImportRuntimeHints(DynamicHints.class)`
from `Application.java` and delete
`src/main/resources/META-INF/native-image/com.example.boot/reflect-config.json`, then rebuild with
`-Pnative`. The dynamic request must now fail with a missing-registration or class-not-found error.
The build must show that **no** file under `target/**/reflect-config.json` mentions
`com.example.boot.DynamicTarget` any more; otherwise another copy is still granting the permission
and the negative control proves nothing.

## Running the automatic verification

```sh
./verify.sh --java-home "$JAVA_HOME" --maven-home /path/to/maven \
    --plugin-version 1.2.3 --repo /tmp/spring-native-repo \
    --evidence /tmp/spring-native-evidence --cases a,b,c
```

Each native build can take several minutes. Results, logs, the server log, the HTTP bodies, the
consumed metadata and the metadata hash snapshots are written under the evidence directory.

## Choosing the plugin version

```sh
$MVN -Dnative.metadata.version=1.2.3 -Pnative -DskipTests clean package
```

## What this sample does *not* claim

- Passing here says nothing about other Spring Boot versions; only 3.5.16 is pinned and exercised.
- A zero-change pruning result is a legitimate outcome for an ordinary Spring application. This
  sample deliberately contains a self-owned duplicate of an AOT registration so that a change is
  expected; do not read that as a promise that Spring applications always shrink.
- No claim is made that pruning reduces binary size or start-up time; that is not measured here.
