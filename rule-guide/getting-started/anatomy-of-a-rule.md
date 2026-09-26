# Anatomy of a rule

<!-- Grounded against the engine model, not the corpus: net.cumba.corej.core.model.Rule and the
     per-block classes (Scope, Requirements, Outcome, GroupingSpec, MatchDataset, WildcardFilter,
     ExpansionDirective, ExecutabilityHint) plus the enums Executability, Sensitivity,
     VariableUniverse, JoinType, ExpansionSource. Severity default: Rule.effectiveSeverity().
     Precondition liveness: RuleRunner:1287. Derived axes: Requirements.java:66-81. -->

This page is the map. It lists **every block a rule file may contain**, grouped by what the
block is *for*, so that what you need is where you would look for it.

If you have not written a rule yet, read [Your first rule](your-first-rule.md) first. This page
assumes you have seen one.

## A rule is two files

```
rules-src/checks/<FAMILY>/<Id>.yaml     what the rule is and what it does
rules-src/docs/<FAMILY>/<Id>.yaml       the guidance it was written from
```

They are separate so that re-citing a rule against a new version of a standard never touches its
logic, and so that a change to the logic is visible in review without citation prose around it.
`<FAMILY>` is the rule's source organisation — `CDISC`, `FDA`, `PMDA` or `DRAFT`.

**This page describes the `checks` file.** The `docs` file is covered in
[Provenance and citations](../authoring/provenance.md).

## Six groups

A rule file answers six questions, in this order:

