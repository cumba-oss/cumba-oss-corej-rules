# Function reference

<!-- Parameter names, types, required flags and return kinds are dumped from the engine's own
     descriptors (FunctionRegistry, OperationDescriptors, MetadataAttribute). -->

One section per function: what it does, every parameter with its type, and a worked example.
The summary tables are in [Expression syntax](expression-syntax.md#functions).

## How to read this page

**Two rules hold everywhere, so only departures from them are noted per function.**

**1. `""` is a present value and is read literally.** `len("")` is `0`, `upper("")` is `""`. There
is no "empty means missing" behaviour — with one deliberate family of exceptions, the emptiness
predicates (`empty` / `is_missing`), whose whole purpose is to report `""` and a genuine missing
alike. A section headed **Missing and empty** appears wherever a function departs from this.

**2. A missing input propagates.** A genuine [missing value](../semantics/values-and-missing.md)
passed to a function yields a missing result rather than a default, an error or a sentinel — so a
check built from ordinary functions ignores rows with missing data by construction. Where that is
not what you want, test for it with `empty(x)` explicitly.

**Functions that reach outside the row have one more failure mode**, and it is not an error.
`record_count(domain="DS")`, `distinct(X, domain="TA")`, `supp_qnam_value(…)`,
`ts_parameter_value(…)`, the library and Define-XML lookups — each names something by string that
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

## The target of an operation: one column, a list, or a computed value

An operation takes **at most one positional argument** — its target. More than one is a load
error naming the operator. What that single argument *is* decides which parameter it binds:

| you write | binds | meaning |
|---|---|---|
| `distinct(ARMCD, …)` | `name` | one column |
| `distinct([ARMCD, ARM], …)` | `names` | a list of columns |
| `max(BASE / 2, …)` | a computed target | an expression, evaluated per row |

Both named forms are also spellable as keywords — `name=ARMCD`, `names=[ARMCD, ARM]` — but the
positional spelling is what the corpus uses throughout.

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
`row_max([TR01EDT, TR02EDT])`, `contains_all(source, keys=[ARMCD])`,
`is_sorted_by(target, by=[asc("VISITNUM")])`.

> ⚠ **This one-positional rule is specific to operations.** An ordinary function takes as many
> positional arguments as it declares parameters — `substring(AETERM, 1, 3)` — as described under
> [Arguments](expression-syntax.md#arguments).

⛔ **A type mismatch is a rule ERROR, not a coercion.** Where a function requires a numeric
operand, a character column raises rather than being parsed; convert it with `num(x)` when the
text really is meant as a number. Sections that gate a type say so under **Type error**. The
general rules — including which operators gate — are in
[Comparison](../semantics/comparison.md).


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

**Missing and empty** — `""` counts as **not present** and is skipped, exactly like a genuine missing: `coalesce("", "a")` returns `"a"`, and an absent column is skipped as well. For **numbers only a missing value is skipped** — `0` and `0.0` are real values and are returned. When every operand is missing or `""`, the result is missing.


### ds_exists

`ds_exists(x)` → **boolean**

True when the named dataset is present in the run. A negated spelling `ds_not_exists(x)` exists; prefer `not ds_exists(x)`.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |

**Example**

```yaml
expression: 'ds_exists("SJ")'
```

### empty

`empty(x)` → **boolean**

True when the value is missing or `""`. Also spelled `is_missing`. The positive spellings `non_empty` / `present` / `is_present` exist; prefer `not empty(x)`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'empty(AGE)'
```

**Missing and empty** — This is one of the few places `""` is **not** read literally: it reports both a genuine missing value and `""` as empty. That is the function's purpose.

### var_exists

`var_exists(x)` → **boolean**

True when the named column exists; accepts a `--` wildcard, a dotted cross-dataset name, a SUPP QNAM pivot and a `${…}` substitution. A negated spelling `var_not_exists(x)` exists; prefer `not var_exists(x)`.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |

**Example**

```yaml
expression: 'var_exists("AWU")'
```

### var_is_null

`var_is_null(x)` → **boolean**

True when the column exists but no row in the dataset populates it

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |

**Example**

```yaml
expression: 'varname() in $expected_variables and var_is_null(varname())'
```

### variable_exists

`variable_exists(name)` → **value**

Whether the named column exists, as a value for reporting

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `domain` | string | no | The dataset to read from |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$TRTSDTM_EXISTS"
  expression: 'variable_exists(TRTSDTM)'
Check:
  expression: '$TRTSDT_EXISTS == false and $TRTSDTM_EXISTS == false'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$MIDS_EXISTS"
  expression: 'variable_exists(MIDS)'
Check:
  expression: '$MIDS_EXISTS == true and not ds_exists("TM")'
```

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.


### variable_is_null

`variable_is_null(name)` → **value**

Whether the column is unpopulated across the dataset, as a value for reporting

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$species_null"
  expression: 'variable_is_null(SPECIES)'
Check:
  expression: '$species_null == true and empty($ts_species)'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$strain_null"
  expression: 'variable_is_null(STRAIN)'
Check:
  expression: '$strain_null == true and empty($ts_strain)'
```

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.



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

True when `needle` occurs anywhere in `x`; `needle` may vary per row. A negated spelling `does_not_contain(x, needle)` exists; prefer `not contains(x, needle)`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |
| `value` | any | yes | The value to compare against |

**Example**

```yaml
expression: 'contains(--LAT, ";")'
```

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
| `value` | any | yes | The value to compare against |

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

**Example**

```yaml
expression: 'not empty(--TESTCD) and not is_valid_testcd(--TESTCD)'
```

### len

`len(x)` → **value**

Length of the value in characters; `len("") = 0`. Also spelled `length`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'len(ETCD) > 8'
```

**Missing and empty** — `len("")` is **0** — an empty string is a present value of length zero. A genuine missing value yields **missing**, not 0. An absent character column reads as all-`""`, so `len(x) == 0` is true for it.

### lower

`lower(x)` → **value**

The value lower-cased. Also spelled `lowcase`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'lower(SEX) == "m"'
```

**Missing and empty** — A missing input yields **that missing value**, identity kept (`.A` in, `.A` out — rulings register `D36`, owner 2026-09-28 *"D36 stands"*). `""` is a present value and yields `""`.

**List form** — given a list (a `$`-binding such as `get_column_order_from_dataset()` or a
`distinct(…)` result), returns a **new list** with every element lower-cased, in order; a **missing
element stays missing** — it is carried through unchanged, in its position (missing propagates
through the string functions, register `D36`), never folded to `""`. Confirmed by the owner 2026-09-28 together
with [`upper`](#upper)'s list form (rulings register `CIT §3`).

### max_value_length

`max_value_length([name])` → **value**

The longest value stored in the column, across all rows

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | no | The column to read |

**Example**

```yaml
Check:
  expression: 'max_value_length(AETERM) > var_length("DEFINE")'
```

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
expression: 'upper(normalize_space(var_label("DATA"))) not in library_variable_label_values'
```

**Missing and empty** — A missing input yields **that missing value**, identity kept (rulings register `D36`). `""` is a present value and yields `""` (it used to yield missing).

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
  expression: 'not prefix_matches(--TESTCD, "^[A-Z]+$", 4)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not prefix_matches(USUBJID, "^[0-9]+$")'
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

### starts_with

`starts_with(x, needle)` → **boolean**

True when `x` begins with `needle`

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |
| `value` | any | yes | The value to compare against |

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
  expression: 'not suffix_matches(--DTC, "^[0-9]{2}$", 2)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not suffix_matches(VISIT, "^WEEK.*$")'
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

### upper

`upper(x)` → **value**

The value upper-cased. Also spelled `upcase`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'not contains(upper(VISIT), upper("UNSCHEDUL"))'
```

**Missing and empty** — A missing input yields **that missing value**, identity kept (`.A` in, `.A` out — rulings register `D36`, owner 2026-09-28 *"D36 stands"*). `""` is a present value and yields `""`.

**List form** — given a list (a `$`-binding such as `get_column_order_from_dataset()` or a
`distinct(…)` result), returns a **new list** with every element upper-cased, in order; a **missing
element stays missing** — it is carried through unchanged, in its position (missing propagates
through the string functions, rulings register `D36`), never folded to `""`. This is how a rule compares the dataset's
column names against the library's names (owner, 2026-09-28: *"upper allows a list of strings as
parameter and returns a list of these strings converted to upper case"*; rulings register
`CIT §3`): a column name a rule handles as a value is a string and compares exactly like one, so
the rule folds **both sides** — the owner's *"compare the upper case version of a name against the
upper case version of a list of strings"*; folding a library list is a no-op under the owner's
assumption that library names are upper case, and it spares the rule from deciding which side is
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
needs no fold.


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

**Missing and empty** — A non-numeric or empty input yields **missing**.

⛔ **Type error on a character column.** The operand must be numeric; a character column raises a rule ERROR rather than being parsed. Convert it explicitly with `num(x)` if the text is meant as a number.

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

**Missing and empty** — A non-numeric or empty input yields **missing**.

⛔ **Type error on a character column.** The operand must be numeric; a character column raises a rule ERROR rather than being parsed. Convert it explicitly with `num(x)` if the text is meant as a number.

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

**Missing and empty** — A non-numeric or empty input yields **missing**.

⛔ **Type error on a character column.** The operand must be numeric; a character column raises a rule ERROR rather than being parsed. Convert it explicitly with `num(x)` if the text is meant as a number.

### is_integer

`is_integer(x)` → **boolean**

True when the value is a finite whole number. A negated spelling `is_not_integer(x)` exists; prefer `not is_integer(x)`.

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

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

**Example**

```yaml
expression: 'is_numeric(--DOSTXT)'
```

**Missing and empty** — `""` is not a number, so this is **false** for it.

### minus

`minus($a, $b)` → **value**

The set difference of two lists — the members of `$a` not in `$b`

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `subtract` | string | no | The list to subtract |
| `value` | list<string> | no | The value to compare against |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$untraceable"
  expression: 'minus($ae_keys, subtract=$adae_keys)'
Check:
  expression: 'not empty($untraceable)'
```

The example above is the complete call.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.

### round

`round(x)` → **value**

The value rounded to the nearest integer, halves away from zero

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'round(AGE) == AGE'
```

**Missing and empty** — A non-numeric or empty input yields **missing**.

⛔ **Type error on a character column.** The operand must be numeric; a character column raises a rule ERROR rather than being parsed. Convert it explicitly with `num(x)` if the text is meant as a number.


## Dates and times

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

`date_diff_days(name, reference, …)` → **value**

Days between a record's date and a reference date, with an optional `offset`

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `domain` | string | no | The dataset to read from |
| `group` | list of columns | no | Columns whose combined value defines a group |
| `missing_values` | string | no | How a missing input affects the result |
| `reference` | string | no | The date to measure from |
| `offset` | string | no | Days to add to the difference |
| `reference_extreme` | string | no | Which extreme of the reference group to measure against |
| `minuend_domain` | string | no | The dataset holding the date to subtract from |
| `minuend_match` | list<string> | no | Columns matching this record to a row of the minuend dataset |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$rpref_day"
  expression: 'date_diff_days(--ENDTC, domain="SJ", reference="SJSTDTC", offset="RPRFDY", reference_extreme="min", group=[USUBJID, RPHASE])'
Check:
  expression: 'is_complete_date(--ENDTC) and not empty(--RPENDY) and --RPENDY != $rpref_day'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$days_from_first_dose"
  expression: 'date_diff_days(--DTC, domain="EX", reference="EXSTDTC", offset="1", group=[USUBJID])'
Check:
  expression: 'is_complete_date(--DTC) and not empty(TFDETECT) and TFDETECT != $days_from_first_dose'
```

**When there is nothing to answer** — **missing** — the calculation was not possible. A comparison against it does not fire; `empty()` on it does.


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

**Missing and empty** — `""` and a missing input yield **missing** — neither carries a date component.

### dy

`dy(name, …)` → **value**

The study day of a record's date relative to `DM.RFSTDTC`; there is no day 0

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `group` | list of columns | no | Columns whose combined value defines a group |
| `reference` | string | no | The date to measure from |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$value_xstdy_algorithm"
  expression: 'dy(--STDTC, reference="RFXSTDTC")'
Check:
  expression: 'not empty(--XSTDY) and is_complete_date(--STDTC) and is_complete_date(DM.RFXSTDTC) and --XSTDY != $value_xstdy_algorithm'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$value_dy_algorithm"
  expression: 'dy(--DTC)'
Check:
  expression: 'not empty(--DY) and is_complete_date_part(--DTC) and is_complete_date_part(DM.RFSTDTC) and --DY != $value_dy_algorithm'
```

**When there is nothing to answer** — **missing** — the calculation was not possible. A comparison against it does not fire; `empty()` on it does.


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

### interval_uncertainty_precision_mismatch

`interval_uncertainty_precision_mismatch(name, delimiter=)` → **value**

True when the two halves of an ISO `a/b` interval are stated at different precisions

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `delimiter` | string | no | The literal string to split on |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

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
  expression: 'interval_uncertainty_precision_mismatch(--DTC, delimiter="/")'
Check:
  expression: '$interval_mismatch_dtc == true'
```

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.


### is_complete_date

`is_complete_date(x)` → **boolean**

True when every date component is present

| parameter | type | required | description |
|---|---|---|---|
| `x` | string | yes | The value to read |

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

**Example**

```yaml
expression: 'is_valid_duration(--ELTM)'
```

**Missing and empty** — `""` is not a valid duration, so this is **false** for it.

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

**Missing and empty** — `""` and a missing input yield **missing** — neither carries a date component.

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

**Missing and empty** — `""` and a missing input yield **missing** — neither carries a date component.


## Aggregates over rows and groups

### constant

`constant(name)` → **value**

The literal value given as `name`, broadcast to every row

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$threshold"
  expression: 'constant("5")'
Check:
  expression: 'num(--STRESN) > num($threshold)'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — **missing** — the calculation was not possible. A comparison against it does not fire; `empty()` on it does.

### distinct

`distinct(name, …)` → **value**

The set of distinct values of a column; with a list target, the set of row tuples

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `domain` | string | no | The dataset to read from |
| `filter` | boolean | no | Restricts which rows the function sees |
| `group` | list of columns | no | Columns whose combined value defines a group |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |
| `value_is_reference` | boolean | no | Whether the target column holds column *names* rather than values |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$scheduled_subjects"
  expression: 'distinct(USUBJID, domain="DS", filter=filter(DSDECOD=["TERMINAL SACRIFICE", "INTERIM SACRIFICE", "RECOVERY SACRIFICE", "NON-MORIBUND SACRIFICE"]))'
Check:
  expression: 'USUBJID in $scheduled_subjects'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$domain_list"
  expression: 'distinct(DOMAIN)'
Check:
  expression: '$domain_is_custom == true and varname() not in ["STUDYID", "DOMAIN", "USUBJID", "POOLID", "SPDEVID", "VISIT", "VISITNUM", "VISITDY", "ELEMENT", "TAETORD", "EPOCH", "NHOID", "FOCID", "SPTOBID", "STOCONID", "IGDCMPID", "MIDS", "RELMIDS", "MIDSDTC", "RSUBJID", "RDEVID", "SREL", "APID"] and prefix(varname(), 2) not in $domain_list'
```

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.


### empty_within_except_last_row

`empty_within_except_last_row(name, group, ordering=, keep_missings=)` → **boolean**

True when a row other than the ordered-last one is unpopulated

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |
| `group` | any | yes | Columns whose combined value defines a group |
| `ordering` | any | yes | Sort keys deciding row order inside the group |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |

**Example**

```yaml
Check:
  expression: 'empty_within_except_last_row(AEENDTC, group=[USUBJID], ordering="AESEQ", keep_missings=false)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'empty_within_except_last_row(AEENDTC, group=[USUBJID], ordering="AESEQ")'
```


### has_mixed_emptiness_within_group

`has_mixed_emptiness_within_group(name, group=)` → **value**

True for a row whose group has both populated and empty values of `name`

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `group` | list of columns | no | Columns whose combined value defines a group |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |
| `qualifying_any_populated` | list<string> | no | Whether one populated value is enough to qualify the group |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

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

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.


### is_last_in_group

`is_last_in_group(group=, ordering=)` → **value**

True on the last row of each group under `ordering=`

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `group` | list of columns | no | Columns whose combined value defines a group |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |
| `ordering` | string | no | Sort keys deciding row order inside the group |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$is_last_se"
  expression: 'is_last_in_group(ordering="SESEQ", group=[USUBJID], keep_missings=false)'
Check:
  expression: '$is_last_se == true and date(SEENDTC) > DS.DSSTDTC'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$is_last_visit"
  expression: 'is_last_in_group(group=[USUBJID])'
Check:
  expression: '$is_last_visit == true and empty(VISITDY)'
```

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.


### max

`max(name, …)` → **value**

The largest value of a column, over the dataset or per group

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `domain` | string | no | The dataset to read from |
| `filter` | boolean | no | Restricts which rows the function sees |
| `group` | list of columns | no | Columns whose combined value defines a group |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$baseline_ayind"
  expression: 'max("AyIND", group=[USUBJID, PARAMCD, BASETYPE, SPDEVID], filter=filter(ABLFL="Y"))'
Check:
  expression: 'var_exists("BASETYPE") and not empty(ByIND) and ByIND != $baseline_ayind'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$max_ds_dsstdy"
  expression: 'max(DSSTDY, domain="DS", group=[USUBJID])'
Check:
  expression: 'USUBJID in $ds_usubjid and not empty(--DY) and not empty($max_ds_dsstdy) and --DY != $max_ds_dsstdy'
```

**When there is nothing to answer** — **missing** — the calculation was not possible. A comparison against it does not fire; `empty()` on it does.


### max_date

`max_date(name, …)` → **value**

The latest date in a column; a value that cannot be positioned yields no value

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `domain` | string | no | The dataset to read from |
| `filter` | boolean | no | Restricts which rows the function sees |
| `group` | list of columns | no | Columns whose combined value defines a group |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |
| `missing_values` | string | no | How a missing input affects the result |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

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

**When there is nothing to answer** — **missing** — the calculation was not possible. A comparison against it does not fire; `empty()` on it does.


### min_date

`min_date(name, …)` → **value**

The earliest date in a column; a value that cannot be positioned yields no value

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `domain` | string | no | The dataset to read from |
| `filter` | boolean | no | Restricts which rows the function sees |
| `group` | list of columns | no | Columns whose combined value defines a group |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |
| `missing_values` | string | no | How a missing input affects the result |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$min_ds_dsstdtc"
  expression: 'min_date(DSSTDTC, domain="DS", group=[USUBJID], filter=filter(DSDECOD="INFORMED CONSENT OBTAINED"))'
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

**When there is nothing to answer** — **missing** — the calculation was not possible. A comparison against it does not fire; `empty()` on it does.


### present_on_multiple_rows_within

`present_on_multiple_rows_within(name, within=)` → **boolean**

True for every member of a group that has two or more rows

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |
| `within` | any | yes | Columns that partition the rows |
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

`record_count(…)` → **value**

The number of rows; with `group=`, the count for each row's group

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `domain` | string | no | The dataset to read from |
| `filter` | boolean | no | Restricts which rows the function sees |
| `group` | list of columns | no | Columns whose combined value defines a group |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |
| `regex` | regex | no | A regular expression, written as a **string** literal |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$inttype_pharm_count"
  expression: 'record_count(filter=filter(TSPARMCD="INTTYPE", TSVAL=["DRUG", "BIOLOGIC", "VACCINE", "DIETARY SUPPLEMENT", "COMBINATION PRODUCT"]))'
Check:
  expression: '$stype_interventional_count >= 1 and $inttype_pharm_count >= 1 and $pclas_count == 0'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$bt_total"
  expression: 'record_count()'
Check:
  expression: '$bt_pop > 0 and $bt_pop < $bt_total'
```

**When there is nothing to answer** — **`0`** — a real count of nothing, not an absence of an answer. So `$n == 0` and `$n <= 1` **fire**.

**Missing and empty** — Always present — a row count, never missing.


### row_max

`row_max(name=/names=, …)` → **value**

The largest of the named columns, per row

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `name_pattern` | regex | no | A regular expression over column names |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$trxx_max"
  expression: 'row_max(name_pattern="^TR(0[1-9]|[1-9][0-9])EDT$")'
Check:
  expression: 'not empty(TRTEDT) and not empty($trxx_max) and TRTEDT != $trxx_max'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$dt_max"
  expression: 'row_max([TR01EDT, TR02EDT])'
Check:
  expression: 'not empty(TRTEDT) and TRTEDT != $dt_max'
```

**When there is nothing to answer** — **missing** — the calculation was not possible. A comparison against it does not fire; `empty()` on it does.


### row_min

`row_min(name=/names=, …)` → **value**

The smallest of the named columns, per row

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `name_pattern` | regex | no | A regular expression over column names |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$trxx_min"
  expression: 'row_min(name_pattern="^TR(0[1-9]|[1-9][0-9])SDT$")'
Check:
  expression: 'not empty(TRTSDT) and not empty($trxx_min) and TRTSDT != $trxx_min'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$dt_min"
  expression: 'row_min([TR01SDT, TR02SDT])'
Check:
  expression: 'not empty(TRTSDT) and TRTSDT != $dt_min'
```


### tuple

`tuple(c1, c2, …)` → **value**

Builds a composite key from two to six columns, for whole-tuple membership

| parameter | type | required | description |
|---|---|---|---|
| `c1` | column | yes | A column of the composite key |
| `c2` | column | yes | A column of the composite key |
| `columns` | list of columns (absorbs the remaining arguments) | no | The columns forming the composite key |

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

`variable_count(name_pattern=…)` → **value**

The number of columns whose name matches `name_pattern=`

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `name_pattern` | regex | no | A regular expression over column names |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

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
  expression: 'variable_count(--LNKID)'
Check:
  expression: 'var_exists("--LNKID") and $variable_count < 2'
```

**When there is nothing to answer** — **`0`** — a real count of nothing, not an absence of an answer. So `$n == 0` and `$n <= 1` **fire**.


### variable_value_count

`variable_value_count(name, …)` → **value**

How many times each value of the column occurs

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$value_census"
  expression: 'variable_value_count(--TESTCD)'
Check:
  expression: 'not empty($value_census)'
```

The example above is the complete call — this function takes no arguments beyond its optional target.



## Uniqueness and consistency

**When there is nothing to answer** — **`0`** — a real count of nothing, not an absence of an answer. So `$n == 0` and `$n <= 1` **fire**.

### has_multiple_values_for

`has_multiple_values_for(name, key, within=, include_empty=)` → **boolean**

True when one `key` maps to more than one `name` — a functional-dependency violation; a blank key or value is excluded unless `include_empty=true`

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |
| `key` | any | yes | The column whose value acts as the key |
| `within` | any | no | Columns that partition the rows |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |
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
| `name` | any | yes | The column to read |

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
| `key` | any | no | The column whose value acts as the key |
| `keys` | list of columns | no | The values that must all be present |
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


### is_unique_relationship

`is_unique_relationship(a, b)` → **boolean**

True when `a` and `b` are in a strict one-to-one relationship. A negated spelling `is_not_unique_relationship(a, b)` exists; prefer `not is_unique_relationship(a, b)`. A blank key or value is excluded.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |
| `value` | any | no | The value to compare against |
| `keys` | list of columns | no | The values that must all be present |

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

True when the tuple of the listed columns occurs exactly once. A negated spelling `is_not_unique_set([…])` exists; prefer `not is_unique_set([…])`. An absent member is dropped, `""` is a real component.

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

**Missing and empty** — `""` is a real component of the tuple; an absent member is dropped wherever it sits.


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


### has_next_corresponding_record

`has_next_corresponding_record(name, value, within=, ordering=, keep_missings=, relation=)` → **boolean**

True when a following record in the group matches under `relation=`; authored under `not`

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |
| `value` | any | yes | The value to compare against |
| `within` | any | yes | Columns that partition the rows |
| `ordering` | any | yes | Sort keys deciding row order inside the group |
| `relation` | string | no | How a record must relate to the next one |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |

**Example**

```yaml
Check:
  expression: 'not has_next_corresponding_record(--ENDTC, --STDTC, within=[USUBJID], ordering="--SEQ", relation="<=", keep_missings=false)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not has_next_corresponding_record(--ENDTC, --STDTC, within=[USUBJID], ordering="--SEQ")'
```

**Missing and empty** — `""` is compared literally, not treated as absent.


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

### is_sorted_by

`is_sorted_by(target, by=[asc("col")[, nulls=]], within=)` → **boolean**

True when the target is sorted by `by=`; authored under `not`, it fires the whole unsorted group

| parameter | type | required | description |
|---|---|---|---|
| `target` | any | yes | The column the function is about |
| `by` | list of columns | yes | Sort keys |
| `within` | any | no | Columns that partition the rows |
| `keep_missings` | boolean | no | Whether a row with a missing key stays in its group |

**Example**

```yaml
Check:
  expression: 'not is_sorted_by(VISITNUM, by=[asc("VISITNUM")], within=[USUBJID], keep_missings=false)'
```

**Minimal — required parameters only**

```yaml
Check:
  expression: 'not is_sorted_by(VISITNUM, by=[asc("VISITNUM")])'
```

**Missing and empty** — `keep_missings=` decides whether a row with a missing sort key stays in its group.


### shares_elements_with

`shares_elements_with($a, $b)` → **boolean**

True when the two lists have at least one member in common. A negated spelling `shares_no_elements_with($a, $b)` exists; prefer `not shares_elements_with($a, $b)`.

| parameter | type | required | description |
|---|---|---|---|
| `name` | any | yes | The column to read |
| `value` | any | no | The value to compare against |
| `keys` | list | no | The values that must all be present |

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



## Dataset and domain metadata

### dataset_class_from_library

`dataset_class_from_library(…)` → **value**

The current table's library observation class

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$dataset_class"
  expression: 'dataset_class_from_library()'
Check:
  expression: 'starts_with(ds_name("DATA"), "AD") and empty($dataset_class)'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — **missing** — the calculation was not possible. A comparison against it does not fire; `empty()` on it does.

### dataset_domain

`dataset_domain(…)` → **value**

The current dataset's domain as a declared operation

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
expression: 'len(dataset_domain) > 2 or len(dataset_domain) < 2'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — **missing** — the calculation was not possible. A comparison against it does not fire; `empty()` on it does.

### dataset_names

`dataset_names()` → **value**

The names of every dataset in the study, as a list

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$study_datasets"
  expression: 'dataset_names()'
Check:
  expression: 'not shares_elements_with($study_datasets, keys=[SUPPAE]) or $aetrtem_count < 1'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.

### domain_is_custom

`domain_is_custom(…)` → **value**

Library classification gate; boolean

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$domain_is_custom"
  expression: 'domain_is_custom()'
Check:
  expression: '$domain_is_custom == true and varname() not in ["STUDYID", "DOMAIN", "USUBJID", "POOLID", "SPDEVID", "VISIT", "VISITNUM", "VISITDY", "ELEMENT", "TAETORD", "EPOCH", "NHOID", "FOCID", "SPTOBID", "STOCONID", "IGDCMPID", "MIDS", "RELMIDS", "MIDSDTC", "RSUBJID", "RDEVID", "SREL", "APID"] and prefix(varname(), 2) not in $domain_list'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.

### ds_class

`ds_class([x,] level)` → **value**

The dataset's observation class; available at every level

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'not empty(ds_class("DEFINE"))'
```

### ds_domain

`ds_domain("DATA")` → **value**

The dataset's domain as `Scope.Domains` resolves it — `SUPPLB`, not the parent, on a SUPP dataset; `DATA` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DATA` |

**Example**

```yaml
expression: 'ds_domain("DATA") == "AE"'
```

### ds_label

`ds_label([x,] level)` → **value**

The dataset's label; available at every level

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'ds_label("DATA") != "Device-level Analysis Dataset"'
```

### ds_name

`ds_name([x,] level)` → **value**

The dataset's name; available at every level

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'ds_name("DATA") !~ /^[A-Z0-9_]{1,8}$/'
```

### ds_structure

`ds_structure([x,] level)` → **value**

The dataset's ADaM data structure; `DEFINE` and `LIBRARY` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'ds_structure("DEFINE")'
```

### extract_metadata

`extract_metadata(name)` → **value**

Scalar dataset-metadata attribute of the current table

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$dataset_location"
  expression: 'extract_metadata("dataset_location")'
Check:
  expression: '$dataset_location =~ /[A-Z]/'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$file_format"
  expression: 'extract_metadata("file_format")'
Check:
  expression: 'not empty($file_format) and $file_format != "XPORT"'
```

**When there is nothing to answer** — **missing** — the calculation was not possible. A comparison against it does not fire; `empty()` on it does.


### referenced_domain_class

`referenced_domain_class(name)` → **value**

Classifies the domain **named in a column value**

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$rdomain_class"
  expression: 'referenced_domain_class(RDOMAIN)'
Check:
  expression: '$rdomain_class not in ["EVENTS", "INTERVENTIONS", "FINDINGS", "FINDINGS ABOUT"] and RDOMAIN != "DM" and RDOMAIN != "SV"'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — **`""`** — the closed-world reading: the source holds nothing under that name. So a `!=` against a literal **fires**.

### split_sibling_length_mismatch

`split_sibling_length_mismatch()` → **value**

Split-family declared-length disagreement (library-independent)

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$len_mismatch"
  expression: 'split_sibling_length_mismatch()'
Check:
  expression: 'varname() in $len_mismatch'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.

### standard_domains

`standard_domains()` → **value**

The domain codes the standard publishes, as a list

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$sdtm_domains"
  expression: 'standard_domains()'
Check:
  expression: 'not empty(SRCDOM) and SRCDOM not in $sdtm_domains'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.

### study_domains

`study_domains()` → **value**

The domain codes present in the study, as a list

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$study_domains"
  expression: 'study_domains()'
Check:
  expression: 'not empty(RDOMAIN) and RDOMAIN not in $study_domains'
```

The example above is the complete call — this function takes no arguments beyond its optional target.



## Variable metadata

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.

### column_series_metadata

`column_series_metadata(name_pattern=, …)` → **value**

Enumerated-series completeness / continuation verdict

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `name_pattern` | regex | no | A regular expression over column names |
| `min_length` | number | no | Shortest series the check applies to |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$coval_series_incomplete"
  expression: 'column_series_metadata(COVAL, name_pattern="^COVAL\\d+$", min_length=200)'
Check:
  expression: '$coval_series_incomplete == true'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$coval_series"
  expression: 'column_series_metadata(COVAL)'
Check:
  expression: '$coval_series == true'
```

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.


### cross_dataset_variable_metadata

`cross_dataset_variable_metadata(…)` → **value**

Per-variable metadata from another dataset (`VariableMetadataResult`)

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `domain` | string | no | The dataset to read from |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$adsl_data_type"
  expression: 'cross_dataset_variable_metadata("data_type", domain="ADSL")'
Check:
  expression: 'not empty($adsl_data_type) and var_type("DATA") != $adsl_data_type'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$sdtm_label"
  expression: 'cross_dataset_variable_metadata("label", domain="*")'
Check:
  expression: 'not empty($sdtm_label) and var_label("DATA") != $sdtm_label'
```

**When there is nothing to answer** — **missing** — the calculation was not possible. A comparison against it does not fire; `empty()` on it does.


### duplicate_label_variables

`duplicate_label_variables()` → **value**

Variables sharing a declared label

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$dup_labels"
  expression: 'duplicate_label_variables()'
Check:
  expression: 'varname() in $dup_labels'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.

### expected_variables

`expected_variables(…)` → **value**

The variables the CDISC Library marks Expected for this dataset, as a list

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$expected_variables"
  expression: 'expected_variables()'
Check:
  expression: 'not contains_all($dataset_variables, $expected_variables)'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.

### get_column_order_from_dataset

`get_column_order_from_dataset(…)` → **value**

The dataset's own column order, as a list of names

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$dataset_variables"
  expression: 'get_column_order_from_dataset()'
Check:
  expression: 'not shares_elements_with($dataset_variables, $timing_variables)'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.

### get_column_order_from_library

`get_column_order_from_library(…)` → **value**

The column order the CDISC Library publishes for this dataset, as a list

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$library_variables"
  expression: 'get_column_order_from_library()'
Check:
  expression: 'upper(varname()) not in $library_variables'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — ⚠ **the rule is SKIPPED** for that dataset, an exception to the totality rule above: an absent or empty column order is mapped to *library not available*. To keep a rule answering when the IG has no list for the dataset, bind [`get_dataset_filtered_variables()`](#get_dataset_filtered_variables) instead — the same IG-resolved list intersected with the dataset's columns, which answers the empty set.

### get_dataset_filtered_variables

`get_dataset_filtered_variables(key_name=, key_value=)` → **value**

Library variables of the current dataset filtered by metadata, intersected with present columns

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `key_name` | string | no | The metadata attribute to filter on |
| `key_value` | string | no | The value that attribute must have |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

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
  expression: 'varname() in $expected_variables and var_is_null(varname())'
```

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.


### get_model_column_order

`get_model_column_order(…)` → **value**

The column order of the standard's model, as a list

It is the **pure SDTM model walk** for the dataset's observation class: the IG's own dataset variables are never merged in. Where an IG runs ahead of the model version it links to, a variable the IG defines for the dataset is therefore **absent** from this list (60 of them at SENDIG 3.0, 5 at SDTMIG 3.3, none at SDTMIG 3.4). `FDA-SD0058` binds it next to the IG list from [`get_dataset_filtered_variables()`](#get_dataset_filtered_variables) and reports such a variable at `WARNING`, a variable in neither list at `ERROR`.

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$model_order"
  expression: 'get_model_column_order()'
Check:
  expression: 'varname() not in $model_order and varname() not in $library_order and varname() not in $events_vars and varname() not in $interventions_vars and varname() not in $findings_vars and varname() not in $findings_about_vars'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — ⚠ **the rule is SKIPPED** for that dataset, an exception to the totality rule above: an absent or empty model list is mapped to *library not available*, so `varname() not in $s` never fans out over every column.

**A domain the run's IG does not define** — a sponsor-defined domain, or a standard one the IG leaves out (at SENDIG-AR 1.0 every SEND domain but DM/CM/FA/MH) — is walked under exactly the observation class the **scope matcher** gives the dataset (the study's declared class, the product, the curated domain map — these three keyed by the `DOMAIN` value, or by the dataset name when there is no `DOMAIN` column — then the topic-variable sniff of the dataset's own columns, which needs a `DOMAIN` column; and for an `AP--` dataset, finally its parent domain's class), so the class a rule is scoped by and the class its model list comes from are one class. Only a dataset no class tier places — a code the curated map does not know whose columns show no topic variable the sniff recognises (e.g. SDTMIG-MD's DI and DR), or an `AP--` dataset whose parent domain is not in the study, which the scope matcher places nowhere either — or one whose class the linked model has no variables for, resolves an empty list — and then the rule is skipped. [`get_model_filtered_variables()`](#get_model_filtered_variables) and [`get_parent_model_column_order()`](#get_parent_model_column_order) read this same walk. [`get_dataset_filtered_variables()`](#get_dataset_filtered_variables) and [`natural_key_variables()`](#natural_key_variables) read the IG-merged walk, which for such a domain is this one when its class is a general-observation class (Events, Interventions, Findings, Findings About) — and the **empty set** when its class is special-purpose, trial-design or relationship (e.g. CO, TS or RELREC in a SENDIG-AR 1.0 run; an `AP--` dataset keeps only its associated-persons identifiers): the IG-merged walk has no model dataset to fall back to. Where this lookup skips, the filtered lookups answer the empty set. [`get_column_order_from_library()`](#get_column_order_from_library) stays keyed by the domain name: the IG has no column order for such a domain, so it skips.

### get_model_filtered_variables

`get_model_filtered_variables(key_name=, key_value=, model_class=)` → **value**

Model variables filtered by metadata. `model_class=` (, coreJ-only) walks the **named** general-observation class's model table

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `key_name` | string | no | The metadata attribute to filter on |
| `key_value` | string | no | The value that attribute must have |
| `model_class` | string | no | The general-observation class whose model table to walk |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

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

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.


### get_parent_model_column_order

`get_parent_model_column_order(…)` → **value**

The column order of the parent model, as a list

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$model_variables"
  expression: 'get_parent_model_column_order()'
Check:
  expression: 'QNAM in $model_variables'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — ⚠ **the rule is SKIPPED**, an exception to the totality rule above: when no row's parent domain resolves a model list, the operation answers *library not available*.

### natural_key_variables

`natural_key_variables()` → **value**

Natural-key-forming role variables present in the dataset

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$natural_key"
  expression: 'natural_key_variables()'
Check:
  expression: 'not is_unique_set([USUBJID, --TESTCD, $natural_key])'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.

### required_variables

`required_variables(…)` → **value**

The variables the CDISC Library marks Required for this dataset, as a list

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$required_variables"
  expression: 'required_variables()'
Check:
  expression: 'varname() in $required_variables and empty(value())'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.

### var_ccode

`var_ccode(x, "DEFINE")` → **value**

The NCI C-code of the bound codelist; `DEFINE` and `LIBRARY` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
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
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'not empty(var_codelist("LIBRARY"))'
```

### var_codelist_coded_codes

`var_codelist_coded_codes(x, level)` → **value**

The codes of the bound codelist's terms, as a list; `DEFINE` and `LIBRARY` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'not empty(var_codelist_coded_codes("LIBRARY"))'
```

### var_codelist_coded_values

`var_codelist_coded_values(x, level)` → **value**

The submission values of the bound codelist's terms, as a list; `DEFINE` and `LIBRARY` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'not empty(var_codelist_coded_values("LIBRARY"))'
```

### var_codelist_extended_values

`var_codelist_extended_values(x, "DEFINE")` → **value**

The sponsor-added values of an extended codelist, as a list; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'not empty(var_codelist_extended_values("DEFINE"))'
```

### var_codelist_extensible

`var_codelist_extensible(x, "LIBRARY")` → **value**

Whether the bound codelist is extensible; `LIBRARY` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
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
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'var_core("LIBRARY") == "Perm"'
```

### var_external_dictionary

`var_external_dictionary(x, "DEFINE")` → **value**

The external dictionary the variable is coded against; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'var_name("DEFINE") in ["LBTOX", "LBTOXGR"] and empty(var_external_dictionary("DEFINE"))'
```

### var_external_dictionary_version

`var_external_dictionary_version(x, "DEFINE")` → **value**

The version of that external dictionary; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'var_external_dictionary_version(varname(), "DEFINE")'
```

### var_format

`var_format([x,] level)` → **value**

The variable's display format; `DATA` and `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DATA/DEFINE` |

**Example**

```yaml
expression: 'empty(var_format("DATA"))'
```

### var_has_codelist

`var_has_codelist(x, "DEFINE")` → **value**

Whether the variable binds a codelist at all; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'var_has_codelist("DEFINE") == true'
```

### var_has_comment

`var_has_comment(x, "DEFINE")` → **value**

Whether Define-XML attaches a comment to the variable; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'not empty(var_name("DEFINE")) and var_has_comment("DEFINE") == false'
```

### var_has_method

`var_has_method(x, "DEFINE")` → **value**

Whether Define-XML attaches a derivation method to the variable; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'var_has_method("DEFINE") == false'
```

### var_label

`var_label([x,] level)` → **value**

The variable's label; available at every level

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'var_label("DATA") =~ /^[a-z]/'
```

**At `LIBRARY`, a dataset whose domain the run's IG does not define has no label** — nor a codelist, core designation or ordinal: the SDTM model's labels are class-level ones, not the label a standard gives the domain, and a sponsor may adjust the labels of a domain it defines. A rule guarded by `not empty(var_label("LIBRARY"))` therefore does not judge such a dataset. Its name, data type and role do come from the model (see [`var_type`](#var_type)).

### var_length

`var_length([x,] level)` → **value**

The variable's declared length; available at every level

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'var_length("DATA") > 200'
```

### var_mandatory

`var_mandatory(x, "DEFINE")` → **value**

Whether Define-XML marks the variable mandatory; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE` |

**Example**

```yaml
expression: 'var_mandatory(varname(), "DEFINE") == "Yes"'
```

### var_name

`var_name([x,] level)` → **value**

The variable's name; available at every level

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'not empty(var_name("DEFINE"))'
```

**At `LIBRARY`, a dataset whose domain the run's IG does not define** — a sponsor-defined domain, or a standard one the IG leaves out (at SENDIG-AR 1.0 every SEND domain but DM, CM, FA and MH; at SENDIG-DART 1.1 EG, PM, TF, VS, CV and RE) — takes its name from the SDTM model's variable of the observation class the scope matcher gives the dataset. If no class places the dataset, if the class is a special-purpose, trial-design or relationship one, or if the model has no such variable (a sponsor's own variable), there is no value, as for any variable the Library does not know.

### var_ordinal

`var_ordinal([x,] level)` → **value**

The variable's position in the declared column order; available at every level

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'var_ordinal("DEFINE")'
```

### var_origin_type

`var_origin_type(x, "DEFINE")` → **value**

The variable's Define-XML origin type — Collected, Derived and so on; `DEFINE` only

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
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
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'not empty(var_role("DEFINE"))'
```

**At `LIBRARY`, a dataset whose domain the run's IG does not define** — a sponsor-defined domain, or a standard one the IG leaves out (at SENDIG-AR 1.0 every SEND domain but DM, CM, FA and MH; at SENDIG-DART 1.1 EG, PM, TF, VS, CV and RE) — takes its role from the SDTM model's variable of the observation class the scope matcher gives the dataset. If no class places the dataset, if the class is a special-purpose, trial-design or relationship one, or if the model has no such variable (a sponsor's own variable), there is no value, as for any variable the Library does not know.

### var_type

`var_type([x,] level)` → **value**

The variable's data type; available at every level

| parameter | type | required | description |
|---|---|---|---|
| `x` | column | no | The column to read; defaults to the variable being iterated |
| `level` | level | yes | Which metadata to read — supported here: `DATA/DEFINE/LIBRARY` |

**Example**

```yaml
expression: 'var_type("DATA") != "Num"'
```

**At `LIBRARY`, a dataset whose domain the run's IG does not define** — a sponsor-defined domain, or a standard one the IG leaves out (at SENDIG-AR 1.0 every SEND domain but DM, CM, FA and MH; at SENDIG-DART 1.1 EG, PM, TF, VS, CV and RE) — takes its data type from the SDTM model's variable of the observation class the scope matcher gives the dataset. If no class places the dataset, if the class is a special-purpose, trial-design or relationship one, or if the model has no such variable (a sponsor's own variable), there is no value, as for any variable the Library does not know.

### variable_names

`variable_names()` → **value**

The current dataset's variable-name list

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$variable_names"
  expression: 'variable_names()'
Check:
  expression: 'QNAM in $variable_names'
```

The example above is the complete call — this function takes no arguments beyond its optional target.



## Define-XML and value-level metadata

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.

### define_dataset_names

`define_dataset_names()` → **value**

Define-XML dataset names; requires DEFINE

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$define_datasets"
  expression: 'define_dataset_names()'
Check:
  expression: 'not contains_all($study_datasets, $define_datasets)'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.

### define_key_variables

`define_key_variables()` → **value**

Define-XML key variables for the domain

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$define_key_variables"
  expression: 'define_key_variables()'
Check:
  expression: 'not is_unique_set([STUDYID, $define_key_variables])'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.

### define_variable_names

`define_variable_names()` → **value**

Define-XML ItemDef names for the domain

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$define_variables"
  expression: 'define_variable_names()'
Check:
  expression: 'not contains_all($dataset_variables, $define_variables)'
```

The example above is the complete call — this function takes no arguments beyond its optional target.

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

`codelist_terms(…)` → **value**

CT terms of the bound codelist (library CT)

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `codelists` | list of columns | no | Which codelists to read |
| `level` | level | no | Which metadata to read — `DATA`, `DEFINE` or `LIBRARY` |
| `returntype` | string | no | Which attribute of a term to return |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$sndigver_terms"
  expression: 'codelist_terms(codelists=[SNDIGVER], level="term", returntype="value")'
Check:
  expression: 'TSPARMCD == "SNDIGVER" and not empty(TSVAL) and TSVAL not in $sndigver_terms'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$nd_terms"
  expression: 'codelist_terms(codelists=[ND], level="term", returntype="value")'
Check:
  expression: 'not empty(--STAT) and --STAT not in $nd_terms'
```

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.


### dictionary_available

`dictionary_available(…)` → **boolean**

The operation form of the availability gate

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `external_dictionary_type` | string | no | Which external dictionary to check against |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Check:
  expression: 'dictionary_available(external_dictionary_type="meddra")'
```

The example above is the complete call.

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.

### dictionary_has_decode

`dictionary_has_decode(…)` → **value**

Any-decode presence (also a per-record function, ); code lookup case-sensitive by default, `case_sensitive=false` folds it

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `external_dictionary_type` | string | no | Which external dictionary to check against |
| `case_sensitive` | boolean | no | Whether the comparison folds case |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$cmtrt_has_decode"
  expression: 'dictionary_has_decode(CMTRT, external_dictionary_type="whodrug", case_sensitive=false)'
Check:
  expression: '$cmtrt_has_decode == false'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$cmtrt_has_decode"
  expression: 'dictionary_has_decode(CMTRT, external_dictionary_type="whodrug")'
Check:
  expression: '$cmtrt_has_decode == false'
```

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.


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

A **registry function** since PLAN-binding-expressions (wave 0 of the operations→functions
programme): the version column is the second positional argument — a column reference, **not** the
quoted `version="TSVCDVER"` of the retired operation, which would now be the string literal
`"TSVCDVER"`. It is an **aggregate**: it folds every row of the dataset whichever row reads it, and
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

`RulePackageLoader` injects this gate at load for a library-dependent operation; a not-met Precondition ⇒ SKIPPED

Takes no arguments.

### valid_codelist_dates

`valid_codelist_dates(…)` → **value**

Published CT-package dates for the standard

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `ct_package_types` | list<string> | no | Overrides the CT package types for the standard |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$valid_dates"
  expression: 'valid_codelist_dates(ct_package_types=[SDTM])'
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

**When there is nothing to answer** — **the empty set**. `X in $s` is then vacuously false; `X not in $s` is vacuously true and **fires**.


### valid_external_dictionary_code

`valid_external_dictionary_code(name, external_dictionary_type=)` → **value**

Dictionary code validity; same evaluator and `case_sensitive` semantics as `_value`

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `external_dictionary_type` | string | no | Which external dictionary to check against |
| `dictionary_term_type` | string | no | Which term type in the dictionary to check |
| `case_sensitive` | boolean | no | Whether the comparison folds case |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

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

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.


### valid_external_dictionary_code_term_pair

`valid_external_dictionary_code_term_pair(name, dictionary_term=, external_dictionary_type=)` → **value**

Code↔decode pairing; code AND decode case-sensitive by default, `case_sensitive=false` folds both

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `external_dictionary_type` | string | no | Which external dictionary to check against |
| `external_dictionary_term_variable` | string | no | The column holding the decode to pair with the code |
| `case_sensitive` | boolean | no | Whether the comparison folds case |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$neoplasm_aligned"
  expression: 'valid_external_dictionary_code_term_pair(--STRESC, external_dictionary_type="neoplasm", external_dictionary_term_variable="--RESCAT")'
Check:
  expression: '--RESCAT in ["BENIGN", "MALIGNANT"] and $neoplasm_aligned == false'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$comptrt_pair_valid"
  expression: 'valid_external_dictionary_code_term_pair(TSVALCD, external_dictionary_type="unii", external_dictionary_term_variable="TSVAL")'
Check:
  expression: 'TSPARMCD == "COMPTRT" and $comptrt_pair_valid == false'
```

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.


### valid_external_dictionary_hierarchy

`valid_external_dictionary_hierarchy(…)` → **value**

Hierarchy ancestor test (also a per-record function, ); operands case-sensitive by default, `case_sensitive=false` folds both

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `external_dictionary_type` | string | no | Which external dictionary to check against |
| `dictionary_parent` | string | no | The ancestor term the value must sit under |
| `case_sensitive` | boolean | no | Whether the comparison folds case |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$hlgt_on_path"
  expression: 'valid_external_dictionary_hierarchy(--HLGT, external_dictionary_type="meddra", dictionary_parent="--SOC")'
Check:
  expression: '$hlgt_on_path == false'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$hlt_on_path"
  expression: 'valid_external_dictionary_hierarchy(--HLT, external_dictionary_type="meddra", dictionary_parent="--SOC")'
Check:
  expression: '$hlt_on_path == false'
```

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.


### valid_external_dictionary_value

`valid_external_dictionary_value(name, external_dictionary_type=)` → **value**

Dictionary term validity; gated by `dictionary_available`; case-SENSITIVE by default — `case_sensitive=false` opts into folded membership

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `external_dictionary_type` | string | no | Which external dictionary to check against |
| `dictionary_term_type` | string | no | Which term type in the dictionary to check |
| `case_sensitive` | boolean | no | Whether the comparison folds case |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

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

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.



## Supplemental qualifiers and trial summary

### supp_qnam_present

`supp_qnam_present(name)` → **value**

Per-record: a matching SUPP QNAM row exists

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `domain` | string | no | The dataset to read from |
| `key_value` | string | no | The value that attribute must have |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$resmod_mi"
  expression: 'supp_qnam_present(domain="SUPPMI", key_value="MIRESMOD")'
Check:
  expression: '--STRESC == --ORRES and --STRESC not in ["NORMAL", "UNREMARKABLE"] and (not empty(--SEV) or not empty(--CHRON) or not empty(--DISTR) or $resmod_cl == true or $resmod_ma == true or $resmod_mi == true)'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$calcn_present"
  expression: 'supp_qnam_present(domain="SUPPPC", key_value="PCCALCN")'
Check:
  expression: '(PCSTRESC == "BLQ" or PCSTRESC == "ALQ") and $calcn_present == false'
```

**When there is nothing to answer** — **`false`** — the predicate was evaluated and did not hold. It is not unknown.


### supp_qnam_value

`supp_qnam_value(name)` → **value**

Per-record: the matching SUPP `QVAL`

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `domain` | string | no | The dataset to read from |
| `key_value` | string | no | The value that attribute must have |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$aetrtem"
  expression: 'supp_qnam_value(QVAL, domain="SUPPAE", key_value="AETRTEM")'
Check:
  expression: '$aetrtem != "Y"'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$aetrtem"
  expression: 'supp_qnam_value(domain="SUPPAE", key_value="AETRTEM")'
Check:
  expression: '$aetrtem != "Y"'
```

**When there is nothing to answer** — **missing** — the calculation was not possible. A comparison against it does not fire; `empty()` on it does.


### ts_parameter_value

`ts_parameter_value(key_value=, …)` → **value**

TS/TX parameter scalar lookup (first matching row's value)

| parameter | type | required | description |
|---|---|---|---|
| `name` | column | no | The column to read |
| `names` | list of columns | no | The columns to read, as a list |
| `domain` | string | no | The dataset to read from |
| `key_name` | string | no | The metadata attribute to filter on |
| `key_value` | string | no | The value that attribute must have |

This operation takes **at most one positional argument**, its target: a scalar binds `name`, a list literal binds `names` and yields row **tuples**, an expression is a computed target. Give one, never both — see [The target of an operation](#the-target-of-an-operation-one-column-a-list-or-a-computed-value).

**Example**

```yaml
Bindings:
- name: "$exp_start"
  expression: 'ts_parameter_value(TSVAL, domain="TS", key_name="TSPARMCD", key_value="EXPSTDTC")'
Check:
  expression: 'not empty(EXSTDTC) and date(EXSTDTC) < date($exp_start)'
```

**Minimal — required parameters only**

```yaml
Bindings:
- name: "$ts_strain"
  expression: 'ts_parameter_value(TSVAL, domain="TS", key_name="TSPARMCD", key_value="STRAIN")'
Check:
  expression: '$strain_null == true and empty($ts_strain)'
```

**When there is nothing to answer** — **missing** — the calculation was not possible. A comparison against it does not fire; `empty()` on it does.



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

**Missing and empty** — Both a missing input and `""` yield **missing** — there is no first character to take.

### colref

`colref(x)` → **value**

The value of the column *named by* `x`'s value — a two-hop dereference

| parameter | type | required | description |
|---|---|---|---|
| `x` | any | yes | The value to read |

**Example**

```yaml
expression: 'not empty(IDVAR) and not empty(IDVARVAL) and str(IDVARVAL) != str(colref(IDVAR))'
```

**Missing and empty** — A named-but-absent column yields **missing**; an empty or non-string first hop is returned as it is.

### value

`value()` → **value**

The value of the variable currently being iterated, for this row

Takes no arguments.

**Example**

```yaml
expression: 'len(value()) > 200'
```

**Missing and empty** — A `""` cell yields `""`. A missing cursor or an absent column yields **missing**.

### varname

`varname()` → **value**

The name of the variable currently being iterated

Takes no arguments.

**Example**

```yaml
expression: 'len(varname()) > 8'
```

**Missing and empty** — Always present — it is the variable's name, never a data value.
