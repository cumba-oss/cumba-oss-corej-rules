# Joins

<!-- Grounded: MatchDataset.java (Name, Keys incl. the sided {left,right} shape, Wildcard, Child,
     Join_Type, Filter + its right-side-only / D89 skip contract), JoinType.java (json values
     "inner" / "left"), ChildMatchPreMerger.java (Child:true — RDOMAIN/IDVAR/IDVARVAL parent
     resolution, child-wins on column collision), RelrecRowExpander.java (forward RELREC, inner
     semantics, one row per matched pair). `_matched_` usage measured in the corpus. -->

`Match_Datasets` brings a second dataset into reach, so a check can compare across the two.

```yaml
Match_Datasets:
- Name: "DM"
  Keys: ["STUDYID", "USUBJID"]
Check:
  expression: 'AESTDTC < DM.RFSTDTC'
```

Columns of the joined dataset are referenced **dot-qualified** — `DM.RFSTDTC`. Unqualified names
always mean the primary dataset.

## The ordinary join

```yaml
Match_Datasets:
- Name: "DM"
  Keys: ["STUDYID", "USUBJID"]
  Join_Type: "left"
```

**`Name`** is the dataset to join.

**`Keys`** are the columns matched on. The usual form is a list of names shared by both sides:

```yaml
Keys: ["STUDYID", "USUBJID"]
```

When the column is named differently on each side, declare both:

```yaml
Keys:
- "STUDYID"
- { left: "AEREFID", right: "EGREFID" }
```

`left` is the primary dataset, `right` is the joined one. The two forms mix freely in one list.

**`Join_Type`** is `inner` or `left`, lowercase.

- **`left`** keeps every primary row, whether or not it found a partner. Unmatched rows see
  [missing values](../semantics/values-and-missing.md) on the joined side.
- **`inner`** keeps only primary rows that matched.

> **The default is not a shrug.** With no `Join_Type` the join behaves as the rule's shape
> implies, and for a rule about *"every AE must have a matching DM record"* that is the wrong
> question to leave to a default — an `inner` join silently removes exactly the rows the rule
> exists to find. Say which you mean whenever the absence of a partner is part of the question.

## Key types must agree — and what to do when they do not

A join key is matched **by type**. A character `AESEQ` and a numeric `AESEQ` are not the same key,
and a rule that meets such a pair **stops with an error** naming the column and both types:

```
Match_Datasets AE: join key AESEQ is Character in the primary dataset and Numeric in AE.
```

That is deliberate. The alternative — matching them anyway — only works for the *canonical*
spelling: `"5"` would join `5`, while `"05"`, `"5.0"` and `" 5"` would not. Whether a subject's
records joined would then depend on the **spelling** of a value whose type is already wrong, which
is not something a rule should decide by accident.

> **This is a data defect, not an authoring one.** In CDISC a variable of a given name has one
> type — measured across the whole metadata library, every join key used anywhere in this corpus is
> single-typed, `AESEQ` included. A divergence means the delivered study disagrees with the
> standard, and `CDISC-AD0199` is the rule that reports exactly that. Your rule erroring is the
> correct outcome: it says *"I cannot answer this question on this data"* instead of inventing an
> answer.

You have two ways to say what should happen instead.

**Declare the types you need, and the rule skips cleanly.** This is the usual choice — the rule
states its precondition and steps aside when the study does not meet it:

```yaml
Requirements:
  Variables:
    All:
    - "AESEQ:N"        # the primary dataset
    - "AE.AESEQ:N"     # the joined dataset
```

> ⚠⚠ **Declare BOTH sides.** A qualified entry like `AE.AESEQ:N` is checked against the **joined**
> dataset only, and an unqualified one against the **primary** only. Declaring just the joined side
> is the common mistake: on a study where the *primary* is the wrong type, that entry is satisfied,
> the rule does not skip, and it errors anyway.

**Or compare the keys as text, on purpose:**

```yaml
Match_Datasets:
- Name: "AE"
  Keys: ["STUDYID", "USUBJID", "AESEQ"]
  Join_Type: "left"
  Join_As_String: true
```

`Join_As_String` applies to **every** key of that entry, not only the mismatched one, so an entry's
behaviour cannot change from study to study. It does not make `"05"` join `5` — it makes the text
comparison explicit rather than accidental.

> ⚠ Two things to know before reaching for it. Numeric keys under `Join_As_String` compare at **12
> significant digits** rather than exactly, because that is the precision of the rendered form. And
> it must be the unquoted boolean `true` — `"true"` in quotes is rejected as a load error rather
> than silently accepted.

There is no numeric counterpart, and none is planned: parsing text into a number is partial
(`"ABC"` has no value) and it *invents* identity, collapsing `"05"`, `"5.0"` and `" 5"` onto the
same key. A join may fail to find a match; it must never manufacture one.

