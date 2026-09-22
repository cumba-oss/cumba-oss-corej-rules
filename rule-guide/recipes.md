# Recipes

<!-- Grounded: every YAML fragment on this page is taken from a REAL shipped rule in
     rules-src/checks/, quoted or minimally trimmed, never invented. The rule id above each
     recipe is where it came from, so a reader can diff the recipe against the original.
     Censused 2026-09-22 over 6 532 authored checks; the pattern frequencies quoted in the
     prose come from that census (empty 3 207 · var_exists 1 511 · has_multiple_values_for 305
     · is_inconsistent_across_dataset 203 · is_unique_set 137 · Match_Datasets 207 rules).
     ⛔ CDISC-AD0688 is deliberately NOT the uniqueness exemplar. It shipped
     `is_unique_set([USUBJID, USUBJID, SPDEVID])` — USUBJID twice, while its own Description,
     Message and Source all said "USUBJID and SPDEVID". Surfaced from this page rather than
     copied, and FIXED 2026-09-22 (the duplicate is gone; the rule now reads
     `[USUBJID, SPDEVID]`). Root cause: the 2026-08-23 is_unique_set flattening migration
     preserved member order with no dedup BY DESIGN — see UniqueSetFlattener's javadoc, which
     names this very rule as "filed, not fixed". The recipe-5 warning stays: nothing in the
     engine rejects a repeated key and no scenario can catch one. -->

Eight shapes that cover most of what a new rule needs to say. Each one is a real shipped rule,
named so you can go and read the original.

Read [The check](authoring/the-check.md) first if you have not — these are patterns, not a
substitute for knowing what the expression means.

> **Every recipe describes the violation.** That is the polarity the engine expects: the
> expression is true for the rows you want reported. If a recipe reads backwards to you, that
> is why.

## 1 — A value must be populated when a condition holds

The most common rule in the corpus, by a wide margin.

```yaml
# FDA-SD1088
Match_Datasets:
- Name: "DM"
  Keys: ["USUBJID"]
Check:
  expression: >-
    is_complete_date(--STDTC) and is_complete_date(DM.RFSTDTC) and empty(--STDY)
```

The condition comes first, the absence last. `empty` is true for **both** a missing value and
an empty string, which is what you almost always want here.

> ⚠ **`empty` is not the opposite of "has a value you can use".** It says nothing about whether
> the value is *valid*. Pair it with a validity test when the rule cares —
> `not empty(X) and <X is malformed>`.

## 2 — A value must be one of a fixed set

```yaml
# CDISC-AD0180
Check:
  expression: >-
    not empty(SRCDOM) and SRCDOM not in $sdtm_domains
```

`not in` against a list. The `not empty` guard in front is doing real work: without it the rule
also fires on every row where `SRCDOM` was never populated, which is a different defect and
usually a different rule's job.

For a literal set, write the list inline:

```yaml
# CDISC-AD0006
Check:
  expression: >-
    var_exists(*FL) and not empty(*FN) and *FN not in [0, 1]
```

> ⚠⚠ **Lists are `[...]`, not `(...)`.** Round brackets are grouping. `X in ("A", "B")` is not
> a list — see [Expression syntax](reference/expression-syntax.md).

## 3 — Two columns must agree, row by row

```yaml
# CDISC-AD0037
Check:
  expression: >-
    not empty(*GRyN) and not empty(*GRy) and has_multiple_values_for(*GRyN, *GRy)
```

`has_multiple_values_for(a, b)` fires when one value of `a` is paired with more than one
distinct `b` anywhere in the dataset — the coded/decoded consistency check. Both guards are
needed, or unpopulated rows join the comparison and manufacture a second "value".

## 4 — A value must be constant within a group

```yaml
# CDISC-SEND-0309
Check:
  expression: >-
    var_exists("SETCD") and is_inconsistent_across_dataset(SPECIES, keys=[SETCD])
```

Read it as: *within each `SETCD`, `SPECIES` must not vary.* The `var_exists` guard is the
[absent-column](semantics/absent-columns.md) precaution — see recipe 8.

## 5 — A key must be unique

```yaml
# CDISC-CG0150
Check:
  expression: >-
    not is_unique_set([SUBJID, STUDYID])
```

`is_unique_set` takes the key as a **list**, and the rule fires when the combination repeats.

> ⚠ **List each key column once.** A repeated column is inert — uniqueness of
> `(A, A, B)` is uniqueness of `(A, B)` — so it changes nothing, fails nothing, and quietly
> contradicts the rule's own message. Nothing rejects it, and no scenario can catch it: the
> verdict is identical either way, which is exactly why it survives review.

## 6 — A value must match a value in another dataset

```yaml
# CDISC-CG0075
Match_Datasets:
- Name: "DM"
  Keys: ["USUBJID"]
Check:
  expression: >-
    not empty(DVSTDTC) and date(DVSTDTC) < date(DM.RFICDTC)
```

`Match_Datasets` brings the second dataset into reach; its columns are **dot-qualified**
(`DM.RFICDTC`), and an unqualified name always means the primary dataset.

Both sides go through `date(...)` so the comparison is a date comparison rather than a string
one. That matters more than it looks — read
[the hull rule](semantics/comparison.md#dates-and-times--the-hull-rule) before writing any date
comparison, because partial dates do not compare the way you expect.

→ [Joins](authoring/joins.md)

## 7 — A row must have a matching parent (the orphan check)

```yaml
# FDA-SD1012
Match_Datasets:
- Name: "TE"
  Keys: ["ETCD", "ELEMENT"]
  Join_Type: "left"
Check:
  expression: >-
    ETCD != "UNPLAN" and empty(TE.ETCD)
```

This is the one recipe where `Join_Type` is load-bearing. A **left** join keeps rows that
matched nothing, and `empty(TE.ETCD)` is then exactly "no parent was found".

An unstated `Join_Type` is normalised to **`inner`**, which keeps only primary rows that *did*
match — so the unmatched rows are gone before the check ever runs.

> ⛔ **An orphan check without `Join_Type: "left"` is silently vacuous.** The rows it exists to
> find are the rows an inner join discards, so it cannot fire, and it will pass every study you
> test it against. Write the join type whenever the *absence* of a partner is the question —
> and write it lowercase.

## 8 — Guarding a column that may not exist

```yaml
# CDISC-AD0007
Check:
  expression: >-
    var_exists(*FN) and not var_exists(*FL)
```

An absent column does not error — it behaves as a column of missing values, broadcast over
every row. That is usually what you want, but it flips the meaning of some tests:
`empty(X)` on an absent `X` is **true on every row**, so a bare `empty` can flood.

Two ways to deal with it, and they are not interchangeable:

| | use when |
|---|---|
| `Requirements.Variables.All` | the rule cannot work without the column — the rule **skips** |
| `var_exists(X) and …` in the check | only one branch needs it, or the rule must still judge the other datasets |

→ [Absent columns](semantics/absent-columns.md) · [Requirements](authoring/requirements.md)

## Assembling a rule from a recipe

A recipe is the `Check`. It is not a rule until it also has an
[identity](authoring/identity.md), a [scope](authoring/scope.md), an
[outcome](authoring/outcome.md), its [provenance](authoring/provenance.md) and its
[standards](authoring/standards.md) — and two [scenarios](testing/scenarios.md), one in each
direction.

[Anatomy of a rule](getting-started/anatomy-of-a-rule.md) is the checklist.

---

**Next:** [Glossary](glossary.md)
