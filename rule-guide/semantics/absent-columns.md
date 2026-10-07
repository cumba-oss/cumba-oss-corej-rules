# Absent columns

<!-- Grounded: ExprCompiler EC-43 — ALL_MISSING is a broadcast ConstVector, nameRefPlan /
     valueRefPlan fold a foldable column reference to it; the fold is TYPE-DERIVED (D76/D131a):
     numeric-expected -> all-missing, otherwise a STRING vector, which is why len(ABSENT) == 0
     holds alongside len(BLANK) == 0 (see BuiltinFunctions' len comment).
     FIRES_ON_ABSENT_COLUMN = {empty} (is_missing left it with register D50, 2026-10-05). Requirements skip semantics:
     VariableRequirement / ScopeMatcher.describeVariablesMismatch. -->

A rule is offered datasets it was written for, but a dataset may still not carry every column the
check names. This page is about what the engine does then — and about what you have to write,
which is less than you would expect.

## An absent column is a value, not an error

**The check still runs.** A column the dataset does not have is folded to a **constant column** —
the same value on every row — and evaluation proceeds normally. Nothing errors, nothing is skipped,
and no special case is needed in the expression.

What that constant is depends on how the check reads the column:

| the check reads it as | the absent column behaves as |
|---|---|
| a number | **missing**, on every row |
| text | **`""`**, on every row |

That second row is the one to remember. It is why `len(x) == 0` is **true** for an absent
character column — the same answer it gives for a blank cell, which is exactly the point: a
column that is not there and a column full of blanks are indistinguishable to a check that asks
about emptiness.

### The same through a binding, and through a function

The position decides wherever the column is read (owner 2026-10-05,
`PLAN-column-typed-function-absent-type`):

- **Through a `$`-binding the binding's *uses* decide.** `$x: AESTDY` read as `$x > AEENDY` is
  `AESTDY > AEENDY` — a number, so missing. A binding answers exactly as its expression written in
  its place, here too.
- **A function whose result is the type of a column it reads** takes that column's default at its
  own position when the column is not there: `read_value` over an absent `domain` dataset or
  column, `max` over an absent column, `row_max` over a pattern no column matches, `colref` naming
  an absent column — missing where the result is read as a number, `""` everywhere else.
- Two `read_value` / `max` calls over the same `domain` dataset and column are **one column**: the
  uses of both decide, as two direct reads of one column do. `value()` over a Define-XML variable
  the data lacks is decided by its position the same way.
- Where the uses disagree (a number in one place, text in another) the **number wins**; with no
  typed use at all (`empty($x)`, a reported value) it is **`""`**. `coalesce` passes its position
  through to its operands.

> ⚠ **Mind the direction of an order comparison.** A missing sorts below every value, so
> `X > $x` over an absent column is **true on every row** where `X` is present — a flood, not
> silence. Guard a numeric use of such a binding with `not empty($x)` (authoring rule R-4.11b: a
> value-level rule does not emit more than one finding per dataset for the sole reason that a
> column is absent).

## The usual consequence: silence

Most checks simply do not fire on an absent column, and that is usually correct.

A [missing value](values-and-missing.md) fails most comparisons, matches no regex, and is in no
literal list — so a check built from ordinary operators judges nothing and reports nothing:

```yaml
expression: 'AESTDY > AEENDY'      # AESTDY absent → no findings, no error
```

> **Silence is the safe default, but it is still silence.** Nothing in the output distinguishes
> *"the rule ran and the data was fine"* from *"the column was not there"*. If that distinction
> matters — and for a conformance rule it usually does — see the next section.

## A predicate that does fire

`empty(x)` reports **every row** of an absent column as empty. (`is_missing(x)` does not: it is
true only for a genuine missing, so over an absent column it answers the column's default — false
where the column reads as `""`, true where its position makes it a number and so the missing;
rulings register `D50`, owner 2026-10-05.) An absent column
carries no data, so it is empty for every row; that is the honest answer, and it is also a trap:

```yaml
expression: 'empty(AEENDTC)'     # fires on EVERY row when AEENDTC is absent
```

A rule that reports a blank column will report the whole dataset when the column is missing
entirely. That is usually what you want from a completeness rule — but decide it rather than
discover it.

### Both folds are opt-in: `Message_Folded`

A rule that declares [`Outcome.Message_Folded`](../authoring/outcome.md#message_folded) reports
**one** dataset-level finding under that message where it would otherwise report per record:

- an **absent** column is decided before a row is read — `empty(AEENDTC)` over an absent `AEENDTC`
  is true on every record, so the rule folds to one finding (the absent-column fold);
- a column that is **present but blank on every record** is a data fact the engine knows only
  after reading the rows: an `empty`-family leaf over it makes the rule's row findings **collapse**
  to one finding (none when no record fires), and the same holds for an `empty`-family leaf over an
  absent column that leaves a per-record residual — but only for a polarity-live leaf (`empty(X)`,
  never `not empty(X)`) and only when **every** live leaf of the Check is decided, its column absent
  or blank on every record; one still filled on some record keeps the rule per record (owner,
  2026-10-07);
- [`Keep_Row_Findings`](../authoring/outcome.md#keep_row_findings) keeps the per-record findings on a
  named column; a dataset with no rows never folds or collapses.

A rule **without** the key folds nothing (owner, 2026-10-05): on an absent column it is evaluated
per record, the column reading as its type default, exactly like a column blank on every record.
Every corpus rule the fold can decide or the collapse can report carries the key
(`MessageFoldedCoverageLintTest`); a custom rule has to declare it to get one finding per dataset.

## Making absence visible

Silence is invisible; a **skip** is auditable. That is what
[`Requirements.Variables`](../authoring/requirements.md) is for:

```yaml
Requirements:
  Variables:
    All: ["AESTDY", "AEENDY"]
Check:
  expression: 'AESTDY > AEENDY'
```

Now an absent `AESTDY` produces a reported **SKIPPED** naming the missing variable, rather than a
clean-looking run that judged nothing.

> ⛔ **But never require the column whose absence is the defect.** A rule that reports a missing
> `AEENDTC` must not require `AEENDTC` — that skips it on exactly the datasets it exists for. This
> is the single most expensive mistake in rule authoring, and it is invisible from the outside;
> [Requirements](../authoring/requirements.md#-the-one-rule-that-matters-more-than-the-syntax)
> spells it out.

## A join does not supply an absent column

An unqualified name is the **primary** dataset's column, so the constant above is what a check
reads even when a joined dataset carries a column of that name — the join is reached only through
the qualified name (`DM.RFSTDTC`). A rule whose scope defines no `X` but whose join does, and that
reads a bare `X`, therefore fails the corpus build unless it declares `X`; see
[Joins](../authoring/joins.md#-a-bare-name-only-the-join-carries-fails-the-build). A `Child: true`
parent is no exception: its columns are read qualified with its domain (`AE.AESMIE`). The bare
names a merge does supply are the primary's supplemental qualifiers (`AETRTEM`).

## What you do *not* have to write

**You do not write defensive presence guards.** Because an absent column folds to a constant, a
check does not need `var_exists(X) and …` in front of it to avoid misbehaving — it will not
misbehave.

Write a presence test only when presence is genuinely **part of the requirement**:

```yaml
# ✅ the source says "... and DSSCAT is present in the dataset"
expression: 'var_exists("DSSCAT") and DSSCAT != DSDECOD'

# ⛔ defensive noise — the check is already safe on an absent DSSCAT
expression: 'var_exists("AESTDY") and AESTDY > AEENDY'
```

The test is whether a reader of the source guidance would recognise the presence test as part of
what the rule says. If not, it is clutter that makes the check harder to compare against its
source.

## Absent in a group key

A grouping column that is absent contributes nothing to the key, so every row falls into the same
group. A rule grouped only by an absent column therefore evaluates one group over the whole
dataset — rarely what was meant, and worth a `Requirements` entry when the grouping key is
essential to the question.

---

**Next:** [Zero-row datasets](zero-rows.md) — when the columns are all there and the rows are not.
