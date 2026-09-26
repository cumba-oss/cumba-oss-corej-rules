# Scope

<!-- Grounded: Scope.java (six axes; Use_Case is a single String, @JsonAlias "Data Structures";
     Scope.Variables rejected by loader gate R1; unknown keys -> gate R2),
     DomainScope (Include/Exclude/include_split_datasets), ClassScope, DataStructureScope,
     SubclassScope, DatasetScope (no split re-test, no include_split_datasets),
     ScopeMatcher.describeDomainMismatch / describeDatasetMismatch / describeClassMismatch
     (FINDINGS ABOUT widening via containsFindings) / describeUseCaseMismatch (comma-split,
     case-insensitive, absent-matches-all; wired in by PLAN-use-case-scope-filter: first axis of
     DatasetRuleResolver.describeScopeSkip, and LibraryValidator's anchor pass), RulePackageLoader
     validateUseCaseShape (R-4.10 / R-4.10a load error), StudyValidationParams (single-code input).
     Vocabularies and the Include+Exclude measurement taken from rules-src/checks 2026-09-21. -->

`Scope` decides **which datasets the rule is offered**.

It is a selection, never a verdict. A dataset outside a rule's scope is not judged by it — which
is a different outcome from being judged and found conforming, and different again from being
[skipped for a missing requirement](requirements.md).

```yaml
Scope:
  Classes:
    Include: ["FINDINGS"]
```

## ⭐ Scope by the concept, not by a list of datasets

**Prefer the most general axis that expresses the requirement.** If the rule is about every
findings domain, write the class:

```yaml
# ✅ says what the rule is about
Scope:
  Classes:
    Include: ["FINDINGS"]

# ⛔ says which datasets happened to exist when it was written
Scope:
  Domains:
    Include: ["LB", "VS", "EG", "PC", "PP", "MB", "IE", "QS", "SC", "FT"]
```

The two may select the same datasets **today**. They behave differently the moment the standard
changes.

### What happens when the standard adds a dataset

A new findings domain is published. The class-scoped rule is offered it immediately and keeps
enforcing the requirement. The domain-list rule is **not** — and nothing reports that. No error,
no warning, no gap in a coverage report. The rule keeps passing, on a population that quietly
stopped being the population it was written for.

> **A stale scope fails silently, and silence is indistinguishable from conformance.** This is
> the argument for the general axis: it is not about brevity, it is about which way the rule
> fails when the world moves underneath it.

### When a narrow scope is the right answer

Scope narrowly when the requirement is about **that dataset's own semantics**, not about a
property its class shares:

- `TS` — the rule is about a named trial-summary parameter. No other dataset has `TSPARMCD`.
- `DM` — the rule is about `RFSTDTC` as the study reference start. Another special-purpose
  dataset acquiring a similar column would not make the rule apply to it.
- `RELREC`, `SUPP--` — the rule is about the relationship mechanism itself.

The test is a question about the future: **if the standard added another dataset of this class
tomorrow, should this rule judge it?** If yes, scope by class or structure. If no, name the
dataset — and the narrow scope is then a statement, not an accident.

## The axes come in two families

`Classes` and `Domains` are **SDTM / SEND** terminology. `Data_Structures` and `Subclasses` are
**ADaM**. They are not alternatives to each other; they describe different standards.

| standard | broad axis | narrow axis |
|---|---|---|
| SDTM / SEND | `Classes` | `Domains` |
| ADaM | `Data_Structures` | `Subclasses` |

Two further axes cut across both:

| axis | selects by |
|---|---|
| `Datasets` | the dataset **name**, exactly |
| `Use_Case` | an externally supplied use case |

**Every axis you give must be satisfied.** They narrow; they do not offer alternatives. Mixing
families — a rule with both `Classes` and `Data_Structures` — asks for a dataset that is
simultaneously SDTM and ADaM, and selects nothing.

> **Give one axis.** A second is right only when it genuinely narrows something the first cannot
> express.

## `Include` and `Exclude`

**Use one facet or the other — never both on the same axis.** Each already implies the other:

- an **`Include` excludes** everything it does not name;
- an **`Exclude` includes** everything it does not name.

```yaml
# ⛔ pointless — AE was never in scope to begin with
Domains:
  Include: ["LB", "VS"]
  Exclude: ["AE"]

# ⛔ also pointless — the Exclude alone says exactly this
Domains:
  Include: ["ALL"]
  Exclude: ["RELREC"]

# ✅ every domain except one
Domains:
  Exclude: ["RELREC"]
```

An axis with no `Include` matches everything the `Exclude` does not remove, so *"all but these"*
is written with an `Exclude` on its own.

> ⛔ **`ALL` is not allowed in an `Exclude`**, and on the closed ADaM axes the loader rejects it
> outright: it can never match a detected token, so the exclusion would be a silent no-op.

### `Include: ["ALL"]`

Selecting every dataset is the **default** — a rule with no `Scope` at all is offered everything.
`Include: ["ALL"]` is therefore not required.

**Write it anyway when that is the intent.** It says *"this rule is deliberately about every
dataset"*, where an absent `Scope` says only *"nobody wrote one"*. The two look identical to the
engine and completely different to a reviewer.

⚠ That is the one place `ALL` belongs: on its own, as the whole scope. Not beside an `Exclude`.

## `Domains` — the SDTM / SEND narrow axis

```yaml
Scope:
  Domains:
    Include: ["AE"]
```

### What an entry may be

| entry | matches |
|---|---|
| `AE` | the `AE` domain, literally |
| `ALL` | every domain |
| `NONE` | no domain — an explicit empty selection |
| `SUPP--` | a glob: every `SUPP` domain (`SUPPAE`, `SUPPDM`, …) |
| `/^AD.*$/` | a regular expression, between slashes |
| `--` | the strict domain-prefix token |

### Split domains

A large domain may be delivered as several files — `LB1`, `LB2` — that together are one `LB`.
`Scope.Domains` is **split-aware**: `Include: ["LB"]` selects `LB1` and `LB2` through the
data-derived unsplit name, not just a file literally called `LB`.

`include_split_datasets` makes that explicit:

- `true` — the rule applies **only** where the domain is actually split. A rule comparing split
  members needs this; without it the rule also runs on unsplit domains, where it has nothing to
  compare.
- `false` — the rule does not apply to split members.
- absent — both are in scope, which is what almost every rule wants.

## `Classes` — the SDTM / SEND broad axis

```yaml
Scope:
  Classes:
    Include: ["FINDINGS"]
```

The vocabulary in use:

`FINDINGS` · `FINDINGS ABOUT` · `INTERVENTIONS` · `EVENTS` · `SPECIAL PURPOSE` · `RELATIONSHIP` ·
`TRIAL DESIGN`

> ⚠ **`FINDINGS` includes `FINDINGS ABOUT`.** A rule scoped to `FINDINGS` is also offered
> `FINDINGS ABOUT` datasets, deliberately — the latter is a specialisation of the former. If your
> rule must *not* see them, exclude them explicitly; the include alone will not do it.

## `Data_Structures` and `Subclasses` — the ADaM axes

```yaml
Scope:
  Data_Structures:
    Include: ["BASIC DATA STRUCTURE"]
```

`Data_Structures` selects on the dataset's **detected** ADaM structure. The vocabulary is
**closed** — exactly these eight tokens, plus the `ALL` sentinel in `Include`:

`SUBJECT LEVEL ANALYSIS DATASET` · `BASIC DATA STRUCTURE` · `OCCURRENCE DATA STRUCTURE` ·
`MEDICAL DEVICE BASIC DATA STRUCTURE` · `MEDICAL DEVICE OCCURRENCE DATA STRUCTURE` ·
`DEVICE LEVEL ANALYSIS DATASET` · `REFERENCE DATA STRUCTURE` · `ADAM OTHER`

`Subclasses` selects on the detected subclass, from the Define-XML 2.1 vocabulary — five tokens,
plus `ALL`:

`ADVERSE EVENT` · `MEDICAL DEVICE TIME-TO-EVENT` · `NON-COMPARTMENTAL ANALYSIS` ·
`POPULATION PHARMACOKINETIC ANALYSIS` · `TIME-TO-EVENT`

### Write the token exactly — short forms are not accepted

> ⛔ **`OCCDS`, `BDS` and `ADSL` are not tokens.** They are the names the engine's *source code*
> uses for these constants; the authored value is the full string. Write
> `OCCURRENCE DATA STRUCTURE`, not `OCCDS`.

The match is by **exact string**, so the spelling matters in full: not an abbreviation, not
`Basic Data Structure` in mixed case, not `BASIC_DATA_STRUCTURE`.

**Getting it wrong fails at load, and that is the point.** These vocabularies are closed
precisely so a wrong token cannot slip through — an unrecognised entry can never match a detected
value, so it would silently disable the rule (in `Include`) or do nothing at all (in `Exclude`).
The loader rejects it by name instead.

⚠ The canonical key spelling is `Data_Structures`. The upstream `Data Structures`, with a space,
is accepted as an alias — prefer the canonical one.

### A specialisation matches its base

The structure tokens form an **is-a** hierarchy, and so do the subclasses. A dataset detected as
`MEDICAL DEVICE BASIC DATA STRUCTURE` also matches a scope naming `BASIC DATA STRUCTURE`.

So scoping to a base token covers its specialisations — the same principle as
[`FINDINGS` covering `FINDINGS ABOUT`](#classes--the-sdtm--send-broad-axis), and the same reason
to prefer it: a specialisation added to the standard later is picked up by a base-scoped rule and
missed by one that enumerates the specialised tokens.

### The asymmetry that catches people

Both are **detected**, and a dataset may have no detectable subclass at all — the normal case for
a plain BDS or ADSL dataset. `Include` and `Exclude` treat that case oppositely:

- **`Include` requires a positive detection.** A dataset whose subclass cannot be detected is
  **not** selected, so an `Include` narrows harder than it looks.
- **`Exclude` rejects only on a positive match.** A dataset whose subclass cannot be detected
  **passes** an exclude-only scope.

> **If your rule should judge every dataset except one subclass, use `Exclude`.** Writing the
> complement as an `Include` list silently drops every dataset whose subclass is undetectable.

## `Datasets` — the pure dataset name

```yaml
Scope:
  Datasets:
    Include: ["ADSL"]
```

**This is not `Domains` under another name.** It matches the dataset's name, exactly, with no
domain interpretation and **no split re-test**: `Datasets: ["LB"]` selects the dataset called
`LB` and nothing else, where `Domains: ["LB"]` would also select `LB1` and `LB2`.

**It is the axis that matters for ADaM.** ADaM datasets are not domains — `ADSL`, `ADAE`, `ADTTE`
are names. A rule about one specific analysis dataset names it here; `Domains` has no meaning for
it.

The entry vocabulary is the same as `Domains` — literals, `ALL`, `NONE`, globs, `/regex/`.

> ⛔ **`include_split_datasets` does not exist on this axis**, and writing it is caught by the
> loader. It is a statement about domain families; on a name axis it has no meaning.

> ⚠ Glob, `/regex/` and `NONE` entries here are a coreJ extension — legal, and **not portable** to
> the upstream CORE schema, whose `Datasets` axis is a name enum plus a name pattern.

## `Use_Case`

```yaml
Scope:
  Use_Case: "INDH"
```

A use case is **supplied externally, by whoever runs the validation** — it is not a property of
the data. It lets one corpus serve several regulatory contexts from the same rules.

The matching rule has three arms:

| | |
|---|---|
| no use case supplied to the run | **every** rule is in scope; the axis does nothing |
| a use case is supplied, and the rule declares none | the rule **is** in scope |
| a use case is supplied, and the rule declares some | in scope only if **any** one of them is the supplied one |

So declaring a `Use_Case` **narrows** a rule to that context, and leaving it absent keeps the rule
universal. Each code is compared whole and case-insensitively: `PROD` matches `prod`, never `PRO`.

A rule outside the run's use case is **not executed** and is reported `SKIPPED` — one
`Skipped_Rules` row per target dataset, with the reason `use case NONCLIN not in Scope.Use_Case
[INDH]`, and `SKIPPED` in `Rules_Report`. That is the report shape of every other scope axis. A
study-level rule is filtered the same way.

The run names **one** use case, a single code of letters (e.g. `-uc INDH`). A list such as
`INDH, PROD` is rejected when the run starts: only a rule may list several.

### Several use cases

A rule may name more than one. They are written as a **comma-separated string**:

```yaml
Use_Case: "INDH, PROD"
```

> ⚠ **Not a YAML array.** The field binds as a single string and is split on commas at match time;
> `Use_Case: ["INDH", "PROD"]` is not the shape the engine reads. Whitespace around the commas is
> trimmed.

> ⛔ **A malformed value is a load error.** The codes are upper-case letters separated by commas,
> with no empty code and no duplicate (`R-4.10`). `"INDH;PROD"`, `"indh"`, `"INDH,"` or `" "` make
> the rule an ERROR execution instead of running: once a run names a use case, a misspelt code
> would match none and silently skip the rule.

## Things that are not scope

> ⛔ **`Scope.Variables` does not exist.** A rule that writes one is **rejected on load**. Variable
> presence is a requirement, not a scope — see [Requirements](requirements.md). The rejection
> exists precisely so the requirement cannot silently not-exist.

> ⛔ **Scope is not a row filter.** It decides which *datasets* the rule sees, never which rows.
> Restricting rows is the [check's](the-check.md) job, or a
> [precondition's](the-check.md#precondition).

Any key under `Scope` that is not one of the six axes is recorded by the loader and reported, so a
misspelled axis fails loudly rather than binding to nothing and leaving the rule unscoped.

---

**Next:** [Requirements](requirements.md) — what must exist before the rule runs.
