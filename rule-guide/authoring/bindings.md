# Bindings

<!-- Grounded: Binding.java — name + expression only, the JsonAnySetter rejects every other key
     loud (id:, operator:, group:, level:, filter:, …); the expression is a single
     function call; a binding with no expression is filed on the loadError channel. -->

A binding gives a name to a sub-result, so the check can refer to it instead of restating it.

```yaml
Bindings:
- name: "$variable_count"
  expression: "variable_count(--LNKID)"
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
  expression: 'record_count(DSCAT, domain="DS", filter=filter(DSCAT="DISPOSITION EVENT"))'
```

**`name`** is the `$`-variable the check will use. Name it for what it *is*, as a noun —
`$subject_count`, `$first_visit_date` — not for how it is computed.

**`expression`** is a single **function call**. Its parameters are keyword arguments inside the
call; there are no sibling keys.


## ⛔ Everything else is rejected, loudly

A binding carries `name` and `expression` and nothing else. Any other key **fails the load** and
names what to write instead:

| you write | why it fails |
|---|---|
| `id:` | not a key a binding carries; the name goes in `name:` |
| `operator:` | not a key a binding carries |
| `group:` · `level:` · `filter:` · any other parameter | parameters go **inside** the expression, as keyword arguments |
| anything else | a binding is two keys |

This strictness is deliberate. The loader otherwise ignores unknown keys, and an ignored
parameter is the worst possible outcome: the binding computes something subtly different from
what you wrote, the rule runs, and it under-reports in silence.

```yaml
# ⛔ load error — filter is not a sibling key
- name: "$n"
  expression: "record_count(DSCAT)"
  filter: 'DSCAT == "DISPOSITION EVENT"'

# ✅ the parameter belongs in the call
- name: "$n"
  expression: 'record_count(DSCAT, filter=filter(DSCAT="DISPOSITION EVENT"))'
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

**When you mean a condition, not a value.** Bindings name *values*. A reusable sub-condition
belongs in a [composite check](the-check.md#composite-conditions-all-any-not).

> **A binding is worth its name when the name is worth reading.** If you cannot name the result
> as a noun a reviewer would recognise, the check is probably clearer without it.

---

**Next:** [Joins](joins.md) — reaching into another dataset.
