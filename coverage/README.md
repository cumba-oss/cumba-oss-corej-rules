# `coverage/` — aggregate JaCoCo coverage

A single sink module with no source of its own. It depends on every `lib/*`
module and runs `jacoco:report-aggregate` at `verify`.

There used to be two sub-modules, `coverage/dev` and `coverage/main`, one per
build profile. Those profiles are gone (being `activeByDefault`, they were
deactivated by any other profile activating, so the reactor built only the
parent pom unless `-P main` was passed).

## Updating the module list

⚠ **The `<dependencies>` in `coverage/pom.xml` ARE the report.**
`jacoco:report-aggregate` builds from that list — it does *not* scan the
reactor. Both former sub-modules had an **empty** list, so the aggregate held
zero classes while the per-module reports held real data — and since
`sonar.coverage.jacoco.xmlReportPaths` pointed there, Sonar read the empty file.

Whenever you add or remove a `lib/*` module in the root pom's `<modules>`,
mirror it here. The `coverage-covers-every-module` enforcer rule checks both
directions and fails the build naming the artifact.

## Output path

After `mvn -T1C verify`:

```
coverage/target/site/jacoco-aggregate/jacoco.xml
```

To confirm it has content, count occurrences — **not** lines:

```bash
grep -o '<class ' coverage/target/site/jacoco-aggregate/jacoco.xml | wc -l
```

JaCoCo writes the whole XML on one line, so `grep -c` reports `1` regardless of
what the report contains. Currently: 396 classes, 90.2% line coverage.
