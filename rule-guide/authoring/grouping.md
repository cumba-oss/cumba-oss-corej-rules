# Grouping

<!-- Grounded: GroupingSpec.java — Variables (ordered, -- resolved at execution time),
     keep_missings (null = engine default = DROP at the rule-level surface; the intended
     direction is true), the deliberate PascalCase/snake_case split, the flat
     Grouping_Variables: alias and the load error when both shapes are declared;
     RuleRunner.executeGroupedQualified + RulePackageLoader.checkQualifiedGroupMembers — a
     qualified member, PLAN-rprfdy-offset-tp-join C3. -->

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

## A qualified member — grouping by a joined value

A grouping key can be a value the rule reads **through a join**. Written qualified, it names the
`Match_Datasets` entry it comes from:

```yaml
Match_Datasets:
- Name: "DM"
  Keys: ["USUBJID"]
Grouping:
  Variables: ["DM.RPATHCD", "RPHASE"]
```

Each evaluated row is grouped by the `RPATHCD` of **its own bound `DM` record** and its own
`RPHASE` — per trial path, per repro phase, although the primary dataset carries no `RPATHCD`.

- **The qualifier names an entry of the same rule** that is an ordinary keyed join (not
  `Child: true`, not `RELREC`, not `SUPP--` / `SQ`, no `--` template name, with `Keys`) and
  **`inner`** — `Join_Type: "inner"` or none. Anything else, and a qualifier naming no entry, is a
  **load error**. So is a `--` or `&` wildcard inside a qualified member. The flat
  `Grouping_Variables` form takes the same members under the same rules.
- **It is a key component like any other.** [`keep_missings`](#keep_missings--rows-whose-key-is-missing)
  applies to it: by default a group whose key carries a blank or missing value — a blank
  `DM.RPATHCD` included — is dropped; with `keep_missings: true` it stays, and `""` and each
  missing value form groups of their own.
- **It is never dropped.** A plain grouping column the dataset lacks partitions nothing and is left
  out of the key. A qualified one the rule cannot read — no `DM` dataset in the study, or `DM`
  without `RPATHCD` — makes the rule **stop with an error**; declare `DM.RPATHCD` in
  [`Requirements.Variables.All`](requirements.md) and such a study **skips** it instead.
- **The finding carries it.** A group's reported key and its unit include the joined value, so two
  paths' groups report two keys.
- **Copies are records.** If the join is not one-to-one per record — `DM` carrying a subject twice,
  on two paths — the join copies the record first, and each copy lands in the group of its **own**
  `DM` record. Groups are then judged over the copies.
- ⚠ **Findings come in first-appearance order** — a group is visited at its first row. The set of
  findings is the one a plain grouping would give; their order, and so which are kept once the
  per-rule cap is reached, can differ.

The same qualified spelling joins a key through an earlier entry in
[`Match_Datasets`](joins.md#a-qualified-key--joining-through-an-earlier-entry) and keys a grouped
function's [`group=`](../reference/functions.md#qualified-group-members) — where, when the two sides
carry different names, the member is written sided, `group=[{left: ACTARMCD, right: ARMCD}]`
(a `domain=` grouped function only; the rule-level `Grouping` has no other side).

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
another dataset, [join](joins.md) it — a joined value can then be a grouping key, written
[qualified](#a-qualified-member--grouping-by-a-joined-value); the groups are still formed over the
evaluated rows.

---

**Next:** [Wildcards and expansion](wildcards.md) — one rule over many variables.
