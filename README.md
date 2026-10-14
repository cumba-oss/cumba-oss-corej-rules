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
| `rules-src/checks/` | The authored rule corpus — 3 443 YAML checks across the `CDISC`, `FDA`, `PMDA` and `DRAFT` families |
| `rules-define-src/` | 409 authored Define-XML conformance rules |
| `rulespec/specs/` | 987 rule-execution specs, run by `RuleExecutionSpecTest` |
| `schema/rule-format.schema.json` | Machine-readable schema every generated rule package is validated against |
| `src/test/resources/.../ruletestsuites/` | 9 091 `.cdt` scenario fixtures |

**The generated corpus is not committed.** `mvn install` generates 47 rule packages into
`target/rules/` and 4 Define-XML packages into `target/rules-define/`, each beside a
`packages.json` manifest. Generating rather than committing is deliberate: it keeps
~80 MB of derived JSON out of the repository's history, where it could never be removed.

The same run also writes **`target/rules-release/`** — the same packages minus the `DRAFT`
family, 32 of the 47 — and that is the tree the release archive packs. See below.

## ⛔ The DRAFT family is generated and tested here, but never released

`rules-src/checks/DRAFT/` holds candidate rules that are not ready to be published:
unfinished scope, unproven checks, or an authority row that does not exist yet. They are
kept because they are meant to be revisited and promoted — not because they are done.

**They are not in any release archive.** The exclusion happens once, at the release
boundary: `RuleCorpusGenerator` writes the full corpus to `target/rules/` and the
release-scoped subset to `target/rules-release/`, and `src/assembly/dist.xml` packs only
the latter. `ReleaseCorpusTest` pins all of it — that the released tree is byte-identical
to the validated one, that it carries no `DRAFT-` package or rule id, and, crucially, that
the full corpus still *has* a DRAFT family, so the exclusion can never quietly become an
exclusion of nothing.

⚠ **The drafts stay fully wired into the test suite on purpose.** Every corpus test, lint
and `.cdt` scenario reads `target/rules/`, so a draft rule is schema-validated, lint-scanned
and executed exactly like a published one. Excluding DRAFT from the generated corpus itself
would have been a smaller change and would have taken some thirty corpus checks off the
drafts in one edit, with nothing red — parked rules that nothing verifies rot in place.

⚠ **This is why the public corpus has no TIG packages.** `rules-draft-tig-1-0.json` is the
only TIG 1.0 package outside the unredistributable CORE family, so TIG coverage is absent
from the released archive until those rules are promoted out of DRAFT.

## ⛔ The CORE rule family is not redistributed here

`rules-src/checks/CORE/` and everything derived from it — its generated packages, its
`.cdt` fixtures, its specs — are **excluded from this repository**. The CORE rules are
externally sourced and may not be redistributed as a set.

The distinction is the *set*, not the logic: coreJ's own check implementations are its
own work. What is not shipped is the packaged CORE rule family.

Consequences you will see in the code:

- 47 generated packages here, the same number as internally, where the 11 CORE-family
  packages have since been retired too. Of the 47, **32 are released** (the other 15 are the DRAFT family, see above).
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

Requires the **`cumba-oss-corej`** version pinned by `dependency.cumba-oss-corej.version` in the
root pom, and its upstreams: on `main` a `-SNAPSHOT` from the Central snapshot repository (see
"Building `main`"), on a release tag the released version on Central.

### ⛔⛔ Five of this repo's gates CANNOT FAIL — they are structurally inert

> ⭐ **Since 2026-09-24 PMD is no longer one of them**, so four remain. PMD now analyses the test
> sources (see its row). The heading keeps its wording because other documents cite it by name.

**Recorded 2026-09-19. A statement of fact about the ladder above, not a proposal
to change it.** The gate command runs the same tool set every repo in the stack
runs, and five of those tools **have no population to judge**, because this repo
has **no `src/main` directory at all** — everything it ships is YAML and JSON, and
all of its Java is test code. Measured on the run of 2026-09-19: `target/classes`
holds **0 `.class` files** and `target/pmd.xml` lists **0 `<file>` elements**.

| tool | configured gate | what actually happens |
|---|---|---|
| **PMD** | `-Dpmd.failOnViolation=true` in the gate command and in CI | ~~analyses 0 source files~~ ⭐ **No longer inert since 2026-09-24:** the PMD profile sets `includeTests=true` (databrowser-meta `PLAN-pmd-test-sources`), so PMD analyses `src/test/java` with the 118-rule set and can red this repo. |
| **SpotBugs** | `-Dspotbugs.failOnError=true` | **0 classes** to analyse. |
| **JaCoCo** | `<jacoco.line.coverage>0.80</jacoco.line.coverage>` | ⚠⚠ **The 0.80 line gate cannot fail, and it says the opposite.** The report goal logs *"Analyzed bundle 'Cumba OSS CDISC Rules' with 0 classes"* and `jacoco:check` then passes — a positive verdict over an empty measurement. The agent still attaches and a `jacoco.exec` is still written, so the build looks instrumented. |
| **Pitest** | `<pitest.mutation.target>55</pitest.mutation.target>`, `<pitest.coverage.target>80</pitest.coverage.target>` | ⚠⚠ Not invoked by `install`/`verify` at all — it is a separate opt-in CI job (`mvn -B -P Pitest verify`) — and when it is, there is nothing to mutate. `<failWhenNoMutations>false</failWhenNoMutations>` turns that into a pass. **Those 55/80 thresholds have never been evaluated, not once.** |
| **Error Prone / NullAway** | the `ErrPrn` profile | ⚠⚠ **Never activated.** `<activeByDefault>false</activeByDefault>`, and no workflow passes `-P ErrPrn`, so the canonical command runs without it. NullAway is an Error Prone plugin, so it is inert for the same reason — and both would have nothing to compile here regardless. ⛔ Do NOT arm it from this note: arming is `PLAN-arm-error-prone`'s scope, and arming a gate can red a repo that has zero findings. |

