# Zero-row datasets

<!-- Grounded: documentation/zero-row-policy.md (owner rulings 2026-08-22 / 2026-08-23, EC-89,
     Fix #349/#350) — a zero-row dataset is an error case reported ONCE per package by the
     "domain table has no records" rule; a row-reading rule returns EXECUTED with zero findings,
     never SKIPPED, which is reserved for an absent provider or dataset; a rule that decides
     without reading a row fires exactly as on a populated dataset; grouped rules see zero
     groups. The synthetic 1-row zero-column substitute table was retired by Fix #349. -->

A dataset can be present, carry every column the rule names, and hold **no rows**. This page is
about what your rule does then.

## It is already reported — do not report it again

An empty dataset is an error case, and every non-draft rule package already carries **exactly one**
rule that reports it: the *"domain table has no records"* rule, scoped to every domain, counting
rows and firing on zero.

> ⛔ **Your rule should not also report it.** If every rule flagged an empty dataset, one missing
> table would produce a finding from every rule in the package, burying the one finding that says
> what actually happened.

Write the check for the question it is about, and let the empty dataset be somebody else's finding.

## What the engine does

**A rule that must read a row** has nothing to judge. It runs, finds nothing, and reports as
**executed with zero findings**.

> ⚠ **Not skipped.** A skip means *"a dataset or a provider was absent"* — a different fact, read
> differently downstream. An empty table was present; it simply had nothing in it.

**A rule that can decide without reading a row** — one that asks about metadata, about Define-XML,
about the library — fires exactly as it would on a populated dataset. The answer does not depend on
rows, so the absence of rows changes nothing:

```yaml
Check:
  expression: 'var_label() != var_label("LIBRARY")'
```

**A grouped rule** sees zero rows, so it forms zero groups, so it reports nothing.

**No fold, no collapse.** A record-level rule on no records reports no finding — the absent-column
fold and the present-but-empty collapse ([`Message_Folded`](../authoring/outcome.md#message_folded))
never turn that into one. (A column of a 0-row table reads as all-missing and single-valued,
vacuously; the engine tests the row count before it folds.)

## What this means when you author

**You do not need a row-count guard.** A check that reads rows is already inert on an empty
dataset; adding `$records > 0 and …` buys nothing and adds a binding to maintain.

**Be deliberate about metadata rules.** A rule that compares a label, checks a declared length or
reads Define-XML *will* fire on an empty dataset, because it does not need rows to be right. That
is normally correct — a mis-declared variable is mis-declared whether or not anyone populated it —
but it is worth knowing that these are the rules an empty table does not silence.

**Scenario coverage is where this gets pinned.** If your rule's behaviour on an empty dataset is
part of what it means, write a zero-row scenario for it. A rule with no such scenario has an
untested answer for a case that happens in real submissions.

---

**Next:** back to [Values and missing values](values-and-missing.md), or on to
[Scenarios](../testing/scenarios.md) to pin any of this.
