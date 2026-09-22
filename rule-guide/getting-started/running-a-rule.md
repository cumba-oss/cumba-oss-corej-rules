# Running a rule

<!-- Grounded, and where a figure or a verdict appears it was RUN, not inferred:
     · the source -> package journey: scripts/regen-rules-corpus.sh header, pom.xml
       "corpus generation" (exec-maven-plugin, process-test-classes -> target/), CorpusPaths
       javadoc (resolution order, why the corpus is not committed).
     · the loop command, its scope and its cost: measured 2026-09-22 on this tree —
       `mvn -o test -Dtest=RuleTestSuitesCdiscFactoryTest -Dscenario.filter=CDISC-CG0270`
       -> Tests run: 4, 1.293 s, total 8.045 s, rc=0.
     · the vacuous-filter warning: measured the same day with -Dscenario.filter=CDISC-CG9999
       -> "Tests run: 0", "BUILD SUCCESS", rc=0. Not inferred from the code path.
     · scenario.filter semantics: AbstractRuleTestSuitesFactory.parseFilter / matchesFilter
       (comma-separated Core.Id PREFIXES, startsWith); family subtree + per-family factory
       classes from the class list and the resource tree.
     · the four execution states and their wording: ExecutionVerdictCheck.verify / Outcome.
     · parking: RuleStorageAssembler.dropParkedRules ("[assemble] N rule(s) parked by
       Executability: \"Not Executable\"").
     · package membership: AbstractRuleTestSuitesFactory ~line 830, pkg.containsKey(coreId).
     · the validator: CdiscValidate.printUsage (option spellings), --rules-dir / COREJ_RULES_DIR
       / corej.rules.dir, and the exit-code contract (0 success / 1 failure / 2 usage) read off
       the run() returns. The "exit 0 regardless of findings" claim is the final `return 0;`
       after the report is written. Report formats are SPI-discovered (supportedFormatNames),
       so this page deliberately does NOT hard-code the list. -->

You have a rule and a scenario. This page is how you run them, what the answer looks like, and
why a rule sometimes does not run at all.

There are two ways a rule runs, and they are the same engine:

| | what feeds it | what you get |
|---|---|---|
| **The authoring loop** | your scenario's small, readable dataset | a pass or a failure, in seconds |
| **The validator** | a real study | a report |

You will live in the first one. The second is how the rule is eventually used.

## You do not run your YAML

This is the one piece of plumbing worth knowing, because every "I fixed it and nothing changed"
traces back to it. The engine never reads the file you edit:

```
rules-src/checks/CDISC/CDISC-CG0270.yaml     what you author
rules-src/docs/CDISC/CDISC-CG0270.yaml
        |
        |   assembly — membership decided by each rule's Standards block
        v
target/rules/rules-cdisc-sdtmig-3-4.json     what the engine loads
target/rules/packages.json
```

Rules are authored one file at a time and loaded a **package** at a time. The assembly step in
between decides which packages your rule belongs to, from its
[`Standards`](../authoring/standards.md) block.

**The build does this for you.** Assembly runs as part of the build, before the tests, and
writes into `target/`. So every test run already has a corpus current with your sources, and
there is no separate "regenerate" step to remember.

> **The generated corpus is not committed, and that is deliberate.** An artefact that cannot
> fall behind its source cannot go stale. Do not hand-edit anything under `target/rules/` — the
> next build overwrites it, and the rule you meant to change is still wrong.

## The authoring loop

Run one rule's scenarios by filtering to its identifier:

```bash
mvn -o test -Dtest=RuleTestSuitesCdiscFactoryTest -Dscenario.filter=CDISC-CG0270
```

That is the whole loop: edit the rule or the scenario, run this, read the result. On this tree
it takes about **eight seconds**, of which the scenarios themselves are one.

**`-Dtest=…` picks the family.** Scenarios are organised by rule family, one subtree and one
test class each, and the class must match the family your rule is in:

| rule ids | scenarios live in | run with |
|---|---|---|
| `CDISC-…` | `cdisc/` | `RuleTestSuitesCdiscFactoryTest` |
| `FDA-…` | `fda/` | `RuleTestSuitesFdaFactoryTest` |
| `PMDA-…` | `pmda/` | `RuleTestSuitesPmdaFactoryTest` |
| `DRAFT-…` | `draft/` | `RuleTestSuitesDraftFactoryTest` |

**`-Dscenario.filter=…` picks the rule.** It matches `Core.Id` **prefixes**, and takes a
comma-separated list — so `-Dscenario.filter=CDISC-CG0270,FDA-CT2001` runs two rules, and
`-Dscenario.filter=CDISC-CG02` runs every rule whose id starts that way. Omit it entirely and
the family's whole suite runs.