⭐ **What does run and can red:** `spotless:check` over `src/test/java`,
`-Dmaven.compiler.failOnWarning=true` over the test sources, the `enforcer` rules
(`enforce-maven-version`, `enforce-deploy-urls`, `require-generated-rule-corpus`),
the corpus-assembly `exec` steps — and above all **this repo's own test corpus**:
the corpus lints, the ratchets, the `.cdt` scenario suites and the `rulespec`
drift guards. Those are the real protection here.

⇒ The point of this section is only that a green gate run in this repo carries
**no** static-analysis, coverage or mutation signal, so *"the gates are green"*
must never be read as *"the code was analysed"*. ⚑ The internal twin carries the
same record for the same reason (it has a `src/main/`, but only `resources/` and
`dist/` in it), where it names four rather than five — this repo additionally has
`ErrPrn` unarmed.

### ⚠ The build needs the CDISC pickle corpus — as the seed of the metadata store

`MetadataStoreFixture` seeds the unified metadata store (`target/metadata-store/store.zip`)
once per JVM from the pickle corpus named by `CDISC_PICKLE_CACHE_DIR` /
`-Dcdisc.pickle.cache.dir`; only `PickleMetadataReaderTest`, the test of the pickle reader the
seeder uses, reads a `.pkl` file — every check reads the store. Without it the tests that
resolve library metadata through the store **fail rather than skip** —
`LibraryProviderGuardTest` hard-fails by design, and the execution specs that need a
library silently flip `EXECUTED` → `SKIPPED`, which would invalidate every
`expected_status` pin if it were tolerated.

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

## Building `main`

On `main` the version is a `-SNAPSHOT`, and so is every sibling `cumba-oss-*`
artifact it depends on. Each sibling publishes its snapshot to the Central
Portal snapshot repository on every push to its `main`. The poms deliberately
name no snapshot repository, so a clone that has not built the siblings itself
needs a settings file that adds it (the same one CI writes):

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <profiles>
    <profile>
      <id>central-portal-snapshots</id>
      <repositories>
        <repository>
          <id>central-portal-snapshots</id>
          <url>https://central.sonatype.com/repository/maven-snapshots/</url>
          <releases><enabled>false</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>central-portal-snapshots</activeProfile>
  </activeProfiles>
</settings>
```

```bash
mvn -gs central-snapshots.xml clean install
```

`-gs` replaces only the Maven installation's global settings; your
`~/.m2/settings.xml` still applies. Alternatively install the siblings locally,
in order: commons → formats → datatable → swingx-msp → corej. A release build
(a tag) never needs this: it depends on released versions from Maven Central
only. Central deletes snapshots after 90 days.

## The Cumba OSS repositories

```
cumba-oss-commons     help · web-api · cdisc-library · bootstrap
cumba-oss-formats     sas-utils · cdisc-dsj                (independent leaf)
      ▲
cumba-oss-datatable   datatable · impl · cdisc-define · providers · manager-local · testkit · dictionary
      ▲
cumba-oss-corej       corej-core · report-json · report-xlsx · ruletest · authoring · define-conformance
      ▲
      ├─ cumba-oss-corej-rules             this rule corpus     (publishes nothing to Central)
      ├─ cumba-oss-corej-cli               the validation CLI   (bundles a pinned corpus release)
      ├─ cumba-oss-corej-rest              the REST API         (bundles a pinned corpus release)
      │                                       ◀─ cumba-oss-corej-web              (browser SPA)
      └─ cumba-oss-corej-rule-editor-rest  the rule editor API  (works on a corpus checkout in git)
                                              ◀─ cumba-oss-corej-rule-editor-web  (browser SPA)
```

`cumba-oss-corej-cli` and `cumba-oss-corej-rest` **bundle a pinned release of this
corpus**: their build downloads the two release assets at `generate-resources`, verifies
each against a pinned `sha256` (`dependency.corej-rules.*` in their poms) and stages them
into the distribution. The corpus is still not a Maven dependency — the engine discovers
packages through the directory's `packages.json`, and an operator can point it at another
corpus directory. The rule editor works on a corpus kept in git, such as a clone of this
repository, and needs no corpus release.

### Engine and corpus compatibility

The corpus and the engine each carry a **rule-language level**: `rules-src/LANGUAGE-LEVEL`
here and `RuleLanguage.LEVEL` in `cumba-oss-corej-core` (both `5` on `main`).
`RuleLanguageLevelTest` fails this build when the two differ, so a corpus release is gated
against the engine level it was built with. ⚠ The engine does **not** read the level at run
time, so a corpus of another level is not refused when it loads — the pairing is simply
unsupported. Run a corpus release on the engine it was built against
(`dependency.cumba-oss-corej.version` in `pom.xml`): the levels first ship with the next
releases, and engine 0.7.x needs corpus 0.6.x or later; of the current releases, `v0.5.0`
was built against engine `0.6.0`. Background:
[`rule-guide/reference/language-level.md`](rule-guide/reference/language-level.md).

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
