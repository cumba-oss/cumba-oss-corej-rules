# Your first rule

<!-- Grounded against CDISC-CG0270 (rules-src/checks/CDISC) and its scenario
     CDISC-CG0270-valid-TS.cdt. The tutorial rule below is its AGEMIN sibling,
     written fresh so the reader authors rather than copies. -->

This page takes one requirement, in plain English, and turns it into a working rule with a
test. It is about twenty minutes. Nothing here is a toy: the rule you end up with is the same
shape as the ones that ship.

## The requirement

Trial Summary carries one row per parameter. When the parameter is the planned **minimum** age
of subjects, its value must be an ISO 8601 duration — `P18Y` for eighteen years, `P6M` for six
months. An empty value is allowed; a value like `18Y` is not.

So the rule must report a row when **all** of these hold:

1. `TSPARMCD` is `AGEMIN`, **and**
2. `TSVAL` is not empty, **and**
3. `TSVAL` is not a valid ISO 8601 duration.

Read that list again. It describes the **violation**, not the requirement. That is what you
author.

## Where the file goes

A rule is two files that share an identifier:

```
rules-src/checks/CDISC/CDISC-CG0269.yaml    the rule: scope, check, outcome
rules-src/docs/CDISC/CDISC-CG0269.yaml      the citations: what guidance says this
```

The `checks` half is what the engine executes. The `docs` half is what a reviewer reads to
confirm the rule matches its source. They are separate files so that re-citing a rule against a
new version of a standard never touches its logic.

## Step 1 — say who the rule is

```yaml
Core:
  Id: "CDISC-CG0269"
  Status: "Published"
  Version: "1"
Description: "Raise an error when TSPARMCD is equal to 'AGEMIN' but TSVAL is not empty and TSVAL is not in ISO 8601 format for a time period (e.g. P18Y)."
Executability: "Fully Executable"
```

`Description` is written for a human reading a list of rules. Describe the **violation** it
raises, in the same voice as the check — the two should never disagree.

## Step 2 — say where it applies

```yaml
Scope:
  Domains:
    Include:
    - "TS"
Requirements:
  Variables:
    All:
    - "TSPARMCD"
```

`Scope.Domains.Include` limits the rule to the `TS` dataset. Without it the rule would be
offered every dataset in the study.

`Requirements.Variables.All` says `TSPARMCD` must exist for the rule to run at all. If it does
not, the rule is **skipped** for that dataset — which is different from finding nothing, and
different again from raising an error.

> **Requiring a variable is not the same as checking it.** Only require what the rule cannot
> work without. A variable you require but never read makes the rule silently inapplicable to
> datasets it should have judged.

Note what is *not* required: `TSVAL`. That is deliberate, and
[Absent columns](../semantics/absent-columns.md) explains why — an absent `TSVAL` behaves as a
column of missing values, and a missing value is not "not empty", so the rule correctly finds
nothing rather than erroring.

## Step 3 — write the check

```yaml
Check:
  expression: >-
    TSPARMCD == "AGEMIN" and not empty(TSVAL) and invalid_duration(TSVAL, negative=false)
```

Three conjuncts, in the order the requirement listed them:

- `TSPARMCD == "AGEMIN"` — a bare name is a **column**; a quoted string is a **literal**. The
  comparison runs per row.
- `not empty(TSVAL)` — `empty` is true for both a missing value and an empty string `""`. This
  is the conjunct that honours "an empty value is allowed".
- `invalid_duration(TSVAL, negative=false)` — true when the text is not a valid ISO 8601
  duration. `negative=false` rejects negative durations, which are syntactically legal ISO but
  meaningless as an age.

The whole expression is evaluated per row. Where it is true, that row is a finding.

> **Order the conjuncts so the cheap, narrowing test comes first.** It reads better, and it
> keeps the later conjuncts away from rows they were never meant to judge.

## Step 4 — say what it reports

```yaml
Outcome:
  Message: "Invalid TSVAL value when TSPARMCD equals 'AGEMIN'; TSVAL must be in ISO 8601 format for a time period (e.g. P18Y) or empty."
  Output_Variables:
  - "TSPARMCD"
  - "TSVAL"
```

`Output_Variables` are the columns whose values appear in the finding. Choose the ones a person
needs in order to find the row in their data and understand why it was flagged — here, the
parameter that triggered the rule and the value that failed it.

> **Never report a variable the rule did not read.** A finding row showing a column the check
> never touched invites the reader to conclude something the rule never checked.

## Step 5 — record where the requirement comes from

The `docs` half:

```yaml
Core: "CDISC-CG0269"
Citations:
  CDISC|SDTMIG|3.4||CG0269|1:
  - Cited_Guidance: "When the trial summary parameter is AGEMIN, then TSVAL should have a value expressed as an ISO8601 time duration (e.g., P18Y for 18 years old)."
    Document: "IG v3.4"
    Section: "7.4.2.1"
```

`Cited_Guidance` is quoted from the standard, not paraphrased. It is the evidence that the rule
is authorised — and the thing a reviewer compares the check against.

## Step 6 — prove it works

A rule without a scenario is an assertion. A scenario is a small dataset plus the verdict you
expect:

```
#!RuleTest
#test CDISC-CG0269 expect=noViolation domain=TS
#note "Row 1 a valid period; row 2 the explicitly allowed empty TSVAL; row 3 a non-duration value under a different parameter, so outside this rule's condition."
#library standard=sdtmig version=3-4
dataset TS label="Trial Summary"
col STUDYID  type=Char label="Study Identifier"
col DOMAIN   type=Char label="Domain Abbreviation"
col TSSEQ    type=Num  label="Sequence Number"
col TSPARMCD type=Char label="Trial Summary Parameter Short Name"
col TSPARM   type=Char label="Trial Summary Parameter"
col TSVAL    type=Char label="Parameter Value"
---
CDISCPILOT01 | TS | 1 | AGEMIN | Planned Minimum Age of Subjects | P18Y
CDISCPILOT01 | TS | 2 | AGEMIN | Planned Minimum Age of Subjects |
CDISCPILOT01 | TS | 3 | AGEMAX | Planned Maximum Age of Subjects | 80Y
```

Three rows, three reasons, and `expect=noViolation` — none of them may be flagged. Row 3 is the
one that earns its place: it is malformed, and it must **not** be reported, because its
parameter is not `AGEMIN`. A scenario that only contains data the rule ignores proves nothing;
a scenario that contains data the rule *nearly* catches proves a great deal.

Then the matching violation scenario, with `expect=violation` and a row carrying `18Y` under
`AGEMIN`.

> **Write the near-miss row first.** Most rules that ship broken are too broad, and a too-broad
> rule passes every scenario built only from data it should flag.

[Scenarios](../testing/scenarios.md) covers the format in full.

## What you have

A rule, its citation, and two scenarios that would fail if the rule were wrong in either
direction. That is the complete unit — nothing ships without all four parts.

---

**Next:** [Anatomy of a rule](anatomy-of-a-rule.md) — the same structure, field by field.
