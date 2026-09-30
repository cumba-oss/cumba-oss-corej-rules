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

Nine shapes that cover most of what a new rule needs to say. Each one is a real shipped rule,
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

## 9 — A SEND subject's first or last dose, pools included

```yaml
# CDISC-SEND-0305
Bindings:
- name: "$ex_usubjid"
  expression: 'distinct(USUBJID, domain="EX")'
- name: "$min_ex_exstdtc"
  expression: 'min_date(EXSTDTC, domain="EX", group=[USUBJID])'
- name: "$pool_min_exstdtc"
  expression: 'min_date(min_date(EXSTDTC, domain="EX", group=[POOLID], filter=(not empty(POOLID))), domain="POOLDEF", group=[USUBJID])'
- name: "$pool_ex_rows"
  expression: 'max(record_count(domain="EX", group=[POOLID], filter=(not empty(POOLID))), domain="POOLDEF", group=[USUBJID])'
- name: "$first_exstdtc"
  expression: 'earliest_date($min_ex_exstdtc, $pool_min_exstdtc)'
Check:
  expression: >-
    not empty(RFXSTDTC) and (USUBJID in $ex_usubjid or $pool_ex_rows > 0) and date(RFXSTDTC) != $first_exstdtc
```

SEND records a dose given to a group-housed pool **once per pool**: the EX row carries `POOLID` and a
blank `USUBJID`, and POOLDEF maps each pool to its subjects (a subject can be in several pools). A
subject's first dose is therefore the **earlier** of its own first dose and its pools' — three
bindings: the own extreme, the pool extreme (an aggregate over EX per pool, read per POOLDEF row
and aggregated again per subject), and `earliest_date` over the two. A last dose is the same shape
with `max_date` and `latest_date` (`CDISC-SEND-0306`). "Is the subject dosed at all" is
`$pool_ex_rows > 0`, a **count** of the pools' EX rows — never `not empty($pool_min_exstdtc)`, so
that a pool dosed with a blank date is still judged (and reported for its underivable reference).

⚠ The pool binding's `filter=(not empty(POOLID))` is **required**, not a tidy-up: with `POOLID`
absent from EX, `group=[POOLID]` partitions nothing, every EX row is one block keyed blank, and a
POOLDEF row with a blank `POOLID` would take the whole study's first dose as its pool's (a non-blank
`POOLID` matches nothing); and an own EX row with a blank `POOLID` must match no POOLDEF row.

A subject in no pool, a pool with no EX row and a study without POOLDEF all answer *missing* for
the pool part, which `earliest_date` skips — so the rule reads exactly as before on a non-pooled
study, with one exception: the pool read compares `POOLID` between EX and POOLDEF, and `USUBJID`
between POOLDEF and the rule's dataset, and a key whose column type differs between the two (Char
against Num) makes the rule **ERROR**, never a silent non-match. A CSV or XLSX study can meet this
without any pooling, because there the type is inferred from the data and a column blank in every
row reads as Char from CSV but as Num from XLSX — e.g. an empty EX `POOLID` in an XLSX study beside
character POOLDEF pool IDs such as `POOL1` (or, from CSV, beside numeric-looking ones).

When the subject's own EX records or any one of its pools give an undeterminable extreme (a partial
or unreadable date) while another part can be determined, the parts that can be determined decide
(`PLAN-scalar-date-extremes` SDE D2) — between two pools too, since the outer aggregate skips the
undeterminable pool as `earliest_date` skips an undeterminable part. The rule may then be silent
where the exact union of all those EX records would have reported, never the reverse.

→ [`earliest_date`](reference/functions.md#earliest_date) · [`min_date`](reference/functions.md#min_date)

## Assembling a rule from a recipe

A recipe is the `Check`. It is not a rule until it also has an
[identity](authoring/identity.md), a [scope](authoring/scope.md), an
[outcome](authoring/outcome.md), its [provenance](authoring/provenance.md) and its
[standards](authoring/standards.md) — and two [scenarios](testing/scenarios.md), one in each
direction.

[Anatomy of a rule](getting-started/anatomy-of-a-rule.md) is the checklist.

---

**Next:** [Glossary](glossary.md)
