# Cumba OSS CDISC Rules

The rule corpus for the [coreJ](https://github.com/cumba-oss/cumba-oss-corej) CDISC
conformance engine: authored rule sources, the generator that packages them, and the
`.cdt` scenario suites and execution specs that pin their behaviour.

Licensed under the **GNU Affero General Public License v3.0 only**
(see [`LICENSE`](LICENSE)).

## ⛔ Nothing here is published to Maven Central

This repository is **content, not a library**. Its single Maven module has no
`src/main`, so its artifact would be an empty jar — it is excluded from Central via
`excludeArtifacts` in the root pom (plugin configuration, deliberately *not* the
`skipPublishing` property, which a release job's `-D` silently overrides per module).

### Getting the rule corpus

The corpus ships as **two tagged release archives**, each with a detached OpenPGP
signature made with the same key as the project's Maven Central artifacts — one trust
root, not two:

| Asset | Contents |
|---|---|
| `cumba-oss-corej-rules-<version>.zip` | the data rule packages + `packages.json` |
| `cumba-oss-corej-rules-define-<version>.zip` | the Define-XML packages + `packages.json` |

```bash
curl -sLO <asset-url> && curl -sLO <asset-url>.asc
gpg --verify <asset>.asc <asset>
```

Both are assembled by this repository's own `package` phase, from the corpus the
same build generates, so `mvn package` reproduces exactly what a release carries:

```bash
mvn -B clean package -Drevision=<version> -DskipTests
ls target/*.zip
```

⚠ **`target/`, not a `dist/` module.** The repository was flattened to a single
module: the two assembly executions write into this module's own `target/`, which
is exactly what the release workflow uploads (`target/*-<version>.zip`). Corpus
generation is bound to `process-test-classes` and has **no** skip flag, so
`-DskipTests` still produces the corpus — it only skips the test run.

⚠ **Release assets are mutable**, unlike Central artifacts — anyone with write access can
replace one. The signature proves an archive is authentic; **your pinned hash proves
*which* authentic archive you adopted.** Record both, and don't assume re-downloading a
tag returns the same bytes.

## What is here

| Path | What it is |
|---|---|
| `rules-src/checks/` | The authored rule corpus — 3 308 YAML checks across the `CDISC`, `FDA`, `PMDA` and `DRAFT` families |
| `rules-define-src/` | 409 authored Define-XML conformance rules |
| `rulespec/specs/` | 531 rule-execution specs, run by `RuleExecutionSpecTest` |
| `rulespec/parity-diff-baseline.json` | The known-divergence baseline those specs are read against |
| `schema/rule-format.schema.json` | Machine-readable schema every generated rule package is validated against |
| `src/test/resources/.../ruletestsuites/` | 7 704 `.cdt` scenario fixtures |

**The generated corpus is not committed.** `mvn install` generates 47 rule packages into
`target/rules/` and 4 Define-XML packages into `target/rules-define/`, each beside a
`packages.json` manifest. Generating rather than committing is deliberate: it keeps
~80 MB of derived JSON out of the repository's history, where it could never be removed.

## ⛔ The CORE rule family is not redistributed here

`rules-src/checks/CORE/` and everything derived from it — its generated packages, its
`.cdt` fixtures, its specs — are **excluded from this repository**. The CORE rules are
externally sourced and may not be redistributed as a set.

The distinction is the *set*, not the logic: coreJ's own check implementations are its
own work. What is not shipped is the packaged CORE rule family.

Consequences you will see in the code:

- 47 rule packages here, against 59 internally — the 12 CORE-family packages are absent.
- Corpus-size floors that asserted the **full** internal corpus were removed from the
  package-generation validators rather than lowered, because this subset deliberately is
  not that corpus. A subset-safe non-vacuity check (`packages > 0`) was kept, so an
  empty or mis-resolved corpus still reds rather than passing over nothing.
- Tests that required a CORE rule, spec or fixture were removed, not skipped.

Everything removed is still enforced internally over the whole corpus.

## Building

```bash
mvn -B clean install -Drevision=<version>
```

The full CI gate, which is what must pass before a tag:

```bash
mvn -B clean install -Drevision=<version> \
    -Dmaven.compiler.failOnWarning=true -Dspotless.check=true \
    -Dpmd.failOnViolation=true -Dspotbugs.failOnError=true
```

Requires **`cumba-oss-corej` 0.3.0** and its upstreams on Central; the engine version is
pinned by `dependency.cumba-oss-corej.version` in the root pom.

### ⚠ The build needs the CDISC pickle metadata cache

Eight tests read Python-engine `.pkl` metadata straight from a cache directory
(`CDISC_PICKLE_CACHE_DIR` / `-Dcdisc.pickle.cache.dir`). Without it they **fail rather
than skip** — `PickleProviderGuardTest` hard-fails by design, and three execution specs
silently flip `EXECUTED` → `SKIPPED`, which would invalidate every `expected_status` pin
if it were tolerated.

CI seeds it from the **public** upstream; both workflows carry the restore / fetch / save
steps. To run the full suite locally:

```bash
export CDISC_PICKLE_CACHE_DIR=/tmp/cdisc-pickle-cache
mkdir -p "$CDISC_PICKLE_CACHE_DIR"
curl -fsSL "https://github.com/cdisc-org/cdisc-rules-engine/archive/v0.16.0.tar.gz" \
  | tar -xz -C "$CDISC_PICKLE_CACHE_DIR" --strip-components=3 --wildcards \
      '*/resources/cache/standards_details.pkl' \
      '*/resources/cache/standards_models.pkl' \
      '*/resources/cache/*ct-*.pkl'
```

~82 MB on the wire, ~450 MB extracted. The locator refuses a path that is set but is not
a directory — a wrong path and no path look identical in a test log otherwise.

## The Cumba OSS repositories

```
cumba-oss-commons     help · web-api · cdisc-library · bootstrap
      ▲
cumba-oss-formats     sas-utils · cdisc-dsj                (independent leaf)
      ▲
cumba-oss-datatable   datatable · impl · cdisc-define · providers · manager-local · testkit
      ▲
cumba-oss-corej       corej-core · report-json · report-xlsx · ruletest · define-conformance
      ▲
cumba-oss-corej-rules the rule corpus                      (publishes nothing to Central)
```

`cumba-oss-corej-cli` (the validation CLI) and `cumba-oss-corej-rest` (the REST API,
with `cumba-oss-corej-web` as its browser SPA) consume the engine and load this corpus
at runtime from a rules directory — none of them depends on this repository at build
time.

## Build conventions

Java 25, `<revision>` + `flatten-maven-plugin`, Spotless-on-save at `process-sources`,
SpotBugs and PMD report-only by default, a JaCoCo line-coverage minimum, with
`projectBasedir` / `repoRoot` / `corpusDir` / `defineCorpusDir` exposed as system
properties. Same conventions as the rest of the stack — see
[`cumba-oss-corej`](https://github.com/cumba-oss/cumba-oss-corej)'s README for the full
table of gate toggles.

⚠ **Surefire's working directory is the module basedir here, not the stack-wide
`target/test-cwd/`.** The corpus tests and the generator tools resolve `rules-src/`,
`rulespec/` and `schema/` by module-relative paths, so this repository overrides the
convention; the system properties above are still set.

⚠ **Do not pass `-Djacoco.line.coverage=…` or `-Dpitest.mutation.threshold=…` on the
command line.** A CLI `-D` is a Maven *user* property and clobbers the pom's
deliberate override.
