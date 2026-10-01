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
2. **The value can be reported.** A `$`-name may be listed in
   [`Output_Variables`](outcome.md), so the number that triggered the finding appears in it —
   *"expected 2, found 1"* rather than *"something about LNKID"*.

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
- **A set difference reads only dataset-level lists.** `minus` — written in the Check or in a
  binding — that reads a per-row binding (`minus($a, subtract=$per_row)`) is a load error: its
  operands have no row to pick the value at.

A **list** binding (a list literal, or a list-valued function such as `get_codelist_attributes`) is
read with `in` / `not in`, `empty(…)` or by a list function such as `minus`; comparing it with `==` / `!=` / `<` … is a
load error, exactly as comparing a column with an inline list is. A list literal holds plain
literals of one kind: numbers, or strings, never both, and never a column. Nor does it hold a
binding: `AETERM in ["A", $codes]` is a load error — the `$codes` element would be read as the text
`"$codes"` and never match — so write `$codes` alone as the operand, or spell the values. The one
place a `$` stands inside a list is a splice of **column names** into
[`record_count`](../reference/functions.md#record_count)'s `group=` or
[`is_unique_set`](../reference/functions.md#is_unique_set)'s members.


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