| group | the question it answers | blocks |
|---|---|---|
| **[Identity](#identity)** | what is this rule? | `Core` · `Description` · `Executability` · `ExecutabilityHint` |
| **[Applicability](#applicability)** | what does it run on? | `Scope` · `Requirements` · `Variable_Universe` · `skipIfLibraryDefined` |
| **[Execution](#execution)** | what does it compute? | `Precondition` · `Bindings` · `Check` · `Match_Datasets` · `Grouping` · `wildcards` · `wildcardExclude` · `wildcardPairCatalogue` · `Expansion` |
| **[Result](#result)** | what does it report? | `Outcome` · `Severity` · `Sensitivity` |
| **[Provenance](#provenance)** | why does it exist? | `Source` · `Source_Proposed` |
| **[Release](#release)** | where does it ship? | `Standards` |

Blocks may be written in any order. The order above is the order the groups are explained, and
the order it is worth filling them in.

## Every block in one file

No real rule uses all of these at once — a rule that did would be doing several jobs. This is a
catalogue, not a template.

```yaml
# ---- Identity ------------------------------------------------------------
Core:
  Id: "CDISC-CG0270"
  Status: "Published"
  Version: "1"
Description: "Raise an error when TSPARMCD is equal to 'AGEMAX' but TSVAL is not empty and TSVAL is not in ISO 8601 format for a time period."
Executability: "Partially Executable - Possible Underreporting"
ExecutabilityHint:
  Category: "partially executable"
  Detail: "A partial TSVAL cannot be compared, so such rows are not judged."

# ---- Applicability -------------------------------------------------------
Scope:                   # SDTM / SEND axes ...
  Classes:
    Exclude: ["TRIAL DESIGN"]   # Exclude alone = everything else; never pair it with Include
  Domains:
    Include: ["TS"]
  Data_Structures:       # ... and the ADaM axes. A real rule uses ONE family:
    Include: ["BASIC DATA STRUCTURE"]   # together they select nothing.
  Subclasses:
    Include: ["ADVERSE EVENT"]
  Datasets:              # by dataset NAME — the ADaM-relevant axis
    Include: ["ADSL"]
  Use_Case: "INDH, PROD" # externally supplied; a comma-separated string, not an array
Requirements:
  Variables:
    All:  ["TSPARMCD", "TSSEQ:N"]   # `:N` / `:C` demands the column's type too
    Any:  ["TSVAL", "TSVALNF"]      # ⛔ a suffix in None is a load error
    None: ["TSVALCD"]
  Datasets: ["DM"]
Variable_Universe: "Define"
skipIfLibraryDefined: true

# ---- Execution -----------------------------------------------------------
Precondition:
  expression: 'DOMAIN == "TS"'
Bindings:
- name: "$variable_count"
  expression: "variable_count(--LNKID)"
Check:
  expression: >-
    TSPARMCD == "AGEMAX" and not empty(TSVAL) and invalid_duration(TSVAL, negative=false)
Match_Datasets:
- Name: "SUPPTS"
  Keys: ["STUDYID", "USUBJID"]
  Join_Type: "left"
  Child: true
  Filter: 'QNAM == "TSVALX"'
- Name: "RELREC"
Grouping:
  Variables: ["USUBJID"]
  keep_missings: false
wildcards:
  "--":
    min: 1
    max: 4
wildcardExclude: ["SUPP"]
wildcardPairCatalogue: true
Expansion:                 # ⛔ mutually exclusive with the varname() / value() cursor
- token: "&VAR"
  over: "all_variables"    # or all_numeric_variables / all_character_variables /
  with: ["AESTDTC", "AEENDTC"]        # shared_variables / domain_from_variable
  pattern: "--DTC"
  known_domain_only: true

# ---- Result --------------------------------------------------------------
Outcome:
  Message: "Invalid TSVAL value when TSPARMCD equals 'AGEMAX'."
  Output_Variables: ["TSPARMCD", "TSVAL"]
Severity: "Warning"
Sensitivity: "Dataset"

# ---- Provenance ----------------------------------------------------------
Source:
  Class: "TDM"
  Domain: "TS"
  Variable: "TSVAL"
  Condition: "TSPARMCD = 'AGEMAX' and TSVAL ^= null"
  Rule: "TSVAL conforms to ISO 8601"
Source_Proposed:
  Rule: "TSVAL conforms to ISO 8601 duration format"
  Sheet_Issue: "The published wording says 'ISO 8601' without naming the duration form."

# ---- Release -------------------------------------------------------------
Standards:
- Organization: "CDISC"
  Standards:
  - Name: "SDTMIG"
    Version: "3.4"
    References:
    - Origin: "SDTM and SDTMIG Conformance Rules"
      Rule_Identifier: { Id: "CG0270", Version: "1" }
      Version: "2.0"
```

---

## Identity

**What this rule is, to a person reading a list of them.** None of it affects evaluation; all of
it affects whether anyone trusts the rule.

**`Core`** — `Id`, `Status` and `Version`. The `Id` is permanent, carries the family as a prefix,
and appears in every finding, so it is what a data manager quotes back to you. It does not change
once published.

**`Description`** — one sentence describing the **violation** the rule raises, in the same voice
as the check. The check is what runs; the description is what people trust. They must not
disagree.

**`Executability`** — an honest statement of how completely the rule evaluates its requirement:
`Fully Executable`, `Partially Executable`, `Partially Executable - Possible Overreporting`,
`Partially Executable - Possible Underreporting`, or `Not Executable`. It is **documentary**: the
engine does not consult it when deciding what to run.

> **Under- and overreporting are not interchangeable — say which.** A reviewer's response to *"it
> may miss violations"* is different from their response to *"it may flag conforming data"*, and a
> downstream consumer may filter on it.

**`ExecutabilityHint`** — `Category` plus `Detail`, carrying the *reason* in prose. Write it
whenever `Executability` is anything but `Fully Executable`: a partial rule with no stated reason
is a rule nobody can act on. `Detail` is where the substance goes.

→ [Identity and executability](../authoring/identity.md)

## Applicability

**Which datasets the rule is offered, and whether it may run at all.** This group decides
*participation*, never a verdict — a dataset excluded here is not judged, which is different from
being judged and found conforming.

**`Scope`** selects the datasets the rule is *about*, on six axes in two families. `Classes`
(broad) and `Domains` (narrow) are **SDTM / SEND**; `Data_Structures` (broad) and `Subclasses`
(narrow) are **ADaM**. `Datasets` selects by dataset **name** — the axis that matters for ADaM,
where datasets are not domains — and `Use_Case` matches an externally supplied context.

**Prefer the broadest axis that expresses the requirement.** A rule scoped to a class keeps
applying when the standard adds a domain to that class; a rule that lists domains silently stops
covering it, with nothing reporting the gap.

Each axis takes `Include` and `Exclude`, but **never both**: an `Include` already excludes
everything it does not name, and an `Exclude` already includes everything it does not name. Write
*"all but these"* as an `Exclude` on its own.

> ⛔ **`Scope.Variables` does not exist.** A rule that writes one is rejected on load. Variable
> presence is a *requirement*, not a scope — it belongs in the block below.

**`Requirements`** says what must exist before the rule is answerable. An `All` or `Any` entry may
carry a **type suffix** — `AESTDY:N`, `AETERM:C` — demanding the column be numeric or character as
well as present; an unmet type **skips** the rule rather than erroring. `Variables` takes four
facets, ANDed: `All` (every one must exist), `Any` (at least one), `None` (none may exist),
`All_Or_None` (each group all present or all absent — for a join key optional on both sides).
`Datasets` lists datasets that must be present in the run. An unmet requirement **skips** the
rule for that dataset — a distinct outcome from "ran and found nothing".

> **Only require what the rule cannot work without.** A variable you require but never read makes
> the rule silently inapplicable to datasets it should have judged, and a skip is a legitimate
> outcome, so nothing reports it as a problem.

**`Variable_Universe`** — `Data` (the default) or `Define`. It chooses which set of variables the
rule iterates: the ones the data actually has, or the ones Define-XML declares. Use `Define` when
the rule is about what was *promised* rather than what was delivered.

**`skipIfLibraryDefined`** — when `true`, the rule stands down if the CDISC Library already
enforces the same thing, so a finding is not raised twice by two authorities.

→ [Scope](../authoring/scope.md) · [Requirements](../authoring/requirements.md)

## Execution

**What the rule computes, and over what unit.** Everything in this group shapes the same
evaluation: the check runs once per row, unless a block here changes that.

**`Check`** is the expression. True means a violation. Usually one expression:

```yaml
Check:
  expression: 'TSPARMCD == "AGEMAX" and not empty(TSVAL)'
```

It may instead be a **severity ladder** — a map from level (`ERROR`, `WARNING`, `INFO`) to its own
expression, each optionally with its own `Message`. Levels are evaluated most severe first. Reach
for it when a weaker form of the same finding is genuinely worth reporting, typically where
partial dates make a definite verdict impossible.

**`Precondition`** is a second expression, evaluated before the check, that decides whether the
check is attempted at all. Where `Requirements` asks *"does the column exist?"*, a precondition
asks *"is this data in scope for the question?"*

**`Bindings`** name sub-results — each entry is exactly `name` (a `$`-variable) plus `expression`.
A binding keeps a long check readable and may be listed in `Output_Variables` so its value reaches
the finding.

> ⛔ **`Bindings:` is the only spelling.** An `Operations:` block, or a check written as
> `operator:` / `name:` / `value:`, makes the rule **fail to load**.

**`Match_Datasets`** joins another dataset so the check can see both sides. Each entry takes
`Name`, `Keys`, `Join_Type` (`inner` or `left`) and `Filter` — a boolean expression over the
joined dataset's **own** rows, applied before the key index is built, so it cannot depend on
which row survives the join. Two special join shapes: `Child` selects parent-row resolution
for `SUPP--` / `CO` / `RELREC` primaries, and a keyless `RELREC` entry selects forward RELREC
expansion. Any other key is a load error.

**`Grouping`** changes the unit of evaluation from the row to the group: `Variables` are the
grouping keys, and `keep_missings` decides whether rows with a missing key form groups of their
own or are dropped.

**`wildcards`**, **`wildcardExclude`** and **`wildcardPairCatalogue`** constrain how a wildcard in
a variable name (`--`, `xx`, `y`, `w`, `zz`) may expand — `wildcards` per token with `min` and
`max` lengths, `wildcardExclude` by prefix, `wildcardPairCatalogue` to pair related expansions.

**`Expansion`** repeats the check over a set of variables: `token` names the placeholder, `over`
selects the source — `all_variables` and its numeric / character variants, `shared_variables`,
`domain_from_variable` — and `with`, `pattern` and `known_domain_only` narrow it. ⛔ `over: all_*`
and the `varname()` / `value()` cursor are mutually exclusive; a rule using both is rejected.

→ [The check](../authoring/the-check.md) · [Bindings](../authoring/bindings.md) ·
[Joins](../authoring/joins.md) · [Grouping](../authoring/grouping.md) ·
[Wildcards and expansion](../authoring/wildcards.md)

## Result

**What a person sees when the rule fires.**

**`Outcome`** — `Message` and `Output_Variables`. The message is read by someone who has never
seen the rule; the output variables are the columns whose values reach the finding, chosen so
that person can locate the row and see why it was flagged.

> **Never report a variable the rule did not read.** A finding showing a column the check never
> touched invites the reader to conclude something the rule never checked.

**`Severity`** — `Warning` or `Reject`. **An absent `Severity` means ERROR**, which is why most
rules omit it; `Severity: "Error"` is not merely redundant, it is not a legal spelling and is
stripped on load.

**`Sensitivity`** — what the finding is *about*: `Record` (the default), `Dataset`, `Group` or
`Study`. A dataset-sensitive rule reports the dataset once rather than naming rows, which is the
right shape when the violation is a property of the whole table.

→ [Outcome, severity and sensitivity](../authoring/outcome.md)

## Provenance

**Why this rule exists, in the source's own words.** None of it is evaluated. It is what a
reviewer compares the check against, which is exactly why it is kept unedited.

**`Source`** — the originating document's own fields: `Class`, `Domain`, `Variable`, `Condition`,
`Rule` and the rest, verbatim.

**`Source_Proposed`** — a proposed correction to that wording, with `Sheet_Issue` stating what is
wrong with the published text. Use it when the source is ambiguous or mistaken: the rule
implements the corrected reading, and this block is the record of the discrepancy, so the
divergence is deliberate and visible instead of looking like an authoring error.

Both blocks live in the authored file only: the build strips them from every released package,
and a package that still carries one fails to load.

→ [Provenance and citations](../authoring/provenance.md)

## Release

**`Standards`** says which standards this rule belongs to, and under which identifier in each —
`Organization`, then one entry per `Name` / `Version`, each with the `References` that tie it to
the published rule identifier. One rule commonly lists several versions of the same standard.

This is what decides where the rule ships. A rule missing a standard is a rule nobody receives.

→ [Standards and release](../authoring/standards.md)

## Not yours to write

Three fields look authorable and are not — the loader **derives** them, and writing them by hand
either has no effect or contradicts what the loader computes:

`Requirements.Library` · `Requirements.Define` · `Requirements.Dictionary`

They record whether the rule needs a CDISC Library provider, a sponsor Define-XML overlay, or an
external dictionary. All three follow from what your check actually references, so state the
dependency by referencing it, not by declaring it.

---

**Next:** [Running a rule](running-a-rule.md) — executing it and reading what comes back.
