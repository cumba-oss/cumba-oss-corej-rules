# Scenarios

<!-- Grounded: src/test/resources/net/cumba/corej/core/ruletestsuites/README-CDT.md — the format
     spec: prelude directives, the four expect= verdicts and why `skipped` / `executionError`
     exist, #expectViolationCount / #expectViolationAt, #library / #define-xml / #dictionaries,
     #runLevel, the structure= / subclass= shorthands, and the table of which requirement a
     `skipped` scenario can be built on. File naming taken from the fixture tree. -->

A rule without a scenario is an assertion. A scenario is a small dataset plus the verdict you
expect, and it is the only thing that will tell you — or the next person — that the rule still
does what it says.

Scenarios are `.cdt` files: the data written out where a reviewer can read it, rather than built
up in code.

## The shape

```
#!RuleTest
#test CDISC-CG0270 expect=noViolation domain=TS
#note "Row 1 a valid period; row 2 the explicitly allowed empty TSVAL; row 3 a non-duration value under a different parameter, so outside this rule's condition."
#library standard=sdtmig version=3-4
dataset TS label="Trial Summary"
col STUDYID  type=Char label="Study Identifier"
col DOMAIN   type=Char label="Domain Abbreviation"
col TSPARMCD type=Char label="Trial Summary Parameter Short Name"
col TSVAL    type=Char label="Parameter Value"
---
CDISCPILOT01 | TS | AGEMAX | P80Y
CDISCPILOT01 | TS | AGEMAX |
CDISCPILOT01 | TS | AGEMIN | 18Y
```

`#!RuleTest` first, then the prelude directives, then the dataset and its columns, then `---`, then
pipe-delimited rows in column order. Comments start with `#` and blank lines are allowed.

Files live beside the rule they test, one directory per rule:

```
src/test/resources/…/ruletestsuites/cdisc/CDISC-CG0270/
    CDISC-CG0270-valid-TS.cdt
    CDISC-CG0270-invalid-TS.cdt
```

The name says what the scenario is *for* — `-valid-`, `-invalid-`, or the shape it exercises.

## The four verdicts

`expect=` asserts the rule's **execution state** and its violations together:

| `expect=` | the rule must | and |
|---|---|---|
| `violation` | have **executed** | fired at least once |
| `noViolation` | have **executed** | not fired |
| `skipped` | **not** have executed | — |
| `executionError` | have **failed** | — |

> ⛔ **`skipped` and `executionError` exist because `noViolation` asserted nothing for them.** A
> skipped rule reports zero violations and is indistinguishable from one that ran and found
> nothing; so is a rule that errored. A scenario written as `noViolation` for either case passes
> while exercising **nothing** — and keeps passing after you tighten a requirement or trim the
> column it needed.

`violation` and `noViolation` now fail with the engine's own **skip reason** when the rule does not
run, and with its **error message** when it errored. So a scenario that quietly stops running tells
you.

### `skipped` scenarios are built by withholding

A `skipped` scenario proves a requirement is real. What you withhold depends on the facet:

| the requirement | the scenario withholds |
|---|---|
| `Variables.All` | one listed column — every entry must be present |
| `Variables.Any` | **all** listed columns; withholding one is not enough |
| `Variables.None` | nothing — it **adds** a listed column |
| `Datasets` | the whole `dataset` block for that name |

## Writing a scenario that proves something

**Write both directions.** A `violation` scenario and a `noViolation` scenario. One alone tells you
the rule can fire, or can stay quiet, but not that it distinguishes.

**Put the near-miss in the `noViolation` file.** Data the rule *nearly* catches is what proves it
is not too broad:

```
CDISCPILOT01 | TS | AGEMIN | 18Y
```

`18Y` is malformed — and must **not** be reported, because its parameter is not `AGEMAX`. A
scenario built only from data the rule ignores proves nothing; one built from data it nearly
catches proves a great deal.

**Cover the shapes this guide warns about**, where they apply to your rule: a blank cell against a
[missing](../semantics/values-and-missing.md) one, a
[partial date](../semantics/comparison.md#dates-and-times--the-hull-rule), an
[absent column](../semantics/absent-columns.md), a
[zero-row dataset](../semantics/zero-rows.md). Each of those is a case where a rule can silently
do the wrong thing, and a scenario is the only place it becomes visible.

## Sharpening the assertion

`expect=violation` says the rule fired. Often you want to say *where*, and *how often*:

```
#expectViolationCount 2
#expectViolationAt row=3 severity=ERROR TSPARMCD=AGEMAX
```

`#expectViolationCount` pins the number of findings; `#expectViolationAt` pins one location, and
optionally the [check level](../authoring/the-check.md#the-severity-ladder) it fired at.

> **Reach for these when the count is the point.** A rule that should fire once per subject and
> fires once per row still passes a bare `expect=violation`.

## Supplying metadata

A rule that reads metadata needs it supplied, or it will skip:

| directive | gives the rule |
|---|---|
| `#library standard=sdtmig version=3-4` | inline CDISC-Library metadata |
| `#library-include <file.yaml>` | the same, from a sidecar |
| `#library-ref standard=… version=…` | a **real** CDISC Library |
| `#define-xml <file.xml>` | a **real** Define-XML sidecar — serves `define_*` and `define_vlm_*` |
| `#dictionaries dummy` | the checked-in dummy external-dictionary bundle |

`#runLevel <LEVEL>` sets the run's severity threshold — the weakest check level the scenario
evaluates.

### ADaM structure and subclass

`Scope.Data_Structures` and `Scope.Subclasses` are normally **detected from the data**. Where a
scenario needs to state them, `structure=` and `subclass=` on the dataset header add the declared
tier, using shorthands:

| shorthand | means |
|---|---|
| `ADSL` | `SUBJECT LEVEL ANALYSIS DATASET` |
| `BDS` | `BASIC DATA STRUCTURE` |
| `OCCDS` | `OCCURRENCE DATA STRUCTURE` |
| `OTHER` | `ADAM OTHER` |

> ⚠ **These shorthands are the scenario format's, not the rule language's.** A rule's
> [`Scope.Data_Structures`](../authoring/scope.md#data_structures-and-subclasses--the-adam-axes)
> takes the **full** token and rejects `BDS` at load. The two vocabularies look alike and are not
> interchangeable.

## One word on `ERROR`

The word means two different things in this format, one line apart:

| spelling | what it is |
|---|---|
| `#expectViolationAt severity=ERROR` | the level a violation **fired at** — the rule ran and found something |
| `#runLevel ERROR` | the run's threshold — which levels are evaluated at all |
| `expect=executionError` | the rule **could not run** |

The verdict is spelled `executionError` rather than `error` deliberately, and `expect=error` is
**not accepted at all** — the parser rejects it and names `executionError`, rather than guessing
which of the three you meant.

---

**Next:** [Self-review](self-review.md) — what to check before proposing the rule.
