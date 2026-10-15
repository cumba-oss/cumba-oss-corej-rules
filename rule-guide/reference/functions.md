# Function reference

<!-- Maintained by hand. The parameter tables were first dumped from the engine's descriptors
     (FunctionRegistry, CompilerDispatchedCalls, MetadataAttribute) and are now edited here.
     FunctionReferenceRosterTest (cumba-corej-rules) holds this page to the registry: every name
     registered in FunctionRegistry needs a `### <name>` section or a backticked mention
     (`name` or `name(`) on this page, or the build reds. -->

One section per function: what it does, every parameter with its type, and a worked example.
The summary tables are in [Expression syntax](expression-syntax.md#functions).

## How to read this page

**Two rules hold everywhere, so only departures from them are noted per function.**

**1. `""` is a present value and is read literally.** `len("")` is `0`, `upper("")` is `""`. There
is no "empty means missing" behaviour — with one deliberate family of exceptions, the emptiness
predicate `empty` (and its positive spellings), whose whole purpose is to report `""` and a genuine
missing alike. `is_missing` is **not** one of them: it is true only for a genuine missing. A section headed **Missing and empty** appears wherever a function departs from this.

**2. A missing input propagates.** A genuine [missing value](../semantics/values-and-missing.md)
passed to a function yields a missing result rather than a default, an error or a sentinel — so a
check built from ordinary functions ignores rows with missing data by construction. Where that is
not what you want, test for it with `empty(x)` explicitly.

**A list form (element-wise) lifts over its first argument only, and one level deep.** Every other
argument of such a function takes one value per row: a list there (`substring(X, $starts)`) fails to
load, or — when only the run knows it is a list — ERRORs the rule, never a zip or a guess. A list whose elements are themselves lists (`distinct([A, B])`'s row tuples) is not lifted: it
fails to load where its type is known, and otherwise ERRORs the rule at run time — `upper(distinct([A, B]))`
loads (only the run knows its elements are lists) and ERRORs when it runs (one level only).

**Functions that reach outside the row have one more failure mode**, and it is not an error.
`record_count(domain="DS")`, `distinct(X, domain="TA")`, `read_value(…, domain=TS)`, the library and Define-XML lookups — each names something that
may not be there: an absent dataset, a filter no row satisfies, a group no row falls in, a name
the library holds nothing under.

**Every such function is total over that.** It answers with its own declared *empty result* —
a count answers `0`, a set answers the empty set, a predicate answers `false`, a text lookup
answers `""`, a calculation answers missing — and the check then computes honestly over that
value. A section headed **When there is nothing to answer** states which, per function. ⚠ A few
library lookups are exceptions and SKIP the rule instead; their section says so.

> ⛔ **An empty answer is a value, never a "do not fire".** `record_count(domain="DS")` on a study
> with no `DS` dataset is **`0`**, so `$n == 0` **fires** — the rule reports, it does not stand
> down. If the rule should not run at all when a dataset is missing, that is
> [`Requirements.Datasets`](../authoring/requirements.md), not something the function decides.

## The target of `distinct`: one column, or a list of columns

`distinct` is the one function whose single positional argument decides the **shape** of its
answer (like every function that reads another dataset it
takes that target as its only positional argument — see below). What that argument *is* decides what the function collects:

| you write | collects | meaning |
|---|---|---|
| `distinct(ARMCD, …)` | the column's distinct **values** | one column, or a value expression over the target dataset (`distinct(upper(ARM), …)`) |
| `distinct([ARMCD, ARM], …)` | the distinct **row tuples** | a list of columns |

There is no `name=` / `names=` keyword spelling: the positional argument is the target, and a
quoted name is a string literal that fails to load.

### A list target yields row tuples

The two forms answer different questions. A single column yields the set of that column's
**values**; a list yields the set of its **row tuples**, one per row of the source, in the order
the list gives.

A tuple set is tested with `tuple(…)`, which builds the same shape from the row in hand:

```yaml
Bindings:
- name: "$ta_arm_pairs"
  expression: 'distinct([ARMCD, ARM], domain="TA")'
Check:
  expression: 'not empty(ARM) and not empty(ARMCD) and tuple(ARMCD, ARM) not in $ta_arm_pairs'
```

That is a real rule — *"a populated ARM / ARMCD pair must coincide with a planned pair in TA"*.
Comparing the two columns separately would accept a row that mixes one arm's code with another's
description; comparing the **pair** does not.

> ⚠ **The list is positional, so the order is part of the value.** `tuple(ARMCD, ARM)` matches a
> set built as `distinct([ARMCD, ARM], …)`; against `distinct([ARM, ARMCD], …)` it matches
> **nothing**. Which way that would show depends on the operator: under `in` the rule silently
> never fires, under `not in` it fires on **every row**. Write the two in the same order and read
> them together.
>
> ⭐ **The loader now refuses both undecidable-by-hand cases** (2026-09-21): a **permutation of the
> same column names**, and a **length mismatch**, are LOAD ERRORS naming the rule — so a reversed
> pair fails to load instead of quietly answering false forever. It cannot catch more than that,
> because the third case is legitimate:
>
> ⚠ Comparing *differently named* columns is legitimate and common —
> `tuple(PPNOMDY, PPTPTREF) not in distinct([PCNOMDY, PCTPTREF])` pairs one dataset's columns
> against another's. It is only a **permutation of the same names**, or a length mismatch, that
> can never match.

Other parameters take a list in the same literal form: `is_unique_set([USUBJID, --SEQ])`,
`contains_all(source, keys=[ARMCD])`,
`is_sorted_by(target, by=[asc(VISITNUM)])`.

> ⚠ **The one-positional rule holds for the functions that read another dataset** — `distinct`,
> `max`, `max_date`, `min_date` and `read_value` take their target as the only positional argument,
> and `record_count` takes none; a further positional (`max_date(DSSTDTC, DS, group=[USUBJID])`) is
> a load error naming the keyword spelling. An ordinary function takes as many positional
> arguments as it declares parameters — `substring(AETERM, 1, 3)` — as described under
> [Arguments](expression-syntax.md#arguments).

⛔ **A type mismatch is a rule ERROR, not a coercion.** Where a function requires a numeric
operand, a character column raises rather than being parsed; convert it with `num(x)` when the
text really is meant as a number. Sections that gate a type say so under **Type error**. The
general rules — including which operators gate — are in
[Comparison](../semantics/comparison.md).


## Qualified `group=` members

The grouped functions that read another dataset — [`read_value`](#read_value), [`max`](#max),
[`max_date`](#max_date), [`min_date`](#min_date), [`record_count`](#record_count) and
[`distinct`](#distinct) — accept a **qualified** `group=` member. It keys the record's side of the
group through a join the rule declares:

```yaml
Match_Datasets:
- Name: "DM"
  Keys: ["USUBJID"]
Bindings:
- name: "$rprfdy"
  expression: 'read_value(RPRFDY, domain="TP", group=[DM.RPATHCD, RPHASE], mode="MAX")'
```

`TP` is grouped by its own `RPATHCD` and `RPHASE`. A record of the evaluated dataset (say `BW`,
which has no `RPATHCD`) reads the group of the `RPATHCD` of **its bound `DM` record** and its own
`RPHASE`. The qualified name says where the record's value comes from; the **unqualified** name is
the same variable on the grouped side. When the two sides carry **different names** the member
is written **sided**:

```yaml
Bindings:
- name: "$ta"
  expression: 'sequence(ETCD, domain="TA", group=[{left: ACTARMCD, right: ARMCD}], by=[asc(TAETORD)])'
```

`TA` is grouped by its own `ARMCD`; each `DM` record reads the group of its own `ACTARMCD`. A
sided member `{left: X, right: Y}` is a literal of its own: each side a plain column (no `--`,
no qualifier, no `$` — a side is never spliced), `domain=` required (without it the two names
would be columns of one table), the key types checked side against side, an absent left column
a member on one side only (it leaves the key, as below), legal **only** as a `group=` member of a
`domain=` grouped call ([`sequence`](#sequence) included) — as a list element or an operand it
fails to load. The qualified `DM.RPATHCD` keeps grouping on `RPATHCD`: it has no sided spelling.

- **Without `domain=` there is no other side**: the qualified member is a column of the
  evaluated rows' one table (the row's `DM.RPATHCD`), exactly as in the rule-level
  [`Grouping`](../authoring/grouping.md#a-qualified-member--grouping-by-a-joined-value).
- **The qualifier names a `Match_Datasets` entry of the rule** — any declared entry: an ordinary
  keyed join, a `left` one (an unmatched row reads the column's type default), a `Child: true`
  parent, a `RELREC` related domain or a `SUPP--` entry. A qualifier naming no entry is a load
  error — in every `Check` level and every binding.
- **Load errors besides:** a `--` wildcard inside a qualified member (`DM.--SEQ`).
  `record_count`'s `regex=` works with a qualified member: the record side's value is normalised
  by the pattern its grouped column takes. A qualified name spliced into `group=` from a `$` list
  reads like one written in the list. A `$` member whose binding holds neither a list of names nor
  a single name **errors the rule** — and when the binding's type already shows it at load (a number, or a per-row
  binding such as `upper(AETERM)`), the rule fails to **load** instead. A compiler-dispatched group operator such as
  [`has_multiple_values_for`](#has_multiple_values_for) has no other side at all: a dotted member
  of its `within=` is a column of the evaluated rows' one table.
- **The answer contract is the function's own.** Only the key is read differently. A record whose
  key has no group reads the function's no-group answer — the type default for `read_value`,
  missing for `max` / `max_date` / `min_date`, `0` for `record_count`, the empty list for
  `distinct`. A blank `DM.RPATHCD` is a key like any other (`keep_missings` defaults to `true`): it
  reads the grouped dataset's blank-`RPATHCD` group if there is one, else the no-group answer.
- **Types are checked against the source.** The grouped column's type is compared with the
  **source** column's (`DM.RPATHCD`), not with the evaluated dataset's; a character key on one side
  and a numeric one on the other makes the rule ERROR.
- **A member on one side only leaves the key — bare and qualified alike.** Under `domain=` a
  `group=` member keys two datasets, like a join key: present on both it keys; present on **one**
  side only — no `DM` dataset in the study, `DM` without `RPATHCD`, a grouped dataset without
  `RPATHCD`, or a bare `VISITNUM` that only one of the two carries — it is dropped from the key on
  both sides with a WARNING in the log
  (`[<rule>] read_value group=: TP: key column DM.RPATHCD/RPATHCD is present on one side only (TP) and is ignored`);
  absent from both it partitions nothing, silently. ⛔ **No member left on both sides** after such a
  drop is a rule **ERROR** (`… group= over 'TP' has no usable key`) — never an aggregate over the
  whole `domain=` dataset — and it holds even when the grouped dataset has no rows. ⚠ The ERROR
  is raised when the call is **read**. A rule without a rule-level `Grouping` reads the bindings of
  its Check before it judges a record (the absent-column fold), so it errors whatever the record
  values are, unless an earlier part of the Check is already false for the whole dataset. Under a
  rule-level `Grouping` a binding is read only when a record reaches it — `and` stops at the first
  part no record passes — so a study whose records all fail an earlier part is answered silently
  instead (`FDA-SE2270`: its leading `USUBJID in $ds_usubjid` is empty when DS lacks `USUBJID`,
  so the grouped DS extreme is never read). Declare the
  member on both sides in [`Requirements.Variables`](../authoring/requirements.md) (`All`, or an
  `All_Or_None` pair) and such a study **skips** the rule instead. A rule that can judge correctly
  without the member is **split** by a gate instead of erroring: the pooled-dose SEND rules
  (`CDISC-SEND-0204` / `-0205` / `-0305` / `-0306`) run on pooled studies (`POOLDEF.POOLID` and
  `EX.POOLID` in `All`), their `-A` siblings on studies that define no pools (`POOLDEF.POOLID` in
  `None`), and a POOLDEF with pools beside an EX without `POOLID` skips both.
- **Copies are judged separately.** The `DM` join runs before any binding. If it is not one-to-one
  per record — `DM` carrying a subject twice, on two paths — each copy of the record reads the group
  of **its own** `DM` record, and the two copies may read two different values. Join on a key that
  makes the source one-to-one if the record should be judged once.

The same convention keys a [`Match_Datasets` join through an earlier entry](../authoring/joins.md#a-qualified-key--joining-through-an-earlier-entry).


## Presence and emptiness

### available

`available(op)` → **boolean**

True when the named binding produced a result at all

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
Bindings:
- name: "$ds_count"
  expression: 'record_count(domain="DS")'
Check:
  expression: 'available($ds_count) and $ds_count == 0'
```

### coalesce

`coalesce(a, b[, c])` → **value**

The first operand that is neither missing nor `""`

| parameter | type | required | description |
|---|---|---|---|
| `a` | any | yes | The first value |
| `b` | any | yes | The second value |
| `c` | any | no | An optional third value |

**Example**

```yaml
Check:
  expression: 'empty(coalesce(AEDECOD, AETERM, AELLT))'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'empty(coalesce(AEDECOD, AETERM))'
```

**Missing and empty** — `""` counts as **not present** and is skipped, exactly like a genuine missing: `coalesce("", "a")` returns `"a"`, and an absent column is skipped as well. For **numbers only a missing value is skipped** — `0` and `0.0` are real values and are returned. When every operand is missing or `""`, the result is missing — and it is the missing the operands carry (rulings register `D85c` / `D86a`): one distinct identity among the genuinely missing operands gives that missing (`coalesce(.A, "")` is `.A`), two or more give the plain `MIS` (`coalesce(.A, .B)`), and none — every operand `""` — gives the plain `MIS`. A plain missing, and an absent numeric column, count as the identity `.`: `coalesce(.A, «missing» .)` is `MIS`; an absent character column reads `""` and contributes no identity. The call's **position passes through to its operands**: in `coalesce(A, B) > 3` an absent `A` takes the default `A > 3` would give it — missing — rule-wide, exactly as `A` read in place; `coalesce`'s own answer is unchanged by it.


### ds_exists

`ds_exists(x)` → **boolean**

True when the named dataset is present in the run — exactly under that name, as a **split domain**
(`LB` shipped as `LB1` + `LB2`), or as a **split supplemental family**: `SUPPAE` shipped as
`SUPPAE1` + `SUPPAE2` (both `RDOMAIN = AE`, no `SUPPAE`) is present, and so is the associated-persons
twin `SQAPMH` shipped as `SQAPMH1` + `SQAPMH2` (`RDOMAIN = APMH`). A family is read off each member's
`RDOMAIN`, never guessed from its name: `SUPPLBCH` / `SUPPLBHE` / `SUPPLBUR` are `SUPPLB`. Every member
name (`SUPPAE1`) and every other name resolves exactly. The same fact decides `Requirements.Datasets`
and the absent-dataset skip, so a rule never skips on a family `ds_exists` answers true. A negated
spelling `ds_not_exists(x)` exists; prefer `not ds_exists(x)`.

| parameter | type | required | description |
|---|---|---|---|
| `name` | dataset reference | yes | The dataset to test, as a plain name (`"SJ"` or `SJ`) — no `--`, no dotted or filter form (a load error) |

**Example**

```yaml
expression: 'ds_exists("SJ")'
```

### empty

`empty(x)` → **boolean**

True when the value is missing or `""`. The positive spellings `non_empty` / `present` / `is_present` exist; prefer `not empty(x)`. `is_missing(x)` is **not** a spelling of it: it is true only for a genuine missing (rulings register `D50`) — `is_missing("")` is false. Until 2026-10-05 `is_missing` was an alias of `empty`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'empty(AGE)'
```

**Missing and empty** — This is one of the few places `""` is **not** read literally: it reports both a genuine missing value and `""` as empty. That is the function's purpose.

### is_missing

`is_missing(x)` → **boolean**

True only when the value is a genuine missing — `.` or any special missing (`.A`–`.Z`, `._`) — and never for `""`, which is a present value (rulings register `D50`). To ask "missing or `""`" write [`empty(x)`](#empty). Until 2026-10-05 `is_missing` was an alias of `empty`; no shipped rule calls it.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'is_missing(AGE)'
```

**Missing and empty** — `is_missing("")` is false, `is_missing(.)` is true. Over an **absent column** it answers the column's default at its position: false where the column reads as `""`, true where its other positions make it a number and so the missing (`is_missing(X) or X > 3`). An empty list is a present value, not a missing.

### var_exists

`var_exists(x)` → **boolean**

True when the named column exists; accepts a `--` wildcard, a dotted cross-dataset name, a SUPP QNAM pivot, a `${…}` substitution and a computed name (`var_exists(concat("ADSL.TRT", printf("%02d", APERIOD), "P"))`). The column part of a dotted string matches in any letter case (`var_exists("ADSL.trt01p")` finds `TRT01P`); the dataset qualifier is matched as written. A negated spelling `var_not_exists(x)` exists; for a written name prefer `not var_exists(x)` (a computed name needs a guard — see *Missing and empty* below).

A written name (a literal or a column reference) is one fact for the whole dataset. A **computed**
name is tested **per row** — the rule runs at record level — and resolves exactly as the written
name would (the dataset through the run's resolver, the SUPP QNAM pivot included). A computed name
that starts with `--` is not specialised to the domain: it answers as any absent name.

**Through a binding**, `var_exists($n)` answers exactly as the binding's expression written in
its place. A binding that holds a **written** name — `$c: TRT01P`, `$n: "--SEQ"`,
`$d: ADSL.TRT01P` — is that name: one fact for the dataset, `--` specialised as for the literal
(`var_exists($c)` asks whether the column TRT01P exists, never what its cells say); a `${…}`
template stays the template, per row. A binding that holds a **computed** name —
`$n: concat("ADSL.TRT", printf("%02d", APERIOD), "P")` — is tested per row, as the computed name
written inline is. An argument whose type cannot name a column — a number, a boolean, a date, a
list such as `find_vars(…)` — is a load error (`PARAMETER_TYPE`), bound or inline. To test the
column a cell names, read the cell: `var_exists(colref("IDVAR"))`.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |

**Example**

```yaml
expression: 'var_exists("AWU")'
```

**Missing and empty** — A computed name that is missing (a missing driver, or one `printf`
cannot format: `printf("%02d", 1.5)` is missing) or a value that is not text names no column, and
then **both** `var_exists(…)` and `var_not_exists(…)` are false on that row. `not var_exists(…)`
is therefore **true** there — `not` flips the false — so a rule that reports an absent computed
column guards the name: `not empty($n) and not var_exists($n)`. A guard on the driver alone
(`not empty(APERIOD)`) does not cover a driver the name cannot be built from.

### var_is_empty

`var_is_empty(x)` → **boolean**

True when the column is absent, or present and blank — `""` or missing — on every record (the column's content status is `EMPTY` or `MISSING`)

**Renamed from `var_is_null`** (2026-10-06): the old name contradicted the never-null doctrine (nothing is ever null — a cell is a value or a missing value). Same semantics; `var_is_null(…)` is now a **load error** with the hint *"var_is_null was renamed to var_is_empty"*, never an alias.

A qualified name (`var_is_empty(DM.DTHDTC)`, or the string `"DM.DTHDTC"`) reads the joined column through its `Match_Datasets` entry over every row of the evaluated dataset, exactly as the operand `DM.DTHDTC` reads it — an unmatched row reads the column's type default, and an absent column or an undeclared join the absent default. (Until 2026-10-01 the dotted text was looked up on the primary and the call answered `true`, silently.)

**An absent column** answers TRUE for either type: it is a present column holding its type's default — `""` for character, a missing value for numeric — on every row.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read (a plain reference, a quoted name, or `varname()`) |

**Example**

```yaml
expression: 'varname() in $expected_variables and var_is_empty(varname())'
```

### var_is_single_value

`var_is_single_value(x)` → **boolean**

True when every row of the dataset holds the same value in the column (the column's uniformity is `SINGLE_VALUE`) and the dataset has at least one row

Value equality is the store's: `""` is not a missing value, and `.A` is not `._` — a column of `""` on every row is single-valued, a column mixing `""` with missings is not, and two distinct missing markers are two values. Reads one dataset-wide fact, like `var_is_empty`, so a Check built only on it derives `Sensitivity: Dataset`. The same three argument shapes as `var_is_empty`.

**An absent column** answers TRUE for either type (its type default on every row) — on a dataset with rows; on an empty dataset FALSE, like a present column.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read (a plain reference, a quoted name, or `varname()`) |

**Example**

```yaml
expression: 'var_is_single_value(ARMCD) and ARMCD != "SCRNFAIL"'
```

### var_is_all_missing

`var_is_all_missing(x)` → **boolean**

True when every cell of the column is a missing value (the column's fill is `MISSING`); `""` is not missing, so a character column of empty strings answers FALSE

The strict counterpart of `var_is_empty` (register D50: `is_missing` is for missing, `empty` for missing or `""`). Reads one dataset-wide fact; the same three argument shapes as `var_is_empty`.

**An absent column** takes the type the **rule** expects for it (D76): TRUE when the rule reads the column as numeric (its default is a missing value on every row), FALSE otherwise — **no numeric expectation ⇒ character ⇒ `""` ⇒ FALSE**.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read (a plain reference, a quoted name, or `varname()`) |

**Example**

```yaml
expression: 'var_is_all_missing(AESTDY) and not var_is_all_missing(AESTDTC)'
```

## Text

### concat

`concat(a, b[, c])` → **value**

Joins two or three values into one string; a missing operand makes the result missing

| parameter | type | required | description |
|---|---|---|---|
| `a` | any | yes | The first value |
| `b` | any | yes | The second value |
| `c` | any | no | An optional third value |

**Example**

```yaml
Check:
  expression: 'concat(STUDYID, "-", USUBJID) != SUBJID'
```

**Missing and empty** — A missing operand makes the result **that missing value** (rulings register `D36`: one missing part poisons the result). The identity is kept — `.A` in gives `.A` out — and when the operands carry two or more distinct missing identities the result is the plain `MIS` (`D86a`). A present `""` contributes nothing, and so does an absent character column (it reads as `""`).

**Minimal — required parameters only**

```yaml
Check:
  expression: 'concat(DOMAIN, "SEQ") == varname()'
```


### contains

`contains(x, needle)` → **boolean**

True when `needle` occurs anywhere in `x`; `needle` may vary per row. A negated spelling `does_not_contain(x, needle)` exists; prefer `not contains(x, needle)`. To ask for any of several substrings, write `contains_any(x, list)`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |
| `value` | any | yes | The value to compare against — **one** value per row. A list here fails to load with the hint *"use contains_any(x, list)"* (for `does_not_contain`: *"use not contains_any(x, list)"*), and when only the run knows the value is a list (an unknown-typed `$`), the rule ERRORs with the same sentence |

**Example**

```yaml
expression: 'contains(--LAT, ";")'
```

### contains_any

`contains_any(x, list)` → **boolean**

True when `x` contains **any** element of `list` as a substring — `contains(x, …)` asked once per element. Negate with `not contains_any(x, list)`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | string | yes | The value to read, per row. A list here fails to load (`contains_any` is never a membership test) |
| `list` | list of strings | yes | The substrings to look for — a list literal (`["ACHE", "PAIN"]`), a `$`-binding holding a list, or a per-row list (`split_by(…)`). A scalar fails to load: write `contains(x, value)` |

**Example**

```yaml
expression: 'contains_any(upper(TESTRL), $arms)'
```

**Missing and empty** — As `contains`: a missing `x` or a missing `list` answers **false**; a missing element never matches and does not make the call false; `[]` answers **false**; an element `""` matches every present `x`, a blank `x` included (a blank is a present `""`). A numeric element probes its canonical text (`100.0` probes `"100"`). There is no case folding — write `upper()` on both sides (`contains_any(upper(X), upper($list))`).

### count

`count(x)` → **value**

Number of elements in a list-valued operand — 1 for a present scalar, 0 for missing or `""`. Also spelled `size`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'count(library_variable_label_values) > 1'
```

**Missing and empty** — Counts **elements**, not characters: 1 for a present scalar, **0** for a missing one or `""`.

### does_not_equal_string_part

`does_not_equal_string_part(name, value, regex=)` → **boolean**

True when `value` differs from capture-group 1 of `regex=` applied to `name`

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |
| `value` | any | yes | The value to compare against |
| `regex` | string | yes | A regular expression, written as a **string** literal |

**Example**

```yaml
expression: 'does_not_equal_string_part(SUFFIX, USUBJID, regex="-([0-9]+)$")'
```

### ends_with

`ends_with(x, needle)` → **boolean**

True when `x` ends with `needle`; `needle` may vary per row

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |
| `value` | any | yes | The value to compare against — **one** value per row. A list here fails to load (*"ends_with has no list form"*), or ERRORs the rule when only the run knows it is a list |

**Example**

```yaml
expression: 'ends_with(varname(), "DT")'
```

### equalsIgnoreCase

`equalsIgnoreCase(x, y)` → **boolean**

Case-insensitive string equality; two empties match

| parameter | type | required | description |
|---|---|---|---|
| `a` | any | yes | The first value |
| `b` | any | yes | The second value |

**Example**

```yaml
expression: 'equalsIgnoreCase(RDOMAIN, "DM")'
```

### has_alpha

`has_alpha(x)` → **boolean**

True when `x` contains any ASCII letter

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**List form (element-wise)** — over a list at `x` — the cells of several columns
([`values`](#values): `has_alpha(values([X, Y]))`), a per-row list such as `split_by(…)`, or a
`$`-list — the predicate answers **one boolean per element** (a list of booleans), consumed only by
[`any`](#any) / [`all`](#all) / [`none`](#none), directly or through a binding (`$ok: has_alpha(values([X,
Y]))`, then `all($ok)`); a missing element gets the predicate's own answer on a missing. Written as a
condition on its own, compared, tested with `in`, passed to another function or reported, the list
form fails the rule's load — quantify it. `[X, Y]` with bare names is a list of column *names*: read
the values with `values([X, Y])`.

**Example**

```yaml
expression: 'has_alpha(LBTOXGR) and has_digit(LBTOXGR)'
```

### has_digit

`has_digit(x)` → **boolean**

True when `x` contains any ASCII digit

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**List form (element-wise)** — over a list at `x` — the cells of several columns
([`values`](#values): `has_digit(values([X, Y]))`), a per-row list such as `split_by(…)`, or a
`$`-list — the predicate answers **one boolean per element** (a list of booleans), consumed only by
[`any`](#any) / [`all`](#all) / [`none`](#none), directly or through a binding (`$ok: has_digit(values([X,
Y]))`, then `all($ok)`); a missing element gets the predicate's own answer on a missing. Written as a
condition on its own, compared, tested with `in`, passed to another function or reported, the list
form fails the rule's load — quantify it. `[X, Y]` with bare names is a list of column *names*: read
the values with `values([X, Y])`.

**Example**

```yaml
expression: 'has_alpha(LBTOXGR) and has_digit(LBTOXGR)'
```

### has_equal_length

`has_equal_length(name, n)` → **boolean**

True when the value's length equals `n`. A negated spelling `has_not_equal_length(name, n)` exists; prefer `not has_equal_length(name, n)`. `n` may be a number, numeric text or a per-row column.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |
| `length` | any | yes | How many characters to take |

**Example**

```yaml
expression: 'has_equal_length(RDOMAIN, 8)'
```

⛔ **Type error on a character `n`.** The length operand must be numeric; a character column there raises a rule ERROR. Convert with `num(n)`.

### imatches

`imatches(x, /re/)` → **boolean**

Case-insensitive unanchored regex search

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |
| `pattern` | regex | yes | The regular expression to match |

**Example**

```yaml
expression: 'imatches(ARM, /placebo/)'
```

**Missing and empty** — A missing value matches **no** pattern, including one that would match an empty string.

### is_valid_name

`is_valid_name(x)` → **boolean**

True when `x` is a valid uppercase SAS name of 1–8 characters

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**List form (element-wise)** — over a list at `x` — the cells of several columns
([`values`](#values): `is_valid_name(values([X, Y]))`), a per-row list such as `split_by(…)`, or a
`$`-list — the predicate answers **one boolean per element** (a list of booleans), consumed only by
[`any`](#any) / [`all`](#all) / [`none`](#none), directly or through a binding (`$ok: is_valid_name(values([X,
Y]))`, then `all($ok)`); a missing element gets the predicate's own answer on a missing. Written as a
condition on its own, compared, tested with `in`, passed to another function or reported, the list
form fails the rule's load — quantify it. `[X, Y]` with bare names is a list of column *names*: read
the values with `values([X, Y])`.

**Example**

```yaml
expression: 'not is_valid_name(varname())'
```

### is_valid_testcd

`is_valid_testcd(x)` → **boolean**

True when `x` is 1–8 characters starting with a letter or underscore

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**List form (element-wise)** — over a list at `x` — the cells of several columns
([`values`](#values): `is_valid_testcd(values([X, Y]))`), a per-row list such as `split_by(…)`, or a
`$`-list — the predicate answers **one boolean per element** (a list of booleans), consumed only by
[`any`](#any) / [`all`](#all) / [`none`](#none), directly or through a binding (`$ok: is_valid_testcd(values([X,
Y]))`, then `all($ok)`); a missing element gets the predicate's own answer on a missing. Written as a
condition on its own, compared, tested with `in`, passed to another function or reported, the list
form fails the rule's load — quantify it. `[X, Y]` with bare names is a list of column *names*: read
the values with `values([X, Y])`.

**Example**

```yaml
expression: 'not empty(--TESTCD) and not is_valid_testcd(--TESTCD)'
```

### len

`len(x[, unit=, encoding=])` → **value**

Length of the value in characters — Unicode code points, so a character outside the Basic Multilingual Plane (an emoji, a rare CJK ideograph) counts one, as `max_value_length` counts it; `len("") = 0`. Also spelled `length`. With `unit="bytes"` the number of bytes the value takes when encoded.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |
| `unit` | string | no | `"chars"` (the default) counts characters (code points); `"bytes"` counts the value encoded in `encoding`. A static string literal |
| `encoding` | string | no | The charset a byte count encodes with (`"UTF-8"`, `"windows-1252"`, …), a static string literal; an unknown name fails the load. Omitted, the table's own `Encoding` (what `extract_metadata("encoding")` reads) — a table declaring none, or `MIXED`, makes the byte count **missing** |

**Example**

```yaml
expression: 'len(ETCD) > 8'
```

**Bytes** — `len(X, unit="bytes", encoding="UTF-8") > 200` asks per row whether the value would fit a 200-byte XPT column once encoded in UTF-8, whatever the data was read from. `len(X, unit="bytes")` counts in the table's own charset, which for an XPT reproduces the stored byte count; a table without a declared `Encoding` (or with `MIXED`, several charsets in one library) has no single byte count, so the answer is missing and nothing fires — write `encoding=` to count in a charset of your choice. `unit="chars"` counts characters (code points), as `len(X)` does.

**Missing and empty** — `len("")` is **0** — an empty string is a present value of length zero. A genuine missing value yields **that missing value**, not 0 — identity kept (`len(.A)` is `.A`, rulings register `D85c`). An absent character column reads as all-`""`, so `len(x) == 0` is true for it.

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `len(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element's length in characters (`unit=` / `encoding=` apply to every element) — a list of numbers, never the list's cardinality, which is [`count`](#count), exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### lower

`lower(x)` → **value**

The value lower-cased. Also spelled `lowcase`.

`lower(X) in [...]` compares the lower-cased value with the members **as written** — only
[`upper`](#upper) / `upcase` is the case-insensitive membership surface that folds the members too.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'lower(SEX) == "m"'
```

**Missing and empty** — A missing input yields **that missing value**, identity kept (`.A` in, `.A` out — rulings register `D36`). `""` is a present value and yields `""`.

**List form** — given a list (a `$`-binding such as `get_column_order_from_dataset()` or a
`distinct(…)` result, a per-row list such as `split_by(…)`, or `lower(values([X, Y]))` over several
columns' values), returns a **new list** with every element lower-cased, in order; a **missing
element stays missing** — it is carried through unchanged, in its position (missing propagates
through the string functions, register `D36`), never folded to `""`. Element-wise as
[`upper`](#upper) is: `lower([X, Y])` with bare column names fails to load, the values are read with
`lower(values([X, Y]))`.

### matches_label_template

`matches_label_template(label, template)` → **boolean**

True when `label` conforms to `template`, a library label read as a template ([`label_template`](#label_template)). The grammar is the ADaM implementation guide's own rule for its labels:

- a **`{phrase}`** must occur somewhere in the label, which is otherwise free (*"labels containing a word or phrase in brackets should be replaced by the producer with appropriate text that contains the bracketed word or phrase somewhere in the text"*): `Start Date of Rescue Med` and `Rescue Med Start Date` both conform to `{Start Date}`;
- **`...`** is any text, including none, with the whitespace beside it optional: `Analysis Value Category 1` and `Analysis Value Category 1 (Low)` both conform to `Analysis Value Category 1 ...`, and `Start Date of First Dose` to ADaMIG 1.0's `Start Date of ...`. Where the fixed text beside it ends (or starts) with a letter or digit, the free text is empty or set off from it by a non-alphanumeric character: text glued to the fixed text is not free text, it changes the word or the index the fixed text ends in — `Analysis Value Category 12` is `AVALCAT12`'s label and does **not** conform to `AVALCAT1`'s `Analysis Value Category 1 ...`, nor `Analysis Flag 012` to `Analysis Flag 01 ...` (the implementation guide allows descriptive text *at the end* of the label, i.e. after it). Any non-alphanumeric character is such a boundary — `Analysis Flag 01, Primary` conforms;
- **fixed text** matches exactly, letter case included — `Planned Treatment for Period 01` conforms only to itself;
- **`Imput.` is `Imputation`** on both sides — the one abbreviation the implementation guide itself publishes (`Date of First Exposure Imput. Flag`); no other.

| parameter | type | required | description |
|---|---|---|---|
| `label` | string | yes | The label to judge, usually `var_label()` |
| `template` | string | yes | The template, usually `label_template("LIBRARY")` |

**Example**

```yaml
expression: 'not empty(label_template("LIBRARY")) and not matches_label_template(var_label(), label_template("LIBRARY"))'
```

**Missing** — A missing `label` or `template` answers **false**, as `contains` does, so the `not empty(label_template("LIBRARY"))` guard is what keeps a column without a template out of the rule; without it, `not matches_label_template(…)` fires on every such column. Both parameters are strings: a number in either position fails to load.

### max_value_length

`max_value_length([name][, unit=, encoding=])` → **value**

The longest value stored in the column, across all rows — in characters (code points), or with `unit="bytes"` in bytes once encoded.

A qualified name (`max_value_length(DM.ARM)`, or the string form) reads the joined column through its `Match_Datasets` entry over every row of the evaluated dataset, exactly as the operand `DM.ARM` reads it. (Until 2026-10-01 the dotted text was looked up on the primary and the call answered `0`, silently.)

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read |
| `unit` | string | no | `"chars"` (the default) counts characters (code points); `"bytes"` counts each value encoded in `encoding`. A static string literal |
| `encoding` | string | no | The charset a byte count encodes with, a static string literal; an unknown name fails the load. Omitted, the table's own `Encoding` — a table declaring none, or `MIXED`, makes the answer **missing** |

**Example**

```yaml
Check:
  expression: 'max_value_length(AETERM) > var_length("DEFINE")'
```

**Bytes** — `var_length() != max_value_length(variable_name, unit="bytes")` compares a variable's declared width with the longest value as stored, in bytes, which is what an XPT width means. With no usable table encoding (none declared, or `MIXED`) the byte count is missing; comparisons are total, so `!=` on it fires and the finding's `variable_max_size` prints the numeric default. A finding reports the length in the Check's own unit: `variable_max_size` is computed from the call the Check wrote (`unit=`, `encoding=` included), not from a plain character count.

**Minimal — required parameters only**

```yaml
Check:
  expression: 'max_value_length() > 200'
```


### normalize_space

`normalize_space(x)` → **value**

Trims the value and collapses each run of internal whitespace to a single space

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'upper(normalize_space(var_label())) not in library_variable_label_values'
```

**Missing and empty** — A missing input yields **that missing value**, identity kept (rulings register `D36`). `""` is a present value and yields `""` (it used to yield missing).

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `normalize_space(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element with its whitespace normalised, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### prefix

`prefix(x, n)` → **value**

The first `n` characters; a shorter value is returned whole

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |
| `n` | number | yes | A number of characters |

**Example**

```yaml
expression: 'prefix(TSVCDREF, 5) == "CDISC"'
```

**Missing and empty** — A missing `x` yields **that missing value**, identity kept (rulings register `D36`); a missing `n` makes the result missing too (the identities of `x` and `n` combine per `D86a`: one distinct identity ⇒ that identity, two ⇒ `MIS`). `""` yields `""`. A value shorter than `n` — or a non-positive or non-integral `n` — returns the value whole.

⛔ **Type error on a character `n`.** The length operand must be numeric; a character column there raises a rule ERROR. Convert with `num(n)`.

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `prefix(values([X, Y]), 2)`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element's first `n` characters, the row's `n` applied to every element, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### prefix_matches

`prefix_matches(x, /re/[, n])` → **boolean**

True when the first `n` characters match the regex, anchored; without `n`, the whole value

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |
| `pattern` | regex | yes | The regular expression to match |
| `n` | number | no | A number of characters |

**Example**

```yaml
Check:
  expression: 'not prefix_matches(--TESTCD, /^[A-Z]+$/, 4)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not prefix_matches(USUBJID, /^[0-9]+$/)'
```

**Missing and empty** — A missing value matches **no** pattern, including one that would match an empty string.

⛔ **Type error on a character `n`.** The length operand must be numeric; a character column there raises a rule ERROR. Convert with `num(n)`.


### split_by

`split_by(x, "delimiter")` → **value**

Splits the value on a literal delimiter into a list of tokens

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |
| `delimiter` | string | yes | The literal string to split on |

**Example**

```yaml
expression: 'not contains_all(var_codelist_coded_values("LIBRARY"), split_by(value(), ";"))` (`CDISC-SEND-0049`)'
```

**Missing and empty** — A missing `x` yields **that missing value** — a single value, not a list (rulings register `D85c`); `""` yields the plain missing (`MIS`). Either way there are no tokens, so a membership test over it does not fire.

### starts_with

`starts_with(x, needle)` → **boolean**

True when `x` begins with `needle`

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |
| `value` | any | yes | The value to compare against — **one** value per row. A list here fails to load (*"starts_with has no list form"*), or ERRORs the rule when only the run knows it is a list |

**Example**

```yaml
expression: 'starts_with(QNAM, "RACE")'
```

### substring

`substring(x, start[, length])` → **value**

The substring starting at `start` for `length` characters; 1-based, as in SAS

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |
| `start` | number | yes | 1-based position of the first character |
| `length` | number | no | How many characters to take |

**Example**

```yaml
Check:
  expression: 'substring(USUBJID, 1, 4) != STUDYID'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'empty(substring(AETERM, 2))'
```

**Missing and empty** — A missing `x`, `start` or `length` yields **that operand's missing value**, identity kept (rulings register `D36`; two or more distinct identities collapse to `MIS`, `D86a`). `""` is evaluated literally against the bounds rather than being suppressed beforehand — `substring("", 1)` is a start past the end of a length-0 string, so it is the computed missing `MIS`.

⛔ **Type error on character bounds.** `start` and `length` must be numeric; a character column raises a rule ERROR. Convert with `num(...)`.


**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `substring(values([X, Y]), 1, 2)`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element's substring, the row's `start` / `length` applied to every element, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### suffix

`suffix(x, n)` → **value**

The last `n` characters; a shorter value is returned whole

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |
| `n` | number | yes | A number of characters |

**Example**

```yaml
expression: 'suffix(QNAM, 1) =~ /^[A-Z0-9]$/'
```

**Missing and empty** — As [`prefix`](#prefix): a missing `x` or `n` yields that missing value, identity kept (rulings register `D36` / `D86a`); `""` yields `""`; a value shorter than `n` — or a non-positive or non-integral `n` — returns the value whole.

⛔ **Type error on a character `n`.** The length operand must be numeric; a character column there raises a rule ERROR. Convert with `num(n)`.

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `suffix(values([X, Y]), 2)`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element's last `n` characters, the row's `n` applied to every element, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### suffix_matches

`suffix_matches(x, /re/[, n])` → **boolean**

True when the last `n` characters match the regex, anchored; without `n`, the whole value

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |
| `pattern` | regex | yes | The regular expression to match |
| `n` | number | no | A number of characters |

**Example**

```yaml
Check:
  expression: 'not suffix_matches(--DTC, /^[0-9]{2}$/, 2)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not suffix_matches(VISIT, /^WEEK.*$/)'
```

**Missing and empty** — A missing value matches **no** pattern, including one that would match an empty string.

⛔ **Type error on a character `n`.** The length operand must be numeric; a character column there raises a rule ERROR. Convert with `num(n)`.


### trim

`trim(x)` → **value**

The value with leading and trailing whitespace removed

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'trim(QVAL) != QVAL'
```

**Missing and empty** — A missing input yields **that missing value**, identity kept (rulings register `D36`). `""` yields `""`.

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `trim(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element trimmed, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### upper

`upper(x)` → **value**

The value upper-cased. Also spelled `upcase`.

**Case-insensitive membership** — `upper(X) in [...]` is the case-insensitive membership surface:
the probe **and** the members are folded, so `upper(X) not in ["a"]` fires only on a value that is
not `a` in any case. `upcase(X) in [...]` is the same surface (and an `upcase("…")` member the same
member as `upper("…")`). [`lower`](#lower) / `lowcase` have no such surface.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'not contains(upper(VISIT), upper("UNSCHEDUL"))'
```

**Missing and empty** — A missing input yields **that missing value**, identity kept (`.A` in, `.A` out — rulings register `D36`). `""` is a present value and yields `""`.

**List form** — given a list (a `$`-binding such as `get_column_order_from_dataset()` or a
`distinct(…)` result, a per-row list such as `split_by(…)`, or the values of several columns read
with [`values`](#values): `upper(values([X, Y]))`), returns a **new list** with every element
upper-cased, in order; a **missing element stays missing** — it is carried through unchanged, in its
position (missing propagates through the string functions, rulings register `D36`), never folded to
`""`. The function is **element-wise**: it applies to every element of a list at `x` and answers a
list of the same length, so `"Y" in upper(values([A, B]))` is a per-row membership. ⚠ `upper([X, Y])`
with bare column names fails to load — `[X, Y]` is a list of column *names* everywhere; to read their
values write `upper(values([X, Y]))`. This is how a rule compares the dataset's
column names against the library's names: a column name a rule handles as a value is a string and
compares exactly like one, so the rule folds **both sides** — the upper-case version of a name
against the upper-case version of a list of strings; folding a library list is a no-op when the
library names are upper case, and it spares the rule from deciding which side is
the library —

```yaml
Bindings:
- name: "$required_variables"
  expression: "required_variables()"
- name: "$dataset_variables"
  expression: "get_column_order_from_dataset()"
- name: "$dataset_variables_upper"
  expression: "upper($dataset_variables)"
- name: "$required_variables_upper"
  expression: "upper($required_variables)"
- name: "$required_variables_absent_upper"
  expression: "minus($required_variables, subtract=$dataset_variables_upper)"
- name: "$missing_required_variables"
  expression: "minus($required_variables_absent_upper, subtract=$dataset_variables)"
Check:
  expression: "not contains_all($dataset_variables_upper, $required_variables_upper)"
Outcome:
  Output_Variables:
  - "$missing_required_variables"
  - "!$dataset_variables_upper"
  - "!$required_variables_upper"
  - "!$required_variables_absent_upper"
```

The un-folded bindings stay available for `Output_Variables` (each sibling is excluded with
`!$x_upper`), so a reported name keeps its own spelling; the two-step `minus` subtracts both
spellings of the dataset's names, so the missing list keeps a library name — or an unmatched template
such as `TRTxxP` — verbatim and drops a template instance the dataset carries in its own spelling. The
scalar spellings are `upper(varname()) in $required_variables` (the membership surface folds the
list too) and `equalsIgnoreCase(varname(), var_name("LIBRARY"))`; a name **test** is
`ends_with(upper(varname()), "DTC")` / `upper(varname()) =~ /^ANL\d\dFL$/`. A `QNAM` / `IDVAR`
**cell** is a value and is not folded — it compares exactly, as every string does. The naming rules
(`varname() =~ /.*[a-z]/`, `varname() !~ /^[A-Z0-9_]+$/` and kin) test the letter case of the name itself and are
never folded; a template carve-out tests the **library** name instead, `var_name("LIBRARY") !~ /[a-z]/`, which
needs no fold — and since the library resolves a numbered column through its template (`TRT01P` → `TRTxxP`,
see [`label_template`](#label_template)), the carve-out is what keeps an index-named column out of a rule whose
authority excepts it, while a rule that checks such names compares with
[`matches_label_template`](#matches_label_template) instead of `!=`.


## Numbers and arithmetic

### abs

`abs(x)` → **value**

The absolute value of a number

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'abs(num(BASE) - num(AVAL)) > 0.001'
```

**Missing and empty** — A missing input yields **that missing value**, identity kept (`abs(.A)` is `.A`, rulings register `D85c`) — also through `num`: `abs(num(X))` keeps `X`'s `.A`. A non-numeric or empty input yields the plain missing (`MIS`).

⛔ **Type error on a character column.** The operand must be numeric; a character column raises a rule ERROR rather than being parsed. Convert it explicitly with `num(x)` if the text is meant as a number.

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `abs(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element's absolute value, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### between

`between(x, lo, hi)` → **boolean**

True when `lo <= x <= hi`, inclusive

| parameter | type | required | description |
|---|---|---|---|
| `x` | number | yes | The value to read |
| `lo` | number | yes | The lower bound |
| `hi` | number | yes | The upper bound |

**Example**

```yaml
expression: 'not empty(PARAMCD) and not between(char(PARAMCD), char("A"), char("Z"))'
```

⛔ **Type error on a character column.** All three operands must be numeric; a character column raises a rule ERROR. Convert with `num(x)`.

### ceil

`ceil(x)` → **value**

The smallest integer not less than `x`

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'ceil(ADY)'
```

**Missing and empty** — As [`abs`](#abs): a missing input yields **that missing value**, identity kept (rulings register `D85c`); a non-numeric or empty input yields the plain missing (`MIS`).

⛔ **Type error on a character column.** The operand must be numeric; a character column raises a rule ERROR rather than being parsed. Convert it explicitly with `num(x)` if the text is meant as a number.

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `ceil(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element rounded up, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### floor

`floor(x)` → **value**

The largest integer not greater than `x`

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'floor(ADY)'
```

**Missing and empty** — As [`abs`](#abs): a missing input yields **that missing value**, identity kept (rulings register `D85c`); a non-numeric or empty input yields the plain missing (`MIS`).

⛔ **Type error on a character column.** The operand must be numeric; a character column raises a rule ERROR rather than being parsed. Convert it explicitly with `num(x)` if the text is meant as a number.

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `floor(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element rounded down, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### is_integer

`is_integer(x)` → **boolean**

True when the value is a finite whole number. A negated spelling `is_not_integer(x)` exists; prefer `not is_integer(x)`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**List form (element-wise)** — over a list at `x` — the cells of several columns
([`values`](#values): `is_integer(values([X, Y]))`), a per-row list such as `split_by(…)`, or a
`$`-list — the predicate answers **one boolean per element** (a list of booleans), consumed only by
[`any`](#any) / [`all`](#all) / [`none`](#none), directly or through a binding (`$ok: is_integer(values([X,
Y]))`, then `all($ok)`); a missing element gets the predicate's own answer on a missing. Written as a
condition on its own, compared, tested with `in`, passed to another function or reported, the list
form fails the rule's load — quantify it. `[X, Y]` with bare names is a list of column *names*: read
the values with `values([X, Y])`. `is_not_integer(x)` lifts the same way.

**Example**

```yaml
expression: 'not empty(PARAMN) and not is_integer(PARAMN)'
```

**Missing and empty** — `""` is not a whole number, so this is **false** for it — and the negated spelling therefore **fires** on a blank.

### is_numeric

`is_numeric(x)` → **boolean**

True when the value is a decimal number; there is no `is_not_numeric` — write `not is_numeric(x)`

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**List form (element-wise)** — over a list at `x` — the cells of several columns
([`values`](#values): `is_numeric(values([X, Y]))`), a per-row list such as `split_by(…)`, or a
`$`-list — the predicate answers **one boolean per element** (a list of booleans), consumed only by
[`any`](#any) / [`all`](#all) / [`none`](#none), directly or through a binding (`$ok: is_numeric(values([X,
Y]))`, then `all($ok)`); a missing element gets the predicate's own answer on a missing. Written as a
condition on its own, compared, tested with `in`, passed to another function or reported, the list
form fails the rule's load — quantify it. `[X, Y]` with bare names is a list of column *names*: read
the values with `values([X, Y])`.

**Example**

```yaml
expression: 'is_numeric(--DOSTXT)'
```

**Missing and empty** — `""` is not a number, so this is **false** for it.

### minus

`minus(value, subtract=)` → **value (a list)**

The set difference of two lists — the members of `value` that are not in `subtract`, in `value`'s order.

| parameter | type | required | description |
|---|---|---|---|
| `value` | list | yes | The minuend — any dataset-level list: a `$`-binding holding one, a list literal of string literals (`["AGEU", "SDESIGN"]`), or the expression such a binding would hold, written inline (`distinct(X)`, `union(…)`) |
| `subtract` | list | yes | The subtrahend — the same spellings |

A **registry function**. Both operands are **dataset-level** lists, and an operand is judged by its **level**, never by its spelling: a `$`-binding holding a dataset-level list, a list literal, or the expression such a binding would hold written inline — `distinct(X)`, another `minus(…)` / `union(…)`, `upper($names)` over a dataset-level `$names` — are all operands (where `$x` is accepted, the expression `$x` holds is accepted too). An operand whose value varies **per row or per variable** — a column `X`, `split_by(X, ",")`, `distinct(…, group=[…])`, a per-variable value — has no row to pick the value at and is a load error, **the same finding whether bound or inline** (*"the list function minus(…) … cannot read the per-row or per-variable operand `…` — a set difference reads only dataset-level lists"*). A list literal must hold string literals — a bare `[AGEU]` names a column and fails to load. Operands are **lists only** (as for `union`): a **scalar**-typed operand (`upper(X)`, `extract_metadata(…)`) fails to load (*"argument 'value' of 'minus' takes list<unknown>, not string"*), and an operand that is a single value at evaluation is a rule ERROR (*"minus(…) operand `value` is not a list (X): a set difference reads lists only — a single value is written as its singleton list literal ["X"]"*). It is an **aggregate**: one list for the dataset. Members compare as set elements: a text against a text, a missing value by its identity, and a row tuple (from `distinct([A, B], …)`) **structurally**, component by component — never by its printed form.

**Example**

```yaml
Bindings:
- name: "$untraceable"
  expression: 'minus($ae_keys, subtract=$adae_keys)'
Check:
  expression: 'not empty($untraceable)'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$missing"
  expression: 'minus(value=["AGEU", "SDESIGN"], subtract=$present)'
Check:
  expression: 'not empty($missing)'
```

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**. An empty minuend answers the empty set (its `subtract` is still read: a single value there is the rule ERROR above).


### union

`union(first, second, …)` → **value (a list)**

The set union of two or more lists — every member of `first` in its order, then each member of the later operands not yet seen; a member occurs once, its first occurrence kept.

| parameter | type | required | description |
|---|---|---|---|
| `first` | list | yes | The first operand — any dataset-level list: a `$`-binding holding one, a list literal of string literals, or the expression such a binding would hold, written inline (`distinct(X)`, `minus(…)`) |
| `second` | list | yes | The second operand — the same spellings |
| `more` | list of lists (absorbs the remaining arguments) | no | Any further operands, the same spellings |

A **registry function**, the set partner of [`minus`](#minus). Every operand is a **dataset-level** list, judged by its **level**, never by its spelling: a `$`-binding holding one (typically the answer of another list function), a list literal of string literals, or the expression such a binding would hold written inline (`union(distinct(X), $b)`, `union(minus(…), $b)`). An operand computed **per row or per variable** (`X`, `split_by(X, ",")`, `distinct(…, group=[…])`, a per-variable value) is refused at load, the same finding bound or inline (*"the list function union(…) … cannot read the per-row or per-variable operand `…` — a set union reads only dataset-level lists"*); a **scalar**-typed operand (`upper(X)`, `extract_metadata(…)`) is additionally a type finding (*"takes list<unknown>, not string"*). A list literal must hold string literals — a bare `[AGEU]` names a column and fails to load, at every operand position. Operands are **lists only**: a single value is written as its singleton literal `["X"]`; a scalar operand is a rule ERROR at evaluation (as for `minus`). It is an **aggregate**: one list for the dataset. Members compare as set elements, exactly as in `minus`: a text against a text, a missing value by its identity, a row tuple **structurally** — and one union holds one element shape (texts, or tuples of one arity; a mixed union is a rule ERROR). Positional arguments only: the third operand on is collected by `more`, and a named argument (`first=`, `more=`, …) fails to load (*"'union' takes positional arguments only — a named argument (first=) cannot address a collector's elements …"*).

**Example**

```yaml
Bindings:
- name: "$all_keys"
  expression: 'union($ae_keys, $adae_keys)'
Check:
  expression: 'USUBJID not in $all_keys'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$permitted"
  expression: 'union($published, ["STUDYID", "DOMAIN"])'
Check:
  expression: 'not contains_all($permitted, $present)'
```

**When there is nothing to answer** — **the empty set** (every operand empty). `X in $u` is then vacuously false; `X not in $u` is vacuously true and **fires**.


### round

`round(x)` → **value**

The value rounded to the nearest integer, a half rounded up toward +∞ (`round(-2.5)` is `-2`)

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'round(AGE) == AGE'
```

**Missing and empty** — As [`abs`](#abs): a missing input yields **that missing value**, identity kept (rulings register `D85c`); a non-numeric or empty input yields the plain missing (`MIS`).

⛔ **Type error on a character column.** The operand must be numeric; a character column raises a rule ERROR rather than being parsed. Convert it explicitly with `num(x)` if the text is meant as a number.


## Dates and times

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `round(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element rounded, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### date_contains

`date_contains(outer, inner)` → **boolean**

True when every instant `inner` could denote lies inside `outer`'s range

| parameter | type | required | description |
|---|---|---|---|
| `outer` | date | yes | The containing value |
| `inner` | date | yes | The value that must lie inside |

**Example**

```yaml
expression: 'date_contains(date(RFSTDTC), date(AESTDTC))'
```

### date_diff_days

`date_diff_days(name, reference)` → **value**

The calendar days from a reference date to a record's date — `date − reference`, with **no** `+1` (unlike `dy`)

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | yes | The record's date column (`--DTC`, `--STDTC`, `--ENDTC`), or a column read through a declared join (`PM.PMDTC`) |
| `reference` | value | yes | The date to measure from: a column of the record, a column read through the rule's declared join, or a **named aggregation** — `min_date(…)` / `max_date(…)` over another dataset |

Both arguments are read **per record**. A reference that is the earliest (or latest) date of a group in another dataset is written as that aggregation, `min_date(SJSTDTC, domain="SJ", group=[USUBJID, RPHASE])` — its `domain=`, `group=`, `filter=`, `keep_missings=` and `missing_values=` belong to [`min_date`](#min_date) / [`max_date`](#max_date), never to `date_diff_days`. An offset is plain arithmetic — a constant (`date_diff_days(…) + 1`) or a value read from another dataset (`+ $rprfdy`). A date read from a *matched record of another dataset* is a declared join and a dotted `name` (`Match_Datasets` on the matching keys, then `PM.PMDTC`). A quoted name (`"--DTC"`) is a string literal, never a column, and fails to load. The keywords `domain=`, `group=`, `reference="…"`, `offset=`, `reference_extreme=`, `missing_values=`, `minuend_domain=` and `minuend_match=` are each a load error on `date_diff_days`; write what they meant as above.

**Example**

```yaml
Match_Datasets:
- Name: "DM"
  Keys: ["USUBJID"]
Bindings:
- name: "$sj_stages"
  expression: 'record_count(domain="SJ", group=[USUBJID, RPHASE])'
- name: "$rprfdy"
  expression: 'read_value(RPRFDY, domain="TP", group=[DM.RPATHCD, RPHASE], mode="MAX")'
- name: "$rpref_day"
  expression: 'date_diff_days(--ENDTC, min_date(SJSTDTC, domain="SJ", group=[USUBJID, RPHASE])) + $rprfdy'
Check:
  expression: 'is_complete_date(--ENDTC) and not empty(--RPENDY) and $sj_stages > 0 and not empty($rprfdy) and --RPENDY != $rpref_day'
```

The repro-phase day of CDISC-SEND-0403: the reference is the earliest `SJSTDTC` of the record's `RPHASE`, and the offset is the phase's first day reference `RPRFDY` (0 or 1) of the subject's own path, read from `TP` through the qualified [`read_value`](#read_value) `group=` member `DM.RPATHCD` — the `RPATHCD` of the record's bound `DM` record. `$sj_stages > 0` and `not empty($rprfdy)` leave a record whose phase has no SJ stage or no TP offset unjudged.

**With an offset**

```yaml
Bindings:
- name: "$days_from_first_dose"
  expression: 'date_diff_days(--DTC, min_date(EXSTDTC, domain="EX", group=[USUBJID])) + 1'
Check:
  expression: 'is_complete_date(--DTC) and not empty(TFDETECT) and TFDETECT != $days_from_first_dose'
```

**When there is nothing to answer** — **missing** — the date or the reference lacks a complete `yyyy-MM-dd` part, a column is absent, or the aggregation has no answer for the record's group (no group, an absent dataset, an [indeterminate](#max_date) extreme). A missing input's own missing is handed through. `empty()` on it is true, and a populated column compared with `!=` against it **fires**: a derived day whose inputs cannot support it is reported, not skipped (EC-45) — declare the reference dataset's columns in `Requirements` if the rule should not run without them.


### date_overlaps

`date_overlaps(a, b)` → **boolean**

True when the two date ranges share at least one possible instant

| parameter | type | required | description |
|---|---|---|---|
| `a` | date | yes | The first value |
| `b` | date | yes | The second value |

**Example**

```yaml
expression: 'not date_overlaps(date(DSSTDTC), date(DM.DTHDTC))'
```

### day

`day(x)` → **value**

The day component of an ISO value, or missing

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'is_complete_date(AESTDTC) and day(AESTDTC) > 28'
```

**Missing and empty** — A missing input yields **that missing value**, identity kept (`day(.A)` is `.A`, rulings register `D85c`). `""` carries no date component and yields the plain missing (`MIS`).

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `day(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element's day, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### duration_between

`duration_between(start, end)` → **duration**

The duration from one ISO-8601 instant to another — `end − start` — as a value of the
[`duration`](#duration) family, so it compares under the hull rule from either side:
`duration(--DUR) != duration_between(--DTC, --ENDTC)` fires only where `--DUR` is *definitely* not
the difference (SENDIG 3.1.1 §4.4.3: `--DUR` is the difference between `--ENDTC` and `--DTC`). Each
instant is **its own hull** — a date spans its day, a minute-precision datetime its minute, a
partial date its month or year — so the answer is the **range** of durations the two could span,
written `lower/upper`, the family's own carrier: `2020-01-01` to `2020-01-03` is anything from
`P1DT1S` to `P2DT23H59M59S`, `2020-01-01` to `2020-01-02T10:00` from `PT10H1S` to `P1DT10H59S`
(so `PT34H` is not *definitely* different from it), `2020-01` to `2020-02-15` from `P14DT1S` to
`P45DT23H59M59S`; an end before the start is negative. Only two instants at **second** precision
give a point (`2020-01-01T00:00:00` to `2020-01-02T12:00:00` is `P1DT12H`), so `==` against
`duration_between` holds only there — `!=` is the comparison that reads the rule's intent. The
carrier is read as a range only where it is `duration_between`'s own result (written in place,
inside `duration(…)` or through a binding holding it); it is not a valid ISO duration for
`is_valid_duration`, and a data cell spelled `PT1H/PT2H` is junk to the family.

| parameter | type | required | description |
|---|---|---|---|
| `start` | any | yes | The first instant — a column, a `date(…)` conversion or a binding |
| `end` | any | yes | The second instant, likewise |

**Example**

```yaml
expression: 'not empty(--DTC) and not empty(--ENDTC) and not empty(--DUR) and duration(--DUR) != duration_between(--DTC, --ENDTC)'
```

**Missing and empty** — A missing operand yields **that missing value**, identity kept (both missing
carry their identity; `.A` beside `.B` is `MIS`). `""` or an instant that cannot be placed on the
calendar yields the plain missing (`MIS`), so `empty(duration_between(…))` is true for it.

### dy

`dy(name, reference)` → **value**

The SDTM study day of a record's date relative to a reference date; there is no day 0

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | yes | The record's date column (`--DTC`, `--STDTC`, `--ENDTC`) |
| `reference` | column | yes | The reference date, read through the rule's declared join: `DM.RFSTDTC`, `DM.RFXSTDTC` or `DM.RFCSTDTC` |

Both arguments are **column references**. The reference is read per row through the `Match_Datasets` join the rule declares (`DM` on `USUBJID`), so on a subject that `DM` carries twice every row copy the join makes reads its own `DM` record — the same one the Check reads through `DM.RFSTDTC`. A quoted name (`reference="RFXSTDTC"`) is a string literal, never a column, and fails to load; so does a call with one argument.

**Example**

```yaml
Match_Datasets:
- Name: "DM"
  Keys: ["USUBJID"]
Bindings:
- name: "$value_xstdy_algorithm"
  expression: 'dy(--STDTC, DM.RFXSTDTC)'
Check:
  expression: 'not empty(--XSTDY) and is_complete_date(--STDTC) and is_complete_date(DM.RFXSTDTC) and --XSTDY != $value_xstdy_algorithm'
```

**Minimal — required parameters only**

```yaml
Match_Datasets:
- Name: "DM"
  Keys: ["USUBJID"]
Bindings:
- name: "$value_dy_algorithm"
  expression: 'dy(--DTC, DM.RFSTDTC)'
Check:
  expression: 'not empty(--DY) and is_complete_date_part(--DTC) and is_complete_date_part(DM.RFSTDTC) and --DY != $value_dy_algorithm'
```

**When there is nothing to answer** — **missing** — the date or the reference lacks a complete `yyyy-MM-dd` part, or a column is absent. `!=` against it fires (a missing is not equal to any value — rulings register `D34a`); `==` is true only against the same missing; an order comparison treats it as the lowest value (`D34 #5`): `X > it` and `it < X` fire, `X < it` does not; `empty()` is true.

**Missing and empty** — A missing date or reference yields **that missing value**, decided before the dates are parsed: with `X` = `.A`, `dy(X, R)` is `.A` whatever present value `R` holds (even an incomplete date), and with `X` = `.A`, `R` = `.B` it is the plain missing (rulings register `D85c` / `D86a`). Only present but incomplete or unparsable dates give the plain missing (`MIS`).


### earliest_date

`earliest_date(a, b)` → **value**

The earlier of two dates — exactly what [`min_date`](#min_date) answers over a group holding the two cells `a` and `b`: the same candidate rule and the same determinability rule, in the same accumulator, so the two can never drift. Written for a SEND subject's first dose, which is the earlier of the subject's own first dose and its pools' (recipe 9).

| parameter | type | required | description |
|---|---|---|---|
| `a` | date | yes | The first date — a column, a `date(…)` conversion, or a date-valued binding such as a `min_date(…)` result. Positional, or by name (`a=`) |
| `b` | date | yes | The second date, likewise. Positional, or by name (`b=`) |

Exactly two arguments; more than two nest (`earliest_date(a, earliest_date(b, c))`), which is exact for determinate operands but not the three-way extreme once an inner pair is undeterminable. A string literal argument fails the rule's load (a type error, `stage A: PARAMETER_TYPE`), as does a number literal — write `earliest_date(date("2012-06"), date("2012-06-01"))`. The result is a **date**, so it meets `date(X) != …` and `date(X) == …` as a date. A **numeric column** is not refused: a number is never a complete calendar date, so it never wins — beside a date it makes the answer undeterminable (no answer) unless its text reads as a year (`2020`), which it then is — a partial date the other date can still beat — exactly as inside `min_date`. When the two arguments differ in column type (a numeric `EXSTDTC` read from an Excel serial beside a character one), the answer is typed as text, which only a character date or a missing can be. A numeric column passed directly as an argument is **observed** as a numeric date read, not an error — the same observe-only treatment `date()` over a numeric column has (`D55`). There is no `missing_values=` parameter: a per-part `indeterminate` belongs on the grouped call that feeds it.

**Determinability (EC-46)** — as `min_date`: the determined candidate that reaches every possible completion of the other wins, and its own cell is the answer. `earliest_date(date("2012-06"), date("2012-06-01"))` is `2012-06-01`; `earliest_date(date("2012-06"), date("2012-06-02"))` has no answer (the partial could be the 1st); two partials have no answer; a present value that cannot be positioned (`UNK`, `2020-02-30`) makes the answer undeterminable. On equal text `a` wins.

**Example**

```yaml
# CDISC-SEND-0305
Bindings:
- name: "$min_ex_exstdtc"
  expression: 'min_date(EXSTDTC, domain="EX", group=[USUBJID])'
- name: "$pool_min_exstdtc"
  expression: 'min_date(min_date(EXSTDTC, domain="EX", group=[POOLID], filter=(not empty(POOLID))), domain="POOLDEF", group=[USUBJID])'
- name: "$first_exstdtc"
  expression: 'earliest_date($min_ex_exstdtc, $pool_min_exstdtc)'
Check:
  expression: 'not empty(RFXSTDTC) and (USUBJID in $ex_usubjid or $pool_ex_rows > 0) and date(RFXSTDTC) != $first_exstdtc'
```

**Missing and empty** — The extremes' candidate rule, **not** propagation: a missing argument (any identity) and a blank or whitespace-only argument are not candidates, and the other argument decides alone — `earliest_date(.A, date("2020-01-01"))` is `2020-01-01`. So a grouped extreme that has no answer (a subject in no pool, an undeterminable pool) is skipped and the other part is taken. Only when **both** arguments are missing is their identity carried: `.A` and `.A` give `.A`, two distinct identities give the plain missing (rulings register `D85c` / `D86a`). Any other case with no answer — two blanks, a missing beside a blank, an undeterminable pair — is the plain missing (`MIS`). `!=` against a missing answer fires (`D34a`); `empty()` on it is true.

### earliest_possible

`earliest_possible(x)` → **value**

The earliest instant an incomplete value could denote

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'not empty(MHENDTC) and date(latest_possible(MHENDTC)) >= date(earliest_possible(DM.RFSTDTC))'
```

**Missing and empty** — A missing input yields **that missing value**, identity kept (rulings register `D85c`). `""`, and a value that cannot be placed on the calendar, yield the plain missing (`MIS`) — never a sentinel date.

**Over a [`duration`](#duration)** — `earliest_possible(duration(X))` / `latest_possible(duration(X))` answer the
shortest / longest duration `X` could denote, as point durations: `P1M` bounds as `P28D` and `P31D`,
`P1Y` as `P365D` and `P366D`, a point (`P1W`) as itself (`P7D`). The written `duration(…)` marker
is what selects the duration reading — `latest_possible(X)` on the same cell reads a **date**, and no
duration places on the calendar, so it answers missing. Compare the bound under the marker:
`duration(latest_possible(duration(--STINT))) > duration(--ENINT)` is the "possibly later" reading.

⚠ **A bound of a complete value is the value itself**, and two complete values compare as points at the coarser precision — so `date(earliest_possible(A)) < latest_possible(B)` answers exactly `date(A) < date(B)` on complete dates and date-times. A "may" reading of a same-day boundary (an element that ends on the day a date-only visit starts *may* still be in progress) needs its own term; `CDISC-CG0218` spells it as `… < … or (date(earliest_possible(SVSTDTC)) == latest_possible(SE.SEENDTC) and (SVSTDTC !~ /T/ or SE.SEENDTC !~ /T/))` — the same-day equality on the bounds (so a partial value whose first or last possible day is the boundary day counts too), granted only when at least one side carries no time.

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `earliest_possible(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element's earliest possible instant, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### interval_uncertainty_precision_mismatch

`interval_uncertainty_precision_mismatch(name, delimiter=)` → **boolean**

True on a row whose ISO 8601 interval of uncertainty (`a/b`) states its two halves at different precisions. Each half is measured after its timezone offset and fractional seconds are stripped, so an offset on one side only is not a precision difference. A value without the delimiter, a blank half, and a blank or missing value are `false`.

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | yes | The interval column (`--DTC`, `--STDTC`, `--ENDTC`) |
| `delimiter` | string | no | The separator between the halves; default `"/"`. Read per row, so any expression of string type may supply it; a missing or empty one is `"/"` |

`name` is a **column reference**; a quoted name (`"LBDTC"`) is a string literal and fails to load. An absent column is a column of blanks, so the rule runs and fires on no row.

**Example**

```yaml
Bindings:
- name: "$interval_mismatch_stdtc"
  expression: 'interval_uncertainty_precision_mismatch(--STDTC, delimiter="/")'
Check:
  expression: '$interval_mismatch_stdtc == true'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$interval_mismatch_dtc"
  expression: 'interval_uncertainty_precision_mismatch(--DTC)'
Check:
  expression: '$interval_mismatch_dtc == true'
```

### is_complete_date

`is_complete_date(x)` → **boolean**

True when every date component is present

| parameter | type | required | description |
|---|---|---|---|
| `x` | string | yes | The value to read |

**List form (element-wise)** — over a list at `x` — the cells of several columns
([`values`](#values): `is_complete_date(values([X, Y]))`), a per-row list such as `split_by(…)`, or a
`$`-list — the predicate answers **one boolean per element** (a list of booleans), consumed only by
[`any`](#any) / [`all`](#all) / [`none`](#none), directly or through a binding (`$ok: is_complete_date(values([X,
Y]))`, then `all($ok)`); a missing element gets the predicate's own answer on a missing. Written as a
condition on its own, compared, tested with `in`, passed to another function or reported, the list
form fails the rule's load — quantify it. `[X, Y]` with bare names is a list of column *names*: read
the values with `values([X, Y])`. `is_incomplete_date(x)` lifts the same way.

**Example**

```yaml
expression: 'is_complete_date(--DTC)'
```

### is_complete_date_part

`is_complete_date_part(x)` → **boolean**

True when the leading `YYYY-MM-DD` portion is complete, ignoring any time. A negated spelling `is_not_complete_date_part(x)` exists; prefer `not is_complete_date_part(x)`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | string | yes | The value to read |

**List form (element-wise)** — over a list at `x` — the cells of several columns
([`values`](#values): `is_complete_date_part(values([X, Y]))`), a per-row list such as `split_by(…)`, or a
`$`-list — the predicate answers **one boolean per element** (a list of booleans), consumed only by
[`any`](#any) / [`all`](#all) / [`none`](#none), directly or through a binding (`$ok: is_complete_date_part(values([X,
Y]))`, then `all($ok)`); a missing element gets the predicate's own answer on a missing. Written as a
condition on its own, compared, tested with `in`, passed to another function or reported, the list
form fails the rule's load — quantify it. `[X, Y]` with bare names is a list of column *names*: read
the values with `values([X, Y])`. `is_not_complete_date_part(x)` lifts the same way.

**Example**

```yaml
expression: 'not is_complete_date_part(--ENDTC)'
```

### is_partial_date

`is_partial_date(x)` → **boolean**

True when the value is calendar-valid but has missing components. Also spelled `is_incomplete_date`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | string | yes | The value to read |

**List form (element-wise)** — over a list at `x` — the cells of several columns
([`values`](#values): `is_partial_date(values([X, Y]))`), a per-row list such as `split_by(…)`, or a
`$`-list — the predicate answers **one boolean per element** (a list of booleans), consumed only by
[`any`](#any) / [`all`](#all) / [`none`](#none), directly or through a binding (`$ok: is_partial_date(values([X,
Y]))`, then `all($ok)`); a missing element gets the predicate's own answer on a missing. Written as a
condition on its own, compared, tested with `in`, passed to another function or reported, the list
form fails the rule's load — quantify it. `[X, Y]` with bare names is a list of column *names*: read
the values with `values([X, Y])`. `is_incomplete_date(x)` lifts the same way.

**Example**

```yaml
expression: 'is_partial_date(AESTDTC)'
```

### is_valid_date

`is_valid_date(x)` → **boolean**

True when the value is a calendar-valid ISO date at any precision. A negated spelling `invalid_date(x)` exists; prefer `not is_valid_date(x)`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | string | yes | The value to read |

**List form (element-wise)** — over a list at `x` — the cells of several columns
([`values`](#values): `is_valid_date(values([X, Y]))`), a per-row list such as `split_by(…)`, or a
`$`-list — the predicate answers **one boolean per element** (a list of booleans), consumed only by
[`any`](#any) / [`all`](#all) / [`none`](#none), directly or through a binding (`$ok: is_valid_date(values([X,
Y]))`, then `all($ok)`); a missing element gets the predicate's own answer on a missing. Written as a
condition on its own, compared, tested with `in`, passed to another function or reported, the list
form fails the rule's load — quantify it. `[X, Y]` with bare names is a list of column *names*: read
the values with `values([X, Y])`. `invalid_date(x)` lifts the same way.

**Example**

```yaml
expression: 'not is_valid_date(--DTC)'
```

**Missing and empty** — `""` is not a calendar-valid date, so this is **false** for it — and `invalid_date("")` fires.

### is_valid_duration

`is_valid_duration(x)` → **boolean**

True when the value is a valid ISO-8601 duration. A negated spelling `invalid_duration(x[, negative=])` exists; prefer `not is_valid_duration(x)`. `negative=` decides whether a negative duration counts.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**List form (element-wise)** — over a list at `x` — the cells of several columns
([`values`](#values): `is_valid_duration(values([X, Y]))`), a per-row list such as `split_by(…)`, or a
`$`-list — the predicate answers **one boolean per element** (a list of booleans), consumed only by
[`any`](#any) / [`all`](#all) / [`none`](#none), directly or through a binding (`$ok: is_valid_duration(values([X,
Y]))`, then `all($ok)`); a missing element gets the predicate's own answer on a missing. Written as a
condition on its own, compared, tested with `in`, passed to another function or reported, the list
form fails the rule's load — quantify it. `[X, Y]` with bare names is a list of column *names*: read
the values with `values([X, Y])`. `invalid_duration(x[, negative=])` lifts the same way (`negative=` applies to every element).

**Example**

```yaml
expression: 'is_valid_duration(--ELTM)'
```

**Missing and empty** — `""` is not a valid duration, so this is **false** for it.

### latest_date

`latest_date(a, b)` → **value**

The later of two dates — exactly what [`max_date`](#max_date) answers over a group holding the two cells `a` and `b`, in the other direction from [`earliest_date`](#earliest_date), whose parameters, nesting, typing and missing rules it shares. Written for a SEND subject's last dose (`CDISC-SEND-0306`: the later of the subject's own latest `EXENDTC` and its pools').

| parameter | type | required | description |
|---|---|---|---|
| `a` | date | yes | The first date — a column, a `date(…)` conversion, or a date-valued binding. Positional, or by name (`a=`) |
| `b` | date | yes | The second date, likewise. Positional, or by name (`b=`) |

**Determinability (EC-46)** — as `max_date`: `latest_date(date("2012-06"), date("2012-06-30"))` is `2012-06-30`; `latest_date(date("2012-06"), date("2012-06-15"))` has no answer (the partial could be the 30th). A date beside a datetime of the same day answers the date, whose hull ends at the end of the day.

**Example**

```yaml
# CDISC-SEND-0306
Bindings:
- name: "$latest_exendtc"
  expression: 'max_date(EXENDTC, domain="EX", missing_values="indeterminate", group=[USUBJID])'
- name: "$pool_latest_exendtc"
  expression: 'max_date(max_date(EXENDTC, domain="EX", missing_values="indeterminate", group=[POOLID], filter=(not empty(POOLID))), domain="POOLDEF", group=[USUBJID])'
- name: "$last_exendtc"
  expression: 'latest_date($latest_exendtc, $pool_latest_exendtc)'
Check:
  expression: 'not empty(RFXENDTC) and (USUBJID in $ex_subjects or $pool_ex_rows > 0) and date(RFXENDTC) != $last_exendtc and date(RFXENDTC) != $last_exstdtc'
```

**Missing and empty** — As [`earliest_date`](#earliest_date): a missing or blank argument is skipped and the other decides; both missing carry their identity (`.A` + `.A` is `.A`, `.A` + `.B` is `MIS`); any other no-answer is the plain missing, and `!=` against it fires.

### latest_possible

`latest_possible(x)` → **value**

The latest instant an incomplete value could denote

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
Check:
  expression: 'date(earliest_possible(--STDTC)) > latest_possible(--ENDTC)'
```

**Missing and empty** — As [`earliest_possible`](#earliest_possible): a missing input yields **that missing value**, identity kept (rulings register `D85c`); `""` and an unplaceable value yield the plain missing (`MIS`). Over a `duration(…)` operand it is the hull's upper end as a point duration (`P1M` ⇒ `P31D`), see there.

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `latest_possible(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element's latest possible instant, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### month

`month(x)` → **value**

The month component of an ISO value, or missing

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'is_complete_date(AESTDTC) and month(AESTDTC) > 12'
```

**Missing and empty** — A missing input yields **that missing value**, identity kept (`month(.A)` is `.A`, rulings register `D85c`). `""` carries no date component and yields the plain missing (`MIS`).

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `month(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element's month, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### time_contains

`time_contains(outer, inner)` → **boolean**

True when every time-of-day `inner` could denote lies inside `outer`'s range

| parameter | type | required | description |
|---|---|---|---|
| `outer` | time | yes | The containing value |
| `inner` | time | yes | The value that must lie inside |

**Example**

```yaml
expression: 'time_contains(time(VSTIM), time(EXTIM))'
```

### time_overlaps

`time_overlaps(a, b)` → **boolean**

True when the two time-of-day ranges share at least one possible instant

| parameter | type | required | description |
|---|---|---|---|
| `a` | time | yes | The first value |
| `b` | time | yes | The second value |

**Example**

```yaml
expression: 'not time_overlaps(time(ATM), time_part(ADTM))'
```

### year

`year(x)` → **value**

The year component of an ISO value, or missing

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'year(BRTHDTC) < 1900'
```

**Missing and empty** — A missing input yields **that missing value**, identity kept (`year(.A)` is `.A`, rulings register `D85c`). `""` carries no date component and yields the plain missing (`MIS`).


## Aggregates over rows and groups

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `year(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element's year, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### distinct

`distinct(name, domain=, filter=, group=, keep_missings=)` → **value (a list)**

The distinct values of a column, or the distinct row tuples over a list of columns — a registry
function on the grouped cross-dataset plan of `max` / `max_date` / `min_date` and `record_count`. Without `group=` it is **one list
for the dataset**; with `group=` (a column target only) it is each row's group's list, read per row
of the evaluated dataset. The answer is a **set by content and a list by carrier**: membership is
what it is for — `X not in $set`, `X in $set`, `tuple(A, B) not in $pairs`, `contains($per_row,
"Y")`, `contains_all($a, $b)`, `minus($a, subtract=$b)`.

| parameter | type | required | description |
|---|---|---|---|
| `name` | column, a value expression, or a **list of columns** | yes | The positional target. A column (`USUBJID`, `--BLFL`, `upper(ARM)`) collects its distinct **values**; a list (`[VISIT, VISITNUM]`) collects the distinct **row tuples** in that order — see [The target of `distinct`](#the-target-of-distinct-one-column-or-a-list-of-columns). A quoted name is a string literal and fails to load. A `$`-binding — bare, or inside a computed target — is one of the rule's own **dataset-level** values, pre-resolved as a `$` inside `filter=` is (`$x: upper("a"); distinct($x)` answers as `distinct(upper("a"))`); a per-row binding there is a load error, and a binding holding a quoted name keeps the literal refusal (the target is not a name slot) |
| `domain` | dataset reference | no | The dataset to read: `TA` or `"TA"`. Absent ⇒ the dataset under evaluation. With `domain`, **every** column argument names a column of that dataset. A family name reads every member: `domain="SUPPAE"` on a study shipping `SUPPAE1` + `SUPPAE2` (both `RDOMAIN = AE`) reads both, as `LB` reads `LB1` + `LB2`. A `--` in the dataset name is the supplemental family of the dataset under evaluation's **domain**: `domain="SUPP--"` reads `SUPPAE` on `AE`, `SUPPLB` on `LBCH` (every SUPP of the LB family) and `SQAPMH` on `APMH` (the SDTMIG-AP name; `SQAP--` is its synonym) — and `SUPPAE` on a `SUPPAE` primary too — as `Match_Datasets` names do |
| `filter` | boolean over the target dataset's columns | no | Which rows are read, in parentheses: `filter=(DSDECOD == "DEATH")`, `filter=(DSDECOD in ["TERMINAL SACRIFICE", "INTERIM SACRIFICE"])`, `filter=(RDOMAIN == "AE" and IDVAR == "AESEQ" and QNAM == "AESOSP")`. Only bare columns of the target dataset — a dotted or `--` reference (under `domain`) is a load error. A `$` reference is one of the rule's own bindings, read as **one dataset-level value of the rule's dataset** (never of the target: `filter=(TSVAL in $c)` with `$c: distinct(FLAG)` compares against the evaluated dataset's `FLAG` values) — a per-row binding there is a load error. The retired map spelling `filter=filter(K="v")` fails to load, naming this one |
| `group` | list of columns | no | Columns whose combined value defines a group — on the target dataset; the list is read per row of the evaluated dataset by the same columns (an absent group column partitions nothing, on both sides). **A column target only**: `group=` beside a list target is a load error (the retired operation ignored it silently). A member may be [qualified](#qualified-group-members) (`DM.RPATHCD`) with `domain`. A member may be a `$`-reference to a dataset-level **list of column names**, or the expression such a binding holds, written in place — spliced exactly as [`record_count`](#record_count)'s `group=` splices it (a dataset-level string is one name; a per-row binding or expression is a load error; a bare `group=$names` / `group=<expression>` is the one-member list); a spliced member is never qualified under `domain`. |
| `keep_missings` | boolean | no | Whether a row with a missing group key stays in its group (default `true`: the blank key forms its own group). Requires `group=` |

**What is a value.** A present, non-blank cell contributes its **text** — a numeric cell renders
as it does in a report (`2`, never `2.0`) and the probe side compares as `==` does, so a numeric
`VISITNUM` meets the text member `2`. A missing cell and a blank `""` are **not** members: the set
of `[S1, "", missing]` is `[S1]`, and a missing probe is a member of nothing. A **tuple** keeps
more: a present component as its text, a **missing component as that missing** (it is not `""`,
not `"."`, not `0` — `(missing, "Day 0")` is not `(0, "Day 0")`), an absent column as its type's
default (`""`; the missing for a column the rule expects numeric), and an `IDVARVAL` component as
its SUPP-- / RELREC join token (`" 1"`, `"01"`, `"1.0"` and `"+1"` are the token `1`), so a
`tuple(USUBJID, AESEQ)` probe meets it. Two tuples are equal component by component, never by
their printed form.

**Example — a value set across datasets**

```yaml
Bindings:
- name: "$scheduled_subjects"
  expression: 'distinct(USUBJID, domain="DS", filter=(DSDECOD in ["TERMINAL SACRIFICE", "INTERIM SACRIFICE", "RECOVERY SACRIFICE", "NON-MORIBUND SACRIFICE"]))'
Check:
  expression: 'USUBJID in $scheduled_subjects'
```

**A tuple set**

```yaml
Bindings:
- name: "$ta_arm_pairs"
  expression: 'distinct([ARMCD, ARM], domain="TA")'
Check:
  expression: 'not empty(ARM) and not empty(ARMCD) and tuple(ARMCD, ARM) not in $ta_arm_pairs'
```

**Grouped, read per row**

```yaml
Bindings:
- name: "$sv_visitnum"
  expression: 'distinct(VISITNUM, domain="SV", group=[USUBJID])'
Check:
  expression: 'not empty(VISITNUM) and VISITNUM not in $sv_visitnum'
```

**Minimal — the dataset's own column**

```yaml
Bindings:
- name: "$domain_list"
  expression: 'distinct(DOMAIN)'
Check:
  expression: '$domain_is_custom == true and prefix(varname(), 2) not in $domain_list'
```

**When there is nothing to answer** — **the empty list**: an absent `domain` dataset, an absent
target column, a group no kept row reaches, a row of the evaluated dataset whose group the target
dataset does not have. `X in $s` is then vacuously false; `X not in $s` is vacuously true and
**fires** — the closed-world reading the operation always had.

⛔ **The group keys are type-checked even when there is nothing to collect.** A grouped call on
another dataset compares its group columns' types with the evaluated dataset's; a character key on
one side and a numeric key of the same name on the other makes the rule **ERROR** rather than
silently match no row — also when the target column is absent from `domain` and every group's
list would be empty.

**Missing and empty** — Always present — a list, never missing. (The former
`distinct(IDVAR, value_is_reference=true)`, which never read `IDVAR`, is
[`referenced_dataset_variables(RDOMAIN)`](#referenced_dataset_variables).)


### empty_within_except_last_row

`empty_within_except_last_row(name, group, by=, keep_missings=)` → **boolean**

True when a row other than the ordered-last one is unpopulated

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |
| `group` | one column reference, or a one-element list | yes | The column whose value defines a group. May be a `$`-reference to a dataset-level string or one-element list of column names, or the expression such a binding holds, written in place — spliced as [`record_count`](#record_count)'s `group=` splices it (a per-row binding or expression is a load error; two or more spliced names make the rule ERROR: the slot takes one column). |
| `by` | one column reference, or a one-element list `[asc(COL)]` / `[COL]` | yes | The sort key, ascending: exactly one column (`by=AESEQ` or `by=[asc(AESEQ)]`), **required** (a call without it fails to load) — `desc(…)`, two keys or a `nulls=` placement fail to load (*"… is not supported for …"*). The rows are ordered as [`sequence`](#sequence) and [`is_sorted_by`](#is_sorted_by) order a sort key: the key is **classified over the group's cells** — numeric when every present cell is a number (`AESEQ` `9` before `10`, in a character column of numeric text too), else ISO dates and datetimes part by part with a UTC offset applied first, then other text, after every dated value — and a blank (`""`, whitespace only) or missing key cell sorts **first**; a tie keeps file order. This is not [`is_last_in_group`](#is_last_in_group)'s order (by the column's type, a character column as text). The retired spelling `ordering=` fails to load with the hint *"ordering= is retired — write by=…"*. Written **by keyword only** — a positional key (`empty_within_except_last_row(…, AESEQ)`) fails to load with the hint *"empty_within_except_last_row takes its sort key by keyword — write by=AESEQ"*. May be a `$`-reference to a dataset-level string or one-element list of column names, or the expression such a binding holds, written in place — spliced as [`record_count`](#record_count)'s `group=` splices it (a per-row binding or expression is a load error; two or more spliced names make the rule ERROR: the slot takes one column). |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |

**Example**

```yaml
Check:
  expression: 'empty_within_except_last_row(AEENDTC, group=[USUBJID], by=AESEQ, keep_missings=false)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'empty_within_except_last_row(AEENDTC, group=[USUBJID], by=AESEQ)'
```


### has_mixed_emptiness_within_group

`has_mixed_emptiness_within_group(name, group=, qualifying_any_populated=, keep_missings=)` → **boolean**

`true` on every row of a group in which the subject column is populated on some rows and unpopulated on others

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | yes | The column whose emptiness is tallied (`BASETYPE`) |
| `group` | list of columns | no | Columns whose combined value defines a group; none, or none present, is one whole-table group. A member may be a `$`-reference to a dataset-level **list of column names**, or the expression such a binding holds, written in place — spliced exactly as [`record_count`](#record_count)'s `group=` splices it (a dataset-level string is one name; a per-row binding or expression is a load error; a bare `group=$names` / `group=<expression>` is the one-member list). |
| `qualifying_any_populated` | list of columns | no | Rows on which none of these columns is populated are out of scope (EC-23); an all-absent list qualifies no row. A member may be a `$`-reference to a dataset-level **list of column names**, or the expression such a binding holds, written in place — spliced exactly as [`record_count`](#record_count)'s `group=` splices it (a dataset-level string is one name; a per-row binding or expression is a load error; a bare `qualifying_any_populated=$names` / `qualifying_any_populated=<expression>` is the one-member list). |
| `keep_missings` | boolean | no | Whether a group whose key is missing is kept as its own group (default `true`, the fold) or dropped; requires a non-empty `group` |

Column parameters are **column references** — a quoted name is a string literal and fails to load — and an absent group column is dropped from the key (EC-44). An absent subject column is all-unpopulated, so no group is mixed (EC-45). The group key is compared by identity (`4.9999999999994` and `5.0` are two groups, `-0.0` and `0.0` one).

**Example**

```yaml
Bindings:
- name: "$basetype_mixed"
  expression: 'has_mixed_emptiness_within_group(BASETYPE, group=[PARAMCD], qualifying_any_populated=[BASE, BASEC])'
Check:
  expression: '$basetype_mixed == true'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$basetype_mixed"
  expression: 'has_mixed_emptiness_within_group(BASETYPE, group=[PARAMCD])'
Check:
  expression: '$basetype_mixed == true'
```

**When there is nothing to answer** — **`false`** — a row in no group, or in a group that is not mixed, is `false`. It is not unknown.

### is_last_in_group

`is_last_in_group(by=, group=, keep_missings=)` → **boolean**

`true` on every row whose ordering value is identical to its group's maximum — the last record of the group

| parameter | type | required | description |
|---|---|---|---|
| `by` | one column reference, or a one-element list `[asc(COL)]` / `[COL]` | yes | The column that orders the group (`SESEQ`), ascending. It orders by its **type**: a numeric column numerically, a character column as **text** — `"10"` < `"9"`, and `"5"` and `"5.0"` (or `"-0.0"` and `"0.0"`) are two values — even when its values look like numbers. A missing value ranks lowest (two missing markers by their value byte), then a blank `""`. The order is total, so the answer never depends on row order. ⚠ This differs from [`sequence`](#sequence) / [`is_sorted_by`](#is_sorted_by), which classify each key over its cells and order a character column whose every present cell is a number numerically; here such a column orders as text. `by` takes a plain column; a numeric order of a character column (`num(X)`) is not spellable here yet. Exactly one key (`by=SESEQ` or `by=[asc(SESEQ)]`), **required** (a call without it fails to load) — `desc(…)`, two keys or a `nulls=` placement fail to load (*"… is not supported for …"*). The retired spelling `ordering=` fails to load with the hint *"ordering= is retired — write by=…"*. A positional key is read as `by`: `is_last_in_group(AESEQ, [USUBJID])` reads as `by=AESEQ`. May be a `$`-reference to a dataset-level string or one-element list of column names, or the expression such a binding holds, written in place — spliced as [`record_count`](#record_count)'s `group=` splices it (a per-row binding or expression is a load error; two or more spliced names make the rule ERROR: the slot takes one column). |
| `group` | list of columns | yes | Columns whose combined value defines a group (`USUBJID`); absent ones are dropped, all absent answers nothing. A member may be a `$`-reference to a dataset-level **list of column names**, or the expression such a binding holds, written in place — spliced exactly as [`record_count`](#record_count)'s `group=` splices it (a dataset-level string is one name; a per-row binding or expression is a load error; a bare `group=$names` / `group=<expression>` is the one-member list). |
| `keep_missings` | boolean | no | Whether a group whose key is missing is kept as its own group or dropped (default `false`, the drop); requires a non-empty `group` |

It takes **no target**: it reads only `by` and `group`. Both are **column references** — `by="SESEQ"` is a string literal and fails to load. "Identical" has one meaning, the key identity: a numeric `-0.0` is `0.0`, `4.9999999999994` is not `5.0`, a character `5` is not `5.0` and a character `-0.0` is not `0.0`, and a missing is identical to the same missing. So **every tied row is last**: the copies a one-to-many `Match_Datasets` join makes of one record, a duplicated `SESEQ` in the source, and every row of a group whose ordering values are all missing.

**Example**

```yaml
Bindings:
- name: "$is_last_se"
  expression: 'is_last_in_group(by=SESEQ, group=[USUBJID], keep_missings=false)'
Check:
  expression: '$is_last_se == true and date(SEENDTC) != date(DS.DSSTDTC)'
```

**When there is nothing to answer** — **`false`** — a row that is not the last of its group, a row dropped for a missing group key, or every row when the ordering column or every group column is absent. It is not unknown.

### max

`max(name, group=, domain=, filter=, keep_missings=)` → **value**

The largest value of a column **per group**, broadcast to every row of the group — a registry
function on the grouped cross-dataset plan that `record_count` and `distinct` share.

| parameter | type | required | description |
|---|---|---|---|
| `name` | column of the target dataset, or any value expression over it | yes | The column to aggregate — bare, never quoted (`max("AyIND", …)` fails to load: a quoted name is a string). An expression is allowed: `max(num(ATOXGR), …)` ranks a character grade numerically. A dataset-level `$`-binding is admitted as on [`distinct`](#distinct): pre-resolved against the rule's dataset; a per-row one is a load error; a binding holding a quoted name keeps the literal refusal |
| `domain` | dataset reference | no | The dataset to read from: `DS` or `"DS"`. Absent ⇒ the dataset under evaluation. With `domain`, **every** column argument names a column of that dataset. A family name reads every member: `domain="SUPPAE"` on a study shipping `SUPPAE1` + `SUPPAE2` (both `RDOMAIN = AE`) reads both, as `LB` reads `LB1` + `LB2`. A `--` in the dataset name is the supplemental family of the dataset under evaluation's **domain**: `domain="SUPP--"` reads `SUPPAE` on `AE`, `SUPPLB` on `LBCH` (every SUPP of the LB family) and `SQAPMH` on `APMH` (the SDTMIG-AP name; `SQAP--` is its synonym) — and `SUPPAE` on a `SUPPAE` primary too — as `Match_Datasets` names do |
| `filter` | boolean over the target dataset's columns | no | Which rows take part, in parentheses: `filter=(ABLFL == "Y")`. Only bare columns of the target dataset — a dotted or `--` reference (under `domain`) is a load error. A `$` reference is one of the rule's own bindings, read as **one dataset-level value of the rule's dataset** (never of the target: `filter=(TSVAL in $c)` with `$c: distinct(FLAG)` compares against the evaluated dataset's `FLAG` values) — a per-row binding there is a load error. The retired `filter=filter(K="v")` spelling fails to load, naming this one |
| `group` | list of columns | **yes** | Columns whose combined value defines a group — on the target dataset; the value is read per row of the evaluated dataset by the same columns (an absent group column partitions nothing, on both sides). A member may be [qualified](#qualified-group-members) (`DM.RPATHCD`) with `domain`. A member may be a `$`-reference to a dataset-level **list of column names**, or the expression such a binding holds, written in place — spliced exactly as [`record_count`](#record_count)'s `group=` splices it (a dataset-level string is one name; a per-row binding or expression is a load error; a bare `group=$names` / `group=<expression>` is the one-member list); a spliced member is never qualified under `domain`. |
| `keep_missings` | boolean | no | Whether a row with a missing group key stays in its group (default `true`: the blank key forms its own group) |

**One positional argument.** The target is the only positional; every other argument is a keyword. A second
positional (`max(DSSTDY, DS, group=[USUBJID])`) is a load error naming the keyword spelling
(*"max takes one positional argument, its target; got 2 — write the others as keywords: …"*),
never a silent binding to `domain`. The same holds for `max_date` and `min_date`.

**Ranking by type.** Candidates are the populated cells (a missing, empty or whitespace-only cell
is no candidate). A numeric column ranks numerically; a character column ranks as **text** —
`"9"` is above `"10"`, and `"3"` and `"3.0"` are two different values (a string is a string,
`D-W1-2a`). The answer is the winning **cell**, with the column's own type, so a comparison against
it is numeric for a numeric column and textual for a character one. Numeric order of a character
grade is spelled `max(num(ATOXGR), …)`. **Text is text** (no special
handling for special texts): a character value that looks like a date ranks as text like any
other — `max` over `2024-01` and `2024-01-15` answers `2024-01-15` — and never takes the date rule;
`max` is not the function for dates — author `max_date`, which is.

**Example**

```yaml
Bindings:
- name: "$baseline_ayind"
  expression: 'max(AyIND, group=[USUBJID, PARAMCD, BASETYPE, SPDEVID], filter=(ABLFL=="Y"))'
Check:
  expression: 'var_exists("BASETYPE") and not empty(ByIND) and ByIND != $baseline_ayind'
```

**Another dataset**

```yaml
Bindings:
- name: "$max_ds_dsstdy"
  expression: 'max(DSSTDY, domain="DS", group=[USUBJID])'
Check:
  expression: 'USUBJID in $ds_usubjid and not empty(--DY) and not empty($max_ds_dsstdy) and --DY != $max_ds_dsstdy'
```

**When there is nothing to answer** — **missing** — for a group with no candidate (all cells
missing or blank, or a filter that keeps no row), and for a row of the evaluated dataset whose key
has no group (a subject with no `DS` row). An **absent `domain` dataset**, or a target **column the
dataset does not carry**, answers what that column would answer read directly at the call's
position: the missing where the call
— or the binding it is bound to — is read as a number (`--DY > $max_ds_dsstdy`), `""` everywhere else
(`empty()` is true either way; until 2026-10-05 the missing everywhere). A `max` over a computed
target keeps the missing. `!=` against the missing fires (a missing is not equal to any value — rulings register `D34a`); `==` is true only against the same missing; an order comparison treats it as the lowest value (`D34 #5`): `X > it` and `it < X` fire, `X < it` does not; `empty()` is true. A group whose inputs are all missing carries their missing
identity.


### max_date

`max_date(name, group=, domain=, filter=, keep_missings=, missing_values=)` → **value**

The latest date in a column **per group**, broadcast to every row of the group; a value that
cannot be positioned yields no value. A registry function; the parameters are
`max`'s, plus `missing_values`:

| parameter | type | required | description |
|---|---|---|---|
| `name` | column of the target dataset, or any value expression over it | yes | The column to aggregate — bare, never quoted (`max("AyIND", …)` fails to load: a quoted name is a string). An expression is allowed: `max(num(ATOXGR), …)` ranks a character grade numerically. A dataset-level `$`-binding is admitted as on [`distinct`](#distinct): pre-resolved against the rule's dataset; a per-row one is a load error; a binding holding a quoted name keeps the literal refusal |
| `domain` | dataset reference | no | The dataset to read from: `DS` or `"DS"`. Absent ⇒ the dataset under evaluation. With `domain`, **every** column argument names a column of that dataset. A family name reads every member: `domain="SUPPAE"` on a study shipping `SUPPAE1` + `SUPPAE2` (both `RDOMAIN = AE`) reads both, as `LB` reads `LB1` + `LB2`. A `--` in the dataset name is the supplemental family of the dataset under evaluation's **domain**: `domain="SUPP--"` reads `SUPPAE` on `AE`, `SUPPLB` on `LBCH` (every SUPP of the LB family) and `SQAPMH` on `APMH` (the SDTMIG-AP name; `SQAP--` is its synonym) — and `SUPPAE` on a `SUPPAE` primary too — as `Match_Datasets` names do |
| `filter` | boolean over the target dataset's columns | no | Which rows take part, in parentheses: `filter=(ABLFL == "Y")`. Only bare columns of the target dataset — a dotted or `--` reference (under `domain`) is a load error. A `$` reference is one of the rule's own bindings, read as **one dataset-level value of the rule's dataset** (never of the target: `filter=(TSVAL in $c)` with `$c: distinct(FLAG)` compares against the evaluated dataset's `FLAG` values) — a per-row binding there is a load error. The retired `filter=filter(K="v")` spelling fails to load, naming this one |
| `group` | list of columns | **yes** | Columns whose combined value defines a group — on the target dataset; the value is read per row of the evaluated dataset by the same columns (an absent group column partitions nothing, on both sides). A member may be [qualified](#qualified-group-members) (`DM.RPATHCD`) with `domain`. A member may be a `$`-reference to a dataset-level **list of column names**, or the expression such a binding holds, written in place — spliced exactly as [`record_count`](#record_count)'s `group=` splices it (a dataset-level string is one name; a per-row binding or expression is a load error; a bare `group=$names` / `group=<expression>` is the one-member list); a spliced member is never qualified under `domain`. |
| `keep_missings` | boolean | no | Whether a row with a missing group key stays in its group (default `true`: the blank key forms its own group) |
| `missing_values` | `"skip"` · `"indeterminate"` | no | How a missing input affects the result: skipped (the default), or making the group's extreme undeterminable (EC-51). `"indeterminate"` may only be consumed by a negative-polarity comparison (`!=`); a positive one (`<`, `==`, …) is a load error, because it would read "no value" as "no violation" |

**Determinability (EC-46).** The extreme is answered only when a fully determined candidate wins
against every possible completion of every other candidate: `max{2012-06, 2012-06-15}` has no
answer (the partial could be the 30th), `max{2012-06, 2012-06-30}` is `2012-06-30`. A present
value that cannot be positioned (`UNK`, a masked day) makes the group's extreme undeterminable.
The answer is the winning cell's own text.

**One positional argument**, as for `max`: `max_date(DSSTDTC, DS, group=[USUBJID])` is a load
error naming the keyword spelling (`domain="DS"`), never a read of `DS`.

**Example**

```yaml
Bindings:
- name: "$max_ex_exendtc"
  expression: 'max_date(EXENDTC, domain="EX", missing_values="indeterminate", group=[USUBJID])'
Check:
  expression: 'USUBJID in $ex_usubjid and date(RFXENDTC) != $max_ex_exstdtc and date(RFXENDTC) != $max_ex_exendtc'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$max_ds_dsstdtc"
  expression: 'max_date(DSSTDTC, domain="DS", group=[USUBJID])'
Check:
  expression: 'SSSTRESC == "DEAD" and date(SSDTC) < $max_ds_dsstdtc'
```

**When there is nothing to answer** — **missing** — an undeterminable group, a group with no
candidate, and a row of the evaluated dataset whose key has no group. `!=` against it fires (a missing is not equal to any value — rulings register `D34a`); `==` is true only against the same missing; an order comparison treats it as the lowest value (`D34 #5`): `X > it` and `it < X` fire, `X < it` does not; `empty()` is true.


### min_date

`min_date(name, group=, domain=, filter=, keep_missings=, missing_values=)` → **value**

The earliest date in a column per group — the same contract as `max_date`, in the other direction
(`min{2012-06, 2012-06-01}` is `2012-06-01`; `min{2012-06, 2012-06-02}` has no answer, the
partial could be the 1st). Its parameters, its `--` resolution in `domain=` and its **one positional
argument** are `max_date`'s: `min_date(DSSTDTC, DS, group=[USUBJID])` is a load error naming the
keyword spelling.

| parameter | type | required | description |
|---|---|---|---|
| `name` | column of the target dataset, or any value expression over it | yes | The column to aggregate — bare, never quoted (`max("AyIND", …)` fails to load: a quoted name is a string). An expression is allowed: `max(num(ATOXGR), …)` ranks a character grade numerically. A dataset-level `$`-binding is admitted as on [`distinct`](#distinct): pre-resolved against the rule's dataset; a per-row one is a load error; a binding holding a quoted name keeps the literal refusal |
| `domain` | dataset reference | no | The dataset to read from: `DS` or `"DS"`. Absent ⇒ the dataset under evaluation. With `domain`, **every** column argument names a column of that dataset. A family name reads every member: `domain="SUPPAE"` on a study shipping `SUPPAE1` + `SUPPAE2` (both `RDOMAIN = AE`) reads both, as `LB` reads `LB1` + `LB2`. A `--` in the dataset name is the supplemental family of the dataset under evaluation's **domain**: `domain="SUPP--"` reads `SUPPAE` on `AE`, `SUPPLB` on `LBCH` (every SUPP of the LB family) and `SQAPMH` on `APMH` (the SDTMIG-AP name; `SQAP--` is its synonym) — and `SUPPAE` on a `SUPPAE` primary too — as `Match_Datasets` names do |
| `filter` | boolean over the target dataset's columns | no | Which rows take part, in parentheses: `filter=(ABLFL == "Y")`. Only bare columns of the target dataset — a dotted or `--` reference (under `domain`) is a load error. A `$` reference is one of the rule's own bindings, read as **one dataset-level value of the rule's dataset** (never of the target: `filter=(TSVAL in $c)` with `$c: distinct(FLAG)` compares against the evaluated dataset's `FLAG` values) — a per-row binding there is a load error. The retired `filter=filter(K="v")` spelling fails to load, naming this one |
| `group` | list of columns | **yes** | Columns whose combined value defines a group — on the target dataset; the value is read per row of the evaluated dataset by the same columns (an absent group column partitions nothing, on both sides). A member may be [qualified](#qualified-group-members) (`DM.RPATHCD`) with `domain`. A member may be a `$`-reference to a dataset-level **list of column names**, or the expression such a binding holds, written in place — spliced exactly as [`record_count`](#record_count)'s `group=` splices it (a dataset-level string is one name; a per-row binding or expression is a load error; a bare `group=$names` / `group=<expression>` is the one-member list); a spliced member is never qualified under `domain`. |
| `keep_missings` | boolean | no | Whether a row with a missing group key stays in its group (default `true`: the blank key forms its own group) |
| `missing_values` | `"skip"` · `"indeterminate"` | no | How a missing input affects the result: skipped (the default), or making the group's extreme undeterminable (EC-51). `"indeterminate"` may only be consumed by a negative-polarity comparison (`!=`); a positive one (`<`, `==`, …) is a load error, because it would read "no value" as "no violation" |

**Example**

```yaml
Bindings:
- name: "$min_ds_dsstdtc"
  expression: 'min_date(DSSTDTC, domain="DS", group=[USUBJID], filter=(DSDECOD=="INFORMED CONSENT OBTAINED"))'
Check:
  expression: 'not empty(DSSTDTC) and DSDECOD == "INFORMED CONSENT OBTAINED" and present_on_multiple_rows_within(DSDECOD, within=USUBJID) and $min_ds_dsstdtc == date(DSSTDTC) and $min_ds_dsstdtc != date(DM.RFICDTC)'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$min_ds_dsstdtc"
  expression: 'min_date(DSSTDTC, domain="DS", group=[USUBJID])'
Check:
  expression: 'DSDECOD == "INFORMED CONSENT OBTAINED" and not empty(DSSTDTC) and date(DSSTDTC) == $min_ds_dsstdtc and not empty(DM.RFICDTC) and date(DM.RFICDTC) != $min_ds_dsstdtc'
```

**When there is nothing to answer** — **missing**, as for `max_date`.

**An aggregate as another aggregate's target.** The target of `max_date` / `min_date` (and of `max`) may itself be a grouped aggregate over a third dataset, evaluated on that dataset and broadcast to the target table's rows before the outer extreme is taken. The shipped use is a SEND subject's first dose per pool: EX records a pool's dose once, on a row whose `POOLID` is set and whose `USUBJID` is blank, and POOLDEF maps each pool to its subjects (SENDIG 3.1.1 §6.1.1, §8.5.1), so

```yaml
- name: "$pool_first_dose"
  expression: 'min_date(min_date(EXSTDTC, domain="EX", group=[POOLID], filter=(not empty(POOLID))), domain="POOLDEF", group=[USUBJID])'
```

reads, per POOLDEF row, the earliest `EXSTDTC` of that row's pool, and then per subject the earliest over the subject's pools. Three answers to know: a subject in **two pools** reads the earlier pool; a pool with **no EX row** (an FW or LB pool, which POOLDEF also defines) has no answer and is skipped by the outer extreme; a study **without POOLDEF** answers missing for every subject — which is what makes [`earliest_date`](#earliest_date)`($own, $pool)` fall back to the subject's own dose. ⛔ An EX **without `POOLID`** beside a POOLDEF that has it leaves the inner call's only `group=` member on one side — a rule ERROR ([qualified and bare `group=` members](#qualified-group-members)) — so the shipped pool readers are split by the pool gate: the base rule runs on pooled studies (`POOLDEF.POOLID` and `EX.POOLID` in `All`), its `-A` sibling on studies that define no pools. ⚠ The inner call's `filter=(not empty(POOLID))` stays **required**: an own EX row with a blank `POOLID` must never match a POOLDEF row with a blank `POOLID`. The inner call's target and filter columns (`EXSTDTC`, `POOLID` in the filter) are columns of **its** `domain=`, not of the outer target table; its `group=` columns (`POOLID`) are read on **both** its `domain=` and the outer target table — they are the key its answer is broadcast on, so POOLDEF must carry `POOLID` too. An inner call without `domain=` reads the outer target table itself.


### present_on_multiple_rows_within

`present_on_multiple_rows_within(name, within=)` → **boolean**

True for every member of a group that has two or more rows

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |
| `within` | one column reference, or a one-element list | yes | The column that partitions the rows. May be a `$`-reference to a dataset-level string or one-element list of column names, or the expression such a binding holds, written in place — spliced as [`record_count`](#record_count)'s `group=` splices it (a per-row binding or expression is a load error; two or more spliced names make the rule ERROR: the slot takes one column). |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |

**Example**

```yaml
Check:
  expression: 'present_on_multiple_rows_within(--TESTCD, within=[USUBJID, VISITNUM], keep_missings=false)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'present_on_multiple_rows_within(--TESTCD, within=[USUBJID])'
```


### record_count

`record_count(domain=, filter=, group=, keep_missings=, regex=)` → **value**

The number of rows of a dataset — a registry function on the grouped cross-dataset plan of
`max` / `max_date` / `min_date`. Without
`group=` it is **one value for the dataset**; with `group=` it is the count of each row's group,
read per row of the evaluated dataset. The bare `record_count()` is the dataset's row count.

| parameter | type | required | description |
|---|---|---|---|
| `domain` | dataset reference | no | The dataset to count: `DS` or `"DS"`. Absent ⇒ the dataset under evaluation. With `domain`, **every** column argument names a column of that dataset. A family name reads every member: `domain="SUPPAE"` on a study shipping `SUPPAE1` + `SUPPAE2` (both `RDOMAIN = AE`) reads both, as `LB` reads `LB1` + `LB2`. A `--` in the dataset name is the supplemental family of the dataset under evaluation's **domain**: `domain="SUPP--"` reads `SUPPAE` on `AE`, `SUPPLB` on `LBCH` (every SUPP of the LB family) and `SQAPMH` on `APMH` (the SDTMIG-AP name; `SQAP--` is its synonym) — and `SUPPAE` on a `SUPPAE` primary too — as `Match_Datasets` names do |
| `filter` | boolean over the target dataset's columns | no | Which rows are counted, in parentheses: `filter=(TSPARMCD == "INDIC")`, `filter=(TSPARMCD == "INTTYPE" and TSVAL in ["DRUG", "BIOLOGIC"])`, `filter=(starts_with(QNAM, "RACE"))`, `filter=(not empty(AEACN))`. Only bare columns of the target dataset — a dotted or `--` reference (under `domain`) is a load error. A `$` reference is one of the rule's own bindings, read as **one dataset-level value of the rule's dataset** (never of the target: `filter=(TSVAL in $c)` with `$c: distinct(FLAG)` compares against the evaluated dataset's `FLAG` values) — a per-row binding there is a load error. The retired map spelling `filter=filter(K="v")` fails to load, naming this one |
| `group` | list of columns | no | Columns whose combined value defines a group — on the target dataset; the count is read per row of the evaluated dataset by the same columns (an absent group column partitions nothing, on both sides). A member may be a `$`-reference to a dataset-level **list of column names** (`group=[USUBJID, --TESTCD, $TIMING_VARIABLES]`) — or the expression such a binding holds, written in place (`group=[USUBJID, --TESTCD, get_dataset_filtered_variables(key_name="role", key_value="Timing")]`) — spliced in place; a dataset-level string is one name; a per-row binding or expression, or one typed as a number, is a load error; a bare `group=$names` / `group=<expression>` is the one-member list. A quoted name (`"USUBJID"`) is not a column and is a load error too. A member may be [qualified](#qualified-group-members) (`DM.RPATHCD`) with `domain` — never spliced, and never beside `regex=` |
| `keep_missings` | boolean | no | Whether a row with a missing group key stays in its group (default `true`: the blank key forms its own group) |
| `regex` | regex | no | A regular expression, written as a **string** literal, applied to the group columns the way `is_unique_set`'s `regex=` is: a group column whose first populated value matches is grouped by each value's first match (`"^\\d{4}-\\d{2}-\\d{2}"` collapses a date-time to its date); a column whose first value does not match is left as it is |

**No target.** `record_count` counts rows, never a column: the former positional target
(`record_count(TSPARMCD, filter=…)`) was never read and is a load error now — name the column in
`filter=(…)` or `group=[…]`.

**Example**

```yaml
Bindings:
- name: "$inttype_pharm_count"
  expression: 'record_count(filter=(TSPARMCD == "INTTYPE" and TSVAL in ["DRUG", "BIOLOGIC", "VACCINE", "DIETARY SUPPLEMENT", "COMBINATION PRODUCT"]))'
Check:
  expression: '$stype_interventional_count >= 1 and $inttype_pharm_count >= 1 and $pclas_count == 0'
```

**Grouped, over another dataset**

```yaml
Bindings:
- name: "$dv_count"
  expression: 'record_count(domain="DV", group=[USUBJID])'
Check:
  expression: 'DSDECOD == "PROTOCOL DEVIATION" and $dv_count == 0'
```

**Minimal — the row count**

```yaml
Bindings:
- name: "$bt_total"
  expression: 'record_count()'
Check:
  expression: '$bt_pop > 0 and $bt_pop < $bt_total'
```

**When there is nothing to answer** — **`0`** — a real count of nothing, not an absence of an answer: a
group no row of the filter reaches, a row of the evaluated dataset whose group the counted dataset
does not have, and a `domain` dataset the study lacks all read `0`. So `$n == 0` and `$n <= 1` **fire**.

**Missing and empty** — Always present — a count, never missing.


### row_max

`row_max(name_pattern=)` → **value**

The largest populated value, per row, among the columns whose names match a regular expression.

| parameter | type | required | description |
|---|---|---|---|
| `name_pattern` | string | yes | A regular expression over column names, matched in full and in any letter case. A static string literal — anything else, or a pattern that does not compile, fails to load |

`row_max` takes **no column target**: the pattern chooses the columns (`row_max(TRTEDT, name_pattern=…)` fails to load). Blank and missing cells are not candidates. The candidates rank **by the matched columns' declared type**, decided once for the dataset, never per row: a set of Num columns ranks numerically; a set of Char columns as text — **text is text**: a date-looking value ranks as text like any other, so `2012-06` against `2012-06-15` answers `2012-06-15`, never the date rule (`max_date` is the date function). A matched column with no candidate on any row takes no part in that decision — a CSV or Excel file delivers an all-blank `TR02EDT` as a Char column, and beside a numeric `TR01EDT` it is ignored rather than a mix. When Num **and** Char columns both carry values, the rule **errors** (a column-type mismatch): the two have no common order. A text value is never read as a number: CHAR `"9"` is larger than CHAR `"12"`; convert with `num(…)` for numeric order. On a tie the first candidate in column order wins. The winning cell is returned as it is, type and all.

**Example**

```yaml
Bindings:
- name: "$trxx_max"
  expression: 'row_max(name_pattern="^TR(0[1-9]|[1-9][0-9])EDT$")'
Check:
  expression: 'not empty(TRTEDT) and not empty($trxx_max) and TRTEDT != $trxx_max'
```

The example above is the complete call.

**When there is nothing to answer** — **missing**: a row whose matched cells are all missing answers their missing value (identity kept when they agree); a row of blanks or an undeterminable date extreme answers the plain missing. A dataset with **no matching column** answers what an absent column would answer at the call's position: the missing where the call — or the binding it is bound to — is read as a number, `""` everywhere else (the example's `TRTEDT != $trxx_max` is no numeric position, so `""`; until 2026-10-05 the missing everywhere). `empty()` on either is true, so guard with `not empty($x)` as the example does.

### sequence

`sequence(name, domain=, filter=, group=, by=[asc(COL), desc(COL)], keep_missings=)` → **value (a list)**

The values of a column **in order** — per row, its group's values in sort-key order, duplicates
kept, a missing cell at its position; the
ordered sibling of [`distinct`](#distinct)`(name, group=)` on the same grouped plan, so `domain=`,
`filter=`, the splice of `group=`, an absent group column and the no-group row (the empty list)
behave as there. Without `group=` it is **one list for the dataset**. Read it with
[`is_prefix_of`](#is_prefix_of), [`is_increasing`](#is_increasing), [`collapse_runs`](#collapse_runs),
[`count`](#count) and the membership operators.

| parameter | type | required | description |
|---|---|---|---|
| `name` | column, or a value expression | yes | The positional target: the column whose values are listed (`ETCD`, `--SEQ`, `upper(ETCD)`). One column only — `distinct([A, B])` collects row tuples, `sequence` orders one column's values. A quoted name is a string literal and fails to load. A dataset-level `$`-binding is admitted as on [`distinct`](#distinct): pre-resolved; a per-row one is a load error; a binding holding a quoted name keeps the literal refusal |
| `domain` | dataset reference | no | The dataset to read, as [`distinct`](#distinct)'s: `TA` or `"TA"`; absent ⇒ the dataset under evaluation; with `domain`, every column argument names a column of that dataset |
| `filter` | boolean over the target dataset's columns | no | Which rows are read, in parentheses — as [`distinct`](#distinct)'s `filter=` |
| `group` | list of columns | no | Columns whose combined value defines a group, as [`distinct`](#distinct)'s — spliced, [qualified](#qualified-group-members) with `domain`, or **sided** (`[{left: ACTARMCD, right: ARMCD}]`: this row's `ACTARMCD` keys the other table's `ARMCD` groups) |
| `by` | list of sort keys: `asc(COL)`, `desc(COL)`, or a bare `COL` (= `asc`) | no | The order inside a group — the SQL default is ascending, so `by=[SESTDTC]` is `by=[asc(SESTDTC)]`; a tie keeps file order; absent ⇒ file order. Each key is **classified over its cells**: a key whose every present cell is a number orders numerically (`9` before `10`); any other key orders ISO dates and datetimes **part by part** — year, month, day, hour, minute, second, millisecond, a missing part before every present value of that part: `2012` before `2012---15` before `2012-01-01`, `2015` before `2015-01` before `2015-01-01` before `2015-01-01T08`, a year-masked `----06-15` before every dated value; a UTC offset is applied first where there is a time to shift (`T10:00+02:00` before `T09:00Z`); equal parts fall back to the text; a four-digit cell is a **year** — `2021` after `2020-06-15`; a value that cannot be placed (`2020-02-30`, `2015-UN-UN`, `2020-01-15 08:00`) sorts after every dated value, with other text, as text — `desc` reverses the whole order (a missing part then sorts last). A whitespace-only cell is blank. A blank or missing key cell sorts **first** unless the descriptor says `nulls="last"` (`asc(COL, nulls="last")`), and under `desc` too — the placement is independent of the direction, as on [`is_sorted_by`](#is_sorted_by); two blank or missing cells tie (file order). `nulls=` takes the string literal `"first"` or `"last"` only — a case variant (`"Last"`), another string or a non-literal fails to load (*"… nulls= takes "first" or "last" (lower case), not "Last""*), and so does any other descriptor keyword (`asc(COL, null="last")`). A key may be a `$`-reference to a dataset-level list of column names (`by=$keys`, each key ascending) or a string naming one column inside a descriptor (`asc($c)`), or the expression such a binding holds, written in place — spliced once per run (a per-row binding or expression is a load error). ⚠ A character column of numeric-looking text is a **numeric** key here (`"9"` before `"10"`), as on [`is_sorted_by`](#is_sorted_by), [`empty_within_except_last_row`](#empty_within_except_last_row) and [`has_next_corresponding_record`](#has_next_corresponding_record); only [`is_last_in_group`](#is_last_in_group) orders a character column as text |
| `keep_missings` | boolean | no | Whether a row with a missing group key stays in its group (default `true`). Requires `group=` |

**What is a value.** A present cell contributes its **text** (a numeric cell its canonical text,
as `distinct` lists it); a **missing cell stays at its position** as that missing — the list of a
group with a missing `SEDY` has a hole, which [`is_increasing`](#is_increasing) answers `true`
on (no finding); a blank `""` is a value. Every cell of the group counts, duplicates included:
`sequence(EPOCH, group=[ARMCD], by=[asc(TAETORD)])` lists `SCREENING, TREATMENT, TREATMENT, FOLLOW-UP`.

**Example — the subject's elements against its arm's plan** (SDTMIG §5.2, assumption 4c.ii)

```yaml
Bindings:
- name: "$se"
  expression: 'sequence(ETCD, domain="SE", group=[USUBJID], by=[asc(SESTDTC)], filter=(ETCD != "UNPLAN"))'
- name: "$ta"
  expression: 'sequence(ETCD, domain="TA", group=[{left: ACTARMCD, right: ARMCD}], by=[asc(TAETORD)])'
Check:
  expression: 'not empty(ACTARMCD) and ACTARMCD not in ["SCRNFAIL", "NOTASSGN", "NOTTRT", "UNPLAN"] and not is_prefix_of($se, $ta)'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$etcds"
  expression: 'sequence(ETCD)'
```

### tuple

`tuple(c1, c2, …)` → **value**

Builds a composite key from two to six columns, for whole-tuple membership

| parameter | type | required | description |
|---|---|---|---|
| `c1` | column | yes | A column of the composite key |
| `c2` | column | yes | A column of the composite key |
| `columns` | list of columns (absorbs the remaining arguments) | no | The columns forming the composite key |

Positional arguments only: a named argument (`tuple(A, B, c1=C)`) fails to load (*"'tuple' takes positional arguments only — a named argument (c1=) cannot address a collector's elements …"*).

**Example**

```yaml
Check:
  expression: 'not tuple(USUBJID, VISITNUM, --TESTCD) in distinct([USUBJID, VISITNUM, --TESTCD], domain="SV")'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not tuple(USUBJID, VISITNUM) in distinct([USUBJID, VISITNUM], domain="SV")'
```


### variable_count

`variable_count(name | name_pattern=)` → **value**

A count of variables, in one of three forms — give the template **or** the pattern, never both (a load error: two counts under one name):

- `variable_count(name_pattern="^.+FL$")` — the columns of the current dataset whose names match the regular expression (full match, letter case ignored);
- `variable_count("--LNKGRP")` — **study-wide**: the datasets of the study whose own `--` resolution of the template exists (`AELNKGRP`, `CMLNKGRP`, …), a split dataset family counted once;
- `variable_count()` — the number of columns of the current dataset.

| parameter | type | required | description |
|---|---|---|---|
| `name` | string | no | A `--` variable-name template, resolved per dataset of the study — any **dataset-level** string: a literal (`"--LNKGRP"`), a `$`-binding holding one, or the expression such a binding would hold, written inline |
| `name_pattern` | regex | no | A regular expression over the current dataset's column names |

A **registry function**. It is an **aggregate**: one value for the dataset, computed once per rule and dataset and read by every row. Its arguments are read once per call: the template is any **dataset-level** string — a literal (`"--LNKGRP"`), a `$`-binding or the same expression inline, judged alike — and a per-row value there (a column, `upper(AETERM)`) fails to load as the level finding, the same for both spellings; `name_pattern` is a regex **literal**. The template names a column of every dataset, not of the current one, so a bare `--LNKGRP` is a per-row value and fails to load. An invalid `name_pattern` fails to load (the retired operation counted 0, silently). The study-wide form reads the study inventory; a run whose dataset resolver cannot enumerate the study is a wiring defect and reports a rule **ERROR** (the retired operation counted the current dataset only).

**Example**

```yaml
Bindings:
- name: "$treatment_variable_count"
  expression: 'variable_count(name_pattern="^(ARM|ACTARM|TRTSEQ[PA]|TRT\\d\\d[PA]|TR\\d\\d[PA]G\\d+|TSEQ[PA]G\\d+|TRT[PA]|TRT[PA]G\\d+)$")'
Check:
  expression: '$treatment_variable_count == 0'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$variable_count"
  expression: 'variable_count("--LNKID")'
Check:
  expression: 'var_exists("--LNKID") and $variable_count < 2'
```

**When there is nothing to answer** — **`0`** — a real count of nothing, not an absence of an answer. So `$n == 0` and `$n <= 1` **fire**.

## Uniqueness and consistency

### has_multiple_values_for

`has_multiple_values_for(name, key, within=, keep_missings=, include_empty=)` → **boolean**

True when one `key` maps to more than one `name` — a functional-dependency violation; a blank key or value is excluded unless `include_empty=true`

| parameter | type | required | description |
|---|---|---|---|
| `name` | column reference (bare, or a `--`prefix reference) | yes | The column to read — a quoted literal is a load error |
| `key` | column reference (bare, or a `--`prefix reference) | yes | The column whose value acts as the key — a quoted literal is a load error |
| `within` | column reference or list of column references (bare, or `--`prefix) | no | A member may be a `$`-reference to a dataset-level list of column names (or a string naming one column), or the expression such a binding holds, written in place — spliced as [`record_count`](#record_count)'s `group=` splices it (a per-row binding or expression is a load error); a nested coalesce component splices inside as at top level. Columns that partition the rows, read **by keyword only** — a positional third argument (`has_multiple_values_for(A, B, USUBJID)`) is a load error (*"`within` on has_multiple_values_for is read by keyword only — write within=USUBJID"*), and so is a quoted literal |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group — **requires `within=`**: with no grouping key it would have no effect, so `keep_missings=` without `within=` — or with the empty `within=[]`, which groups by nothing — fails to load (*"`keep_missings` on has_multiple_values_for requires `within=`; with no grouping key it would have no effect"*). Read **by keyword only**, like `within=`: a positional fourth argument is a load error |
| `include_empty` | boolean | no | Whether a blank key or value takes part |

**Example**

```yaml
Check:
  expression: 'has_multiple_values_for(ARMCD, ARM, within=[STUDYID], keep_missings=false, include_empty=false)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'has_multiple_values_for(ARMCD, ARM)'
```

**Missing and empty** — A blank key or dependent is **excluded** from the relationship unless `include_empty=true`.


### has_same_values

`has_same_values(name)` → **boolean**

True on every row when the column has at most one distinct value

| parameter | type | required | description |
|---|---|---|---|
| `name` | column reference (bare, or a `--`prefix reference) | yes | The column to read — a quoted literal is a load error |

**Example**

```yaml
expression: 'not empty(MHCAT) and has_same_values(MHCAT)'
```

### inconsistent_enumerated_columns

`inconsistent_enumerated_columns(name)` → **boolean**

True when the numbered series `name`, `name1`, `name2` … has a gap

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |

**Example**

```yaml
expression: 'inconsistent_enumerated_columns(--VAL)'
```

### is_inconsistent_across_dataset

`is_inconsistent_across_dataset(name, keys=[…], include_empty=)` → **boolean**

True on the minority rows of a group that disagrees; blank targets excluded unless `include_empty=true`

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |
| `key` | any | no | The column whose value acts as the key. A `$`-binding here fails to load (*"argument 'key' of 'is_inconsistent_across_dataset' takes a column, not the binding $k — a binding of column names is spliced through keys=[$k]"*): a binding of column names goes in `keys=[$k]`. |
| `keys` | list of columns | no | The values that must all be present. A member may be a `$`-reference to a dataset-level **list of column names**, or the expression such a binding holds, written in place — spliced exactly as [`record_count`](#record_count)'s `group=` splices it (a dataset-level string is one name; a per-row binding or expression is a load error; a bare `keys=$names` / `keys=<expression>` is the one-member list). |
| `include_empty` | boolean | no | Whether a blank key or value takes part |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |

**Example**

```yaml
Check:
  expression: 'is_inconsistent_across_dataset(--TEST, keys=[--TESTCD], include_empty=false, keep_missings=false)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'is_inconsistent_across_dataset(--TEST)'
```

**Missing and empty** — A blank target is **excluded** unless `include_empty=true`.


### is_not_unique_member_set

`is_not_unique_member_set(members=[…], group=[…])` → **boolean**

True on every row whose group (by `group`) has a **member set** — the set of distinct `members` tuples over its rows — equal to the member set of at least one **other** group: two `POOLID`s pooling the same subjects, two `NHOID`s with the same (`OIPARMCD`, `OIVAL`) taxonomy. The comparison is set against set: a tuple repeated inside one group counts once, the row order is irrelevant, and the whole set must agree (one differing pair is a different set; a subject in two different pools is no finding). There is no positive twin — a rule writes `is_not_unique_member_set(…)` directly. Both lists splice as [`is_unique_set`](#is_unique_set)'s members do (a `$` name list or the expression it holds; a per-row member fails the rule's load).

| parameter | type | required | description |
|---|---|---|---|
| `members` | list of columns | yes | The columns whose distinct tuples form a group's member set |
| `group` | list of columns | yes | The columns that identify a group |
| `keep_missings` | boolean | no | Whether a row with a missing group key stays in its group |

**Example**

```yaml
Check:
  expression: 'is_not_unique_member_set(members=[USUBJID], group=[POOLID])'
```

**Missing and empty** — `""` and a missing are real, distinct components of a member tuple and of the group key (the [`is_unique_set`](#is_unique_set) policy); with `keep_missings=false` a row whose group key is missing takes no part. An absent `members` column is dropped wherever it sits; an absent `group` column partitions nothing — one group, nothing to compare, no finding.


### is_unique_relationship

`is_unique_relationship(a, b)` → **boolean**

True when `a` and `b` are in a strict one-to-one relationship. A negated spelling `is_not_unique_relationship(a, b)` exists; prefer `not is_unique_relationship(a, b)`. A blank key or value is excluded.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |
| `value` | any | no | The column to compare against — a column, read per row. A `$`-binding here fails to load (*"argument 'value' of 'is_unique_relationship' takes a column, not the binding $k — a binding of column names is spliced through keys=[$k]"*): a binding of column names goes in `keys=[$k]`. |
| `keys` | list of columns | no | The values that must all be present. A member may be a `$`-reference to a dataset-level **list of column names**, or the expression such a binding holds, written in place — spliced exactly as [`record_count`](#record_count)'s `group=` splices it (a dataset-level string is one name; a per-row binding or expression is a load error; a bare `keys=$names` / `keys=<expression>` is the one-member list). |

**Example**

```yaml
Check:
  expression: 'not is_unique_relationship(ARMCD, keys=[ARM])'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not is_unique_relationship(ARMCD, ARM)'
```

**Missing and empty** — A blank key or value is **excluded** from the relationship.


### is_unique_set

`is_unique_set([V1, V2, …])` → **boolean**

True when the tuple of the listed columns occurs exactly once. A negated spelling `is_not_unique_set([…])` exists; prefer `not is_unique_set([…])`. An absent member is dropped, `""` is a real component. A member is a column of the dataset under evaluation, written bare. A `$` member — or the dataset-level expression such a binding holds, written as the member (`is_unique_set([USUBJID, --TESTCD, natural_key_variables()])`) — splices exactly as [`record_count`](#record_count)'s `group=` does: a list of names element by element, a single name as one member; one typed as a number fails the rule's load; a value that is neither a list of names nor a name makes the rule **ERROR** when it runs (until 2026-10-01 such a member was dropped silently as absent); a spliced **qualified** name is a member like an authored one — a column of the rule's evaluation table (`DM.RPATHCD` of the bound `DM` record). A **per-row** expression — `coalesce(PCDTC, PCELTM)`, `upper(AETERM)`, or a `$` binding holding one — is a **value** member: its per-row value is a key component with the identity a column cell has (`""` and each missing distinct), so `not is_unique_set([USUBJID, PCTESTCD, coalesce(PCDTC, PCELTM)])` keys on the observation time whichever column carries it (since 2026-10-08; before, a per-row member failed the rule's load). The **level** decides, never the value's content: a dataset-level `$` or expression splices names, a per-row one is a value. A `duration(X)` member keys by the duration's **hull** — `P1W` and `P7D` are one key, `P1M` (28–31 days) is not `P30D`; an unpositionable text keys by its text. A per-row **list** is not a key component (a load error), and `regex=` applies to column members only — beside a value member it is a load error: write the normalisation as the member (`substring(…)`, `prefix(…)`). The bare operand `is_unique_set($keys)` (or `is_unique_set(natural_key_variables())`) reads as the one-member list `is_unique_set([$keys])`; the list is the canonical spelling.

| parameter | type | required | description |
|---|---|---|---|
| `members` | list of columns | yes | The columns forming the tuple |
| `regex` | string | no | A regular expression, written as a **string** literal |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |

**Example**

```yaml
Check:
  expression: 'not is_unique_set([USUBJID, --TESTCD, VISITNUM], keep_missings=false)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not is_unique_set([USUBJID, --SEQ])'
```

**Missing and empty** — `""` is a real component of the tuple; an absent member is dropped wherever it sits; a value member's `""` and missing keep the identity the cell has.


### is_unique_value

`is_unique_value(name)` → **boolean**

True when the value occurs exactly once in the column. A negated spelling `is_not_unique_value(name)` exists; prefer `not is_unique_value(name)`.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |

**Example**

```yaml
expression: 'not is_unique_value(USUBJID)'
```


## Sets and ordering

### contains_all

`contains_all(source, keys=[…])` → **boolean**

True when the source's distinct values include every value in `keys=`. A negated spelling `not_contains_all(source, keys=[…])` exists; prefer `not contains_all(source, keys=[…])`.

| parameter | type | required | description |
|---|---|---|---|
| `source` | any | yes | The column or list to look in |
| `required` | any | no | The values that must all be present |
| `keys` | list | no | The values that must all be present |

**Example**

```yaml
Bindings:
- name: "$study_arms"
  expression: 'distinct(ARMCD, domain="TA")'
Check:
  expression: 'not contains_all($study_arms, keys=[ARMCD])'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$study_arms"
  expression: 'distinct(ARMCD, domain="TA")'
Check:
  expression: 'not contains_all($study_arms)'
```


### collapse_runs

`collapse_runs(list)` → **value (a list)**

The list with adjacent duplicates collapsed — `[S, T, T, F, T]` becomes `[S, T, F, T]`. Over a per-row list ([`sequence`](#sequence)'s output)
and a dataset-level list alike; elements compare as the set operations compare them (text exact,
a missing by its marker); a missing list is the empty list.

| parameter | type | required | description |
|---|---|---|---|
| `list` | list | yes | The list to collapse — a list binding, `sequence(…)`, `distinct(…)`, `values([…])`; a scalar is a rule-definition error |

**Example — an EPOCH must be one contiguous block of its arm's `TAETORD` order**

```yaml
Check:
  expression: 'count(collapse_runs(sequence(EPOCH, group=[ARMCD], by=[asc(TAETORD)]))) != count(distinct(EPOCH, group=[ARMCD]))'
```

### has_next_corresponding_record

`has_next_corresponding_record(name, value, within=, by=, keep_missings=, relation=)` → **boolean**

True when a following record in the group matches under `relation=`; authored under `not`

| parameter | type | required | description |
|---|---|---|---|
| `name` | column reference — or `duration(COL)` | yes | The column read on the current record. Written as `duration(COL)` on **both** operands, the cells are compared as [durations](#duration) — under `relation="<="` / `">="` the hull order, under the default the hull equality, so `P1W` corresponds to `P7D`; on one operand only it is a load error. Unmarked, the cells compare as today (text identity, widened by `relation=`'s numeric / date reading), whatever they contain |
| `value` | column reference — or `duration(COL)` | yes | The column read on the next record, marked exactly as `name` is |
| `within` | one column reference, or a one-element list | yes | The column that partitions the rows. May be a `$`-reference to a dataset-level string or one-element list of column names, or the expression such a binding holds, written in place — spliced as [`record_count`](#record_count)'s `group=` splices it (a per-row binding or expression is a load error; two or more spliced names make the rule ERROR: the slot takes one column). |
| `by` | one column reference, or a one-element list `[asc(COL)]` / `[COL]` | yes | The sort key, ascending: exactly one column (`by=AESEQ` or `by=[asc(AESEQ)]`), **required** (a call without it fails to load) — `desc(…)`, two keys or a `nulls=` placement fail to load (*"… is not supported for …"*). The rows are ordered as [`sequence`](#sequence) and [`is_sorted_by`](#is_sorted_by) order a sort key: the key is **classified over the group's cells** — numeric when every present cell is a number (`AESEQ` `9` before `10`, in a character column of numeric text too), else ISO dates and datetimes part by part with a UTC offset applied first, then other text, after every dated value — and a blank (`""`, whitespace only) or missing key cell sorts **first**; a tie keeps file order. This is not [`is_last_in_group`](#is_last_in_group)'s order (by the column's type, a character column as text). The retired spelling `ordering=` fails to load with the hint *"ordering= is retired — write by=…"*. Written **by keyword only** — a positional key (`has_next_corresponding_record(…, AESEQ)`) fails to load with the hint *"has_next_corresponding_record takes its sort key by keyword — write by=AESEQ"*. May be a `$`-reference to a dataset-level string or one-element list of column names, or the expression such a binding holds, written in place — spliced as [`record_count`](#record_count)'s `group=` splices it (a per-row binding or expression is a load error; two or more spliced names make the rule ERROR: the slot takes one column). |
| `relation` | string | no | How a record must relate to the next one: `"=="` (the default), `"<="` or `">="`, applied **in disjunction with** identity (it only widens what corresponds). Numeric cells compare as numbers, ISO dates under the date hull rule, and — only when both operands are written `duration(COL)` — durations under the duration hull rule |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |

**Example**

```yaml
Check:
  expression: 'not has_next_corresponding_record(--ENDTC, --STDTC, within=[USUBJID], by=--SEQ, relation="<=", keep_missings=false)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not has_next_corresponding_record(--ENDTC, --STDTC, within=[USUBJID], by=--SEQ)'
```

**Missing and empty** — `""` is compared literally, not treated as absent.


### is_increasing

`is_increasing(list)` → **boolean**

True when every element is **strictly** greater than its predecessor. Numerically when
**every** element is a number (a numeric cell, or text that reads as one: `"9"` before `"10"`),
else as text; an empty or one-element list is increasing; a list holding a **missing element**
answers `true` — vacuously, as the empty list does — so the authored `not is_increasing(…)` gives
**no finding** on it (the BOOLEAN functions are two-valued, and `true` is the answer that fires
nothing). A **missing list** — `split_by` of a blank cell — answers `true` the same way. Over a per-row list ([`sequence`](#sequence)'s
output) and a dataset-level list alike.

| parameter | type | required | description |
|---|---|---|---|
| `list` | list | yes | The list to judge — a list binding, `sequence(…)`, `values([…])`; a scalar is a rule-definition error |

**Example — the numeric suffix of `MIDS` follows the chronological order** (SDTMIG 3.3 SM)

```yaml
Bindings:
- name: "$nums"
  expression: 'sequence(MIDS ~ /^.*?([0-9]+)$/$1/, group=[USUBJID, MIDSTYPE], by=[asc(SMSTDTC)], filter=(MIDS =~ /[0-9]$/))'
Check:
  expression: 'not is_increasing($nums)'
```

The digits are extracted in `sequence`'s value target and stay text; `is_increasing` reads them as
numbers because every element is one. A year-only `SMSTDTC` sorts as the year among the dates
(`2021` after `2020-06-15`).

### is_ordered_subset_of

`is_ordered_subset_of(name, $b)` → **boolean**

True when the column's values appear in `$b` in the same order. A negated spelling `is_not_ordered_subset_of(name, $b)` exists; prefer `not is_ordered_subset_of(name, $b)`.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |
| `value` | any | yes | The value to compare against |

**Example**

```yaml
expression: 'not is_ordered_subset_of($column_order_from_dataset, $model_column_order)'
```

### is_prefix_of

`is_prefix_of(a, b)` → **boolean**

True when `a`'s elements equal `b`'s first `|a|` elements — `a` starts `b`. Element identity is the set operations' (text exact, a missing by
its marker); the empty list is a prefix of every list, a list is its own prefix; a missing list on
either side answers `false`. Over per-row lists ([`sequence`](#sequence)'s output) and dataset-level
lists alike. A negated spelling `is_not_prefix_of(a, b)` exists; prefer `not is_prefix_of(a, b)`.

| parameter | type | required | description |
|---|---|---|---|
| `a` | list | yes | The candidate prefix |
| `b` | list | yes | The list it must start |

**Example** — the subject's elements must start the plan of the subject's arm: [`sequence`](#sequence).

```yaml
Check:
  expression: 'not is_prefix_of($se, $ta)'
```

### is_sorted_by

`is_sorted_by(target, by=[asc(col)[, nulls=]], within=)` → **boolean**

True when the target is sorted by `by=`; authored under `not`, it fires the whole unsorted group

| parameter | type | required | description |
|---|---|---|---|
| `target` | column reference (bare, or a `--`prefix reference) | yes | The column the function is about — a quoted literal is a load error |
| `by` | list of sort keys: `asc(COL)`, `desc(COL)`, or a bare `COL` (= `asc`, the SQL default) | yes | Sort keys — `COL` is bare (or `--`prefix); a quoted `asc("COL")` is a string literal and fails to load. A key may be a `$`-reference to a dataset-level list of column names (`by=$keys`) or a string naming one column inside a descriptor (`asc($c)`), or the expression such a binding holds, written in place — spliced once per run (a per-row binding or expression is a load error). Each key is ordered in its own direction — `desc(COL)` reverses it. A blank or missing key cell sorts **first** unless the descriptor says `nulls="last"` (`asc(COL, nulls="last")`), and under `desc` too — the placement is independent of the direction; two blank or missing cells tie (file order). `nulls=` takes the string literal `"first"` or `"last"` only — a case variant (`"Last"`), another string or a non-literal fails to load (*"… nulls= takes "first" or "last" (lower case), not "Last""*), and so does any other descriptor keyword (`asc(COL, null="last")`). The other cells of a key are **classified over the group** as [`sequence`](#sequence)'s keys are: a key whose every present cell is a number orders numerically (`9` before `10`); any other key orders ISO dates and datetimes **part by part** — year, month, day, hour, minute, second, millisecond, a missing part before every present value of that part: `2012` before `2012---15` before `2012-01-01`, `2015` before `2015-01` before `2015-01-01` before `2015-01-01T08`, a year-masked `----06-15` before every dated value; a UTC offset is applied first where there is a time to shift (`T10:00+02:00` before `T09:00Z`); equal parts fall back to the text; a four-digit cell is a **year** — `2021` after `2020-06-15`; a value that cannot be placed (`2020-02-30`, `2015-UN-UN`, `2020-01-15 08:00`) sorts after every dated value, with other text, as text — `desc` reverses the whole order (a missing part then sorts last). A whitespace-only cell is blank. A character column of numeric-looking text is therefore a numeric key, as on [`sequence`](#sequence), [`empty_within_except_last_row`](#empty_within_except_last_row) and [`has_next_corresponding_record`](#has_next_corresponding_record); only [`is_last_in_group`](#is_last_in_group) orders a character column as text. Written **by keyword only** — a positional key (`is_sorted_by(SESEQ, [SESTDTC])`) fails to load with the hint *"is_sorted_by takes its sort keys by keyword — write by=[SESTDTC]"* |
| `within` | one column reference, or a one-element list (bare, or `--`prefix) | no | The column that partitions the rows — a quoted literal is a load error, and so is a list of two or more columns (it used to run ungrouped). May be a `$`-reference to a dataset-level string or one-element list of column names, or the expression such a binding holds, written in place — spliced as [`record_count`](#record_count)'s `group=` splices it (a per-row binding or expression is a load error; two or more spliced names make the rule ERROR: the slot takes one column). |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |

**Example**

```yaml
Check:
  expression: 'not is_sorted_by(VISITNUM, by=[asc(VISITNUM)], within=[USUBJID], keep_missings=false)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not is_sorted_by(VISITNUM, by=[asc(VISITNUM)])'
```

**Missing and empty** — `keep_missings=` decides whether a row with a missing `within=` key stays in its group; a blank or missing **sort**-key cell sorts first (`nulls="last"` puts it last), whatever the key's direction.


### shares_elements_with

`shares_elements_with($a, $b)` → **boolean**

True when the two lists have at least one member in common. A negated spelling `shares_no_elements_with($a, $b)` exists; prefer `not shares_elements_with($a, $b)`.

Each operand is a **dataset-level** set: a `$`-binding, or the expression such a binding holds,
written in place
(`shares_elements_with(distinct(USUBJID, domain="AE"), keys=[USUBJID])`), evaluated once over the
rule's dataset exactly as the binding is; a scalar is the one-member set. A **per-row** operand — a
bare column, `upper(AETERM)`, or a binding holding one — fails the rule's load, bound and inline
alike (*"cannot read the per-row or per-variable operand"*).

| parameter | type | required | description |
|---|---|---|---|
| `name` | a dataset-level list or value: a `$`-binding, or the expression it holds | yes | The first set |
| `value` | a dataset-level list or value, likewise | no | The second set — or write `keys=` |
| `keys` | list | no | The second set as a written list of values |

**Example**

```yaml
Bindings:
- name: "$ae_subjects"
  expression: 'distinct(USUBJID, domain="AE")'
Check:
  expression: 'not shares_elements_with($ae_subjects, keys=[USUBJID])'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$ae_subjects"
  expression: 'distinct(USUBJID, domain="AE")'
Check:
  expression: 'not shares_elements_with($ae_subjects)'
```


## Quantifiers over joined rows

A `Match_Datasets` entry that matches several partner records to one record of the dataset under
evaluation gives that record one evaluation row per partner record (the merged evaluation table —
[joins](../authoring/joins.md)). A condition written on such a row answers for **that row alone**:
`EPOCH != SE.EPOCH` is true on every SE element whose epoch differs, and a Check anchored on it fires
once per element. The quantifiers below answer for the **record**: `joined(c)` is the list of `c`'s
answers over every evaluation row of the current record, and `any` / `all` / `none` fold that list.

| spelling | reads |
|---|---|
| `any(joined(c))` | some row of this record satisfies `c` |
| `all(joined(c))` | every row of this record satisfies `c` |
| `none(joined(c))` | no row of this record satisfies `c` (the same as `not any(joined(c))`) |

The domain is the record's rows in the evaluation table — on an inner join every matched
combination; on a `Join_Type: left` entry an unmatched record keeps its one placeholder row, which
**is** in the domain (guard it with `J._matched_`, as everywhere else); on a rule whose entries expand
nothing (one partner per record, or an entry declared `n:1` / `1:1`) the record is its one row and
`any(joined(c))` is simply `c`. Several expanding entries compose, so the domain is their
combinations — the three quantifiers do not care how often a combination repeats. The logic is
two-valued: a condition is true or false on every row, there is no missing boolean.

**The second producer — an element-wise predicate over a list.** `joined(c)` is one way to make the
list of booleans a quantifier folds; the other is one of the **element-wise predicates** over a
list: `is_valid_date(values([--STDTC, --ENDTC]))` answers, per row, one boolean per element — the
predicate's own answer on each cell — and `any(is_valid_date(values([--STDTC, --ENDTC])))` is "at
least one of the two is a valid date", `none(invalid_date(values([--STDTC, --ENDTC, --DTC])))` "no
invalid date among the three", written once instead of an `or` / `and` chain over sibling columns.
The list may be the values of several columns ([`values`](#values)), a per-row list
(`split_by(X, ",")`), or a `$`-list (`$l: ["2020-01-01", "x"]`, then quantified once for the
dataset). The sixteen predicates that lift are marked **element-wise** in their sections:
`is_valid_date`, `invalid_date`, `is_complete_date`, `is_incomplete_date`, `is_partial_date`,
`is_complete_date_part`, `is_not_complete_date_part`, `is_valid_duration`, `invalid_duration`,
`is_integer`, `is_not_integer`, `is_numeric`, `has_alpha`, `has_digit`, `is_valid_name`,
`is_valid_testcd`. The list of booleans may also be **bound** and quantified later —
`$ok: is_numeric(values([--ORRES, --STRESC]))` then `all($ok)` — and a quantifier over a scalar
predicate or a scalar binding is simply that predicate (`any(is_valid_date(X))` is
`is_valid_date(X)`, `none(…)` its negation) — and so is a quantifier over a condition written inline,
as it is over the binding holding it: `any((EPOCH == SE.EPOCH))`, `all(contains(A, "x"))`,
`none((not empty(A)))` (a comparison or a `not` is parenthesised there). Every other use of such a
list is a load error: written as a condition on its own (`is_valid_date(values([X, Y]))` — quantify it), compared (`$ok == true`),
tested with `in`, passed to another function, or reported in `Output_Variables`; and the quantifiers
take nothing else — `any(values([A, B]))` (cells, not answers) or `any($strings)` fail to load.
A quantifier inside `joined(…)` stays refused.

⚠ The quantifiers are legal **only in a Check level expression** (bare, or under `and` / `or` /
`not`), never in a binding, the Precondition, a comparison operand, a function argument or a
`Match_Datasets` Filter; `joined(…)` is legal only as a quantifier's direct argument; and a rule
that declares no keyed or RELREC `Match_Datasets` entry has nothing for `joined(…)` to range over —
each of these fails the rule's load with a message naming the position. A quantifier answers the
same on every row of a record, so a Check that is only a quantifier fires on every evaluation row
of a failing record — anchor the level on a row condition beside it (`<row> and none(joined(…))`),
as `CDISC-CG0218` does. The YAML Check composites `any:` / `all:` (a list of conditions under a
level) are a different thing: they combine whole conditions, they do not range over rows.

### any

`any(list)` → **boolean**

True when at least one element of the list is true. The list is `joined(<condition>)` — some
evaluation row of the current record satisfies the condition — or an element-wise predicate over a
list — some element satisfies the predicate (`any(is_valid_date(values([X, Y])))`: at least one of
the two cells is a valid date) — or a `$`-binding holding such a predicate's answer. `any([])` is
false.

| parameter | type | required | description |
|---|---|---|---|
| `list` | list of booleans | yes | The answers to fold — `joined(<condition>)`, an element-wise predicate over a list (`is_valid_date(values([X, Y]))`), or a `$`-binding holding one; a condition written inline (`(A == B)`, `(not empty(A))`, `contains(A, "x")`) is its own one answer |

**Example**

```yaml
Match_Datasets:
- Name: "SE"
  Keys: ["STUDYID", "USUBJID"]
Check:
  expression: 'not empty(EPOCH) and not any(joined(EPOCH == SE.EPOCH))'
```

> A quantifier answers the same on every evaluation row of a record, so a Check that is only a
> quantifier fires once per joined row of the record. Anchor it on a row conjunct (`not
> empty(EPOCH) and …` above) to report the record once, where the finding belongs.

**Example** — over the values of two columns, per row

```yaml
Check:
  expression: 'not empty(--STDTC) and not any(is_valid_date(values([--STDTC, --ENDTC])))'
```

### all

`all(list)` → **boolean**

True when every element of the list is true — every evaluation row of the current record satisfies
the condition (`joined(c)`), or every element satisfies the predicate (`all(is_numeric(values([A,
B])))`), or every answer in the bound list is true (`$ok: is_numeric(values([A, B]))`, then
`all($ok)`). `all([])` is true.

| parameter | type | required | description |
|---|---|---|---|
| `list` | list of booleans | yes | The answers to fold — `joined(<condition>)`, an element-wise predicate over a list, or a `$`-binding holding one; a condition written inline (`(A == B)`, `(not empty(A))`, `contains(A, "x")`) is its own one answer |

**Example**

```yaml
Match_Datasets:
- Name: "SE"
  Keys: ["STUDYID", "USUBJID"]
Check:
  expression: 'not empty(SVSTDTC) and all(joined(empty(SE.SEENDTC)))'
```

> Anchored on `not empty(SVSTDTC)`: a quantifier-only Check fires once per joined row (see `any`).

### none

`none(list)` → **boolean**

True when no element of the list is true — no evaluation row of the current record satisfies the
condition, or no element satisfies the predicate (`none(invalid_date(values([--STDTC, --ENDTC])))`:
no invalid date among the two). `none(L)` and `not any(L)` are the same condition; `none([])` is
true.

| parameter | type | required | description |
|---|---|---|---|
| `list` | list of booleans | yes | The answers to fold — `joined(<condition>)`, an element-wise predicate over a list, or a `$`-binding holding one; a condition written inline (`(A == B)`, `(not empty(A))`, `contains(A, "x")`) is its own one answer |

**Example** — `CDISC-CG0218`'s `ERROR` level as shipped: the row condition anchors the finding on
the element that contains the visit start (end-exclusive: the transition instant belongs to the
next element), the quantifier silences it when any element that *may* be in progress at the
visit's start — judged on the dates' possible ranges, a date-time's day end-inclusive only when
both sides are dates — carries the visit's EPOCH

```yaml
Match_Datasets:
- Name: "SE"
  Keys: ["STUDYID", "USUBJID"]
Check:
  ERROR:
    expression: >-
      date(SE.SESTDTC) <= date(SVSTDTC) and date(SVSTDTC) < date(SE.SEENDTC) and EPOCH != SE.EPOCH
      and none(joined(not empty(earliest_possible(SE.SESTDTC)) and not
      empty(earliest_possible(SVSTDTC)) and date(earliest_possible(SE.SESTDTC)) <=
      latest_possible(SVSTDTC) and (date(earliest_possible(SVSTDTC)) < latest_possible(SE.SEENDTC)
      or date(earliest_possible(SVSTDTC)) == latest_possible(SE.SEENDTC) and (SVSTDTC !~ /T/ or
      SE.SEENDTC !~ /T/)) and EPOCH == SE.EPOCH))
```

The two `not empty(earliest_possible(…))` guards inside `joined(…)` are load-bearing: a blank
`SE.SESTDTC` read as a date has an unbounded range, so without them an element with no start
would count as a candidate and silence the finding
(`CDISC-CG0218-invalid_blank_sestdtc_candidate_inside_joined-SV.cdt`).

### joined

`joined(condition)` → **value** (a list of booleans)

The list of the condition's answers over every evaluation row that stands for the current record.
Legal only as the direct argument of `any`, `all` or `none` — the engine evaluates the pair as one
step, so the list is never a value a rule can bind, compare or report (the element-wise predicates
are the other producer of such a list, and the one that may be bound — see the section intro). The condition is written bare
inside the parentheses (`joined(A == 1 and empty(B))`), unlike a boolean handed to any other
function, which needs its own parentheses (`filter=(A == 1)`). A reference inside the condition
(`SE.EPOCH`) is reported, as an output variable, at the row the finding is anchored on.

| parameter | type | required | description |
|---|---|---|---|
| `condition` | boolean | yes | The row condition to answer per evaluation row of the record |

**Example**

```yaml
Match_Datasets:
- Name: "AE"
  Keys: ["STUDYID", "USUBJID"]
Check:
  expression: 'DTHFL != "Y" and any(joined(AE.AEOUT == "FATAL"))'
```

> Anchored on the row conjunct `DTHFL != "Y"`: a quantifier-only Check fires once per joined row.


## Dataset and domain metadata

### dataset_class_from_library

`dataset_class_from_library()` → **value**

The CDISC Library's class name of the current dataset's domain (e.g. `"BASIC DATA STRUCTURE"`), looked up by the dataset's domain code.

A **registry function**. It is an **aggregate**: one value for the dataset, computed once per rule and dataset and read by every row. It needs the CDISC Library: without one — or with one that could not be consulted — the rule is **SKIPPED** (*"Rule skipped — no Library access"*). Under the Define-XML fallback for an unreachable Library, a blank class means the define could not answer and the rule is **SKIPPED** too. (`ds_class("LIBRARY")` is not the same question: its tiered lookup answers where this one does not.)

**Example**

```yaml
Bindings:
- name: "$dataset_class"
  expression: 'dataset_class_from_library()'
Check:
  expression: 'starts_with(ds_name(), "AD") and empty($dataset_class)'
```

The example above is the complete call.

**When there is nothing to answer** — **missing** — the calculation was not possible. `!=` against it fires (a missing is not equal to any value — rulings register `D34a`); `==` is true only against the same missing; an order comparison treats it as the lowest value (`D34 #5`): `X > it` and `it < X` fire, `X < it` does not; `empty()` is true.

### dataset_names

`dataset_names()` → **value (a list)**

The names of every dataset in the study, upper-cased, as a list — split members as themselves (`LBCH`, not `LB`; that is [`study_domains()`](#study_domains)'s question), `SUPP--` and `RELREC` included.

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `dataset_names(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It reads the study inventory; a run whose dataset resolver cannot enumerate the study is a wiring defect and reports a rule **ERROR** (the retired operation answered the empty set there, silently).

**Example**

```yaml
Bindings:
- name: "$study_datasets"
  expression: 'dataset_names()'
Check:
  expression: 'not shares_elements_with($study_datasets, keys=[SUPPAE]) or $aetrtem_count < 1'
```

The example above is the complete call.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.


### domain_is_custom

`domain_is_custom()` → **value (boolean)**

Whether the CDISC Library declares the current dataset's domain a **custom** (sponsor) domain, looked up by the dataset's domain code: `true` or `false`.

A **registry function**. It is an **aggregate**: one value for the dataset, computed once per rule and dataset and read by every row. It needs the CDISC Library: without one — or with one that could not be consulted — the rule is **SKIPPED** (*"Rule skipped — no Library access"*). ⚠ A Library that could not be consulted SKIPs the rule **even under the Define-XML fallback**: `false` ("not custom") is the answer that lets a rule fire, and a define cannot tell a custom domain — "custom" means "not in the standard", and the standard is what is missing.

**Example**

```yaml
Bindings:
- name: "$domain_is_custom"
  expression: 'domain_is_custom()'
Check:
  expression: '$domain_is_custom == true and not var_exists("--TESTCD")'
```

The example above is the complete call.

**When there is nothing to answer** — it always answers, `true` or `false`; a Library that cannot answer SKIPs the rule instead.

### ds_class

`ds_class([[x,] level])` → **value**

The dataset's observation class; available at every level

At `DATA` it takes no argument: the DATA-level class is the evaluated dataset's, so `ds_class("DM", "DATA")` fails to load — write `ds_class()`, or read another dataset's class at `DEFINE` / `LIBRARY`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | string | no | The dataset's name as a string (`"DM"`); a bare column fails to load; defaults to the dataset under evaluation |
| `level` | level | no | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` — with no dataset name, an omitted level reads `DATA` (`ds_class()`); a dataset name needs its level |

**Example**

```yaml
expression: 'not empty(ds_class("DEFINE"))'
```

### ds_domain

`ds_domain([[x,] level])` → **value**

The dataset's domain as `Scope.Domains` resolves it — `SUPPLB`, not the parent, on a SUPP dataset; `DATA` only

At `DATA` an argument names the dataset read (`ds_domain("DM", "DATA")`), resolved through the study's datasets like every dataset-naming read; a dataset the study lacks answers a missing. (Until 2026-10-01 the argument was ignored at `DATA` and the evaluated dataset answered.)

| parameter | type | required | description |
|---|---|---|---|
| `x` | string | no | The dataset's name as a string (`"DM"`); a bare column fails to load; defaults to the dataset under evaluation |
| `level` | level | no | Which metadata to read — supported here: `DATA` — with no dataset name, an omitted level reads `DATA` (`ds_domain()`); a dataset name needs its level |

**Example**

```yaml
expression: 'ds_domain() == "AE"'
```

### ds_label

`ds_label([[x,] level])` → **value**

The dataset's label; available at every level

At `DATA` an argument names the dataset read (`ds_label("DM", "DATA")`), resolved through the study's datasets like every dataset-naming read; a dataset the study lacks answers a missing. (Until 2026-10-01 the argument was ignored at `DATA` and the evaluated dataset answered.)

| parameter | type | required | description |
|---|---|---|---|
| `x` | string | no | The dataset's name as a string (`"DM"`); a bare column fails to load; defaults to the dataset under evaluation |
| `level` | level | no | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` — with no dataset name, an omitted level reads `DATA` (`ds_label()`); a dataset name needs its level |

**Example**

```yaml
expression: 'ds_label() != "Device-level Analysis Dataset"'
```

### ds_name

`ds_name([[x,] level])` → **value**

The dataset's name; available at every level

At `DATA` an argument names the dataset read (`ds_name("DM", "DATA")`), resolved through the study's datasets like every dataset-naming read; a dataset the study lacks answers a missing. (Until 2026-10-01 the argument was ignored at `DATA` and the evaluated dataset answered.)

| parameter | type | required | description |
|---|---|---|---|
| `x` | string | no | The dataset's name as a string (`"DM"`); a bare column fails to load; defaults to the dataset under evaluation |
| `level` | level | no | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` — with no dataset name, an omitted level reads `DATA` (`ds_name()`); a dataset name needs its level |

**Example**

```yaml
expression: 'ds_name() !~ /^[A-Z0-9_]{1,8}$/'
```

### ds_structure

`ds_structure([x,] level)` → **value**

The dataset's ADaM data structure; `DEFINE` and `LIBRARY` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string | no | The dataset's name as a string (`"DM"`); a bare column fails to load; defaults to the dataset under evaluation |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'ds_structure("DEFINE")'
```

### extract_metadata

`extract_metadata(name)` → **value**

One dataset-metadata value of the current dataset, named by its key: `"dataset_name"`, `"dataset_label"`, `"dataset_size"` (bytes of the parsed file), `"dataset_location"` / `"filename"` (the source file's name), `"encoding"` / `"encoding_source"` (the charset the dataset's text was decoded with — below), or any other key the dataset's reader publishes (`"file_format"`, …).

Its result type follows the key: `"dataset_size"` is a **number**; `"dataset_name"`, `"dataset_label"`, `"dataset_location"`, `"filename"`, `"file_format"`, `"encoding"` and `"encoding_source"` are **strings**. A finding reporting a binding over one of these keys with no value prints `.` or `""` by that type ([Outcome](../authoring/outcome.md#output_variables)). Any other key is **untyped at load** — its value is whatever the dataset's reader publishes, present on one file format and absent on another — so a binding over it cannot be reported: naming it in `Output_Variables` is a load error (it is never derived, so simply leave it out). Reading it in the Check is always fine: a binding the engine does not derive is never reported — not even by a rule that lists no `Output_Variables` at all, whose reported names the engine otherwise infers from the Check. Use it in the Check only, or use a typed key.

**The decode charset.** `"encoding"` is the canonical Java name of the charset the reader decoded the dataset's text with (`UTF-8`, `ISO-8859-1`, `windows-1252`, `US-ASCII`, …), or `MIXED` when the strings of one dataset decoded with more than one (an RDS file whose R encoding marks differ). `"encoding_source"` says where that charset came from: `DECLARED` — the file names it (a sas7bdat header, R's per-string marks or the RDS header); `FORMAT` — the format fixes it (Dataset-JSON, Parquet, XLSX, CDT, Databricks, the OpenStudyBuilder and CDISC Library APIs); `CONFIGURED` — a charset other than the reader's default was chosen for a format that records none (XPT, CSV); `DEFAULT` — the reader's default, because nothing was declared or chosen, or what was declared could not be used. The value describes the decode, never the file's claim: a sas7bdat whose encoding byte maps to no usable charset reports the fallback it was read with, as `DEFAULT`. Use these snake-case keys: the reader publishes the values under `"Encoding"` / `"EncodingSource"`, and `extract_metadata("Encoding")` is an untyped raw lookup. A dataset whose reader publishes neither (a table built in memory) answers **missing**. A `.cdt` scenario declares `Encoding=` to simulate another format's decode ([Scenarios](../testing/scenarios.md)).

| parameter | type | required | description |
|---|---|---|---|
| `name` | string | yes | The metadata key — any **dataset-level** string: a literal (`"dataset_name"`), a `$`-binding holding one, or the expression such a binding would hold, written inline |

A **registry function**. It is an **aggregate**: one value for the dataset, computed once per rule and dataset and read by every row. Its argument is read once per call, so it takes any **dataset-level** string — a literal, a `$`-binding or the same expression inline, judged alike — and a per-row value there (a column, `upper(AETERM)`) fails to load as the level finding, the same for both spellings.

**Example**

```yaml
Bindings:
- name: "$dataset_location"
  expression: 'extract_metadata("dataset_location")'
Check:
  expression: '$dataset_location =~ /[A-Z]/'
```

The example above is the complete call.

**When there is nothing to answer** — **missing** — the calculation was not possible. `!=` against it fires (a missing is not equal to any value — rulings register `D34a`); `==` is true only against the same missing; an order comparison treats it as the lowest value (`D34 #5`): `X > it` and `it < X` fire, `X < it` does not; `empty()` is true.

### referenced_dataset_variables

`referenced_dataset_variables(name[, qualifiers=])` → **value (a list)**

The variable names of the dataset **named in a column value**, per row — the list a SUPP-- / CO /
RELREC record's `RDOMAIN` refers to, so a Check tests the row's `IDVAR` against *its* referenced
dataset's columns, never a flat union over every referenced domain. A registry function.

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | yes | The column naming a dataset (`RDOMAIN`) |
| `qualifiers` | dataset-level boolean | no | `true` adds the `QNAM` values of the dataset's `SUPP--` companion(s) after its columns; default `false` |

`name` is a **column reference** and is required — the former implicit `RDOMAIN` read is spelled
out. Per row the answer is the column-name list of every dataset member whose domain is the value
(a split family unions its members, in declaration order), in the datasets' own spelling —
`upper($vars)` folds the list element by element. A missing or blank value, or a value no dataset
carries, answers the **empty list**. The function reads the study's own datasets, never the CDISC
Library.

**`qualifiers=true`** appends the supplemental
qualifier names the referenced dataset's `SUPP--` companion(s) deliver — every `QNAM` of
`SUPP<dataset>` and of the domain-level members of its `SUPP<domain>` family, the same sources the
merged evaluation table reads — after the columns, in sorted order. It is what ADaM's `SRCVAR` may
hold when `SRCDOM` names a domain whose source is a supplemental qualifier (ADaMIG 1.1 and later):
`upper(SRCVAR) not in referenced_dataset_variables(SRCDOM, qualifiers=true)` fires on a `SRCVAR`
that is neither a column nor a qualifier of `SRCDOM`. A dataset without a `SUPP--` answers its
columns alone. The flag is a dataset-level boolean — a literal, or any expression that does not
vary per row — read once per call; a per-row operand is a load error.

**Example**

```yaml
Bindings:
- name: "$rdomain_variables"
  expression: 'referenced_dataset_variables(RDOMAIN)'
- name: "$rdomain_variables_upper"
  expression: 'upper($rdomain_variables)'
Check:
  expression: 'var_exists("RDOMAIN") and not empty(IDVAR) and IDVAR not in $rdomain_variables and IDVAR not in $rdomain_variables_upper'
```

The example above is the complete call.

**When there is nothing to answer** — **the empty list** — no dataset of that name, a blank or
missing `RDOMAIN`: `IDVAR not in $rdomain_variables` **fires**, `IDVAR in` does not.

**Missing and empty** — Always present — a list, never missing.

### referenced_domain_class

`referenced_domain_class(name)` → **value**

The CDISC Library observation class of the domain **named in a column value**, per row — upper-cased (`EVENTS`, `FINDINGS ABOUT`, …) whatever the Library's own spelling, or `""` when the Library cannot classify the domain.

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | yes | The column naming a domain (`RDOMAIN`) |

`name` is a **column reference** and is required — the former `RDOMAIN` default is gone. A missing cell answers that missing value. The function needs the CDISC Library: with none configured, or one that could not be consulted, the rule reports **SKIPPED**.

**Example**

```yaml
Bindings:
- name: "$rdomain_class"
  expression: 'referenced_domain_class(RDOMAIN)'
Check:
  expression: '$rdomain_class not in ["EVENTS", "INTERVENTIONS", "FINDINGS", "FINDINGS ABOUT"] and RDOMAIN != "DM" and RDOMAIN != "SV"'
```

The example above is the complete call.

**When there is nothing to answer** — **`""`** — the closed-world reading: the Library holds no class for that domain. So a `not in` / `!=` against a class literal **fires**.

### referenced_record_count

`referenced_record_count(dataset, value, key_variable=[, keys=])` → **value**

How many records of the dataset **named in a column value** carry this row's pointer value in the
column `key_variable` names and this row's `keys` — per row. The value-addressed record lookup: a `SUPP--` row's `RDOMAIN` / `IDVAR` /
`IDVARVAL`, a RELREC link, ADaM's `SRCDOM` / `SRCSEQ` traceability triple. It is a **count, never a
merge**: a `Child` entry pairs a pointer row with every parent record it addresses (`n:m`), and a
`1` side refuses a duplicate as a rule ERROR, so "resolves to exactly one record" is observable only
here.

| parameter | type | required | description |
|---|---|---|---|
| `dataset` | value | yes | The dataset name, per row — a column (`RDOMAIN`, `SRCDOM`) or a string naming one dataset |
| `value` | value | yes | The pointer value, per row (`IDVARVAL`, `SRCSEQ`) |
| `key_variable` | value or list of strings | yes | The column of the referenced dataset the pointer is looked up in: a per-row name (`IDVAR`), or a list of **candidate templates** resolved against the referenced dataset (`["--SEQ", "ASEQ"]` — a leading `--` is the referenced dataset's domain prefix; the first candidate it carries wins); one string is the one-candidate list (`"--SEQ"` is `["--SEQ"]`) |
| `keys` | list of columns | no | The same-named key columns that must agree on both sides (`[USUBJID]`, or `[STUDYID, USUBJID]`), written unqualified (a qualified `DM.USUBJID` is a load error: the referenced dataset is read without a join); a `$`-binding of names splices as in `group=` |

**What answers what.** A missing or blank `dataset`, `value` or column name answers **missing**; a
`dataset` value no study dataset carries answers **missing** (an unloaded dataset is
`CDISC-AD0746`'s finding, not a broken pointer — firing here would double-report); a column the
referenced dataset lacks answers **`0`** (nothing can match). Otherwise every matching record counts,
and a split family (`lbch` / `lbhe`) sums its members. The pointer compares as the join token of the
`SUPP--` / RELREC merges: both sides stripped, and canonicalised when the referenced column is numeric
(`"01"` and `"1.0"` match a `--SEQ` of `1`); a text column compares the stripped text exactly. The
keys compare by their text identity; a key column absent on either side is dropped from the key, and
a blank key component matches nothing. The candidate templates are resolved against the
**referenced** dataset, never against the dataset in hand (`--SEQ` on an `ADAE` row pointing at `AE`
is `AESEQ`); writing them bare (`[AESEQ, ASEQ]`) is a load error.

**Example**

```yaml
# ADaMIG 1.3 §3.3.9: a populated SRCSEQ is the --SEQ (or ASEQ) of a record of the same subject in
# the dataset SRCDOM names
Check:
  expression: 'not empty(SRCSEQ) and referenced_record_count(SRCDOM, SRCSEQ, key_variable=["--SEQ", "ASEQ"], keys=[USUBJID]) == 0'
```

```yaml
# SDTMIG 3.3 DV: a SUPPDV record pointing through DVSPID resolves to exactly one DV record
Scope: {Domains: {Include: [SUPPDV]}}
Check:
  expression: 'IDVAR in ["DVREFID", "DVSPID"] and not empty(IDVARVAL) and referenced_record_count(RDOMAIN, IDVARVAL, key_variable=IDVAR, keys=[USUBJID]) != 1'
```

**Missing and empty** — a missing answer compares as any missing does: `== 0` is silent on it,
`!= 1` **fires** on it (the register: `X != "Y"` on a missing value fires) — guard with
`not empty(IDVARVAL)` where the pointer may be blank. The dataset under evaluation is read through
the run's resolver; the referenced dataset is indexed once per rule and dataset and the index is
shared across rules — keyed on a compact hash of the key text, each hit verified. The count
is **per pointer row** whatever its operands are: with a string `dataset` and `value` and a bound
name list at `keys=` (`keys=$k`) it still reads this row's keys.

### referenced_key_is_unique

`referenced_key_is_unique(dataset, key_variable=[, keys=])` → **value (boolean)**

Whether the dataset **named in a column value** has at most one record per (`keys`, the column
`key_variable` names) — per row. The `keys` name columns of the **referenced** dataset, so they are
no read of this row: with a string `dataset` and a candidate `key_variable` the verdict is one fact
for the dataset, written keys and a bound name list alike. The value-addressed uniqueness verdict: RELREC declares `RELTYPE = "ONE"` for a dataset and a column,
and the declaration is checked against that dataset.

| parameter | type | required | description |
|---|---|---|---|
| `dataset` | value | yes | The dataset name, per row — a column (`RDOMAIN`) or a string naming one dataset |
| `key_variable` | value or list of strings | yes | The column of the referenced dataset, as for `referenced_record_count` (a per-row name such as `IDVAR`, or candidate templates) |
| `keys` | list of columns | no | The key columns the tuple starts with (`[USUBJID]`); a `$`-binding of names splices as in `group=` |

The policy is `is_unique_set`'s: `""` and a missing are real, distinct key components; a column the
referenced dataset lacks is **dropped** from the tuple — a stated answer over the columns it has
(with none present every record shares the empty key). A split family is judged as the **one
domain** it is (SDTMIG: `--SEQ` is unique across a split domain's datasets): a key carried twice —
inside one member or by two members (`lbch` and `lbhe` both holding subject `A`'s `LBSEQ` 1) — is
`false`, as `referenced_record_count` sums the members. A missing or blank `dataset` or column name, or a dataset no
study dataset carries, answers **missing**. A boolean value, not a predicate: compare it
(`== false`).

**Example**

```yaml
# SDTMIG 3.3 §8.3: a dataset RELREC declares ONE for has at most one record per USUBJID and IDVAR
Scope: {Domains: {Include: [RELREC]}}
Check:
  expression: 'empty(USUBJID) and RELTYPE == "ONE" and referenced_key_is_unique(RDOMAIN, key_variable=IDVAR, keys=[USUBJID]) == false'
```

The example above is the complete call.

**Missing and empty** — a missing verdict never equals `false`, so the example is silent on an
unknown dataset or a blank `IDVAR`.

### split_sibling_length_mismatch

`split_sibling_length_mismatch()` → **value (a list)**

The variables whose declared column length differs across the current dataset's split-family members (every dataset whose data-driven unsplit name equals the current one's), as a list — library-independent.

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `split_sibling_length_mismatch(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It reads the study inventory; a run whose dataset resolver cannot enumerate the study is a wiring defect and reports a rule **ERROR** (the retired operation answered the empty set there, silently).

**Example**

```yaml
Bindings:
- name: "$len_mismatch"
  expression: 'split_sibling_length_mismatch()'
Check:
  expression: 'varname() in $len_mismatch'
```

The example above is the complete call.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**. A dataset that is not split, and a family whose shared variables all agree, answer it.


### standard_domains

`standard_domains()` → **value (a list)**

The domain codes the standard publishes, as a list.

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `standard_domains(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It needs the CDISC Library: without one the rule is **SKIPPED** (*"Rule skipped — no Library access"*).

**Example**

```yaml
Bindings:
- name: "$sdtm_domains"
  expression: 'standard_domains()'
Check:
  expression: 'not empty(SRCDOM) and SRCDOM not in $sdtm_domains'
```

The example above is the complete call.

**When there is nothing to answer** — ⚠ **the rule is SKIPPED**, an exception to the totality rule above: a Library that publishes no dataset names for the standard is *library not available* (*"Rule skipped — library returned no data for standard_domains"*).


### study_domains

`study_domains()` → **value (a list)**

The distinct data-driven SDTM domain codes across the study — the `DOMAIN` cell of each dataset, not its member name — as a list: a split `LB` (`lbch` / `lbhe`) contributes one `LB`, which is what an `RDOMAIN` of `LB` matches; the no-domain datasets (`SUPP--`, `RELREC`) contribute `""`. Each code is **upper-cased** and listed **once after upper-casing**: a `DOMAIN` cell spelled `lb` or `Lb` is the one `LB` in the list (dataset names match ignoring case). To compare a value that may itself be lower case, upper-case it too — `upper(RDOMAIN) not in $study_domains`.

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `study_domains(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It reads the study inventory; a run whose dataset resolver cannot enumerate the study is a wiring defect and reports a rule **ERROR** (the retired operation answered the empty set there, silently).

**Example**

```yaml
Bindings:
- name: "$study_domains"
  expression: 'study_domains()'
Check:
  expression: 'not empty(RDOMAIN) and RDOMAIN not in $study_domains'
```

The example above is the complete call.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.


## Variable metadata

The `var_*` accessors read the metadata of a variable **of the dataset under evaluation**, so the
name they take is the variable's own name. A qualified literal (`var_label("DM.AGE")`) names column
`AGE` of the rule's declared join `DM` — read from the merged table at `DATA`, from the dataset `DM`
at `DEFINE` / `LIBRARY`; a qualifier naming no join of the rule answers a missing, and a qualified
name together with `dataset=` fails to load (only the `vlm_*` accessors refuse a qualified name). Another
dataset's DATA-level label, type, length or format is read with `dataset=`
(`var_label("AGE", dataset="DM")`), whose variable name matches in any letter case.
With no level an accessor that has a `DATA` reading reads `DATA`: `var_label()` is the label of the
variable being iterated, `var_label("AETERM")` that of `AETERM` (in the one-argument form a string
is the name unless it is a level — `"DATA"`, `"DEFINE"`, `"LIBRARY"`). The name is a **string**: a
bare column, `var_label(AETERM)`, fails to load in either form, naming `var_label("AETERM")`. An
accessor with no `DATA` reading (`var_role`, `var_core`, …) must name its level — `var_role()` fails
to load, naming `var_role("DEFINE")` and `var_role("LIBRARY")`.

### column_series_metadata

`column_series_metadata([name,] name_pattern=, min_length=)` → **value (boolean)**

Whether a numbered column series (`COVAL1..n`) is **incomplete**: its members are the columns whose names match `name_pattern` (by their trailing number) plus the optional un-numbered base column `name` as member 0. `true` (the check fires) when the present numbers are not contiguous from the lowest to the highest, or when `min_length` is given and a member other than the last declares a length below it; `false` with fewer than two members.

| parameter | type | required | description |
|---|---|---|---|
| `name` | string | no | The un-numbered base column's name |
| `name_pattern` | regex | yes | A regular expression over column names |
| `min_length` | number | no | The length every non-terminal member must reach |

A **registry function**. It is an **aggregate**: one value for the dataset, computed once per rule and dataset and read by every row. Its arguments are read once per call, so each is a **literal** — a column or any other expression there fails to load. The base column is named by a **string** (`"COVAL"`) — its metadata is read, not its values — so a bare `COVAL` fails to load; an invalid `name_pattern` or a non-integer `min_length` fails to load too.

**Example**

```yaml
Bindings:
- name: "$coval_series_incomplete"
  expression: 'column_series_metadata("COVAL", name_pattern="^COVAL\\d+$", min_length=200)'
Check:
  expression: '$coval_series_incomplete == true'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$coval_series"
  expression: 'column_series_metadata(name_pattern="^COVAL\\d+$")'
Check:
  expression: '$coval_series == true'
```

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.

### cross_dataset_variable_metadata

`cross_dataset_variable_metadata(name, domain=)` → **value (per variable)**

Per variable of the current dataset, one metadata attribute — `"label"`, `"data_type"` (`Char` / `Num`), `"length"` (a **number**) or `"format"` (the other three are **strings**) — of the **same-named variable in another dataset**: the one `domain=` names, or with `domain="*"` the first dataset of the study that carries the variable (shortest name first, then alphabetical — so an SDTM domain wins over an ADaM dataset), the current dataset excluded by its name.

| parameter | type | required | description |
|---|---|---|---|
| `name` | string | yes | The attribute: `label`, `data_type`, `length` or `format` |
| `domain` | string | yes | The dataset to read from, or `"*"` for the whole study |

A **registry function**. It is an **aggregate**: one value for the dataset, computed once per rule and dataset and read by every row. Its arguments are read once per call, so each is a **literal** — a column or any other expression there fails to load. Its one value is the whole **per-variable** answer, which the rule reads variable by variable: a Check comparing `var_label() != $x` runs once per variable and sees that variable's value (reported in the finding too). It is read **only through a binding** — written inline in a Check it fails to load. `domain` is a **string**, since `"*"` is a pattern, not a dataset; an attribute outside the four fails to load (the retired operation answered nothing, silently). The binding is therefore the rule's **variable cursor** — the one declared exception to *"where `$x` is accepted, the expression it holds is accepted too"*: written inline, the call would be the whole map, not the current variable's value, so it is a load error that says why (*"… is read only through a binding: that binding is the rule's variable cursor (the rule runs once per variable, $x is that variable's value), so the inline call — the whole map, not one value — cannot stand for it"*).

**Example**

```yaml
Bindings:
- name: "$adsl_data_type"
  expression: 'cross_dataset_variable_metadata("data_type", domain="ADSL")'
Check:
  expression: 'not empty($adsl_data_type) and var_type() != $adsl_data_type'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$sdtm_label"
  expression: 'cross_dataset_variable_metadata("label", domain="*")'
Check:
  expression: 'not empty($sdtm_label) and var_label() != $sdtm_label'
```

**When there is nothing to answer** — **missing** for a variable the other dataset does not carry, and for every variable when the named dataset is not in the study. `!=` against it fires (a missing is not equal to any value — rulings register `D34a`); `==` is true only against the same missing; an order comparison treats it as the lowest value (`D34 #5`): `X > it` and `it < X` fire, `X < it` does not; `empty()` is true.

### duplicate_label_variables

`duplicate_label_variables()` → **value (a list)**

The variables of the current dataset whose declared label is shared by another of its columns, as a list; a blank label is never a duplicate.

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `duplicate_label_variables(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row.

**Example**

```yaml
Bindings:
- name: "$dup_labels"
  expression: 'duplicate_label_variables()'
Check:
  expression: 'varname() in $dup_labels'
```

The example above is the complete call.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**. Every label unique answers it.


### expected_variables

`expected_variables()` → **value (a list)**

The variables the CDISC Library marks Expected for this dataset, as a list — for an ADaM dataset, the expected variables of its detected data structure(s), naming templates substituted against the dataset's own columns, and `--` and domain-prefixed names resolved as [`required_variables()`](#required_variables) describes.

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `expected_variables(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It needs the CDISC Library: without one the rule is **SKIPPED** (*"Rule skipped — no Library access"*).

**Example**

```yaml
Bindings:
- name: "$expected_variables"
  expression: 'expected_variables()'
Check:
  expression: 'not contains_all($dataset_variables, $expected_variables)'
```

The example above is the complete call.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**. ⚠ Two exceptions SKIP the rule instead: an ADaM dataset for which the declared product publishes no data structure (*"Rule skipped — library returned no data for expected_variables"*), and the Define-XML fallback for an unreachable Library answering nothing.


### get_column_order_from_dataset

`get_column_order_from_dataset()` → **value (a list)**

The dataset's own column names in declaration order, in the dataset's own spelling, as a list — the "actual" side of a presence or ordering check.

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `get_column_order_from_dataset(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row.

**Example**

```yaml
Bindings:
- name: "$dataset_variables"
  expression: 'get_column_order_from_dataset()'
Check:
  expression: 'not shares_elements_with($dataset_variables, $timing_variables)'
```

The example above is the complete call.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**. (A dataset with no columns is the only way to get it.)


### get_column_order_from_library

`get_column_order_from_library()` → **value (a list)**

The column order the CDISC Library publishes for this dataset, as a list — the IG-defined variable order for the dataset's domain, the "expected" side of an ordering check.

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `get_column_order_from_library(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It needs the CDISC Library: without one the rule is **SKIPPED** (*"Rule skipped — no Library access"*).

**Example**

```yaml
Bindings:
- name: "$allowed_variables"
  expression: 'get_model_column_order()'
- name: "$library_order"
  expression: 'get_column_order_from_library()'
Check:
  expression: 'upper(varname()) in $allowed_variables and upper(varname()) not in $library_order'
```

The example above is the complete call: a variable the model allows that the implementation guide does not list for the domain (`FDA-SD1076` / `PMDA-SD1076`). For an ADaM dataset the list is the governing data structure's (ADSL, BDS, OCCDS …), selected as `Scope.Data_Structures` detects it, with its naming templates substituted against the dataset's columns (`TRTxxP` → the dataset's `TRT01P`) as `required_variables()` substitutes them. Its `--` and domain-prefixed names resolve as [`required_variables()`](#required_variables) describes. The ADaM stem rules `CDISC-AD0503`–`AD0520` do not read it: they read their stem's phrase through [`label_template("LIBRARY", stem="*SDT")`](#label_template).

**When there is nothing to answer** — ⚠ **the rule is SKIPPED** for that dataset, an exception to the totality rule above: an absent or empty column order is *library not available* (*"Rule skipped — library returned no data for get_column_order_from_library"*). To keep a rule answering when the IG has no list for the dataset, bind [`get_dataset_filtered_variables()`](#get_dataset_filtered_variables) instead — the same IG-resolved list intersected with the dataset's columns, which answers the empty set.


### get_dataset_filtered_variables

`get_dataset_filtered_variables(key_name=, key_value=)` → **value (a list)**

Library variables of the current dataset filtered by metadata, intersected with the columns present — the IG-resolved list for the dataset's domain, keeping the variables whose attribute `key_name=` has the value `key_value=`; without a filter, the whole intersected list.

| parameter | type | required | description |
|---|---|---|---|
| `key_name` | string | no | The metadata attribute to filter on — a static string literal naming an attribute the Library publishes for a variable (`role`, `core`, `label`, `simpleDatatype`, …); any other name fails to load, naming the attributes that exist |
| `key_value` | string | no | The value that attribute must have — any **dataset-level** string (a literal, a `$`-binding or the same expression inline; a per-row value fails to load), compared exactly |

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `get_dataset_filtered_variables(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It needs the CDISC Library: without one the rule is **SKIPPED** (*"Rule skipped — no Library access"*). Both parameters are read once per call, which is why they must be literals (a column bound there would be read at row 0 and silently stand for every row). A `key_name` the dataset's own Library rows do not carry (`core` on a domain the run's IG does not define, whose rows are the SDTM Model's; `role` on an ADaM dataset) is legal at load, because servability is a per-dataset fact — at run time it **SKIPs** the rule when a `key_value` is given (the filter could never match); without a `key_value` the unserved key matches every row.

**Example**

```yaml
Bindings:
- name: "$TIMING_VARIABLES"
  expression: 'get_dataset_filtered_variables(key_name="role", key_value="Timing")'
Check:
  expression: '$record_count_per_subject_test_timing > 1 and (empty(--REPNUM) or not is_unique_set([--REPNUM, USUBJID, --TESTCD, $TIMING_VARIABLES], regex="^\\d{4}-\\d{2}-\\d{2}"))'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$expected_variables"
  expression: 'get_dataset_filtered_variables(key_name="core", key_value="Exp")'
Check:
  expression: 'varname() in $expected_variables and var_is_empty(varname())'
```

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**. Under the Define-XML fallback for an unreachable Library, an empty answer means the define could not answer and the rule is **SKIPPED** instead. ⚠ A `key_name` none of the dataset's Library rows carries, with a `key_value`, is *library not available* and the rule is **SKIPPED** (*"Rule skipped — library returned no data for get_dataset_filtered_variables"*) — never a silent pass: on a sponsor domain, or a standard domain the run's IG leaves out, the rows are the SDTM Model's and carry no `core`. The key, the dataset and the attributes the rows do carry are in the log line.


### get_model_column_order

`get_model_column_order()` → **value (a list)**

The column order of the standard's model for the dataset's observation class, as a list.

It is the **pure SDTM model walk** for the dataset's observation class: the IG's own dataset variables are never merged in. Where an IG runs ahead of the model version it links to, a variable the IG defines for the dataset is therefore **absent** from this list (60 of them at SENDIG 3.0, 5 at SDTMIG 3.3, none at SDTMIG 3.4). `FDA-SD0058` binds it next to the IG list from [`get_dataset_filtered_variables()`](#get_dataset_filtered_variables) and reports such a variable at `WARNING`, a variable in neither list at `ERROR`.

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `get_model_column_order(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It needs the CDISC Library: without one the rule is **SKIPPED** (*"Rule skipped — no Library access"*).

**Example**

```yaml
Bindings:
- name: "$model_order"
  expression: 'get_model_column_order()'
Check:
  expression: 'varname() not in $model_order and varname() not in $library_order and varname() not in $events_vars and varname() not in $interventions_vars and varname() not in $findings_vars and varname() not in $findings_about_vars'
```

The example above is the complete call.

**When there is nothing to answer** — ⚠ **the rule is SKIPPED** for that dataset, an exception to the totality rule above: an absent or empty model list is *library not available* (*"Rule skipped — library returned no data for get_model_column_order"*), so `varname() not in $s` never fans out over every column.

**A domain the run's IG does not define** — a sponsor-defined domain, or a standard one the IG leaves out (at SENDIG-AR 1.0 every SEND domain but DM/CM/FA/MH) — is walked under exactly the observation class the **scope matcher** gives the dataset (the study's declared class, the product, the curated domain map — these three keyed by the `DOMAIN` value, or by the dataset name when there is no `DOMAIN` column — then the topic-variable sniff of the dataset's own columns, which needs a `DOMAIN` column; and for an `AP--` dataset, finally its parent domain's class), so the class a rule is scoped by and the class its model list comes from are one class. Only a dataset no class tier places — a code the curated map does not know whose columns show no topic variable the sniff recognises (e.g. SDTMIG-MD's DI and DR), or an `AP--` dataset whose parent domain is not in the study, which the scope matcher places nowhere either — or one whose class the linked model has no variables for, resolves an empty list — and then the rule is skipped. [`get_model_filtered_variables()`](#get_model_filtered_variables) and [`get_parent_model_column_order()`](#get_parent_model_column_order) read this same walk. [`get_dataset_filtered_variables()`](#get_dataset_filtered_variables) and [`natural_key_variables()`](#natural_key_variables) read the IG-merged walk, which for such a domain is this one when its class is a general-observation class (Events, Interventions, Findings, Findings About) — and the **empty set** when its class is special-purpose, trial-design or relationship (e.g. CO, TS or RELREC in a SENDIG-AR 1.0 run; an `AP--` dataset keeps only its associated-persons identifiers): the IG-merged walk has no model dataset to fall back to. Where this lookup skips, the filtered lookups answer the empty set. [`get_column_order_from_library()`](#get_column_order_from_library) stays keyed by the domain name: the IG has no column order for such a domain, so it skips.


### get_model_filtered_variables

`get_model_filtered_variables(key_name=, key_value=, model_class=)` → **value (a list)**

Model variables filtered by metadata, **not** intersected with the dataset's columns — the model-level set itself. `model_class=` walks the **named** general-observation class's model table instead of the dataset's own class.

| parameter | type | required | description |
|---|---|---|---|
| `key_name` | string | no | The metadata attribute to filter on — a static string literal naming an attribute the Library publishes for a variable (`role`, `core`, `label`, `simpleDatatype`, …); any other name fails to load, naming the attributes that exist |
| `key_value` | string | no | The value that attribute must have — any **dataset-level** string (a literal, a `$`-binding or the same expression inline; a per-row value fails to load), compared exactly |
| `model_class` | string | no | The general-observation class whose model table to walk — a static string literal; `Events`, `Interventions`, `Findings`, `Findings About` in any letter case and spacing (`"EVENTS"`, `" Findings About "`), any other spelling fails to load |

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `get_model_filtered_variables(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It needs the CDISC Library: without one the rule is **SKIPPED** (*"Rule skipped — no Library access"*). The three parameters are read once per call and must be literals. A `key_name` the walked rows do not carry (`core` on a model walk of a standard SDTM domain) is legal at load — SUPP--/SQ-- datasets and every ADaM dataset publish `core` on this walk — and at run time **SKIPs** the rule when a `key_value` is given; without a `key_value` the unserved key matches every row.

**Example**

```yaml
Bindings:
- name: "$timing_variables"
  expression: 'get_model_filtered_variables(key_name="role", key_value="Timing")'
Check:
  expression: 'not shares_elements_with($dataset_variables, $timing_variables)'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$events_vars"
  expression: 'get_model_filtered_variables(model_class="EVENTS")'
Check:
  expression: 'varname() not in $model_order and varname() not in $library_order and (varname() in $events_vars or varname() in $interventions_vars or varname() in $findings_vars or varname() in $findings_about_vars)'
```

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**. Under the Define-XML fallback for an unreachable Library, an empty answer means the define could not answer and the rule is **SKIPPED** instead. ⚠ With `model_class=`, a class the linked model has no variables for is *library not available* and the rule is **SKIPPED** (*"Rule skipped — library returned no data for get_model_filtered_variables"*) — the dataset's own-class walk answers the empty set, the named-class walk does not. ⚠ Likewise a `key_name` none of the walked rows carries, with a `key_value`: the rule is **SKIPPED** with the same text (*"Rule skipped — library returned no data for get_model_filtered_variables"*); the key, the dataset and the attributes the rows do carry are in the log line.


### get_parent_model_column_order

`get_parent_model_column_order(name)` → **value (a list, per row)**

The model column order of the **parent domain named in a column value**, per row — for a `SUPP--` dataset, the SDTM model variables of the domain each row's `RDOMAIN` names, so `QNAM in $model_variables` asks whether the qualifier duplicates a parent variable.

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | yes | The column holding the parent domain code (`RDOMAIN`) — a column reference; a quoted name fails to load |

A **registry function**; it takes the column naming the parent — `get_parent_model_column_order()` with no argument fails to load. It needs the CDISC Library: without one the rule is **SKIPPED** (*"Rule skipped — no Library access"*). Unlike the other list functions it answers **per row**: each row reads the list of the parent its own cell names (the lists are computed once per distinct parent). ⭐ **A split parent resolves**: when the parent domain is submitted as split members (`LBCH` + `LBHE` for `RDOMAIN = LB`), the list is the union of the members' model column orders, in member-name order (members sorted by dataset name, `LBCH` before `LBHE`), so the list is the same on every run; a dataset named exactly like the parent is used alone when it exists, and a study dataset that cannot be opened is never a member and does not stop the parent resolving — the retired operation looked for a dataset literally named `LB`, found none, and answered nothing for every such row.

**Example**

```yaml
Bindings:
- name: "$model_variables"
  expression: 'get_parent_model_column_order(RDOMAIN)'
Check:
  expression: 'QNAM in $model_variables'
```

The example above is the complete call.

**When there is nothing to answer** — a row whose cell is blank, or names a parent no dataset of the study carries, reads **the empty set** (`QNAM in $s` is false; `not in` fires). ⚠ The rule is **SKIPPED**, an exception to the totality rule above, when **no** row's parent resolves a model list (*"Rule skipped — library returned no data for get_parent_model_column_order"*), and when a resolved parent's model list is *library not available* (no product declared, or the Library unreachable) — as [`get_model_column_order()`](#get_model_column_order) skips for its own dataset.


### get_permitted_column_order

`get_permitted_column_order()` → **value (a list)**

The model column order of [`get_model_column_order()`](#get_model_column_order) with every **permitted additional variable** the dataset carries placed where the implementation guide puts it — the reference order the five variable-order rules (`CDISC-CG0330`, `CDISC-CG0664`, `FDA-SD1079`, `PMDA-SD1079`, `CDISC-SEND-0393`) compare a dataset against. The authority is SDTMIG section 4.1.1.4: *"Variables for the three general observation classes must be ordered with Identifiers first, followed by the Topic, Qualifier, and Timing variables. Within each role, variables must be ordered as shown in SDTM: Tables 2.2.1 … 2.2.5."* The IG applies the same rule to a variable added to a special-purpose dataset (DM Assumption 9), and SENDIG section 4.1.2 orders the variables of its domains the same way.

- **Only special-purpose datasets get additions placed.** The class is the one the model order was built from (the product's class for the domain — never a Define-XML class): `Special-Purpose` (SDTMIG DM, CO, SE, SV, SM; SENDIG DM, CO, SE, SV, SJ). Every other dataset answers exactly `get_model_column_order()`.
- **What counts as an addition**: a column of the dataset that is in the Findings-class model walk — the general-observation identifiers, the Findings topic and qualifiers, the timing variables — and not in the dataset's own model order. A column in neither is a sponsor variable and stays out, as it does today; whether an addition is *permitted* at all is the business of `CDISC-CG0013-A`..`D` and `FDA-SD0058`, not of this list.
- **Where it goes.** Roles rank Identifier, Topic, any qualifier, Timing. An addition goes before the first own variable of its role whose position in the model's role table comes after it — or right after the last such variable; when the dataset has no own variable of its role that the role table lists, right after the last own variable of an equal or earlier role (the start, if none). Identifier and timing positions come from the general-observation table as all three general classes list it (so `SVSTDTC` places `EPOCH` before it at SDTMIG 3.4, although the Findings class does not carry `--STDTC` there). Additions never reorder one another or the dataset's own variables: the list restricted to the model order **is** the model order, so a dataset this order passes also passes the model order.
- **Roles of the dataset's own variables** come from the implementation guide's row for the variable (whether or not the dataset carries it), else the model's, else the first model role table that names it.

Worked examples (SDTMIG 3.3):

| dataset | carries | reference (abridged) |
|---|---|---|
| SV | `EPOCH` | … `VISITDY` **`EPOCH`** `SVSTDTC` … |
| SV | `SVSEQ` | … `USUBJID` **`SVSEQ`** `VISITNUM` … |
| SE | `SESTDY`, `SEENDY` | … `SEENDTC` **`SESTDY` `SEENDY`** `SEUPDES` |
| DM | `DMXFN`, `VISITNUM`, `VISIT`, `VISITDY` | … `COUNTRY` **`DMXFN` `VISITNUM` `VISIT` `VISITDY`** `DMDTC` `DMDY` |
| CO (3.4) | `COGRPID`, `VISITNUM` | … `COSEQ` **`COGRPID`** `IDVAR` … `COEVALID` **`VISITNUM`** `CODTC` `CODY` |

It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row; names are upper-cased. It needs the CDISC Library: without one the rule is **SKIPPED** (*"Rule skipped — no Library access"*).

**Example**

```yaml
Bindings:
- name: "$permitted_column_order"
  expression: "get_permitted_column_order()"
- name: "$permitted_column_order_upper"
  expression: "upper($permitted_column_order)"
- name: "$column_order_from_dataset_upper"
  expression: "upper(get_column_order_from_dataset())"
- name: "$not_in_reference"
  expression: "minus($column_order_from_dataset_upper, subtract=$permitted_column_order_upper)"
- name: "$known_order"
  expression: "minus($column_order_from_dataset_upper, subtract=$not_in_reference)"
Check:
  expression: >-
    not empty($permitted_column_order) and not is_ordered_subset_of($known_order,
    $permitted_column_order_upper)
```

The example above is the shape of `CDISC-CG0330`: the dataset's columns the reference knows must appear in the reference's order.

**When there is nothing to answer** — ⚠ **the rule is SKIPPED** exactly when [`get_model_column_order()`](#get_model_column_order) skips (no model list for the dataset). Every other missing input — no Findings role table, no implementation-guide rows — makes it answer the model order itself, never a skip.

### label_template

`label_template([x,] "LIBRARY"[, stem="*DT"])` → **value**

The variable's library label as a **template**, in the grammar of [`matches_label_template`](#matches_label_template); `LIBRARY` only — the template is a product of the Library's naming conventions, which no Define-XML publishes.

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `LIBRARY` |
| `stem` | string literal | no | A published `*`-stem (`"*DT"`): read that stem's template instead of the column's own (the stem-selecting form below) |

**Example**

```yaml
expression: 'not empty(label_template("LIBRARY")) and not matches_label_template(var_label(), label_template("LIBRARY"))'
```

**How the column finds its template.** An ADaM product's variables are keyed by **data structure** (ADSL, BDS, OCCDS …), and the dataset's structures are detected as the `Scope.Data_Structures` gate detects them (the Define-XML declaration first). Within the governing structure the column resolves, in order: by its **literal** name; through a published **index template** — `TRT01P` is `TRTxxP`, `AVALCAT1` is `AVALCATy`, `P02S1SDT` is `PxxSwSDT`, with `xx`/`zz` two digits, `y` any number and `w` one digit; through the **ADSL** structure (an ADSL variable copied into a BDS or OCCDS dataset keeps its ADSL definition); and last through the longest published `*`-**stem** (`XXSDT` is `*SDT`'s, never `*DT`'s). `var_name("LIBRARY")` then names what resolved — the published spelling, the template (`TRTxxP`) or the stem (`*SDT`) — and `var_label("LIBRARY")` the label with the column's digits substituted as whole words (`Planned Treatment for Period 01`). A `*`-stem serves **no label** and no type, only its phrase here, so a rule guarded by `not empty(var_label("LIBRARY"))` never judges a stem column — the stem rules (`CDISC-AD0503`–`AD0520`) read the stem-selecting form below.

**What the template holds.** For a literal, the label itself. For an index template, the substituted label, and — when the name carries `y` or `zz` — a trailing ` ...`, the IG's descriptive tail (*"descriptive text is allowed at the end of the labels of variables whose names contain indexes 'y' or 'zz'"*): `AVALCAT1` → `Analysis Value Category 1 ...` (ADaMIG 1.1–1.3; ADaMIG 1.0 publishes `Analysis Category y`, so there it is `Analysis Category 1 ...`), `TRT01P` → `Planned Treatment for Period 01` (an `xx` name allows none). For a stem, the published phrase: `{Start Date}`; ADaMIG 1.0 spells it `Start Date of ...`. **Missing** — so a `not empty(label_template("LIBRARY"))` guard decides — for a column the Library does not know, a column spelled like the template itself (`TRTXXP`), two templates matching one column, and one name the structure publishes twice with different labels: the OCCDS structures publish `DECDORGw` both as the MedDRA `PT in Original Dictionary w` and as the WHO Drug `Standardized Med Name in Orig Dict w`, and which one a `DECDORG1` must carry depends on a dictionary the name does not tell. Where a subclass structure publishes a name its base publishes too, the subclass's row governs.

**The stem-selecting form.** `label_template("LIBRARY", stem="*DT")` answers the **named** stem's template when the column's name ends in that stem's suffix and the column is a stem case — no literal, index template or ADSL name claims it — and a **missing** otherwise (a library variable such as `ADT` or `TRTSDT` is governed by its own published label, never by the suffix convention). A nested column answers every stem it ends in: `RANDSDT` is `{Date}` for `stem="*DT"` and `{Start Date}` for `stem="*SDT"`, so each stem rule judges every column its suffix matches with its **own** phrase, and `RANDSDT` is judged by `CDISC-AD0503` and `AD0509` alike. The stem is a string literal rooted at `*` with at least one character after it; any other value, and `stem=` on any other accessor, is a load error. A finding of a rule whose `label_template` reads all name one stem reports **that** stem's template as `library_variable_label_template`; the plain form keeps the column's own template (the longest stem it ends in).

```yaml
expression: 'ends_with(upper(varname()), "DT") and not empty(label_template("LIBRARY", stem="*DT")) and not matches_label_template(var_label(), label_template("LIBRARY", stem="*DT"))'
```

**An ADaMIG 1.1 run and ADAE.** ADaMIG 1.1 publishes no occurrence structure; the OCCDS product of the IG's own generation is added for the run (OCCDS 1.0 for ADaMIG 1.1, OCCDS 1.1 for 1.2 and 1.3) unless a product publishing one is declared with `-mp`, and the run log names it. Nothing is added for ADaMIG 1.0 or a TIG leg.

### natural_key_variables

`natural_key_variables()` → **value (a list)**

The natural-key-forming role variables present in the dataset — the Library variables of the dataset's domain whose role is Timing, Record Qualifier, Variable Qualifier, Result Qualifier, Synonym Qualifier or Grouping Qualifier, intersected with the columns present.

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `natural_key_variables(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It needs the CDISC Library: without one the rule is **SKIPPED** (*"Rule skipped — no Library access"*).

**Example**

```yaml
Bindings:
- name: "$natural_key"
  expression: 'natural_key_variables()'
Check:
  expression: 'not is_unique_set([USUBJID, --TESTCD, $natural_key])'
```

The example above is the complete call.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**. Under the Define-XML fallback for an unreachable Library, an empty answer means the define could not answer and the rule is **SKIPPED** instead.


### required_variables

`required_variables()` → **value (a list)**

The variables the CDISC Library marks Required (`Req`) for this dataset, as a list — for an ADaM dataset, the required variables of its detected data structure(s), naming templates substituted against the dataset's own columns.

**An ADaM occurrence dataset's domain-bound names.** An occurrence structure publishes the variables it copies from an SDTM domain as `--` names (`--SEQ`, `--TERM`), and the base OCCDS structures mix the AE and CM conventions (`CMTRT` and `AESER` are both Required in OCCDS 1.0). The dataset's **source domains** come from the occurrence variables it carries, as ADaMIG-OCCDS says (*"the 2-letter prefix of the variable name is the SDTM domain name"*), never from its name: a domain is named only by its sequence or topic variable — every column named a two-letter prefix plus `SEQ`, `TERM` or `TRT` (letter case ignored) names its prefix, so `AESEQ` and `AETERM` both name `AE` — while a `--DECOD` column alone names nothing (an ADSL `DCDECOD` copied onto an `ADAE` does not make it a two-domain dataset), and neither do `ASEQ`, `SRCSEQ` or timing variables. With **one** domain a `--` name resolves to it and is listed whether or not the column is present: an `ADAE` with `AETERM` and no `AESEQ` is asked for `AESEQ`. With **several** (a multi-domain dataset, for which the IG offers `--SEQ` per domain or `SRCDOM` / `SRCSEQ`) or **none**, no `--` name is listed: the function never names a variable it cannot tie to one domain. A name that carries a domain's prefix — its published description opens with that domain's source token (`CMTRT`: `CM.CMTRT`) — is left out only when the dataset's occurrence variables name other domains and not that one, so an `ADAE` whose variables name `AE` is not asked for `CMTRT`; a dataset with no occurrence variable at all is still asked for every such name, so a missing column never hides its own requirement. ADSL, BDS and TTE publish no such names.

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `required_variables(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It needs the CDISC Library: without one the rule is **SKIPPED** (*"Rule skipped — no Library access"*).

**Example**

```yaml
Bindings:
- name: "$required_variables"
  expression: 'required_variables()'
Check:
  expression: 'varname() in $required_variables and empty(value())'
```

The example above is the complete call.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**. ⚠ Two exceptions SKIP the rule instead: an ADaM dataset for which the declared product publishes no data structure (*"Rule skipped — library returned no data for required_variables"*), and the Define-XML fallback for an unreachable Library answering nothing.


### var_ccode

`var_ccode([x,] level)` → **value**

The NCI C-code of the bound codelist; `DEFINE` and `LIBRARY` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'empty(var_ccode("DEFINE"))'
```

### var_codelist

`var_codelist([x,] level)` → **value**

The name of the codelist bound to the variable; `DEFINE` and `LIBRARY` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'not empty(var_codelist("LIBRARY"))'
```

### var_codelist_coded_codes

`var_codelist_coded_codes([x,] level)` → **value**

The codes of the bound codelist's terms, as a list; `DEFINE` and `LIBRARY` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'not empty(var_codelist_coded_codes("LIBRARY"))'
```

### var_codelist_coded_values

`var_codelist_coded_values([x,] level)` → **value**

The submission values of the bound codelist's terms, as a list; `DEFINE` and `LIBRARY` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'not empty(var_codelist_coded_values("LIBRARY"))'
```

### var_codelist_extended_values

`var_codelist_extended_values([x,] "DEFINE")` → **value**

The sponsor-added values of an extended codelist, as a list; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'not empty(var_codelist_extended_values("DEFINE"))'
```

### var_codelist_extensible

`var_codelist_extensible([x,] "LIBRARY")` → **value**

Whether the bound codelist is extensible; `LIBRARY` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `LIBRARY` |

**Example**

```yaml
expression: 'var_codelist_extensible("LIBRARY") == true'
```

### var_core

`var_core([x,] level)` → **value**

The variable's core designation — Required, Expected or Permissible; `DEFINE` and `LIBRARY` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'var_core("LIBRARY") == "Perm"'
```

### var_external_dictionary

`var_external_dictionary([x,] "DEFINE")` → **value**

The external dictionary the variable is coded against; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'var_name("DEFINE") in ["LBTOX", "LBTOXGR"] and empty(var_external_dictionary("DEFINE"))'
```

### var_external_dictionary_version

`var_external_dictionary_version([x,] "DEFINE")` → **value**

The version of that external dictionary; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'var_external_dictionary_version(varname(), "DEFINE")'
```

### var_format

`var_format([x,] [level])` → **value**

The variable's display format; `DATA` and `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | no | Which metadata to read — supported here: `DATA/DEFINE` — omitted, it reads `DATA` (`var_format()`, `var_format("AETERM")`) |

**Example**

```yaml
expression: 'empty(var_format())'
```

### var_has_codelist

`var_has_codelist([x,] "DEFINE")` → **value**

Whether the variable binds a codelist at all; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'var_has_codelist("DEFINE") == true'
```

### var_has_comment

`var_has_comment([x,] "DEFINE")` → **value**

Whether Define-XML attaches a comment to the variable; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'not empty(var_name("DEFINE")) and var_has_comment("DEFINE") == false'
```

### var_has_method

`var_has_method([x,] "DEFINE")` → **value**

Whether Define-XML attaches a derivation method to the variable; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'var_has_method("DEFINE") == false'
```

### var_has_value_list

`var_has_value_list([x,] "DEFINE")` → **value**

Whether Define-XML documents the variable per value — its `ItemDef` carries a `def:ValueListRef`; `DEFINE` only. Missing for a variable the define does not declare.

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'var_has_value_list("DEFINE") == true and vlm_has_method(varname()) != true'
```

### var_vlm_has_comment

`var_vlm_has_comment([x,] "DEFINE")` → **value**

Whether EVERY value-level entry of the variable carries a comment: its `ItemDef`'s `def:ValueListRef` names a `def:ValueListDef` with at least one `ItemRef`, and each entry's value-level `ItemDef` resolves and carries a `def:CommentOID`. `false` for a variable without a value list, or whose value list does not resolve, holds no entry, or has one entry without a comment; missing for a variable the define does not declare. One answer per variable, so a Check stays variable-level under both universes — unlike the per-record `vlm_has_comment(x)`, which answers the entry matching each row; `DEFINE` only.

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example** — a variable documented per value (Define-XML 2.1 §5.3.12.3) is not reported for lacking a variable-level comment:

```yaml
expression: 'not empty(var_name("DEFINE")) and var_has_comment("DEFINE") == false and var_vlm_has_comment("DEFINE") != true'
```

### var_vlm_has_method

`var_vlm_has_method([x,] "DEFINE")` → **value**

Whether EVERY value-level entry of the variable carries a derivation method (`ItemRef/@MethodOID` in its `def:ValueListDef`, each entry's `ItemDef` resolving); otherwise as `var_vlm_has_comment`. `DEFINE` only.

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'var_origin_type("DEFINE") == "Derived" and var_has_method("DEFINE") == false and var_vlm_has_method("DEFINE") != true'
```

### var_vlm_has_origin

`var_vlm_has_origin([x,] "DEFINE")` → **value**

Whether EVERY value-level entry of the variable has an origin (a `def:Origin` element or the v2.0 `@Origin` attribute on each entry's value-level `ItemDef`); otherwise as `var_vlm_has_comment`. `DEFINE` only.

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'not empty(var_name("DEFINE")) and empty(var_origin_type("DEFINE")) and var_vlm_has_origin("DEFINE") != true'
```

### var_label

`var_label([x,] [level])` → **value**

The variable's label; available at every level

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | no | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` — omitted, it reads `DATA` (`var_label()`, `var_label("AETERM")`) |

**Example**

```yaml
expression: 'var_label() =~ /^[a-z]/'
```

**At `LIBRARY`, a dataset whose domain the run's IG does not define has no label** — nor a codelist, core designation or ordinal: the SDTM model's labels are class-level ones, not the label a standard gives the domain, and a sponsor may adjust the labels of a domain it defines. A rule guarded by `not empty(var_label("LIBRARY"))` therefore does not judge such a dataset. Its name, data type and role do come from the model (see [`var_type`](#var_type)).

**At `LIBRARY` on an ADaM dataset**, a column named after a published index template (`TRT01P`, `ANL01FL`) reads the template's label with its digits substituted (`Planned Treatment for Period 01`), an ADSL variable copied into a BDS or OCCDS dataset reads its ADSL label, and a column governed only by a `*`-stem (`RANDSDT`) has **no label** — the stem's phrase is served by [`label_template`](#label_template), which says how the column found its row.

### var_length

`var_length([x,] [level])` → **value**

The variable's declared length; available at every level

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | no | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` — omitted, it reads `DATA` (`var_length()`, `var_length("AETERM")`) |

**Example**

```yaml
expression: 'var_length() > 200'
```

### var_mandatory

`var_mandatory([x,] "DEFINE")` → **value**

Whether Define-XML marks the variable mandatory; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'var_mandatory(varname(), "DEFINE") == "Yes"'
```

### var_name

`var_name([x,] [level])` → **value**

The variable's name; available at every level

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | no | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` — omitted, it reads `DATA` (`var_name()`, `var_name("AETERM")`) |

**Example**

```yaml
expression: 'not empty(var_name("DEFINE"))'
```

**At `LIBRARY`, a dataset whose domain the run's IG does not define** — a sponsor-defined domain, or a standard one the IG leaves out (at SENDIG-AR 1.0 every SEND domain but DM, CM, FA and MH; at SENDIG-DART 1.1 EG, PM, TF, VS, CV and RE) — takes its name from the SDTM model's variable of the observation class the scope matcher gives the dataset. If no class places the dataset, if the class is a special-purpose, trial-design or relationship one, or if the model has no such variable (a sponsor's own variable), there is no value, as for any variable the Library does not know.

### var_ordinal

`var_ordinal([x,] [level])` → **value**

The variable's position in the declared column order; available at every level

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | no | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` — omitted, it reads `DATA` (`var_ordinal()`, `var_ordinal("AETERM")`) |

**Example**

```yaml
expression: 'var_ordinal("DEFINE")'
```

### var_origin_type

`var_origin_type([x,] "DEFINE")` → **value**

The variable's Define-XML origin type — Collected, Derived and so on; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'not empty(var_name("DEFINE")) and empty(var_origin_type("DEFINE"))'
```

### var_role

`var_role([x,] level)` → **value**

The variable's role — identifier, topic, qualifier and so on; `DEFINE` and `LIBRARY` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'not empty(var_role("DEFINE"))'
```

**At `LIBRARY`, a dataset whose domain the run's IG does not define** — a sponsor-defined domain, or a standard one the IG leaves out (at SENDIG-AR 1.0 every SEND domain but DM, CM, FA and MH; at SENDIG-DART 1.1 EG, PM, TF, VS, CV and RE) — takes its role from the SDTM model's variable of the observation class the scope matcher gives the dataset. If no class places the dataset, if the class is a special-purpose, trial-design or relationship one, or if the model has no such variable (a sponsor's own variable), there is no value, as for any variable the Library does not know.

### var_type

`var_type([x,] [level])` → **value**

The variable's data type; available at every level

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or a per-row expression | no | The variable's name as a string (`"AETERM"`) or an expression whose value names it; a bare column fails to load; defaults to the variable being iterated |
| `level` | level | no | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` — omitted, it reads `DATA` (`var_type()`, `var_type("AETERM")`) |

**Example**

```yaml
expression: 'var_type() != "Num"'
```

**At `LIBRARY`, a dataset whose domain the run's IG does not define** — a sponsor-defined domain, or a standard one the IG leaves out (at SENDIG-AR 1.0 every SEND domain but DM, CM, FA and MH; at SENDIG-DART 1.1 EG, PM, TF, VS, CV and RE) — takes its data type from the SDTM model's variable of the observation class the scope matcher gives the dataset. If no class places the dataset, if the class is a special-purpose, trial-design or relationship one, or if the model has no such variable (a sponsor's own variable), there is no value, as for any variable the Library does not know.

### variable_names

`variable_names()` → **value (a list)**

Every variable name the standard publishes, across all of its datasets, as a list — the standard's whole variable vocabulary, not the current dataset's.

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `variable_names(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It needs the CDISC Library: without one the rule is **SKIPPED** (*"Rule skipped — no Library access"*).

**Example**

```yaml
Bindings:
- name: "$variable_names"
  expression: 'variable_names()'
Check:
  expression: 'QNAM in $variable_names'
```

The example above is the complete call.

**When there is nothing to answer** — ⚠ **the rule is SKIPPED**, an exception to the totality rule above: a Library that publishes no variable names for the standard is *library not available* (*"Rule skipped — library returned no data for variable_names"*).


## Define-XML and value-level metadata

The `vlm_*` accessors read the value-level metadata of a column **of the dataset under
evaluation**, its `WhereClause` judged on the evaluated row. A qualified name (`vlm_length(DM.AGE)`,
or the string form) fails to load, naming the bare spelling: a joined dataset's value-level
metadata is not served. (Until 2026-10-01 it never matched a value-level definition, silently.)

### define_dataset_names

`define_dataset_names()` → **value (a list)**

The dataset names the Define-XML declares, upper-cased, as a list.

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `define_dataset_names(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It needs the sponsor Define-XML: without one the rule is **SKIPPED** before any row is read.

**Example**

```yaml
Bindings:
- name: "$define_datasets"
  expression: 'define_dataset_names()'
Check:
  expression: 'not contains_all($study_datasets, $define_datasets)'
```

The example above is the complete call.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.


### define_key_variables

`define_key_variables()` → **value (a list)**

The key variables the Define-XML declares for the current dataset's domain, in key order, as a list.

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `define_key_variables(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It needs the sponsor Define-XML: without one the rule is **SKIPPED** before any row is read.

**Example**

```yaml
Bindings:
- name: "$define_key_variables"
  expression: 'define_key_variables()'
Check:
  expression: 'not is_unique_set([STUDYID, $define_key_variables])'
```

The example above is the complete call.

**When there is nothing to answer** — ⚠ **the rule is SKIPPED**, an exception to the totality rule above: a Define-XML that declares no key variables for the domain leaves a uniqueness check with no key to test, so the answer is *Define-XML not available* (*"Rule skipped — Define-XML returned no data for define_key_variables"*) rather than an empty key set that would make `is_unique_set` vacuous.


### codelist_decode

`codelist_decode([name])` → **value**

Per record, the decode the Define-XML codelist bound to the variable gives the record's value — the `Decode` of the `CodeListItem` whose `CodedValue` is the value. Needs the Define-XML (the rule is SKIPPED without one).

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read — a bare column, a quoted name, `varname()`, or nothing for the variable being iterated. A qualified name (`DM.X`) fails to load: a joined dataset's define metadata is not served |

**Example**

```yaml
expression: 'not empty(DTYPE) and vlm_has_method(AVAL) != true and empty(codelist_decode(DTYPE))'
```

**Missing** — the answer is missing when the variable is not in the define, binds no codelist or one without decodes (`EnumeratedItem`s, an `ExternalCodeList`), the value is empty, or the value is not one of the decoded terms. The example is the ADaM reading of "a `DTYPE` value must be documented": by the value-level method of the result variable **or** by a decode of the term in `DTYPE`'s codelist.

### define_variable_decode_matches

`define_variable_decode_matches([name])` → **value**

Whether the record's code and decode agree at variable level, per the Define-XML: the current
variable is the **code** variable, its ItemDef codelist supplies the coded-value → decode map, and
the record's decode partner (the code name without its trailing `N`, `NUM` or `CD` — `AVISITN` →
`AVISIT`, `VISITNUM` → `VISIT`, `PARAMCD` → `PARAM`) must hold the decode of the record's coded
value. The variable-level twin of [`vlm_decode_matches`](#vlm_decode_matches). Written as the
operand `define_variable_decode_matches` in a `Value Check against Define XML Variable` rule.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | `variable_name` / `varname()`, or nothing — the variable currently being iterated |

**Example**

```yaml
expression: 'not empty(value()) and define_variable_decode_matches(variable_name) == false'
```

**When there is nothing to answer** — No decision, so the row does not fire: an empty code value, a
variable whose codelist carries no decodes, a decode partner that cannot be resolved, or a code value
that is not a term of the codelist (an out-of-codelist value is another rule's concern). Without a
Define-XML the rule is SKIPPED.

### define_variable_names

`define_variable_names()` → **value (a list)**

The Define-XML ItemDef names for the current dataset's domain, in Define order, as a list.

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `define_variable_names(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It needs the sponsor Define-XML: without one the rule is **SKIPPED** before any row is read.

**Example**

```yaml
Bindings:
- name: "$define_variables"
  expression: 'define_variable_names()'
Check:
  expression: 'not contains_all($dataset_variables, $define_variables)'
```

The example above is the complete call.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.


### vlm_codelist_coded_codes

`vlm_codelist_coded_codes(X)` → **value**

The codes of the codelist bound by the value-level match, as a list

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read |

**Example**

```yaml
expression: 'variable_value not in vlm_codelist_coded_codes(varname())'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not empty(vlm_codelist_coded_codes())'
```


### vlm_codelist_coded_values

`vlm_codelist_coded_values(X)` → **value**

The submission values of the codelist bound by the value-level match, as a list

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read |

**Example**

```yaml
expression: 'value() not in vlm_codelist_coded_values(variable_name)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not empty(vlm_codelist_coded_values())'
```


### vlm_codelist_extensible

`vlm_codelist_extensible(X)` → **value**

Whether the codelist bound by the value-level match is extensible

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read |

**Example**

```yaml
expression: 'vlm_codelist_extensible(variable_name) == true'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not empty(vlm_codelist_extensible())'
```


### vlm_data_type

`vlm_data_type(X)` → **value**

The `@DataType` of the Define-XML item matched at value level

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read |

**Example**

```yaml
expression: 'vlm_data_type(varname()) == "text"'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not empty(vlm_data_type())'
```


### vlm_decode_matches

`vlm_decode_matches(X)` → **value**

Whether the record's code and decode agree at value level

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read |

**Example**

```yaml
expression: 'not empty(value()) and vlm_decode_matches(variable_name) == false'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not empty(vlm_decode_matches())'
```


### vlm_has_codelist

`vlm_has_codelist(X)` → **value**

Whether the value-level match binds a codelist

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read |

**Example**

```yaml
expression: 'vlm_has_codelist(variable_name) == true'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not empty(vlm_has_codelist())'
```


### vlm_has_comment

`vlm_has_comment(X)` → **value**

Whether the value-level `ItemDef` matched for the record carries a `def:CommentOID` — read exactly as `var_has_comment("DEFINE")` reads the variable's. Missing when no value-level entry matches the record.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read |

**Example**

```yaml
expression: 'vlm_has_comment(variable_name) == false'
```

**Missing versus false** — `== false` fires only on a **matched** entry without a comment; `!= true` fires also where no entry matches.

### vlm_has_method

`vlm_has_method(X)` → **value**

Whether the value-level `ItemRef` matched for the record carries a `MethodOID` — a derivation method documented for that value, as `var_has_method("DEFINE")` answers it for the whole variable. Missing when no value-level entry matches the record.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read |

**Example**

```yaml
expression: 'var_has_method("DEFINE") == false and var_has_comment("DEFINE") == false and vlm_has_method(varname()) != true and vlm_has_comment(varname()) != true'
```

**The composite** — the example is "neither the variable nor the record's value-level entry documents it": a define that documents `AVISIT` per `PARAMCD` only passes it, a define that documents it nowhere fires it. `== false` fires only on a **matched** entry without a method; `!= true` fires also where no entry matches.

### vlm_length

`vlm_length(X)` → **value**

The `@Length` of the Define-XML item matched at value level

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read |

**Example**

```yaml
expression: 'not empty(value()) and vlm_value_length(variable_name) > vlm_length(variable_name)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not empty(vlm_length())'
```


### vlm_mandatory

`vlm_mandatory(X)` → **value**

Whether the Define-XML item matched at value level is mandatory

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read |

**Example**

```yaml
expression: 'vlm_mandatory(variable_name) == "Yes" and empty(value())'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not empty(vlm_mandatory())'
```


### vlm_origin_type

`vlm_origin_type(X)` → **value**

The origin type of the value-level `ItemDef` matched for the record — `def:Origin/@Type`, else the v1.0 `@Origin` attribute, the same rule as `var_origin_type("DEFINE")`; `""` on a matched entry that declares none. Missing when no value-level entry matches the record.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read |

**Example**

```yaml
expression: 'empty(var_origin_type("DEFINE")) and empty(vlm_origin_type(varname()))'
```

**Why both levels** — Define-XML 2.1 §5.3.12.3: *"In some cases, def:Origin will be provided only at the Value level"*, and a parent whose every value-level `ItemDef` carries one *should* carry none itself. An origin missing at the variable level is only a finding when the record's value level has none either.

### vlm_type_conforms

`vlm_type_conforms(X)` → **value**

Whether the record's value conforms to the matched `@DataType`

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read |

**Example**

```yaml
expression: 'not empty(value()) and vlm_type_conforms(variable_name) == false'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not empty(vlm_type_conforms())'
```


### vlm_value_length

`vlm_value_length(X)` → **value**

The stored length of the record's value under the matched type

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read |

A missing value under a matched value-level entry is handed through with its own missing identity
(`.A` stays `.A`); a record that no value-level entry matches, or a type whose length is not measured,
gives the plain missing — so `vlm_value_length(X) > vlm_length(X)` never fires on either.

**Example**

```yaml
expression: 'not empty(value()) and vlm_value_length(variable_name) > vlm_length(variable_name)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not empty(vlm_value_length())'
```


## Controlled terminology and dictionaries

### codelist_terms

`codelist_terms(codelists=, level=, returntype=)` → **value (a list)**

The terms of the named CT codelists, in the representation `level=` / `returntype=` select, as one de-duplicated list in Library order.

| parameter | type | required | description |
|---|---|---|---|
| `codelists` | list of strings | yes | Which codelists to read, by codelist short name — any **dataset-level** list of strings: a list literal of string literals (`["DOMAIN"]`, `["ND"]`), a `$`-binding holding one, or the expression such a binding would hold, written inline (`union(…)`); several are unioned in order |
| `level` | string | yes | `"term"` reads the terms of each codelist; `"codelist"` reads the codelist's own entry. Any other value fails to load |
| `returntype` | string | no | Which attribute to return: `"value"` (submission value), `"code"` (NCI concept id, the C-code), `"pref_term"` (NCI preferred term) or `"synonym"` (the CDISC synonyms — every synonym of every term at `level="term"`, the codelist's own at `level="codelist"`). Any other value fails to load. Absent: `code` at `level="term"`, `value` at `level="codelist"` |

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `codelist_terms(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It needs the CDISC Library: without one the rule is **SKIPPED** (*"Rule skipped — no Library access"*). The three parameters are read once per call and must be literals — a bare `codelists=[DOMAIN]` names the `DOMAIN` column and fails to load, naming the quoted spelling. The eight `level` × `returntype` shapes: `term`/`value` the terms' submission values · `term`/`code` their C-codes · `term`/`pref_term` their preferred terms · `term`/`synonym` their CDISC synonyms, flattened in Library order without repeats (a term without synonyms contributes nothing) · `codelist`/`value` the codelist's own submission value · `codelist`/`code` its C-code · `codelist`/`pref_term` its preferred term · `codelist`/`synonym` its own synonyms. A synonym can equal a submission value — of its own term or of another one — so a rule that asks "a known synonym, not a submission value" writes both tests: `X not in $values and X in $synonyms`. ⚠ Compare the representation the other operand really carries: a codelist compared against 2-character submission values wants `"value"`, one compared against `C…` codes wants `"code"`.

**Example**

```yaml
Bindings:
- name: "$sndigver_terms"
  expression: 'codelist_terms(codelists=["SNDIGVER"], level="term", returntype="value")'
Check:
  expression: 'TSPARMCD == "SNDIGVER" and not empty(TSVAL) and TSVAL not in $sndigver_terms'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$domain_codes"
  expression: 'codelist_terms(codelists=["DOMAIN"], level="term")'
Check:
  expression: 'not empty(DOMAIN) and DOMAIN not in $domain_codes'
```

**When there is nothing to answer** — ⚠ **the rule is SKIPPED**, an exception to the totality rule above: when the named codelists resolve no term in the selected representation — a codelist the Library does not hold, or a shape the metadata store cannot serve — the answer is *library not available* (*"Rule skipped — library returned no data for codelist_terms"*), never an empty list a `not in` would fire over.


### define_available

`define_available()` → **boolean**

True when a Define-XML was **supplied** to the run — the CLI's `-dxp`, the data browser's define, the REST request's `defineXmlFilename` — including one that could not be parsed (that failure is reported on its own: one study-level **ERROR** finding `CUMBA-DEFINE-PARSE` under `STUDY`, beside the report's `Define_Metadata_Basis` line); false otherwise. Authorable, unlike the three machine-emitted gates: its purpose is the presence rule `not define_available()` (study level, scope `ALL`, one finding per run). It needs no define itself, so that rule is never SKIPPED for the absence it reports; the define-reading rules keep SKIPPING without a define, as before.

Takes no arguments.

**Example**

```yaml
Check:
  expression: 'not define_available()'
```

### dictionary_available

`dictionary_available(type)` → **boolean**

The external-dictionary availability gate: `true` on every row when a dictionary of the named type is loaded for the run, else `false`. The loader injects it as a Precondition for every inline `valid_external_dictionary_*` call, so a rule whose dictionary is not supplied SKIPs instead of passing; a declared (`$`-bound) dictionary function needs no gate, because the runner skips the rule from the function's own dictionary need.

| parameter | type | required | description |
|---|---|---|---|
| `type` | string | yes | Which external dictionary to test for, e.g. `"meddra"` |

**Example**

```yaml
Precondition:
  expression: 'dictionary_available("meddra")'
```

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.

### dictionary_has_decode

`dictionary_has_decode(name, external_dictionary_type=, case_sensitive=)` → **boolean**

Decode presence against an external dictionary: `true` on a row whose code has **any** decode in the named dictionary (its code↔decode pairs or attribute tables). A blank or missing code has none (`false`). The code lookup is case-sensitive by default; `case_sensitive=false` folds it.

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | yes | The code column (`CMTRT`) |
| `external_dictionary_type` | string | yes | Which external dictionary to check against — a string literal (`"whodrug"`) |
| `case_sensitive` | boolean | no | Default `true`; only `false` folds case. Any **dataset-level** boolean — a literal, a `$`-binding or the same expression inline; a per-row value fails to load |

`name` is a **column reference**; a quoted name fails to load, and an absent column answers `false` on every row. The dictionary type must be a static string literal: the function declares the dictionary it needs from it, so a rule whose dictionary is not loaded reports **SKIPPED** — never a finding, never a silent pass — before any row is read, and a run's provider forecast lists the type. Reached without its dictionary the function reports a rule ERROR, never `false`.

**Example**

```yaml
Bindings:
- name: "$cmtrt_has_decode"
  expression: 'dictionary_has_decode(CMTRT, external_dictionary_type="whodrug")'
Check:
  expression: '$cmtrt_has_decode == true and empty(CMDECOD)'
```

The example above is the complete call.

### get_codelist_attributes

`get_codelist_attributes(name, version, ct_attribute=)` → **value** (a list)

The values of one CT-package attribute (e.g. `"Term CCODE"`) for every CT package the rows name. Per
row, the package is derived from the target and the version: `name = "CDISC"` or `"CDISC CT"` with
`version = 2024-09-27` names `sdtmct-2024-09-27` under an SDTM standard (`adamct-…` / `sendct-…`
under ADaM / SEND); any other target names `<target>-<version>`; a blank version names none. The
answer is the order-preserving union over all rows — **one list for the dataset**.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The target: the column naming the CT dictionary (`TSVCDREF`) |
| `version` | any | yes | The column holding the per-row CT version (`TSVCDVER`) |
| `ct_attribute` | string | yes | Which CT-package attribute to extract, e.g. `"Term CCODE"` |

A **registry function**: the version column is the second positional argument — a column reference;
a quoted `version="TSVCDVER"` is the string literal `"TSVCDVER"`, never a column. It is an **aggregate**: it folds every row of the dataset whichever row reads it, and
it is computed once per rule and dataset. It needs the CDISC Library.

**Example**

```yaml
Bindings:
- name: "$VALID_TERM_CODES"
  expression: 'get_codelist_attributes(TSVCDREF, TSVCDVER, ct_attribute="Term CCODE")'
Check:
  expression: 'prefix(TSVCDREF, 5) == "CDISC" and not empty($VALID_TERM_CODES) and TSVALCD not in $VALID_TERM_CODES'
```

(`CDISC-CG0288`.) The call may also be written directly as the right-hand side of `in` / `not in`
— the verdict is the binding's.

**When there is nothing to answer** — the rule is **SKIPPED**, never passed: with no CDISC Library
(*"Rule skipped — no Library access"*), and when the Library knows none of the packages the rows
name, or the dataset has no rows (*"Rule skipped — library returned no data for
get_codelist_attributes"*). It never answers an empty list, so `not empty(...)` cannot quietly read
`false`.

### library_available

`library_available()` → **boolean**

`RulePackageLoader` injects this gate at load for a call that needs the CDISC Library; a not-met Precondition ⇒ SKIPPED

Takes no arguments.

### library_variable_code_pair_matches

`library_variable_code_pair_matches([name])` → **value**

Whether the record's code and its paired decode map to the **same** CDISC CT term: the current
variable is the code variable (e.g. `--TESTCD`), its decode partner follows the CDISC convention
(drop the trailing `CD` — `--TEST`), and each value is mapped to its term's concept id (C-code)
through its own variable's bound codelist; `true` when the two concept ids are equal. Written as the
operand `library_variable_code_pair_matches` in a `Value Check against Library Metadata` rule.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | `variable_name` / `varname()`, or nothing — the variable currently being iterated |

**Example**

```yaml
expression: 'not empty(value()) and library_variable_code_pair_matches(variable_name) == false'
```

**When there is nothing to answer** — No decision, so the row does not fire: an empty code value, a
decode variable that cannot be derived or is absent, or either value not a term of its codelist.
Without the CDISC Library the rule is SKIPPED.

### valid_codelist_dates

`valid_codelist_dates(ct_package_types=)` → **value (a list)**

The publication dates of the CT packages the Library publishes for the applicable package types (`sdtmct-2023-10-26` → `2023-10-26`), sorted, as a list.

| parameter | type | required | description |
|---|---|---|---|
| `ct_package_types` | list of strings | no | The CT package families to consider — any **dataset-level** list of strings: a list literal of string literals (`["SDTM"]`, `["SDTM", "ADAM"]`), a `$`-binding holding one, or the same expression inline (a family maps to its package type by lower-casing and appending `ct`). Omitted: the families of the rule's standard — `sdtmig` ⇒ SDTM CT, `sendig` ⇒ SEND CT, `cdashig` ⇒ CDASH CT, `adamig` ⇒ SDTM + ADaM CT, `usdm` ⇒ DDF + SDTM CT |

A **registry function**. It takes **no column target** — the `name` / `names` rows of the retired operation are gone, and `valid_codelist_dates(X)` fails to load. It is an **aggregate**: one list for the dataset, computed once per rule and dataset and read by every row. It needs the CDISC Library: without one the rule is **SKIPPED** (*"Rule skipped — no Library access"*). The list is read once per call and must be a list literal of string literals — a bare `[SDTM]` names a column and fails to load, naming the quoted spelling.

**Example**

```yaml
Bindings:
- name: "$valid_dates"
  expression: 'valid_codelist_dates(ct_package_types=["SDTM"])'
Check:
  expression: 'TSVCDREF == "CDISC" and TSVCDVER not in $valid_dates'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$valid_dates"
  expression: 'valid_codelist_dates()'
Check:
  expression: 'TSVCDVER not in $valid_dates'
```

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**. A Library that publishes no CT package, or none of the applicable families, answers the empty set — so `TSVCDVER not in $valid_dates` **fires** on every populated version. Under the Define-XML fallback for an unreachable Library, an empty answer means the define could not answer and the rule is **SKIPPED** instead.


### valid_external_dictionary_code

`valid_external_dictionary_code(name, external_dictionary_type=, dictionary_term_type=, case_sensitive=)` → **boolean**

Code validity against an external dictionary: `true` on a row whose value is a member of the named dictionary at the level `dictionary_term_type` names (e.g. `"PTCD"`, `"LLTCD"`, `"UNII"`, `"SNOMEDCD"`, `"MEDRTCD"`, `"LOINC"`). A blank or missing value is `true` — completeness is another rule's concern. By default the value must match the dictionary's **preferred case**; `case_sensitive=false` accepts any case. `valid_external_dictionary_code` and `valid_external_dictionary_value` are **one function under two names**: the level alone decides whether terms or codes are checked.

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | yes | The column holding the value to check |
| `external_dictionary_type` | string | yes | Which external dictionary to check against — a string literal (`"meddra"`, `"whodrug"`, `"unii"`, `"snomed"`, `"medrt"`, `"loinc"`) |
| `dictionary_term_type` | string | yes | Which level of the dictionary to check. A static string literal — anything else fails to load |
| `case_sensitive` | boolean | no | Default `true` (the preferred case); `false` accepts any case. Any **dataset-level** boolean — a literal, a `$`-binding or the same expression inline; a per-row value fails to load |

`name` is a **column reference**; a quoted name fails to load, and an absent column is a column of blanks (valid on every row). The dictionary type must be a static string literal: the function declares the dictionary it needs from it, so a rule whose dictionary is not loaded reports **SKIPPED** — never a finding, never a silent pass — before any row is read, and a run's provider forecast lists the type. Reached without its dictionary the function reports a rule ERROR, never `false`.

**Example**

```yaml
Bindings:
- name: "$tdigrp_code_valid"
  expression: 'valid_external_dictionary_code(TSVALCD, external_dictionary_type="snomed", dictionary_term_type="SNOMEDCD")'
Check:
  expression: 'TSPARMCD == "TDIGRP" and $tdigrp_code_valid == false'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$comptrt_srs_code"
  expression: 'valid_external_dictionary_code(TSVALCD, external_dictionary_type="unii", dictionary_term_type="UNII")'
Check:
  expression: 'TSPARMCD == "COMPTRT" and $comptrt_srs_code == false'
```

### valid_external_dictionary_code_term_pair

`valid_external_dictionary_code_term_pair(name, external_dictionary_term_variable, external_dictionary_type=, case_sensitive=)` → **boolean**

Code↔decode pairing against an external dictionary: `true` on a row whose code (the first column) decodes to the term (the second column) in the named dictionary; a blank code or a blank term is `true` (completeness is another rule's concern). Code and decode compare case-sensitively by default; `case_sensitive=false` folds both.

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | yes | The code column (`TSVALCD`, `--STRESC`) |
| `external_dictionary_term_variable` | column | yes | The decode / term column to pair with the code (`TSVAL`, `--RESCAT`) |
| `external_dictionary_type` | string | yes | Which external dictionary to check against — a string literal (`"unii"`, `"snomed"`, `"medrt"`, `"neoplasm"`) |
| `case_sensitive` | boolean | no | Whether the comparison is case-sensitive; default `true` (compares verbatim). Only `false` folds case. Any **dataset-level** boolean — a literal, a `$`-binding or the same expression inline; a per-row value fails to load |

Both column parameters are **column references**; a quoted name (`external_dictionary_term_variable="TSVAL"`) is a string literal and fails to load, and a call without the term column fails to load. The dictionary type must be a string literal: the function declares the dictionary it needs from it, so a rule whose dictionary is not loaded reports **SKIPPED** — never a finding, never a silent pass — before any row is read, and a run's provider forecast lists the type.

**Example**

```yaml
Bindings:
- name: "$neoplasm_aligned"
  expression: 'valid_external_dictionary_code_term_pair(--STRESC, --RESCAT, external_dictionary_type="neoplasm")'
Check:
  expression: '--RESCAT in ["BENIGN", "MALIGNANT"] and $neoplasm_aligned == false'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$comptrt_pair_valid"
  expression: 'valid_external_dictionary_code_term_pair(TSVALCD, TSVAL, external_dictionary_type="unii")'
Check:
  expression: 'TSPARMCD == "COMPTRT" and $comptrt_pair_valid == false'
```

**When the dictionary is not loaded** — the rule **SKIPs** (the reason names the type and its state). The function has no silent default: reached without its dictionary it reports a rule ERROR, never `false`.


### valid_external_dictionary_hierarchy

`valid_external_dictionary_hierarchy(name, dictionary_parent, external_dictionary_type=, case_sensitive=)` → **boolean**

Hierarchy ancestor test against an external dictionary: `true` on a row whose child term (the first column) lies on the hierarchy path of the parent term (the second column); a blank child or a blank parent is `true`. Operands compare case-sensitively by default; `case_sensitive=false` folds both.

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | yes | The child term column (`--HLT`, `--HLGT`, `--DECOD`, `--LLT`, or a code column) |
| `dictionary_parent` | column | yes | The candidate ancestor column (`--SOC`) |
| `external_dictionary_type` | string | yes | Which external dictionary to check against — a string literal (`"meddra"`) |
| `case_sensitive` | boolean | no | Whether the comparison is case-sensitive; default `true` (compares verbatim). Only `false` folds case. Any **dataset-level** boolean — a literal, a `$`-binding or the same expression inline; a per-row value fails to load |

Both column parameters are **column references**: the `--` in `--HLT` / `--SOC` is resolved like any other variable name, and a quoted name (`dictionary_parent="--SOC"`) is a string literal and fails to load. The dictionary type must be a string literal; a rule whose dictionary is not loaded reports **SKIPPED** before any row is read.

**The level of each column decides the lookup.** A MedDRA name can sit at more than one level (a PT name can also be an HLT or an LLT name), so the dictionary is read at the level of the variable — child **and** parent — derived from the column's name when the rule loads: `--DECOD` is the PT, `--LLT`, `--HLT`, `--HLGT`, `--SOC` their levels, and `--PTCD`, `--LLTCD`, `--HLTCD`, `--HLGTCD`, `--SOCCD` the code levels. The path is the **primary** one: a PT (or its code) lies under its one primary HLT, HLGT and SOC, an LLT under its PT's path, an HLT / HLGT under the SOCs it reaches primarily — so `valid_external_dictionary_hierarchy(--DECOD, --SOC, external_dictionary_type="meddra")` is `false` for a secondary SOC, and an HLT name in `--SOC` is no SOC. `--BODSYS` maps to no level (it may hold a secondary SOC). **A column that names no MedDRA level fails to load** — `--BODSYS`, a `$`-binding or a computed operand in either position: there is no level to read the dictionary at. **The MedDRA house file must carry the level-scoped section** (`hierarchy_by_level`, written by the dictionary converter: PT, HLT and HLGT and their codes, never LLT). On a file converted before that section existed, or for a child level the section lacks (`--LLT`, `--LLTCD`), the rule reports **SKIPPED** with the reason — *"external dictionary meddra cannot answer the HLT → SOC hierarchy: its house file has no level-scoped hierarchy (hierarchy_by_level) — it was converted before this release; re-install meddra to re-convert it"* — while the file's membership and pair rules still answer, and the run's `Dictionary_Basis` names the file. Re-installing MedDRA re-converts it (an install without the section succeeds with a warning naming the gap). There is no bare-name fallback for MedDRA. ⚠ **The level-scoped read, the load error for a column naming no level and the stale-file SKIP are MedDRA's only**: on any other `external_dictionary_type` (`whodrug`, …) the call reads that dictionary's bare-name `hierarchy` map at any column.

**Example**

```yaml
Bindings:
- name: "$hlgt_on_path"
  expression: 'valid_external_dictionary_hierarchy(--HLGT, --SOC, external_dictionary_type="meddra")'
Check:
  expression: '$hlgt_on_path == false'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$hlt_on_path"
  expression: 'valid_external_dictionary_hierarchy(--HLT, --SOC, external_dictionary_type="meddra")'
Check:
  expression: '$hlt_on_path == false'
```

**When the dictionary is not loaded** — the rule **SKIPs**. Reached without its dictionary the function reports a rule ERROR, never `false`.


### valid_external_dictionary_value

`valid_external_dictionary_value(name, external_dictionary_type=, dictionary_term_type=, case_sensitive=)` → **boolean**

Term validity against an external dictionary: `true` on a row whose value is a member of the named dictionary at the level `dictionary_term_type` names (e.g. `"PT"`, `"LLT"`, `"SOC"`, `"SRS"`, `"SNOMED"`, `"MEDRT"`). A blank or missing value is `true` — completeness is another rule's concern. By default the value must match the dictionary's **preferred case**; `case_sensitive=false` accepts any case. `valid_external_dictionary_value` and `valid_external_dictionary_code` are **one function under two names**: the level alone decides whether terms or codes are checked.

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | yes | The column holding the value to check |
| `external_dictionary_type` | string | yes | Which external dictionary to check against — a string literal (`"meddra"`, `"whodrug"`, `"unii"`, `"snomed"`, `"medrt"`, `"loinc"`) |
| `dictionary_term_type` | string | yes | Which level of the dictionary to check. A static string literal — anything else fails to load |
| `case_sensitive` | boolean | no | Default `true` (the preferred case); `false` accepts any case. Any **dataset-level** boolean — a literal, a `$`-binding or the same expression inline; a per-row value fails to load |

`name` is a **column reference**; a quoted name fails to load, and an absent column is a column of blanks (valid on every row). The dictionary type must be a static string literal: the function declares the dictionary it needs from it, so a rule whose dictionary is not loaded reports **SKIPPED** — never a finding, never a silent pass — before any row is read, and a run's provider forecast lists the type. Reached without its dictionary the function reports a rule ERROR, never `false`.

**Example**

```yaml
Bindings:
- name: "$bodsys_valid"
  expression: 'valid_external_dictionary_value(--BODSYS, external_dictionary_type="meddra", dictionary_term_type="SOC", case_sensitive=false)'
Check:
  expression: '$bodsys_valid == false'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$comptrt_srs_term"
  expression: 'valid_external_dictionary_value(TSVAL, external_dictionary_type="unii", dictionary_term_type="SRS")'
Check:
  expression: 'TSPARMCD == "COMPTRT" and $comptrt_srs_term == false'
```


## Supplemental qualifiers and trial summary

### read_value

`read_value(name, domain=, filter=, mode=, group=, keep_missings=)` → **value**

One value of another dataset, read once per rule execution and broadcast to every row: the `name`
column of `domain`, taken from the rows a `filter` over that dataset's own columns selects, by the
`mode` the author names. The TS/TX parameter lookup (formerly `ts_parameter_value`) is
`read_value(TSVAL, domain=TS, filter=(TSPARMCD == "SPECIES"), mode="FIRST")`.

| parameter | type | required | description |
|---|---|---|---|
| `name` | column of `domain` | yes | The column to read — bare, never quoted, never dotted, never `--` |
| `domain` | dataset reference | yes | The dataset to read from: `TS` or `"TS"` (both spellings name the dataset). A `--` in the dataset name resolves against the dataset under evaluation: `domain="SUPP--"` on `AE` reads `SUPPAE` (and on `SUPPAE` too), as `Match_Datasets` names do. The dataset is read **raw**: the declared SUPP merge (`Supp_Merge`) serves the dataset under evaluation only, so a `SUPP--` qualifier is never a column of `domain` — neither as `name` nor in `filter` |
| `filter` | boolean over `domain`'s columns | no | Which rows qualify, in parentheses: `filter=(TSPARMCD == "SPECIES")`. Only columns of `domain` — a dotted or `--` reference is a load error. A `$` reference is one of the rule's own bindings, read as **one dataset-level value of the rule's dataset** (never of `domain`) — a per-row binding there is a load error. Absent ⇒ every row |
| `mode` | `"FIRST"` · `"ONLY"` · `"MIN"` · `"MAX"` | yes | Which qualifying row answers: the first in dataset order; exactly one (a second qualifying row is a rule **ERROR**); the smallest / largest `name` by the engine's one ordering (a missing below every value, numbers numerically, text as text) |
| `group` | list of columns of `domain` | no | Per-group reading: the `mode` selects **within each group** of `domain`, and every row of the evaluated dataset reads its own group's value by the same columns. `ONLY` then asserts one qualifying row per group. A row whose key has no group reads the type default. A member may be [qualified](#qualified-group-members) (`DM.RPATHCD`): the record reads the group of its bound `DM` record's value. A member may be a `$`-reference to a dataset-level **list of column names**, or the expression such a binding holds, written in place — spliced exactly as [`record_count`](#record_count)'s `group=` splices it (a dataset-level string is one name; a per-row binding or expression is a load error; a bare `group=$names` / `group=<expression>` is the one-member list); a spliced member is never qualified under `domain`. |
| `keep_missings` | boolean | no | With `group`: whether a row with a missing group key stays in its group (default `true`) |

**Example**

```yaml
Bindings:
- name: "$exp_start"
  expression: 'read_value(TSVAL, domain=TS, filter=(TSPARMCD == "EXPSTDTC"), mode="FIRST")'
Check:
  expression: 'not empty(EXSTDTC) and date(EXSTDTC) < date($exp_start)'
```

**Two datasets, one key each**

```yaml
Bindings:
- name: "$ts_species"
  expression: 'read_value(TSVAL, domain=TS, filter=(TSPARMCD == "SPECIES"), mode="FIRST")'
- name: "$tx_species"
  expression: 'read_value(TXVAL, domain=TX, filter=(TXPARMCD == "SPECIES"), mode="FIRST")'
Check:
  expression: 'empty(SPECIES) and empty($ts_species) and empty($tx_species)'
```

**When there is nothing to answer** — the column's **type default**, never an error: `""` for a
character column, missing for a numeric one — so `empty($x)` is true either way. The character
default `""` is an ordinary empty text: `$x == ""` is true, and `!=` against a present literal
(`$x != "RAT"`) is true. The numeric default is a missing: `!=` against it fires (rulings register
`D34a`); `==` is true only against the same missing; an order comparison treats it as the lowest
value (`D34 #5`): `X > $x` and `$x < X` fire, `X < $x` does not. That is the answer for a filter no row satisfies.
An **absent `domain` dataset**, or a **`name` the dataset does not carry**, has no column type to take a
default from, so the call answers what that column would answer read directly **at the call's
position**: the missing where the
call — or the binding it is bound to — is read as a number (`$x > 5`, `$x + 1`, `X > $x`), `""`
everywhere else; where the uses disagree the number wins, and with no typed use at all (`empty($x)`,
a reported value) it is `""`. The uses that count are those of **every** `read_value` / `max` call
over the same `domain` dataset and column, as two direct reads of one column share one type:
`read_value(X, domain=D, mode="FIRST") > 5 or read_value(X, domain=D, mode="MAX") == ""` reads the
missing in both calls. One limit: a call that names the dataset under evaluation in `domain=`
(`max(AEX, domain=AE)`) and one that omits `domain=` (`max(AEX)`) are **not** counted as one column,
although on an `AE` evaluation they read the same one — which dataset is under evaluation is known
only when the rule runs, not when it loads, so each keeps its own uses. Until 2026-10-05 it was
always `""`. Either way the rule executes (it
does not skip), and a numeric use over a possibly absent column wants a `not empty($x)` guard (the
missing sorts lowest: `X > $x` would fire on every row). A qualifying row whose
`name` cell is missing hands that missing through. `mode="ONLY"` is the one loud case: two or more
qualifying rows report the rule as ERROR (`… — ONLY asserts exactly one qualifying row`), because the
rule asserted a single row and the data says otherwise.

⚠ `read_value` cannot express *presence* — a no-match answers the type default (D13), never an
error, whether or not `group=` is given.


## Values and references

### char

`char(x)` → **value**

The first character of the value as its numeric code point

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'not empty(PARAMCD) and not between(char(PARAMCD), char("A"), char("Z"))'
```

**Missing and empty** — A missing input yields **that missing value**, identity kept (`char(.A)` is `.A`, rulings register `D85c`); `""` has no first character to take and yields the plain missing (`MIS`).

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `char(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element's first code point, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### colref

`colref(x)` → **value**, or a **list** of values when `x` is a list of names

The value of the column *named by* `x` — a dynamic column read. A name reads exactly the column the
same name written in the expression reads: a bare name the dataset under validation (and its
supplemental qualifiers), `DS.X` the merged column `DS.X` — a joined dataset's, a `Child: true`
parent's or a RELREC-related domain's. The value keeps its column's type (an ADaM date
stays a number). Over a list of names — `colref(find_vars("ADSL.TRTxxA"))` — it answers one value
per name, in the list's order: `TRTA in colref(find_vars("ADSL.TRTxxA"))` is the function spelling
of `TRTA in ADSL.TRT${*}A`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | string, or list of strings | yes | The column name(s), per row |

**Example**

```yaml
expression: 'not empty(IDVAR) and not empty(IDVARVAL) and str(IDVARVAL) != str(colref(concat(RDOMAIN, ".", IDVAR)))'
```

```yaml
Bindings:
  - name: "$ap_sdt"
    expression: colref(concat("ADSL.AP", printf("%02d", APERIOD), "SDT"))
Check:
  expression: not empty(APERSDT) and not empty($ap_sdt) and APERSDT != $ap_sdt
```

**Missing and empty** — A named-but-absent column takes the default the **written** name would
take at the same position: the plain missing where the expression expects a number (`colref(…) <
3`), `""` otherwise (rulings register `DCF Q14`). A missing `x` yields **that missing value**
(`D85c`); a present `x` that is not text names no column and yields the plain missing (`DCF Q7`);
a list keeps a missing member as a member (`DCF Q4`). Through a binding the position is the
binding's **use**: `$x < 3` over `$x: colref("X")` defaults to the missing, exactly as
`colref("X") < 3` and `$x: X` do.

**Names.** A name matches its column whatever its case, and so does the absent default:
`colref("zz")` and `colref("ZZ")` answer alike. A **written** `--` name (`colref("--SEQ")`) is
specialised to the domain like any written name; a name **computed** per row that starts with
`--` names no column and takes the absent default. A **written** `colref("DS._matched_")` fails
the rule — the join-match flag is a condition, never a value — exactly as `DS._matched_` written as
a value does; a name computed per row that spells the flag names no column. A dataset qualifier
matches the rule's `Match_Datasets` name whatever its case (`colref("adsl.TRT01A")` reads `ADSL`).

**In a `Match_Datasets` Filter.** A **written** name — `colref("AESEV")`, or a list of written
names — is checked like the plain name: a column the filtered dataset does not carry fails the rule
unless the rule declares it in `Requirements.Variables` (then the rule is skipped), and a written
`colref("DM.SEX")` is refused like `DM.SEX` — a filter sees only its own dataset's columns. A name
computed per row cannot be checked before the run.

**Supplemental qualifiers too.** `colref("AETRTEM")` reads the `SUPPAE` qualifier exactly as the
written `AETRTEM` does, and a qualifier the SUPP merge could not serve (a duplicate supplemental row, an `IDVAR` the
domain lacks) fails the rule when `colref` reads it, as the written name does.

**A `Child: true` parent** is read qualified with its domain, so the referenced-record check builds
the qualified name from the pointer row: `colref(concat(RDOMAIN, ".", IDVAR))` reads the column
`IDVAR` names in the domain `RDOMAIN` names, on the parent record the row points at.

**In a membership test.** On the right of `in`, a list of names is a per-row set:
`TRTA in colref(find_vars("ADSL.TRTxxA"))`, or through a binding, `$vars: find_vars("ADSL.TRTxxA")`
and then `TRTA in colref($vars)` (a binding holding one name is a one-member set). On the left,
`colref(find_vars(…)) in [...]` compares element by element and holds when **any** member is in
the set — as a list-valued metadata accessor does on the left. `colref($x)` decides row by row: a
row whose binding holds a list is compared as above; any other row is an ordinary value, compared
as the written name would be (numerically against a number list, as dates against `date(…)`
members). An absent member of a list takes the absent default like any `colref` read: `""`,
unless the name is expected to be a number elsewhere in the rule (then the plain missing).

### values

`values(columns)` → **value (a list)**

The per-row list of the named columns' **values** — `values([X, Y])` answers, on each row, the
cells of `X` and `Y` in the written order. The argument is a list of column **names**, never
values: a bare name, a `--` name or a qualified `DS.X`, each read exactly as the same name written
alone in the expression reads (a bare name the dataset under validation, `DS.X` the merged column
through the join). The cells keep their columns' types (a numeric column stays a number). `[X, Y]`
alone keeps its one meaning everywhere — a list of column names, as at `group=` or in
`is_unique_set` — so reading several columns as values is always spelled `values([X, Y])`.

| parameter | type | required | description |
|---|---|---|---|
| `columns` | list of column names | yes | The columns to read, bare — `[X, Y]`, a `$`-binding holding a list of names, or the expression such a binding holds |

**Example**

```yaml
expression: 'not empty(USUBJID) and "Y" in values([AESER, AESDTH, AESLIFE])'
```

```yaml
Bindings:
  - name: "$flags"
    expression: 'values([AESER, AESDTH, AESLIFE])'
Check:
  expression: 'not empty(USUBJID) and "Y" not in $flags'
Outcome:
  Output_Variables: ["USUBJID", "$flags"]
```

**Missing and empty** — The list always has one member per name: a missing cell stays at its
position **as that missing value** (the list keeps its length), and a named-but-absent column
contributes the default the written name would take at the same position — `""` in a character
position, the plain missing where the rule expects a number (`D34 #3/#4`). An empty list
`values([])` is a load error.

**Names.** The members are judged at load: a quoted name (`values(["X"])`) is a string literal,
never a column, and fails to load; a nested list or any other value fails to load naming the
parameter type. A `$`-binding holding a **dataset-level** list of names — `$keys:
["STUDYID", "USUBJID"]`, `natural_key_variables()`, `find_vars("…")` — is spliced into the list
exactly as `record_count`'s `group=` and `is_unique_set`'s members splice it, and so is the
expression such a binding holds written in place: `values($keys)` reads as `values([$keys])`, and
`values([STUDYID, $keys])` mixes written and spliced names. A per-row member (`upper(AETERM)`, a
binding computed per row) cannot be spliced — it fails to load (*"argument 'columns' of 'values'
cannot splice the per-row member …"*). The spliced names are resolved at evaluation: a binding
that holds no names there is a rule ERROR. `values(…)` itself is per-row, so it is never a member
of another splice list (`is_unique_set([USUBJID, values([X])])` fails to load the same way).

**In a membership test.** On the right of `in`, the list is a per-row set: `"Y" in values([A, B])`
holds where `A` or `B` is `Y` on that row. On the left, `values([A, B]) in ["Y", "N"]` holds when
**any** member is in the set, as a list-valued accessor does. Compared with `==` against a scalar
the list is refused at load. `count(values([A, B]))` is the number of names (a missing member
counts).

**Not `colref`, not `tuple`.** [`colref(x)`](#colref) reads the column whose name a *cell* holds
(`colref(IDVAR)` — a computed name); `values` reads the columns the rule **names**.
[`tuple(…)`](#tuple) builds one text key of several columns; `values` keeps the typed cells.

### date

`date(x)` → **date**

The value read as an ISO-8601 date or datetime. Nothing is flattened: the ISO text itself is the
carrier, so a partial date (`2012-06`) stays partial. Written on either side of a comparison it
makes the comparison **temporal** — the [hull rule](../semantics/comparison.md#dates-and-times--the-hull-rule):
a partial date is the range of instants it could denote. A `date(…)` probe against a written-out
list compares dates too: `date(--DTC) in [date("2020-01-01")]`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The ISO-8601 value — a column, a string literal or a binding |

**Example**

```yaml
expression: 'not empty(DVSTDTC) and date(DVSTDTC) < date(DM.RFICDTC)'
```

**Missing and empty** — A missing input yields **that missing value**. A value that cannot be read
as a date at any precision — `""`, malformed text — is not an error: every comparison over it
answers false ([Junk answers false](../semantics/comparison.md#junk-answers-false)), so guard it with
`is_valid_date(…)` or `not empty(…)` when that is not what you want.

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `date(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element read as a date; `date(values([X, Y])) in [date("…")]` compares each element by the date rule, a partial date a range, exactly as `date(X) in […]` does, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### duration

`duration(x)` → **duration**

The value read as an ISO-8601 **duration** (`P1W`, `PT90M`, `P1Y2M`, `-P1D`), the ISO text itself the
carrier. Written on either side of a comparison it makes the comparison a **duration comparison**
— the [hull rule on durations](../semantics/comparison.md#duration--the-hull-rule-on-durations):
`PT90M` and `PT1H30M` are the same point, `P2D` is
longer than `PT5H`, and a **month or year** is a range (`P1M` is any length from 28 to 31 days), so
`duration(--STINT) > duration(--ENINT)` fires only where the start offset is *definitely* later.
Without the marker a comparison on duration text is a text comparison (`"PT9H" > "PT10H"` holds) —
the marker is what selects the family; the engine never decides by the cell's content.

A `duration(…)` operand is also what [`earliest_possible`](#earliest_possible) /
[`latest_possible`](#latest_possible) read as a duration (the hull's ends as point durations), and
what [`has_next_corresponding_record`](#has_next_corresponding_record)'s `relation=` compares as a
duration when written on both operands.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The ISO-8601 duration — a column, a string literal or a binding. One value per row: `duration` is **not element-wise** — a list at `x` fails to load where its type is known (*"duration() converts a string, not list<…>"*) and is the rule's ERROR where only the run sees it |

**Example — a planned interval's start must not denote a later point than its end** (SENDIG 3.1.1)

```yaml
expression: 'not empty(--STINT) and not empty(--ENINT) and duration(--STINT) > duration(--ENINT)'
```

**Missing and empty** — A missing input yields **that missing value**. A value that is not a valid
duration (`is_valid_duration` is the same test: `""`, `P`, malformed text, a date, a number such
as `5`, the `lower/upper` spelling in a data cell) is not an error:
every comparison over it answers false, and `!=` fires — exactly as [`date`](#date) treats junk — so
guard it with `is_valid_duration(…)` or `not empty(…)` when that is not what you want.

### find_vars

`find_vars(entry)` → **list** of column names

The names of the columns one [`Requirements.Variables`](../authoring/requirements.md) entry
selects — the same entry grammar and the same matcher: a template (`"ADSL.TRTxxPN"`: `xx`/`zz`
two digits, `y` one or more, `w` one), a glob (`"TRT*P"`), a regex over the variable name with the
qualifier outside the slashes (`"ADSL./TRT[0-9]+PN/"`), or a literal name (an existence test).
Unqualified entries read the dataset under validation; a qualified entry reads the joined dataset's
real columns (never a SUPP qualifier), its qualifier matched to the rule's `Match_Datasets` name
whatever its case. The names come in the dataset's column order — **bare**
(`TRT01P`) for an unqualified entry, `DS.COLUMN` for a qualified one; `colref` dereferences them. It never expands the rule, so
`count(find_vars("TRTxxP")) == 0` asks "is no `TRTxxP` present?" and the rule **runs** either way.

| parameter | type | required | description |
|---|---|---|---|
| `entry` | string | yes | One `Requirements.Variables` entry |

**Example**

```yaml
expression: 'not var_exists("TRTP") and count(find_vars("TRTxxP")) == 0'
```

⛔ **Load errors.** A `/regex/` entry must be a string literal (a computed one is an evaluation
error); a type suffix (`:N`), a qualifier written inside the regex (`"/ADSL.TRT…/"` — write
`"ADSL./TRT…/"`), a `--` in a qualified name and an invalid regex fail the rule at load.

**Missing and empty** — A missing entry yields a missing result (so `not empty(find_vars(X))` does
not fire on it); `""` selects nothing (`[]`).

**When there is nothing to answer** — `[]`: for a qualifier the rule does not declare in
`Match_Datasets` or a dataset the run was not given (the loader also reports an undeclared
qualifier, as it does for a written `DS.X`), and for a computed entry that starts with `--` (only a
written entry is specialised to the domain).

### lpad

`lpad(x, width, fill)` → **text**

`x` left-padded with `fill` (one character, default a space) to `width` characters. A longer `x`
is returned unchanged — never truncated.

| parameter | type | required | description |
|---|---|---|---|
| `x` | string | yes | The text to pad |
| `width` | number | yes | The target width, an integer ≥ 0 |
| `fill` | string | no | The one-character fill, default `" "` |

**Example**

```yaml
expression: 'lpad(str(APERIOD), 2, "0") == "01"'
```

⛔ **Load errors.** A written `width` that is negative, not a whole number or above 32767, and a
written `fill` that is not exactly one character (a number too, by its text: `lpad(X, 3, 0)` is
fine, `lpad(X, 3, 10)` is not), fail the rule at load.

**Missing and empty** — A missing `x` yields **that missing value**; `""` pads to `width` fills.
When `width` or `fill` comes from the data, a missing, negative or non-whole `width`, a `width`
above 32767, or a `fill` that is not exactly one character yields the plain missing (`MIS`).

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `lpad(values([X, Y]), 3, "0")`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element left-padded, the row's `width` / `fill` applied to every element, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### num

`num(x)` → **number**

The value as a number: a numeric cell as it is, text parsed as a number. It also selects numeric
comparison: `num(QVAL) > 8` compares numerically where a character `QVAL > 8` is a type error.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to convert |

**Example**

```yaml
expression: 'num(QVAL) > 8'
```

**Missing and empty** — A missing input yields **that missing value**; text that is not a number,
and `""`, yield the plain missing (`MIS`).

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `num(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element read as a number; `num(values([X, Y])) in [1, 2]` compares each element numerically, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### printf

`printf(format, args…)` → **text**

Formats its arguments, always in the root locale (`%.1f` of 1.5 is `1.5` on every machine).
Supported: `%s %d %f %e %x %%`, the flags `0 - + space`, a width and a precision. `%d` / `%x` take
a whole number — judged with the engine's 12-digit tolerance, so a stored `2.0000000000001` counts as
`2` — and format the nearest whole number; `%f` / `%e` format the number as stored; `%s` renders
through `str`.

| parameter | type | required | description |
|---|---|---|---|
| `format` | string | yes | The format — a string literal |
| `args` | any | one per conversion | The values |

**Example**

```yaml
expression: 'printf("%02d", APERIOD) == "01"'
```

⛔ **Load errors.** A computed format, any other conversion (`%n`, `%t…`, `%1$s`), a number of
arguments that differs from the conversions, a string literal at a numeric conversion (`%d %f %e
%x`), a written number that is not whole at `%d` / `%x` (`printf("%d", 1.5)`), and any named argument
(`printf("%s", format="x")` — positional arguments only) fail the rule at load.

**Missing and empty** — A missing argument makes the result **that missing value** (two different
missing arguments give the plain missing). `%d` / `%x` of a value that is not a whole number (`1.5`,
or text) yields the plain missing — never a truncated `1`, unlike the `${APERIOD:%02d}` template,
which keeps its truncation — and so does a negative value at `%x` (no two's complement).

### str

`str(x)` → **text**

The value as text — a cell's own text: a floating-point cell's cleaned plain text (the engine's
12-digit numeric cleaning; no `.0` on a whole number, never scientific), an integer cell's exact
digits (a large integer stays exact), a character value as it is. A computed number (`str(AGE +
1)`) is written in the same plain notation, and so is a written number (`str(3.0)` is `"3"`).
`str(A) == str(B)` compares two values as text, whatever their column types. ⚑ Until 2026-09-30
that comparison read a number written on its **left** side as `"3.0"`; whether the plain
notation stays there is not yet settled — no shipped rule writes a number inside `str`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to convert |

**Example**

```yaml
expression: 'str(IDVARVAL) != str(colref(concat(RDOMAIN, ".", IDVAR)))'
```

**Missing and empty** — A missing input yields **that missing value**, identity kept.

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `str(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element's text, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### time

`time(x)` → **time**

The value read as an ISO-8601 time of day, partial precision retained — `time` mirrors
[`date`](#date): written on either side of a comparison it makes the comparison temporal, under the
same hull rule on times of day.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The ISO-8601 time — a column, a string literal or a binding |

**Example**

```yaml
expression: 'not empty(QVAL) and time(QVAL) < time("06:00")'
```

**Missing and empty** — As for [`date`](#date): a missing input yields **that missing value**; a
value that cannot be read as a time answers false in every comparison.

**List form (element-wise)** — over a list at `x` — the cells of several columns ([`values`](#values): `time(values([X, Y]))`), a per-row list such as `split_by(…)`, or a `$`-list — the function answers **a list of the same length**, each element read as a time of day; membership compares each element by the time rule, exactly as the scalar call answers it; a missing element answers as the scalar call answers a missing value, in its position, `[]` gives `[]` and a missing list stays that missing. The list is a per-row membership set on the right of `in`, and on the left of `in` it asks whether **any** element is a member; compared with a single value (`==`, `<`, …) it fails the rule's load when the list is known statically — over an unknown-typed `$` value it is the rule's run-time ERROR. `[X, Y]` with bare names is a list of column *names*: read the values with `values([X, Y])`.

### value

`value()` → **value**

The value of the variable currently being iterated, for this row

Takes no arguments.

**Example**

```yaml
expression: 'len(value()) > 200'
```

**Missing and empty** — A `""` cell yields `""`. A missing cursor names no variable and yields **missing**. A cursor naming a column the dataset does not carry (a Define-XML variable absent from the data) answers what that column would answer read directly **at the call's position**: the missing where `value()` — or the binding it is bound to — is read as a number, `""` everywhere else. Until 2026-10-05 it was the missing everywhere. Under the default `Data` variable universe the cursor only ever names a column the dataset carries.

### varname

`varname()` → **value**

The name of the variable currently being iterated

Takes no arguments.

**Example**

```yaml
expression: 'len(varname()) > 8'
```

**Missing and empty** — Always present — it is the variable's name, never a data value.
