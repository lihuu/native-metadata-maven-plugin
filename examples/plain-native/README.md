# plain-native: pruned reflection metadata in a GraalVM native image

This is a standalone Maven project. It does **not** inherit from the plugin's POM, is not part of the
plugin's reactor, and consumes the plugin only through its Maven coordinates. Open it with any IDE,
or run it from a copy of this directory; nothing outside this directory is needed except a GraalVM
installation with `native-image`.

## What it proves

The application (`com.example.plain.App`) reflects on a class, a constructor, a field and a method
whose names all come from run-time input (command line or system properties). GraalVM cannot fold
those strings into a compile-time reflection target, so the program only works if the configuration
handed to `native-image` really registers what it uses. That is what makes the three groups below
meaningful.

| Group | Configuration | Expected |
| --- | --- | --- |
| A baseline | `src/main/resources/META-INF/native-image/.../reflect-config.json`, plugin disabled | native build succeeds, run prints `APP_OK` |
| B pruned | the same sources, but `rules.json` narrows the broad registration and removes a duplicate | native build succeeds, identical output, `changelog.json` shows a `NARROWED` and a `DELETED` change |
| C negative | a copy of B with the required `describe` registration removed from every native input | the same run fails with a registration-related error |

`verify.sh` runs all three and writes its evidence under `target/plain-native-evidence`.

`verify-pruning.sh` asks the same question without needing a native toolchain: on an already-built
copy of this project it asserts that the changelog changed exactly `com.example.plain.Greeting`, that
the configuration the native build consumes still carries every member the application reflects on,
that entries no rule mentions are untouched, and that `src/` was not rewritten in place. The plugin's
own `mvn verify` runs it against its Invoker clone.

```sh
# Built project and the pristine copy to compare its sources against
./verify-pruning.sh --project <built-project> --source .
```

## Prerequisites

- A GraalVM JDK that contains `native-image` (any of 17, 21, 25; this sample's business code targets
  Java 17 by default).
- Maven 3.9.9 or newer.

## Using the sample by hand

Everything below is exactly what `verify.sh` does; run it manually if you want to watch each step.

```sh
# Point at your GraalVM; the same JDK runs Maven and native-image.
export JAVA_HOME=/path/to/graalvm
MVN=/path/to/maven/bin/mvn

# --- A: baseline, plugin disabled -------------------------------------------------
$MVN -Pbaseline clean package
"$JAVA_HOME/bin/native-image" --no-fallback -cp target/classes -o target/plain-native-a com.example.plain.App
./target/plain-native-a            # prints FIELD_* / METHOD_RESULT / APP_OK
                                   # METHOD_RESULT=hello-via-field:world

# --- B: pruned --------------------------------------------------------------------
$MVN clean package                 # runs native-metadata:analyze and native-metadata:prune
cat target/native-metadata/changelog.json
cat target/classes/META-INF/native-image/com.example.plain/reflect-config.json
"$JAVA_HOME/bin/native-image" --no-fallback -cp target/classes -o target/plain-native-b com.example.plain.App
./target/plain-native-b            # identical output to A

# --- C: remove a required registration (do this in a scratch copy) ---------------
```

For C, copy the directory elsewhere, keep the pruned `target/classes` configuration, delete the
`"describe"` method entry from **every** metadata file on the native-image classpath, rebuild the
image and run it again. The run must fail with `NoSuchMethodException: com.example.plain.Greeting.describe([Ljava/lang/String;)`
(or a GraalVM missing-registration error). If it still succeeds, something else is still granting
that permission and the sample is not proving anything.

## Choosing the plugin version

The plugin version is the `native.metadata.version` property:

```sh
$MVN -Dnative.metadata.version=1.2.3 clean package
```

`verify.sh` accepts the same version plus an isolated repository:

```sh
./verify.sh --java-home "$JAVA_HOME" --maven-home /path/to/maven \
    --plugin-version 1.2.3 --repo /tmp/plain-native-repo \
    --evidence /tmp/plain-native-evidence
```

Useful switches:

- `--cases a`, `--cases a,b`, `--cases a,b,c` — run a subset.
- `--skip-native-build` — run only the Maven side (not an acceptance result; native groups are then
  reported as skipped).
- `--native-image <path>` — override the `native-image` executable; the difference is recorded.
- `--expected "hello-via-field:world"` — expected `METHOD_RESULT`.

## Reading the result

`target/plain-native-evidence/result.json` lists the group outcomes. Per group you get the Maven log,
the native-image log, the application output, the configuration actually consumed by the native build
(`consumed-config.json`) and, for B, the changelog.

## What this sample deliberately does *not* claim

- It does not claim that pruning makes the binary smaller or faster; no measurement of that is made
  here.
- It does not claim that unused registrations are deleted automatically. `com.example.plain.Unused`
  stays registered after pruning: absence of a bytecode reference is not evidence of absence of a
  dynamic use.
