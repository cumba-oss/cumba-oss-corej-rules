# Bindings

<!-- Grounded: Binding.java — name + expression only, the JsonAnySetter rejects every other key
     loud (id:, operator:, group:, level:, filter:, …); a binding with no expression is filed on
     the loadError channel. Since PLAN-binding-expressions (W0): the expression is ANY expression,
     and since runbook W8 EVERY binding is a compiled binding — RulePackageLoader.materialiseBindings
     parses it once (CompiledBinding), and it is evaluated lazily in the reading context
     (BindingValue). -->

A binding gives a name to a sub-result, so the check can refer to it instead of restating it.

```yaml
Bindings:
- name: "$variable_count"
  expression: "variable_count(\"--LNKID\")"
Check:
  expression: 'var_exists("--LNKID") and $variable_count < 2'
```

Two reasons to use one:

1. **The check stays readable.** A computation with its own name reads as a noun in the
   condition, which is how the requirement was stated in the first place.
2. **The value is reported.** A `$`-name the check reads is derived into
   [`Output_Variables`](outcome.md) by the engine, so the number that triggered the finding
   appears in it — *"expected 2, found 1"* rather than *"something about LNKID"* — without
   listing it.

## The shape

A binding is exactly two keys:

```yaml
Bindings:
- name: "$disposition_event_count"
  expression: 'record_count(domain="DS", filter=(DSCAT == "DISPOSITION EVENT"))'
```

**`name`** is the `$`-variable the check will use. Name it for what it *is*, as a noun —
`$subject_count`, `$first_visit_date` — not for how it is computed.

**`expression`** is **any expression** the Check could contain: a function call, a
column, arithmetic, a comparison, a list literal. Parameters are keyword arguments inside the call;
there are no sibling keys.

```yaml
Bindings:
- name: "$term_upper"                 # a function
  expression: 'upper(AETERM)'
- name: "$age"                        # a column of a declared join, per row
  expression: 'DM.AGE'
- name: "$limit"                      # arithmetic over another binding
  expression: '$n - 1'
- name: "$in_dm"                      # a condition — read it as `$in_dm == true` / `== false`
  expression: 'USUBJID in distinct(USUBJID, domain="DM")'
- name: "$allowed"                    # a list literal — read it with `in` / `not in`
  expression: '["MILD", "MODERATE", "SEVERE"]'
```

A binding's **level** follows its expression: `upper(AETERM)` has a value per row (reported at the
finding's row), `record_count() + 0` or a list literal has one value for the dataset. Three rules
hold whatever the expression:

- **A name is declared once.** A second binding with the same `name` is a load error.
- **A binding reads only earlier bindings.** Reading a later one, or itself, is a load error.
- **A set difference or union reads only dataset-level lists.** `minus` / `union` — written in the
  Check or in a binding — that reads a per-row operand, bound (`minus($a, subtract=$per_row)`) or
  written inline (`minus($a, subtract=split_by(X, ","))`), is a load error with one message for both
  spellings: the operand has no row to pick the value at. Any dataset-level list expression is an
  operand, inline or through a binding — a binding is only a name for its expression.

