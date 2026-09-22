# Grouping

<!-- Grounded: GroupingSpec.java — Variables (ordered, -- resolved at execution time),
     keep_missings (null = engine default = DROP at the rule-level surface; the intended
     direction is true), the deliberate PascalCase/snake_case split, the flat
     Grouping_Variables: alias and the load error when both shapes are declared. -->

`Grouping` changes the **unit of evaluation** from the row to the group.

```yaml
Grouping:
  Variables: ["USUBJID", "PARAMCD"]
Check:
  expression: '...'
```

Without it, the check runs once per row and each true row is a finding. With it, the rows are
partitioned by the listed columns and the check answers a question about each partition.

Reach for it when the requirement is about a *set* of records rather than a record: *"a subject
must have exactly one baseline per parameter"*, *"visits must not overlap within a subject"*.

## `Variables` — the grouping key

```yaml
Grouping:
  Variables: ["USUBJID", "PARAMCD"]
```

The columns whose combined value defines a group, **in declared order**. The `--` domain-prefix
placeholder is resolved against the dataset in hand, exactly as elsewhere.

> **Order the key from coarse to fine.** `["USUBJID", "PARAMCD"]` reads as *"per subject, per
> parameter"*, which is how the requirement was almost certainly stated.

## `keep_missings` — rows whose key is missing

```yaml
Grouping:
  Variables: ["USUBJID", "PARAMCD"]
  keep_missings: true
```

A row can have a [missing value](../semantics/values-and-missing.md) in a grouping column. This
decides what happens to it:

- **`true`** — the row stays, folded into a group under the blank key.
- **`false`** — the row is dropped, along with its whole group.
- **absent** — the engine default, which at this surface is **drop**.

> ⚠ **The default drops those rows, and that is rarely what a requirement means.** A missing value
> is a valid value of a variable, so rows with one usually belong in the population. The default
> is conservative rather than correct — it exists so that adding the block to an existing rule
> moves no findings.
>
> **Decide it explicitly.** If the rule is about completeness, a row with a missing key is very
> likely the one you wanted to catch, and the default silently removes it.

> ⚠ **Not to be confused with the `missing_values` keyword argument**, which governs a different
> axis entirely: how a missing *input* affects a *function's result*. This one governs whether a row
> takes part in a group at all.

## Casing

`Variables` is PascalCase and `keep_missings` is snake_case, in the same block. That is
deliberate and follows the corpus convention: **structural keys are PascalCase**
(`Scope`, `Domains`, `Include`, `Variables`), **parameters are snake_case** (`keep_missings`,
`value_is_literal`, `missing_values`).

It is not a typo. Do not "correct" it.

## The older flat form

```yaml
Grouping_Variables: ["USUBJID"]
```

Still accepted, and equivalent to a `Grouping` block with only `Variables`. Prefer the block: it
is the only form that can carry `keep_missings`, and a flat parameter beside a flat variable list
allows a rule to set the parameter with no variables at all — meaningless, and silently so.

> ⛔ **Declaring both shapes on one rule is a load error.** If you are converting a rule to the
> block form, remove the flat key in the same edit.

## What grouping does not do

**It does not filter.** Every row still participates unless `keep_missings` removes it or the
check excludes it. Grouping changes the question's unit, not its population.

**It does not make a rule cross-dataset.** Groups are formed within the dataset in hand. To reach
another dataset, [join](joins.md) it.

---

**Next:** [Wildcards and expansion](wildcards.md) — one rule over many variables.
