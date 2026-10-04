# Glossary

<!-- Grounded: each definition is taken from the guide chapter that owns the term, so the two
     cannot drift apart; where a term names an engine concept the spelling was checked against
     the code (JoinType.java inner/left · Sensitivity.java Record/Dataset/Group/Study ·
     LevelCheck REJECT/ERROR/WARNING/INFO with NOTICE never authorable · Executability's five
     values · RuleExecutionStatus EXECUTED/SKIPPED/ERROR). ⛔ No history: terms that were
     renamed are listed under their CURRENT name only — "operation" and "metadata accessor" are
     deliberately absent, since every one of them is a function. -->

Terms as this guide uses them. Where a term has a chapter, the chapter is authoritative.

## The rule document

**Rule** — one structured document: who it is, where it applies, what it checks, what it
reports. Authored as two files sharing an identifier — the *check* half the engine executes,
and the *docs* half a reviewer reads.
→ [Anatomy of a rule](getting-started/anatomy-of-a-rule.md)

**`Core.Id`** — the rule's identifier, e.g. `CDISC-CG0270`. It names the rule everywhere: its
two source files, its scenario directory, and the filter that runs it.

**Family** — the prefix group a rule belongs to: `CDISC`, `FDA`, `PMDA` or `DRAFT`. It decides
which source directory, which scenario subtree and which package the rule lives in.

**`Executability`** — how completely the rule evaluates its requirement. Its values are a fixed
set; `Not Executable` **parks** the rule, removing it from the corpus entirely.
→ [Identity](authoring/identity.md)

**Parked** — removed at assembly by `Executability: "Not Executable"`. A parked rule reaches no
package, so nothing can run it. The build names the parked rules as it drops them.

## Where a rule applies

**Scope** — which datasets the rule is offered. Its axes differ by standard: `Classes` and
`Domains` are SDTM/SEND vocabulary, `Data_Structures` and `Subclasses` are ADaM's.
→ [Scope](authoring/scope.md)

**Requirements** — what must be true of a dataset before the rule runs at all: variables that
must exist, datasets that must be present. Unmet, the rule is **skipped**.
→ [Requirements](authoring/requirements.md)

**Skipped** — the rule deliberately did not run. Distinct from running and finding nothing, and
distinct again from erroring; the engine states the reason.

**Expansion** — one authored rule standing for many concrete ones, via a declared token
(`&NAME&`, e.g. `&DOM&`, closing `&` mandatory) written bare in a variable name. The expansion
happens before the check runs, once per binding of the token.
→ [Wildcards and expansion](authoring/wildcards.md)

**Wildcard** — `--` in an SDTM variable name, standing for the domain prefix (`--STDTC` in `AE`
is `AESTDTC`). ⚠ A `Match_Datasets` key once spelled `Wildcard` is unrelated, retired, and a load
error.

## Execution

**Check** — the expression evaluated per row. True means **violation**: you author the
condition that describes the problem, never the condition that describes correct data.
→ [The check](authoring/the-check.md)

**Precondition** — a condition that narrows which rows the check judges, written as its own
expression rather than folded into the check.

**Bindings** — named sub-expressions computed once and referred to by name in the check.
→ [Bindings](authoring/bindings.md)

**Check level** — the strength a check fires at: `REJECT` → `ERROR` → `WARNING` → `INFO`,
evaluated strictest first. One rule may carry a different condition per level. `NOTICE` is never
authorable.

**Run threshold** — the weakest level a given run evaluates. Levels below it are not evaluated,
and a rule with nothing at or above it is skipped. The default is `WARNING`, so an `INFO`-only
rule does not run unless the run asks for it.

**`Match_Datasets`** — brings a second dataset into reach so the check can compare across the
two. Its columns are referenced **dot-qualified** (`DM.RFSTDTC`); unqualified names always mean
the primary dataset.
→ [Joins](authoring/joins.md)

**`Join_Type`** — `inner` or `left`, lowercase. An unstated join type is normalised to `inner`,
which discards primary rows that matched nothing — the reason an orphan check needs `left`.

**Grouping** — evaluating the check over groups of rows rather than single rows.
→ [Grouping](authoring/grouping.md)

## Values

**Missing value** — a cell with no value. It is a real value in this engine, never a null, and
comparisons against it are **total**: they return true or false, never "unknown".
→ [Values and missing values](semantics/values-and-missing.md)

**Empty** — `empty(X)` is true for **both** a missing value and an empty string `""`. The two
are not the same thing, and only some tests treat them alike.

**Absent column** — a column the dataset does not have. It does not error: it behaves as a
column of missing values broadcast over every row. So `empty(X)` on an absent `X` is **true on
every row**, which is how a bare `empty` floods.
→ [Absent columns](semantics/absent-columns.md)

**Partial date** — an ISO date missing some of its components (`2024-03`). It stands for a
*range* of possible instants, which is why comparing it is not ordinary comparison.

**The hull rule** — `A op B` holds when it holds for **every** candidate instant `B` could
mean. A partial date therefore fails comparisons a reader expects it to pass.
→ [Comparison](semantics/comparison.md#dates-and-times--the-hull-rule)

**Type** — `Char` or `Num`. A check that reads a column the wrong way is a **type error**, not
a silently coerced comparison.
→ [Types](semantics/types.md)

## The result

**Violation** — one finding: a row (or dataset, or group, or study) the check was true for.

**`Outcome.Message`** — the text a person reads, written for someone who has never seen the
rule. A level may carry its own message; one that does not falls back to this.

**`Output_Variables`** — the columns whose values reach the finding. The engine derives them from
the check and the bindings; author only a context column it would not derive, and never one the
engine derives anyway (the build rejects it).

**`Severity`** — `Warning` or `Reject`. An **absent** `Severity` means ERROR, which is why most
rules omit it.

**Sensitivity** — what the finding is *about*: `Record` (the default), `Dataset`, `Group` or
`Study`.
→ [Outcome](authoring/outcome.md)

## Provenance and release

**`Source`** — the originating document's own fields, verbatim. Never evaluated; it is what a
reviewer compares the check against.

**`Source_Proposed`** — a proposed correction to that wording, with `Sheet_Issue` saying what is
wrong with the published text. It makes a deliberate divergence visible instead of looking like
an authoring error.

**Citation** — the quoted guidance in the docs half, in the standard's own words. It is the
evidence that the rule is authorised.
→ [Provenance](authoring/provenance.md)

**`Standards`** — which standards the rule belongs to and under which identifier in each. It
decides package membership, and therefore where the rule ships.
→ [Standards and release](authoring/standards.md)

## Running and testing

**Corpus** — the generated rule packages the engine loads. It is built from the authored
sources by the build; the engine never reads the YAML you edit.

**Package** — one standard-and-version's worth of rules, the unit the engine loads. Membership
comes from the rule's `Standards` block.

**Scenario** — a `.cdt` file: a small dataset plus the verdict expected of one rule against it.
The data is written out where a reviewer can read it.
→ [Scenarios](testing/scenarios.md)

**Verdict** — a scenario's `expect=`: `violation`, `noViolation`, `skipped` or
`executionError`. The last two exist because the first two could not distinguish a rule that
never ran, or errored, from one that ran and found nothing.

**Near-miss** — data a rule *nearly* catches, placed in a `noViolation` scenario. It is what
proves a rule is not too broad; a scenario built only from data the rule ignores proves nothing.

→ [Running a rule](getting-started/running-a-rule.md)

---

**Next:** [The guide index](index.md)