A **list** binding (a list literal, or a list-valued function such as `get_codelist_attributes`) is
read with `in` / `not in`, `empty(…)` or by a list function such as `minus`; comparing it with `==` / `!=` / `<` … is a
load error, exactly as comparing a column with an inline list is. A list literal holds plain
literals of one kind: numbers, or strings, never both. Nor does it hold a
binding: `AETERM in ["A", $codes]` is a load error — the `$codes` element would be read as the text
`"$codes"` and never match — so write `$codes` alone as the operand, or spell the values. The one
place a `$` stands inside a list is a splice of **column names** into
[`record_count`](../reference/functions.md#record_count)'s `group=`,
[`is_unique_set`](../reference/functions.md#is_unique_set)'s members or
[`values`](../reference/functions.md#values)' columns — and there, as everywhere,
the expression the binding holds may be written in its place
(`is_unique_set([USUBJID, natural_key_variables()])`).

A **list of column names** is its own binding form: `$keys: [STUDYID, USUBJID]` — bare column
references, `--SEQ` and `DM.USUBJID` included, never mixed with a quoted literal — holds the
**names**, not the cells. It is read exactly where the written list of names is read: as the
members of `is_unique_set($keys)` / `is_not_unique_set($keys)` (also as a member,
`is_unique_set([STUDYID, $keys])`), as `record_count(group=$keys)`, and as the columns whose
per-row values `values($keys)` lists. Anywhere else — `X in $keys`, a comparison, a function's value
argument, another binding, `Output_Variables` — it is a load error with the hint *"`$keys` holds
column names: use it in a name slot (`group=$keys`) or read its values with `values($keys)`; to
compare names as text, write a string list"*. A `group=` that does not splice (`max`, `distinct`,
`read_value`) refuses it as it refuses a string list of names.


## ⛔ Everything else is rejected, loudly

A binding carries `name` and `expression` and nothing else. Any other key **fails the load** and
names what to write instead:

| you write | why it fails |
|---|---|
| `id:` | not a key a binding carries; the name goes in `name:` |
| `operator:` | not a key a binding carries |
| `group:` · `level:` · `filter:` · any other parameter | parameters go **inside** the expression, as keyword arguments |
| anything else | a binding is two keys |

This strictness is deliberate, and it is the loader's rule everywhere, not only here: an unknown
key at **any** level of a rule is a load error. It is sharper for a binding than elsewhere — a stray
key fails the **whole package**, not only the rule — because an ignored parameter is the worst
possible outcome: the binding computes something subtly different from
what you wrote, the rule runs, and it under-reports in silence.

```yaml
# ⛔ load error — filter is not a sibling key
- name: "$n"
  expression: "record_count()"
  filter: 'DSCAT == "DISPOSITION EVENT"'

# ✅ the parameter belongs in the call
- name: "$n"
  expression: 'record_count(filter=(DSCAT == "DISPOSITION EVENT"))'
```

A binding with no `expression` at all is also rejected, and the rule is reported as failing to
load rather than running without it.

## Using a binding in the check

Refer to it by its `$`-name:

```yaml
Bindings:
- name: "$subject_visit_count"
  expression: 'record_count(group=[USUBJID])'
Check:
  expression: '$subject_visit_count < 2'
```

A binding is computed **once**, not once per reference, so using the same name several times in
one condition costs nothing:

```yaml
Check:
  expression: '$subject_visit_count > 0 and $subject_visit_count < 2'
```

A `$`-name answers **exactly as its expression written in its place** — wherever the expression is
accepted, the binding is, and the other way round. That holds for a column name handed to
[`var_exists`](../reference/functions.md#var_exists) / `var_not_exists` too:

```yaml
Bindings:
- name: "$trt_p"                         # a COMPUTED name: tested per row
  expression: 'concat("ADSL.TRT", printf("%02d", APERIOD), "P")'
- name: "$planned"                       # a WRITTEN name: one fact for the dataset
  expression: "ADSL.TRT01P"
Check:
  expression: 'not empty($trt_p) and not var_exists($trt_p)'
```

`var_exists($planned)` is `var_exists(ADSL.TRT01P)` — whether that column exists, not what its
cells hold — and `var_exists($trt_p)` is `var_exists(concat(…))`, asked of each row's name. A
computed name that comes out missing makes both `var_exists` and `var_not_exists` false on that
row, so `not var_exists($trt_p)` alone would be true there: guard the name with
`not empty($trt_p)`. A binding whose type cannot name a column (a number, a date, a list) handed
to `var_exists` is a load error (`PARAMETER_TYPE`), as the same expression written inline is.

The same holds for an **absent column**: the binding's *uses* give it its type, as the position
gives a column read in place. `$x: AESTDY` read as `$x > 5` takes the missing an absent `AESTDY > 5`
takes, and `$v: read_value(X, domain=D, …)` over an absent `D` or `X`, read as a number, is the
missing too; read only as text, or not typed at all, it is `""` (see
[Absent columns](../semantics/absent-columns.md#the-same-through-a-binding-and-through-a-function)).

## When not to use one

**When the expression is already clear.** A binding that wraps a single column reference, or a
comparison you use once, adds a level of indirection and a name to keep consistent, for nothing.

```yaml
# needless
Bindings:
- name: "$sev"
  expression: "AESEV"
```

**When the condition is used once.** A binding can name a condition, and a condition used in
several places reads better under a name. Used once, it is clearer written in the Check; a reusable
sub-condition that combines others belongs in a
[composite check](the-check.md#composite-conditions-all-any-not).

> **A binding is worth its name when the name is worth reading.** If you cannot name the result
> as a noun a reviewer would recognise, the check is probably clearer without it.

---

**Next:** [Joins](joins.md) — reaching into another dataset.
