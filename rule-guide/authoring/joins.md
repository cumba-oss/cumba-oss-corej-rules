# Joins

<!-- Grounded: MatchDataset.java (Name, Keys incl. the sided {left,right} shape, Child,
     Join_Type, Filter + its right-side-only / D89 skip contract, Cardinality), JoinType.java (json
     values "inner" / "left"), MergedEvaluation.java (the one merged evaluation table per rule —
     PLAN-merged-evaluation-table), ChildEntries.java (Child:true — RDOMAIN/IDVAR/IDVARVAL parent
     resolution into a part qualified with the parent's domain), RelrecEntries.java (forward
     RELREC — one part per related domain, the cross product per relationship), KeyGates +
     RulePackageLoader.checkQualifiedKeys / qualifiedSourceError + UnresolvedQualifiedKeyException
     (a qualified key, PLAN-rprfdy-offset-tp-join C1), RulePackageLoader.checkCardinality /
     checkSuppKeys (Cardinality, Supp_Keys), the datatable's net.cumba.datatable.merge specs
     (KeyedMergeSpec, ChildMergeSpec, RelrecPartSpec, SuppMergeSpec.DEFAULT_KEYS). `_matched_` usage
     measured in the corpus (8 authored checks at 2026-09-22: four whole-check
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
always mean the primary dataset, and a bare name that only the join carries
[fails the build](#-a-bare-name-only-the-join-carries-fails-the-build).

### One table per rule

Every declared merge of a rule — each keyed entry, a `Child: true` parent, a forward `RELREC`
entry and the [SUPP merge](requirements.md#supp_merge) — becomes **columns of one table**, and the
rule runs on that table. The primary's own columns keep their names; a keyed partner's columns are
`<Name>.<column>` (`DM.AGE`); a `Child` parent's and a RELREC-related domain's are
`<domain>.<column>` (`AE.AESMIE`, `PC.PCRFTDTC`); the primary's supplemental qualifiers are bare
(`AETRTEM`), a joined dataset's are `<Name>.<QNAM>` (`DM.RACEOTH`) — each dataset's qualifiers from
its own SUPP sources (its `SUPP<dataset>` plus the domain-level members of its `SUPP<domain>` /
`SQAP<xx>` family: a split `SUPPAE1` + `SUPPAE2` feeds `AE` whole; a keyed `LB` that resolves as the
split union `LB1` + `LB2` is fed the whole `SUPPLB` family; a keyed `LBCH` its `SUPPLBCH` plus any
domain-level member of the family, never a sibling's `SUPPLBHE`).
A keyed entry may name a supplemental family — `Name: "SUPPAE"` resolves every member (`SUPPAE1` +
`SUPPAE2`) as one partner — and a non-`Child` `SUPP--` / `SQAP--` is the supplemental family of the
primary's **domain** (`SUPPLB` on `LBCH`, `SQAPMH` on `APMH`), never the member name (the
[`Child: true`](#child-true--resolving-a-parent-row) form keeps matching the primary's own name).
⚠ When an exactly-named `SUPPAE` ships beside `SUPPAE1`, a keyed `SUPPAE` partner on a rule that also
reads the SUPP merge is refused (`MERGE_REFUSED`): one name cannot be both the family the join reads
and the member the merge feeds — name the member, or write `Supp_Merge: false`. The qualifier matches the entry
ignoring letter case (`dm.age` reads `DM.AGE`; upper case is the expected spelling). Every reader —
the `Check`, `Bindings`, `Output_Variables`, `Grouping`, the group functions, `colref` — reads the
same cell for the same name.

A row of that table is one primary record **combined with** its partners. When a partner may
repeat (a keyed `n:m` join, a `Child` parent, a RELREC relationship), one primary record becomes
several rows. Two consequences you can rely on:

- **Findings are not repeated.** Within one rule, findings that agree in their position (the
  primary record, and the column for a per-variable finding) and in every reported value are one
  finding, so a record that fires on three combined rows reports once unless its
  `Output_Variables` print something that differs between the rows (a partner's column).
- **Counting is per record.** A uniqueness check, a grouped function or `record_count` whose
  operands are all primary columns counts each primary record once, however many rows it became.
  An operand that reads a partner's column counts every combined row — that is the question it
  asks.

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

When the value to join on lives in a dataset the rule **already joins**, not in the primary, write
it [qualified](#a-qualified-key--joining-through-an-earlier-entry): `Keys: ["DM.RPATHCD", "RPHASE"]`.

**`Join_Type`** is `inner` or `left`, lowercase.

- **`left`** keeps every primary row, whether or not it found a partner. Unmatched rows see
  [missing values](../semantics/values-and-missing.md) on the joined side.
- **`inner`** keeps only primary rows that matched.

Two kinds of entry fix the join type themselves, and a value that could never take effect is a
**load error** rather than silently ignored:

- A keyed entry naming a supplemental-qualifier or relationship dataset — `SUPP…` (including
  `SUPP--`), `SQ…` or `RELREC` — always keeps every primary row (`left`). Write `left` or nothing;
  `inner` there is a load error. (Any other `--` template — `XX--` for a domain `XX` — is resolved
  to its dataset first and honours its `Join_Type`.)
- An entry that builds no join at all — no `Keys` and neither `Child: true` nor the forward
  `RELREC` entry, or `Keys` without a `Name` — takes no `Join_Type`; any value is a load error.

> **The default is not a shrug.** With no `Join_Type` the join behaves as the rule's shape
> implies, and for a rule about *"every AE must have a matching DM record"* that is the wrong
> question to leave to a default — an `inner` join silently removes exactly the rows the rule
> exists to find. Say which you mean whenever the absence of a partner is part of the question.

## `Cardinality` — how many partners a record may have

```yaml
Match_Datasets:
- Name: "DM"
  Keys: ["STUDYID", "USUBJID"]
  Join_Type: "left"
  Cardinality: "n:1"
```

`Cardinality` says how many rows each side may carry per key — `1:1`, `1:n`, `n:1` or `n:m`, the
left side being the primary and the right side the joined dataset. It is a separate axis from
`Join_Type`.

| authored | primary key unique? | partner key unique? | rows per primary record |
|---|---|---|---|
| `1:1` | required | required | 0 or 1 (`inner`) / exactly 1 (`left`) |
| `1:n` | required | — | 0..n (`inner`) / 1..n (`left`) |
| `n:1` | — | required | 0 or 1 (`inner`) / exactly 1 (`left`) |
| `n:m` (the default) | — | — | every combination |

- **A duplicate key on a side declared `1` stops the rule with an error** naming the dataset, the
  key and the first two duplicate rows — never a silent pick of one partner. Two blank keys count as
  the same key (two missing values are one key; a missing value and `""` are not).
- **Unauthored is `n:m`:** a repeated partner key makes the primary record appear once per partner
  row, every combination.
- Any other spelling (`many-to-one`) is a load error; letter case is ignored (`N:1` is `n:1`).
- On a `Child: true` entry the IG's answer is `n:m`; an authored value wins and the load log warns
  that it disagrees. On a forward `RELREC` entry the authored value overrides every relationship's
  `RELTYPE` ([below](#forward-relrec--name-relrec-with-no-keys)). A keyless entry other than `RELREC`
  builds nothing to check, so anything but `n:m` there is a load error.

> ⭐ **Join `DM` and `ADSL` on `[STUDYID, USUBJID]`, `Cardinality: "n:1"`.** That is how every
> corpus rule joins them. One subject has one `DM` / `ADSL` record, so a duplicate is a data defect
> the rule must not paper over — it errors, and the dedicated duplicate-subject rules report it.
> `STUDYID` belongs in the key: a pooled submission may carry the same `USUBJID` in two studies,
> and that is not a duplicate. Declare it like any key (`All_Or_None: [["STUDYID", "DM.STUDYID"],
> …]`).

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
- **`Child: true` entries declare their keys bare in `All` only** (`STUDYID`, `USUBJID`, `IDVAR`,
  `IDVARVAL` on the SUPP--/CO/RELREC primary — every corpus `Child` entry keys
  `[STUDYID, USUBJID, IDVAR, IDVARVAL]`). The parent is resolved per row, so it has no name
  to declare — and no `All_Or_None` group may name a `Child` key, unless an ordinary entry of the
  same rule also keys on it (a `DM` join sharing `USUBJID` carries `[USUBJID, DM.USUBJID]`).
- **A joined dataset absent from the study skips the rule of an ordinary keyed entry** (one
  carrying a key group); `Child` entries, `&DOM&` templates and keyless joins are not affected.
  The group's qualified half (`DM.USUBJID`) is then absent while the primary's bare key is
  present, so the group is only partly present —
  the rule cannot be answered, and non-executability is not a finding. If the joined dataset's
  **absence must itself be reported**, that is a separate dataset-presence rule (e.g.
  `not ds_exists("TA")`), not something the joining rule can say.
- **A [qualified key](#a-qualified-key--joining-through-an-earlier-entry)** (`DM.RPATHCD` on the
  `TP` entry) is declared under its qualified name: the group is `[DM.RPATHCD, TP.RPATHCD]`, and
  `DM.RPATHCD` goes in `All` **as well** — whichever position the key has. The gate demands `All`
  only for a first key, but a group alone lets a study that lacks `RPATHCD` on **both** sides run,
  and there the rule [errors](#when-a-qualified-key-cannot-be-resolved) instead of skipping.
- **Expansion-template keys** (`&DOM&`, `&DOM&SEQ`) cannot be declared — loader gate R6 bars a
  token from `Requirements` — so only a literal first key goes in `All`.

The corpus is held to this by `JoinKeyDeclarationLintTest`, and **every package by the loader**
(`RulePackageLoader.validateJoinKeyDeclarations`, the same shape rules): a keyed entry whose key is not
declared is a per-rule **load error** naming the rule, the entry and the key — in a user package too,
which the lint cannot see. In the corpus the lint fires first: a keyed entry without its
declarations fails the build, naming the rule and the key.

> ⚠ **The key is not the only column to declare.** A column the check *reads* is declared bare in
> `All` too when its presence is a precondition of the check — never a column that is one
> disjunct among several, and never the column whose absence **is** the finding
> ([the one rule](requirements.md#-the-one-rule-that-matters-more-than-the-syntax)).

## ⛔ A bare name only the join carries fails the build

A bare `X` is the **primary** dataset's column, whatever the rule joins. So when no dataset in the
rule's scope defines `X` but a joined dataset does, a bare `X` — in the `Check`, a binding or
`Output_Variables` — does not read the join: it reads the primary's
[absent-column value](../semantics/absent-columns.md) (`""` or missing on every row), and the rule
reports too few findings, or wrong ones, with nothing to say so.

A rule scoped to `EX` that joins `DM` on `USUBJID`:

```
date(EXSTDTC) < date(RFXSTDTC)       ⛔ EX defines no RFXSTDTC, so this is never DM's
date(EXSTDTC) < date(DM.RFXSTDTC)    ✅
```

The same holds for an ADaM template in `Output_Variables` when the `Check` does not itself read
that template: a bare `APxxSDT` names a column of exactly that name on the primary, which no dataset
has, so the finding reports nothing for it. The joined period dates are reported as
`ADSL.AP${*}SDT` (every `ADSL` column the template matches). A template the `Check` does read is
different: the rule is expanded once per matching column, and the `Output_Variables` entry is
renamed with it — `TRTxxP` in both reports the `TRT01P` the check just read.

The corpus build rejects such a rule (`UnqualifiedJoinedReadLintTest`), naming the rule, the name
and the join. There are two ways out:

- **Qualify it** — `DM.RFXSTDTC`. This is almost always what was meant.
- **Declare it**, when the primary really is meant. A broadly scoped rule rarely needs to: if any
  dataset in its scope defines the name (a rule over every domain reading `VISIT` is judged against
  `SV` too), it is not flagged at all. Declaring is for a **narrowly** scoped rule whose primary may
  carry a column the standard's list for it omits — a Permissible variable such as `EPOCH` or
  `TAETORD` on `SV`, or a variable an ADaM dataset copies from `ADSL`. A bare entry in `All` (the
  rule skips where the primary lacks it), a bare entry in an `Any` group, or an `All_Or_None` group
  pairing the bare and the joined side — `[X, DM.X]` — each says which side the bare name means.
  They differ where the primary lacks `X`: the `All` entry skips the rule; the pair skips only when
  the join carries `X` and runs when neither side does; an `Any` group skips only when none of its
  members is present. So with the last two the check can still run on the absent column's constant,
  and that verdict must be the one you want. `None`, and a qualified `DM.X` on its own, do not
  declare it.

"Defined by the scope" is judged against the standard's own dataset definitions: a name any
dataset in the rule's scope defines is not flagged, even when the join carries it too. A `Child:
true` parent and a RELREC-related domain are no exception: their columns are read qualified with
the domain too (`AE.AESMIE`, `PC.PCRFTDTC`).

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

## A qualified key — joining through an earlier entry

Some keys are not in the primary dataset at all. A `BW` record reaches its trial path through its
subject: `TP` has no `USUBJID`, and the subject's path `RPATHCD` lives in `DM`. Join `DM` first,
then key `TP` on the **qualified** `DM.RPATHCD`:

```yaml
Requirements:
  Variables:
    All: ["USUBJID", "DM.RPATHCD", "RPHASE"]
    All_Or_None:
    - ["USUBJID", "DM.USUBJID"]
    - ["DM.RPATHCD", "TP.RPATHCD"]
    - ["RPHASE", "TP.RPHASE"]
Match_Datasets:
- Name: "DM"
  Keys: ["USUBJID"]
- Name: "TP"
  Keys: ["DM.RPATHCD", "RPHASE"]
Check:
  expression: 'TP.RPRFDY == 1'
```

**The qualified name says where the record's side of the key comes from**: `DM.RPATHCD` is the
`RPATHCD` of the row's bound `DM` record. The joined dataset is matched on the **same variable,
unqualified** — `TP.RPATHCD`. There is no `{left, right}` to write: `DM.RPATHCD` joins on
`RPATHCD`.

- **The qualifier names an EARLIER entry of the same rule** — never the entry itself, never a later
  one: the source must be bound before this join runs. That entry must be an ordinary keyed join
  (not `Child: true`, not `RELREC`, not `SUPP--` / `SQ`, no `--` template name, with `Keys`) and
  **`inner`** — `Join_Type: "inner"` or none. A `left` source is refused: an unmatched row has no
  `DM` record to read the key from, and an empty key is not an answer. Anything else is a **load
  error** naming the rule and the key.
- **The entry carrying the key is an ordinary one too** — a qualified key on a `Child`, `RELREC` or
  `SUPP--` entry, or a template, is a load error.
- **A bare string only.** A dotted name inside a sided element (`{ left: "DM.RPATHCD", right:
  "RPATHCD" }`) is a load error naming the bare spelling. Neither half may carry a `--` or `&`
  wildcard: the two sides resolve against different datasets, so name the column.
- **It expands rows like any other join.** The join is n-to-m: a record whose phase has three `TP`
  stages becomes three rows. And each row reads **its own** bound `DM` record — if `DM` carries a
  subject twice, on two paths, the record is copied by the `DM` join and each copy joins its own
  path's stages.
- **Everything else is as on any entry**: `Join_Type`, [`Filter`](#filter--narrow-the-other-side-before-matching),
  `TP._matched_`, `keep_missings` for a blank source value (a missing `DM.RPATHCD` never pairs with
  an empty `TP.RPATHCD`), and `Join_As_String`. The key-type check compares the **source** column
  with the joined one — a character `DM.RPATHCD` against a numeric `TP.RPATHCD` stops the rule with
  an error naming both.

### When a qualified key cannot be resolved

A plain key absent on one side contributes an empty value, and absent on both sides it is dropped
(see [Declare the key](#-declare-the-key--required-for-every-keyed-join)). **Neither applies to a
qualified key.** Dropping it would join `TP` on `RPHASE` alone — every path's stages, the wrong
answer with nothing to say so. So the rule **stops with an error** when:

- the study has no dataset for the source entry (no `DM`),
- the source dataset lacks the column (`DM` without `RPATHCD`),
- the joined dataset lacks it (`TP` without `RPATHCD` — it needs the same variable, unqualified).

Declared as [above](#-declare-the-key--required-for-every-keyed-join) — `DM.RPATHCD` in `All` and
the pair `[DM.RPATHCD, TP.RPATHCD]` — each of these **skips** the rule instead. The error is what an
undeclared or group-only declaration gets: a group alone is satisfied by a study lacking
`RPATHCD` on both sides, and that study reaches the join.

⚑ The same qualified spelling works in a grouped function's
[`group=`](../reference/functions.md#qualified-group-members) and in the rule-level
[`Grouping`](grouping.md#a-qualified-member--grouping-by-a-joined-value), under the same
convention.

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

A boolean expression evaluated against the **joined dataset's own rows, before matching**. Rows
that fail it can never become a partner.

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
- Name: "SUPP--"
  Keys: ["STUDYID", "USUBJID", "IDVAR", "IDVARVAL"]
  Child: true
```

⚠ **`Name` names the child, never the parent**: it is the primary the entry applies to (`SUPP--`,
`CO`, `RELREC`); when no `Child` entry's `Name` matches the primary, the first `Child` entry
applies. The parent is read per row. The engine resolves each row's parent domain (from `RDOMAIN`,
or from the `SUPP` / `SQAP` name when there is no `RDOMAIN` column), finds the parent record whose `IDVAR` column equals `IDVARVAL`
(compared as text; a numeric parent column canonicalised, so `" 1"` and `"01"` find `1`), and adds
that parent's columns to the row, **qualified with the parent's domain**:

```yaml
Check:
  expression: 'QNAM == "AESOSP" and var_exists("AE.AESMIE") and AE.AESMIE != "Y"'
```

On a `SUPP--` / `CO` / `RELREC` primary whose rows point at several domains, each parent domain is
its own set of columns, supplied only on the rows whose parent is that domain: a row pointing at
an AE record reads `AE.AESEQ`, and its `CM.CMSEQ` reads not supplied. To read "the column `IDVAR`
names, in the domain `RDOMAIN` names", build the name:
`colref(concat(RDOMAIN, ".", IDVAR))`.

The remaining keys — `Keys` minus `IDVAR` and `IDVARVAL` — narrow which parent records are
considered.

- **Every record is kept.** A `Child` entry is always a `left` merge: a record whose parent is not
  found reads the parent's columns as not supplied (`""` / missing), which is exactly what a
  referenced-record rule such as `CDISC-CG0371` reports. An authored `Join_Type` other than `left`
  is a load error.
- **`<domain>._matched_`** (`AE._matched_`) is true on a row whose parent is an `AE` record that was
  found. The entry's own template name is the child dataset, not a parent: `SUPPAE._matched_`
  against a `SUPP--` entry is a load error.
- **A parent may be addressed many times** (`IDVAR` can name a grouping variable such as
  `AEGRPID`): the IG's cardinality is `n:m`, and a record expands into one row per parent record.
- **Its `Name` must be a concrete dataset or a template with a trailing `--`** (`SUPP--`, `SQ--`),
  and `keep_missings: false` has no effect on it (the pointer merge keeps blank keys
  unconditionally) — both load errors.
- Under SEND a pointer row naming no subject (`USUBJID` and `POOLID` blank) addresses the parent
  records of every subject and pool, and `POOLID` is a subject key beside `USUBJID`.

> ⛔ **Declare the child-side keys bare in `All`** — see
> [Declare the key](#-declare-the-key--required-for-every-keyed-join).

> ⚠ **`STUDYID` participates only if you declare it.** It is not added implicitly. Omitting it
> from `Keys` means rows are matched across studies, which matters for a pooled submission:
> `USUBJID` is unique within a study only, so a `SUPPAE` record of study S2 would find study S1's
> AE record with the same `USUBJID` and `AESEQ`. Every `Child` entry of the corpus therefore keys
> `[STUDYID, USUBJID, IDVAR, IDVARVAL]`, and `JoinKeyDeclarationLintTest` reds on one that does not.

> ⚠ One shape no check sees: a read whose *qualifier* is itself substituted (`${X}.COL`) is bound
> per row and reads the not-supplied default silently when it names no part — do not write it.

> ⭐ **Not every value-addressed read is a merge.** A rule that only has to *count* the records a
> pointer resolves to, test whether the pointed-at dataset's key is unique, or read a variable's
> metadata in the dataset a value names, does so without an entry — through
> [`referenced_record_count`](../reference/functions.md#referenced_record_count),
> [`referenced_key_is_unique`](../reference/functions.md#referenced_key_is_unique),
> [`referenced_dataset_variables(…, qualifiers=true)`](../reference/functions.md#referenced_dataset_variables)
> and a `var_*` accessor with a computed name and `dataset=`
> (see [the metadata accessors](../reference/expression-syntax.md#the-metadata-accessors-take-a-level)).
> `Child: true` pairs the rows (`n:m`, a `1` side a rule ERROR on a duplicate); the functions answer
> the count or the verdict per pointer row, which a merge cannot report.

## `Supp_Keys` — the key of the SUPP merge

Every rule reads its dataset's supplemental qualifiers as columns ([`Supp_Merge`](requirements.md#supp_merge)),
keyed by the IG's key: `STUDYID`, the domain (`DOMAIN` against `RDOMAIN`), the subject (`USUBJID`,
and `POOLID` under SEND, `APID` for associated persons, `SPDEVID` for devices) and the `IDVAR` /
`IDVARVAL` pair (the column `IDVAR` names equals `IDVARVAL`, compared as text). A rule that needs a
different key says so on the rule:

```yaml
Supp_Merge: true          # the default
Supp_Keys:                # omitted = the IG's key
- "STUDYID"
- { left: "DOMAIN", right: "RDOMAIN" }
- "USUBJID"
- "IDVAR"
- "IDVARVAL"
```

- The elements have the `Keys` shape: a column name shared by both sides, or `{left, right}` — the
  left side the evaluated dataset, the right side its `SUPP--` dataset.
- A key column present on both sides is used; on one side only it is ignored (with a log warning);
  on neither it is ignored.
- Load errors: `Supp_Keys` with `Supp_Merge: false` (there is no merge to key), an empty list, a
  qualified name (`DM.USUBJID` — the key reads the dataset and its `SUPP--` only), half of the
  `IDVAR` / `IDVARVAL` pair, or the pair with a differing left side.
- Data the key cannot serve **errors the rules that read the qualifier**, and only those: two
  supplemental rows qualifying one record for one `QNAM`, a subject-level and a record-level row for
  the same record and `QNAM`, or an `IDVAR` naming a column the domain lacks. Every other rule on
  the dataset runs normally.

A joined dataset's own qualifiers are its columns too: `DM.RACEOTH` on a rule that joins `DM` reads
`SUPPDM`'s `RACEOTH` for the joined record. `SUPPDM` qualifies `DM` only — another domain reaches it
through a `DM` join, never bare. A split family counts whole: a joined `AE` on a study shipping
`SUPPAE1` + `SUPPAE2` reads both; a joined split member `LBCH` reads its own `SUPPLBCH`; a joined `LB`
that resolves as the split union `LB1` + `LB2` reads the whole `SUPPLB` family — a subject-level row
(blank `IDVAR`) of any member then qualifies the subject's records of every member, and the same
subject-level `QNAM` in two members is the duplicate that errors the rules reading it.

## Forward RELREC — `Name: RELREC` with no `Keys`

```yaml
Match_Datasets:
- Name: "RELREC"
Check:
  expression: 'PC._matched_ and not empty(PC.PCRFTDTC) and PPRFTDTC == PC.PCRFTDTC and PPCAT != PC.PCTEST'
```

A keyless `RELREC` entry merges **every domain the study's RELREC relates to the primary's
domain**, each as its own set of columns qualified with that domain (`PC.PCRFTDTC`). The key is
the IG's, not authored: a relationship is the RELREC rows sharing `STUDYID`, `USUBJID` and `RELID`
under SDTM, and `STUDYID` and `RELID` under SEND, where each row names its record by `USUBJID` or
by `POOLID`. Record-level links match `IDVAR` = `IDVARVAL` as text, a numeric column canonicalised.

- **The rows are the cross product per relationship.** A primary record related (one `RELID`) to
  one CM record and three LB records becomes three rows, the CM record beside each LB record; two
  CM records and three LB records make six. Records of two **different** relationships are never
  combined: each relationship gives its own rows. A domain a relationship does not reach reads as
  not supplied on that relationship's rows.
- **`<domain>._matched_`** (`PC._matched_`) says whether **this row** carries a record of that
  domain — guard a read of a domain that may be absent with it. A domain qualifier no relationship
  of the study reaches reads not supplied, and its flag is false.
- **`Join_Type` is `inner` by default.** A primary record that no relationship addresses is not
  evaluated. A record a relationship **does** address is kept even when the related record is not
  found — its related columns read not supplied. A **dataset-level** relationship (`IDVARVAL`
  blank) addresses every primary record that carries a value in the `IDVAR` column, so such a
  record is kept even when no related record shares that value. `Join_Type: "left"` keeps every
  record; **`RELREC._matched_`** is then true on a record some relationship addresses
  (`RELREC._matched_` under `inner` is constant-true and a load error, as on any entry).
- **`RELTYPE` is the checked cardinality** of a dataset-level relationship (`ONE` / `MANY` on each
  side): related data that repeats a side declared `ONE` stops the rule with an error. An authored
  `Cardinality` on the entry overrides every relationship's `RELTYPE`.
- **A related domain lacking the column RELREC names** (`IDVAR`) cannot be paired: the rules that
  read that domain error; the others run.
- **`RELREC.<column>` and `RELREC.**<suffix>` are load errors.** The entry has no columns of its
  own, and a per-row alias has no single answer on a row combining several related records. Name
  the domain: `PC.PCTEST`, or one guarded arm per domain you mean
  (`AE._matched_ and FAOBJ != AE.AETERM or CM._matched_ and FAOBJ != CM.CMTRT`). A `**` pattern on
  a related domain (`AE.**TERM`) does not resolve — write the concrete name.
- A keyed entry with the same name as a related domain (`Name: CM` beside a forward `RELREC`
  relating CM) is refused when the rule runs: the two would both be `CM.*`.

> ⚠ **A related domain is read by its name, so a rule sees only the domains it names.** The FA
> rules `CDISC-CG0174`, `CDISC-CG0601`, `CDISC-CG0602` and `CDISC-CG0603` compare `FAOBJ` with the
> parent record's topic variable, one guarded arm per IG general-observation domain:
> Events `AE` `BE` `CE` `DS` `DV` `HO` `MH` (`--TERM` / `--DECOD`) and Interventions `AG` `CM` `EC`
> `EX` `ML` `PR` `SU` (`--TRT` / `--DECOD`), each behind `<D>._matched_` (CG0601 reads the Events
> arms only, CG0602 the Interventions arms only). Two consequences, both accepted:
>
> - **A sponsor-defined custom domain of the Events or Interventions class is not checked.** An FA
>   record related to a custom `XE` record matches no arm, so the rule is silent on it — not a
>   pass, an unasked question.
> - **A Findings parent is silent by design** (FA → `LB`): the parent's `--TERM` / `--TRT` /
>   `--DECOD` exist only on Events and Interventions, so there is nothing to compare `FAOBJ` with.
>
> Selecting the parent by its observation class instead of its name is not built. A new arm
> means a new domain named in the expression.

> ⛔ **`Wildcard` is retired and is a load error.** It was accepted on this entry and read by
> nothing; it never filtered the related domain. Any other key an entry does not model is a load
> error too.

## "Some joined row" — `any` / `all` / `none` over `joined(…)`

A join that matches **several** partner records to one record gives that record one evaluation row
per partner. A condition written on such a row is answered per row, so `EPOCH != SE.EPOCH` is true
on every element whose epoch differs — and a Check anchored on it reports one finding per such
element, whatever the other elements say. When the question is about the record's partners **as a
set** — *is there any element the visit matches?* — write the condition inside `joined(…)` and fold
it with a quantifier:

| spelling | reads |
|---|---|
| `any(joined(c))` | some row of this record satisfies `c` |
| `all(joined(c))` | every row of this record satisfies `c` |
| `none(joined(c))` | no row of this record satisfies `c` (the same as `not any(joined(c))`) |

The rows are grouped by **record**, never by subject. Two visits of one subject against three
elements (inner join on `STUDYID`, `USUBJID`) are six evaluation rows; `any(joined(EPOCH ==
SE.EPOCH))` answers per visit:

| eval row | visit | `EPOCH` | `SE.EPOCH` | `EPOCH == SE.EPOCH` | `any(joined(EPOCH == SE.EPOCH))` |
|---|---|---|---|---|---|
| 0 | 0 | SCREENING | SCREENING | true | true |
| 1 | 0 | SCREENING | TREATMENT | false | true |
| 2 | 0 | SCREENING | FOLLOW-UP | false | true |
| 3 | 1 | FOLLOW-UP | SCREENING | false | false |
| 4 | 1 | FOLLOW-UP | TREATMENT | false | false |
| 5 | 1 | FOLLOW-UP | FOLLOW-UP | true | true |

Visit 1's match on its third element does not leak into visit 0, and visit 0's matches do not
silence visit 1. Notice the last row: visit 1 "matches" an element that need not contain it at all —
a quantifier ranges over **every** partner row, so restrict the condition to the rows that matter
(`<element may be in progress> and EPOCH == SE.EPOCH`), as `CDISC-CG0218` does.

Three things to know:

- **A quantifier answers the same on every row of a record.** A Check that is only a quantifier
  fires on every evaluation row of a failing record — one finding per partner, all distinct by the
  partner's columns. Anchor the level on a row condition beside it: `<this element contains the
  visit start> and EPOCH != SE.EPOCH and none(joined(<may be in progress> and EPOCH == SE.EPOCH))`
  fires once, on the containing element.
- **The domain is the record's rows in the evaluation table.** On an inner join every matched
  combination; on a `left` join an unmatched record keeps its one placeholder row, which **is** in
  the domain — guard it with `J._matched_` inside the condition, as everywhere else. A rule whose
  entries expand nothing (one partner per record, `Cardinality: "n:1"`) sees one row per record, and
  `any(joined(c))` is simply `c`.
- **Where it is legal.** Only in a Check level expression (bare, or under `and` / `or` / `not`) —
  never in a binding, the Precondition, a comparison operand, a function argument or a `Filter`;
  `joined(…)` only as the quantifier's direct argument, with its condition written **bare** inside
  the parentheses; and only in a rule that declares a keyed or RELREC `Match_Datasets` entry. Each
  of these fails the rule's load with a message naming the position. The YAML `any:` / `all:`
  composites under a Check level combine whole conditions — they do not range over rows.

Reference: [`any`](../reference/functions.md#any), [`all`](../reference/functions.md#all),
[`none`](../reference/functions.md#none), [`joined`](../reference/functions.md#joined).

## Which shape do you need?

| the question | the shape |
|---|---|
| compare a value here with a value there | ordinary join, `Keys` |
| does a related record exist? | `left` join + `_matched_`, usually with `Filter` |
| does a related record *of a particular kind* exist? | `Filter` on the joined side |
| my primary dataset points at a parent row | `Child: true`, the parent's columns as `<domain>.X` |
| each record has at most one partner, and a duplicate is a defect | `Cardinality: "n:1"` |
| the SUPP merge must use another key | `Supp_Keys` |
| the two sides type the key differently | fix the data, or `Requirements.Variables` on **both** sides, or `Join_As_String: true` |
| walk RELREC relationships | `Name: "RELREC"`, no `Keys`; read `<domain>.X` guarded by `<domain>._matched_` |
| the key lives in a dataset the rule already joins | a [qualified key](#a-qualified-key--joining-through-an-earlier-entry) on a later entry |
| does **some** of a record's partners satisfy a condition — or none, or all? | the ordinary join, then [`any` / `all` / `none` over `joined(…)`](#some-joined-row--any--all--none-over-joined) in the Check |

> ⚠ **A join changes what an unqualified name means in only one respect: nothing.** Unqualified
> names keep referring to the primary dataset even after a join. If you mean the joined side,
> qualify it — see [Values and missing values](../semantics/values-and-missing.md) for what an
> unmatched row looks like.

---

**Next:** [Grouping](grouping.md) — evaluating per group instead of per row.