> ⚠⚠ **A filter that matches nothing is a silent, green pass.** Measured: a mistyped id reports
> `Tests run: 0` and `BUILD SUCCESS`, and the build exits 0 — exactly like a run where
> everything passed.
>
> ```
> Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
> [INFO] BUILD SUCCESS
> ```
>
> **Read the count, not the colour.** A typo in the id, or the right id against the wrong
> family class, both land here — and "my rule passes now" is the conclusion they invite. Check
> that the number of tests matches the number of `.cdt` files you expect to have run.

## Reading what comes back

A scenario asserts the rule's **execution state** before it asks about violations, so a failure
names the first thing that is wrong rather than a symptom of it. There are four states, and the
message tells you which one you are in:

| the rule | you will read |
|---|---|
| **ran**, found nothing | *expected a violation but got none* |
| **ran**, fired | *expected no violation but got N violation(s)* |
| **did not run** | the engine's own skip reason, quoted back to you |
| **failed** | the error, and a note that `expect=executionError` exists if the failure *is* the contract |

That third row is the one that saves time. A rule which never ran and a rule which ran and
found nothing both report zero violations, and the harness tells them apart — so a scenario
that quietly stops exercising anything says so, instead of passing.

[Scenarios](../testing/scenarios.md) covers the verdicts themselves, and how to pin *where* and
*how often* a rule fires.

## When a rule does not run at all

Five mechanisms, roughly in the order they bite. Each is deliberate; none is an error.

**1. The rule is parked.** `Executability: "Not Executable"` removes the rule at assembly — it
never reaches a package, so nothing can run it. The build says so, by name:

```
[assemble] 3 rule(s) parked by Executability: "Not Executable", …
```

→ [Identity and executability](../authoring/identity.md#executability--how-completely-the-rule-evaluates-its-requirement)

**2. The rule is not in the package.** Membership comes from the
[`Standards`](../authoring/standards.md) block. A scenario that declares a standard and version
checks that the rule is actually a member of that package — so a rule missing the standard it
is being tested against fails there, rather than silently never running.

**3. A requirement is not met.** A variable listed in
[`Requirements.Variables`](../authoring/requirements.md) is absent, or a required dataset is
not there. The rule is **skipped**, with a stated reason. This is the intended behaviour, and
it is what `expect=skipped` exists to assert.

**4. The scope does not match.** The dataset is not in the rule's
[`Scope`](../authoring/scope.md) — wrong domain, wrong data structure, wrong class.

**5. Every check level is below the run threshold.** A run evaluates levels at or above its
threshold, and a rule with nothing at or above it is skipped, again with the reason stated. The
default threshold is `Warning`, so an `Info`-only rule does not run unless the run asks for it.

→ [Outcome, severity and sensitivity](../authoring/outcome.md)

> **A rule that needs metadata it was not given is skipped, not failed.** If your rule reads
> CDISC Library metadata, a Define-XML overlay or an external dictionary, the scenario has to
> supply it — otherwise the rule skips, and an `expect=violation` scenario fails with *"the
> rule never executed"* rather than with anything naming the metadata.
> [Scenarios](../testing/scenarios.md#supplying-metadata) lists the directives.

## Running against a real study

The validator is the command-line face of the same engine. Its smallest useful form names a
rule package and some data:

```bash
./run.sh --rules-package cdisc-sdtmig-3-4 --data /path/to/study
```

A package's short name is its file name without the `rules-` prefix and the extension:
`rules-cdisc-sdtmig-3-4.json` is `cdisc-sdtmig-3-4`. `--rules-package` is repeatable and accepts
a comma-separated list.

The options you will reach for while developing a rule:

| option | what it does |
|---|---|
| `--rules <id>` | run **only** matching rule ids — the validator's answer to `scenario.filter` |
| `--rules-dir <dir>` | load the corpus from here instead of the bundled one |
| `--output <file>` | where the report goes (it defaults to a timestamped name) |
| `--output-format <fmt>` | repeatable; `json` unless you say otherwise |
| `--severity-level <level>` | the run threshold — the weakest check level evaluated |

`--rules-dir` is what points the validator at a corpus you just built, rather than the one
shipped in the bundle. `COREJ_RULES_DIR` and the `corej.rules.dir` system property set the same
thing.

> ⚠ **`--rules` and `--rules-dir` are different options one letter apart in intent.** One
> filters which rules run; the other says where rules are loaded from. Passing a directory to
> `--rules` gives you a run that matches no rule — and a clean report.

> ⚠⚠ **A successful run exits 0 whether or not it found anything.** The exit code reports
> whether the *validator* worked, not whether the *data* conformed: `0` success, `1` a run that
> failed, `2` a usage error. A pipeline that treats exit 0 as "the study is clean" will pass
> every study it ever sees. Read the report, or the finding count the run prints.

Which report formats are available depends on which writer modules the bundle carries — the
validator discovers them at startup, and `--output-format` with no valid value lists what it
found.

---

**Next:** [Identity](../authoring/identity.md) — the authoring chapters, in the order you fill a
rule in.
