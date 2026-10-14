# Publishing to Maven Central

This plugin is published from a developer machine, by `scripts/publish-central.sh`. There is no CI
publishing job: publishing is permanent and needs a signing key, and a tag is a claim that can be
withdrawn while a Central release cannot.

Coordinates: `io.github.lihuu:native-metadata-maven-plugin` —
<https://repo1.maven.org/maven2/io/github/lihuu/native-metadata-maven-plugin/>

---

## 1. Prerequisites

1. **A GPG key with no passphrase**, published to a keyserver.
   Central requires a signature on every artifact. Use an RSA 4096 key generated with
   `%no-protection` (an empty passphrase), e.g. in `~/.gnupg/gpg.conf`:

   ```sh
   gpg --full-generate-key          # RSA, 4096 bits, no passphrase
   gpg --keyserver keyserver.ubuntu.com --send-keys <key id>
   ```

   No passphrase is not laziness. Signing runs headless, and
   `<gpgArguments>--pinentry-mode loopback</gpgArguments>` cannot answer a prompt — a protected key
   hangs the build until it times out. If you do want a passphrase, supply it through the plugin's
   `passphraseEnvName` instead of relying on pinentry; see the note in section 4.

2. **A Central Portal token** in `~/.m2/settings.xml` under server id `central`, generated at
   <https://central.sonatype.com/usertoken>:

   ```xml
   <server>
     <id>central</id>
     <username>...</username>
     <password>...</password>
   </server>
   ```

   The script reads this file itself (and never prints the token). Nothing is read from the
   environment, so there is exactly one place the credential lives.

3. **The `io.github.lihuu` namespace**, claimed on the Central Portal via GitHub authorization.

## 2. Running it

```sh
./scripts/publish-central.sh              # build, sign, assemble, validate the layout, upload
./scripts/publish-central.sh --dry-run    # the same, minus the upload: nothing leaves the machine
./scripts/publish-central.sh --release    # ... and then publish (irreversible)
```

**Two steps, on purpose.** Uploading is reversible — a deployment that is uploaded and never
published simply expires. Publishing is not: a version on Central cannot be withdrawn, and the
version number can never be reused. So the default run uploads, waits until the Portal reports
`VALIDATED`, prints the deployment id and stops.

`--dry-run` runs the entire flow — build, sign, checksums, layout check, `gpg --verify`, pack — and
stops before the upload. Use it to answer "is this flow right?" without touching Central or leaving a
deployment record. It does not need a token, so it works on a machine that has never been configured.

## 3. What the script checks before anything is uploaded

- **Layout.** No directory in the bundle may hold files without a `.pom`; every artifact needs its
  `.asc` and its md5 and sha1. This is the check that stops a rejected upload before it happens.
- **Signatures.** `gpg --verify` on the pom's `.asc` — a `.asc` file existing is not the same as it
  being a signature of the file beside it.
- **A clean tree**, for a real run. What gets published has to be what was reviewed.

## 4. Why a script instead of `mvn -Pcentral deploy`

`central-publishing-maven-plugin` treats its staging directory as a Maven *local repository*. The
install mechanism therefore writes its bookkeeping files into the tree it is about to publish:

```text
io/github/lihuu/native-metadata-maven-plugin/maven-metadata-local.xml
io/github/lihuu/native-metadata-maven-plugin/1.0.0/_remote.repositories
...plus a checksum for each
```

Central rejects that outright, because a directory holding files and no `.pom` is invalid:

```text
Bundle has content that does NOT have a .pom file: io/github/lihuu/native-metadata-maven-plugin
```

This is measured, not theoretical, and not fixable from the outside: `-Dmaven.install.skip=true` does
not stop it, a dedicated `-Dmaven.repo.local` does not stop it, deleting the files beforehand does not
survive (staging is wiped and repopulated on every run), and the plugin exposes no file-level filter
— `excludeArtifacts` matches whole modules, not files. The sibling `panamaview` repository hit this
first and its notes are where this paragraph comes from.

So `publish-central.sh` does the one deterministic thing: it lets `mvn -Pcentral verify` produce the
signed artifacts, **assembles the Central-shaped tree itself from `target/`** — which is also why
those files are never created — validates it, and uploads it through the same Portal API the plugin
uses. The plugin does not upload. Its `autoPublish=false` / `waitUntil=validated` settings exist so
that nobody who does run `mvn -Pcentral deploy` by hand publishes by accident.

## 5. The `-Pcentral` profile

Activating `-Pcentral` adds everything Central requires, and nothing that uploads:

| Plugin | Why |
| :--- | :--- |
| `maven-source-plugin` | The `-sources.jar`. Central refuses a jar without one. |
| `maven-javadoc-plugin` | The `-javadoc.jar`, with `doclint=none` so a missing `@param` cannot stop a release. |
| `maven-gpg-plugin` | The `.asc` for every artifact, at the `verify` phase. |
| `central-publishing-maven-plugin` | Kept for validation and bundling; configured not to publish. The script does not use its upload. |

### The `gpgArguments` note

The signing plugin is configured with:

```xml
<gpgArguments>
  <arg>--pinentry-mode</arg>
  <arg>loopback</arg>
</gpgArguments>
```

and **not** with a `<pinentryMode>` element. This plugin has no `pinentryMode` parameter, and naming
one that does not exist is silently ignored: Maven prints `Parameter 'pinentryMode' is unknown for
plugin` as a warning and signs anyway, so a configuration that does nothing looks exactly like one
that works. This was checked against the plugin's own `plugin.xml` rather than against the
documentation.

## 6. The isolated local repository

Every Maven invocation in the script runs with `-Dmaven.repo.local=target/central-repo`. Nothing it
does can disturb your own `~/.m2`, and the run does not depend on whatever state `~/.m2` happens to be
in — which is the state a release must not depend on. The first run downloads the plugin toolchain
again; that is the price.

## 7. If an upload is rejected

The script prints the Portal's own reasons, grouped, from the status response. The bundle it built is
left at `target/central-publish/central-bundle.zip` to inspect. Nothing was published.

If a deployment is `VALIDATED` but you do not want to rebuild, it can be released directly:

```sh
curl -X POST -H "Authorization: Bearer <token>" \
  https://central.sonatype.com/api/v1/publisher/deployment/<deployment id>
```

Deployments are listed at <https://central.sonatype.com/publishing/deployments>. Central's sync to
`repo1.maven.org` can take up to a couple of hours after a publish.
