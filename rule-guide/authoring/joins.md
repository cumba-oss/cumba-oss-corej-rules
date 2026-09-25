# Joins

<!-- Grounded: MatchDataset.java (Name, Keys incl. the sided {left,right} shape, Wildcard, Child,
     Join_Type, Filter + its right-side-only / D89 skip contract), JoinType.java (json values
     "inner" / "left"), ChildMatchPreMerger.java (Child:true — RDOMAIN/IDVAR/IDVARVAL parent
     resolution, child-wins on column collision), RelrecRowExpander.java (forward RELREC, inner
     semantics, one row per matched pair). `_matched_` usage measured in the corpus (8 authored checks at 2026-09-22: four whole-check
     `not X._matched_` rules and the four unplanned-visit cascade carriers that use it as a tier
     discriminator). -->

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

## ⛔ Declare the key — required for every keyed join

**A rule that joins on a key MUST declare every key column in
[`Requirements.Variables`](requirements.md).** Undeclared, a study that lacks a key column does
not skip the rule — it runs it: a key absent on one side contributes an empty value, no row
matches, and every row is reported (a flood); a key set absent on both sides is an execution
error. Neither is an answer about the data. Declared, the rule **skips**, and the skip names the
column.

The declaration, per key column:

```yaml
Requirements:
  Variables:
    All: ["VISITNUM"]                 # the FIRST key, bare
    All_Or_None:                      # every key: bare side + joined side, one group each
    - ["VISITNUM", "TV.VISITNUM"]
    - ["VISIT", "TV.VISIT"]
    - ["VISITDY", "TV.VISITDY"]
Match_Datasets:
- Name: "TV"
  Keys: ["VISITNUM", "VISIT", "VISITDY"]
```

- **One [`All_Or_None`](requirements.md#all_or_none--every-entry-present-or-none-of-them) group
  per key column**, `[KEY, DS.KEY]` — for a sided key `[LEFT, DS.RIGHT]`. A key present on one
  side only skips the rule; a key absent on **both** sides is dropped from the join and the rule
  runs on the remaining keys (a `Perm` key such as `VISITDY` may legitimately be missing from
  both).
- **The first key also bare in `All`.** That is what turns "every key absent on both sides" into
  a skip instead of an error, and it guarantees the primary side of the key the rule is mostly
  about. Where the check already requires it in `All`, nothing is added.
- **Several joins on the same key share ONE group** — `[USUBJID, DM.USUBJID, AE.USUBJID]` for a
  rule joining DM and AE on `USUBJID`. Two groups repeating `USUBJID` mean the same thing but draw
  a load warning.
- **`Child: true` entries declare their keys bare in `All` only** (`USUBJID`, `IDVAR`,
  `IDVARVAL` on the SUPP--/CO/RELREC primary). The parent is resolved per row, so it has no name
  to declare — and no `All_Or_None` group may name a `Child` key, unless an ordinary entry of the
  same rule also keys on it (a `DM` join sharing `USUBJID` carries `[USUBJID, DM.USUBJID]`).
- **A joined dataset absent from the study skips the rule of an ordinary keyed entry** (one
  carrying a key group); `Child` entries, `&DOM` templates and keyless joins are not affected.
  The group's qualified half (`DM.USUBJID`) is then absent while the primary's bare key is
  present, so the group is only partly present —
  the rule cannot be answered, and non-executability is not a finding. If the joined dataset's
  **absence must itself be reported**, that is a separate dataset-presence rule (e.g.
  `not ds_exists("TA")`), not something the joining rule can say.
- **Expansion-template keys** (`&DOM`, `&DOMSEQ`) cannot be declared — loader gate R6 bars a
  token from `Requirements` — so only a literal first key goes in `All`.

The corpus is held to this by `JoinKeyDeclarationLintTest`: a keyed entry without its
declarations fails the build, naming the rule and the key.

> ⚠ **The key is not the only column to declare.** A column the check *reads* is declared bare in
> `All` too when its presence is a precondition of the check — never a column that is one
> disjunct among several, and never the column whose absence **is** the finding
> ([the one rule](requirements.md#-the-one-rule-that-matters-more-than-the-syntax)).

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

### ⭐ The second use: saying what you conclude when the join found nothing

`_matched_` is not only for rules whose whole question is "does a partner exist". It is also how a
rule that reads a joined column says what it concludes **when there is no partner** — and without
it, that case is decided by accident.

The trap is that `var_exists(SV.SVPRESP)` looks like it answers "can I read this", but it is a
**schema** fact, broadcast to every row: *does `SV` have this column at all*. It says nothing about
whether **this** row matched. So a cascade written as

```
   var_exists(SV.SVPRESP) and SV.SVPRESP == "Y"          -- "SV says planned"
or not var_exists(SV.SVPRESP) and <fallback>             -- "SV cannot say"
```

silently drops every unmatched row on a study whose `SV` *does* carry the column: the first arm is
selected because the column exists, `SV.SVPRESP` on an unmatched row is the type default (`""`, not
`"Y"`), and the second arm can never be reached. No finding, no `SKIPPED`, nothing in the report.

Adding an explicit arm fixes it, and states the intent:

```
   SV._matched_ and SV.SVPRESP == "Y"
or SV._matched_ and not var_exists(SV.SVPRESP) and <fallback>
or not SV._matched_ and <what to conclude with no SV record>
```

⛔ **Two things this obliges you to do:**

- **Declare the dataset.** `_matched_` is unresolvable without it — `Requirements.Datasets: ["SV"]`
  — and the rule is a hard **ERROR** otherwise, not a skip (`D89b`: declare ⇒ skip, don't declare ⇒
  error, with no third answer).
- **Then cover the population that declaration excludes.** The rule now skips when the dataset is
  absent. If that absence is legitimate, the study needs a sibling rule gated `not ds_exists("SV")`.

⚠ **Join on the other dataset's own key.** A key component present on one side and absent on the
other makes the row unequal *unconditionally*, so joining on a `Perm` column makes `_matched_` false
for the whole study and routes every row to the unmatched arm — the failure looks exactly like "no
partner ever existed". For `SV` that key is `USUBJID` + `VISITNUM`, never `VISIT`.

⭐ The worked instance is the canonical `unplanned(visit)` predicate; the full doctrine, both
polarities and the four-case table are house rules rather than guide material.

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

> ⛔ **A `Child` entry is joined only through its pointer — it has no direct join.** The engine
> builds no keyed lookup for it, so `AE._matched_` and a dotted read such as `AE.AESMIE` are
> **load errors** on a `Child` entry — and so are their template spellings, `SUPPAE._matched_` or
> `SUPPAE.QVAL` against a `SUPP--` entry. Read the parent's columns **bare**: they arrive through
> the merge above, under their own names.

> ⛔ **Declare the child-side keys bare in `All`** — see
> [Declare the key](#-declare-the-key--required-for-every-keyed-join).

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