**Three cases are exempt**, because they match a text-carried key against a typed column by design
and have their own rules: `Child: true`, `Name: "RELREC"`, and `SUPP--` / `SQ*` entries. A
`SUPPAE.IDVARVAL` of `"1"` is *meant* to find `AE.AESEQ` of `1`. `Join_As_String` has no effect on
these, and writing it on one is a load error rather than a silent no-op — as it is on an entry with
no `Keys` at all, which builds no key comparison to apply it to.

### Sided keys must declare both sides

If you use the `{ left, right }` form, **every** such element needs both halves as strings:

```yaml
Keys:
- "STUDYID"
- { left: "AEREFID", right: "EGREFID" }   # ✅
- { left: "VISITNUM" }                    # ⛔ load error
```

The two sides are read as parallel lists and paired by position, so an element only one side can
read does not make the pair "partly declared" — it shifts everything after it. A half-declared
element, an empty object, or a non-string name is rejected at load.

⛔ **Sided keys and the `Child` / `RELREC` / `SUPP--` family do not combine.** That family pivots on
the standard `IDVAR` / `IDVARVAL` names, which are the same on both sides by construction, and its
merge reads the left names only — so a sided declaration there would be silently ignored. It is a
load error instead.

## Did it match? — `_matched_`

A rule that asks *"is there a related record at all?"* does not need a column from the other
side. It needs to know whether the join found one:

```yaml
Match_Datasets:
- Name: "AE"
  Keys: ["STUDYID", "USUBJID"]
  Join_Type: "left"
  Filter: 'AEOUT == "FATAL"'
Check:
  expression: 'not AE._matched_'
```

`<DATASET>._matched_` is true on a primary row that found a partner. With a `left` join and a
`Filter`, it is the natural way to ask *"does this subject have a fatal AE?"*

## `Filter` — narrow the other side before matching

```yaml
Filter: 'AEOUT == "FATAL"'
```

A boolean expression evaluated against the **joined dataset's own rows, before the key index is
built**. Rows that fail it can never become a partner.

That ordering is the point. Filtering after the join would depend on *which* partner row
survived; filtering before it makes the question — *does a fatal AE exist for this subject?* —
independent of the collapse.

Two constraints:

- **Right-side columns only.** A reference to the primary dataset, or to a `$`-binding, is a load
  error. A filter that correlates the two sides is a different feature and is not this one.
- **An unresolvable filter column is a bind error** — unless it is declared in
  [`Requirements.Variables`](requirements.md), in which case the rule skips cleanly instead.

## `Child: true` — resolving a parent row

For a primary dataset that *points at* another — `SUPP--`, `CO`, `RELREC` — whose rows carry
`RDOMAIN`, `IDVAR` and `IDVARVAL` naming the parent row:

```yaml
Match_Datasets:
- Name: "AE"
  Keys: ["STUDYID", "USUBJID", "IDVAR", "IDVARVAL"]
  Child: true
```

The engine resolves the parent domain (from `RDOMAIN`, or by stripping the `SUPP` prefix), finds
the parent row where `parent[IDVAR] == IDVARVAL`, and **augments the primary row with the
parent's columns**. The check can then read those columns directly.

The remaining keys — `Keys` minus `IDVAR` and `IDVARVAL` — narrow which parent rows are
considered.

> ⚠ **`STUDYID` participates only if you declare it.** It is not added implicitly. Omitting it
> from `Keys` means rows are matched across studies, which matters for a pooled submission.

> ⚠ **On a column-name collision the child wins.** If the primary already has a column of that
> name, the parent's value does not overwrite it.

## `Wildcard` — forward RELREC expansion

```yaml
Match_Datasets:
- Name: "RELREC"
  Wildcard: "PC"
```

Builds one evaluation row per *(primary record, related record)* pair via RELREC, with the
related columns reachable as `RELREC.*`.

Two behaviours worth knowing before you use it:

- It is **inner** — a primary record with no related record produces no row, so a rule written
  this way cannot report *"nothing is related"*.
- A declared forward join with no matching pairs yields **zero rows**, and therefore no findings.

## Which shape do you need?

| the question | the shape |
|---|---|
| compare a value here with a value there | ordinary join, `Keys` |
| does a related record exist? | `left` join + `_matched_`, usually with `Filter` |
| does a related record *of a particular kind* exist? | `Filter` on the joined side |
| my primary dataset points at a parent row | `Child: true` |
| the two sides type the key differently | fix the data, or `Requirements.Variables` on **both** sides, or `Join_As_String: true` |
| walk RELREC relationships | `Name: "RELREC"` + `Wildcard` |

> ⚠ **A join changes what an unqualified name means in only one respect: nothing.** Unqualified
> names keep referring to the primary dataset even after a join. If you mean the joined side,
> qualify it — see [Values and missing values](../semantics/values-and-missing.md) for what an
> unmatched row looks like.

---

**Next:** [Grouping](grouping.md) — evaluating per group instead of per row.
