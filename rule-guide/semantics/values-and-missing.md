# Values and missing values

<!-- Grounded: BuiltinFunctions.java:432-439 (the five presence predicates, two alias pairs);
     net.cumba.datatable.values.MissingValue. -->

This page answers one question: **what does your expression see when the data does not have a
value?**

Get this wrong and a rule is silently too narrow or silently too broad. Nothing errors. The
rule simply judges a different set of rows than you intended.

## There are exactly three things a cell can be

| the cell holds | what your expression sees |
|---|---|
| a value | that value |
| nothing at all | a **missing value** |
| an empty string `""` | an empty string — which is a **value** |

That third row is the one that costs people an afternoon. `""` is present. It has a length of
zero. It is not missing.

**A value is never nothing.** Every column read, every function result, every sub-expression
yields either a real value or a missing value. There is no third state, no null, and no
special case you have to guard against. You can always ask a question of a result.

## Comparisons are always true or false

There is **no third truth value**. A comparison involving a missing value does not evaluate to
"unknown" and does not quietly drop the row — it answers true or false like any other.

If you have written SQL, unlearn that here. The rules are different, and the difference is the
source of most surprises on this page.

This chapter covers what *missing* does to a comparison. What each operator accepts, and how it
behaves per type — including the interval semantics that govern partial dates — is
[Comparison](comparison.md).

### Missing values sort below everything

The order is:

```
missing values  <  ""  <  every other string
```

So `AESTDY < AEENDY` is **true** when `AESTDY` is missing and `AEENDY` is not. A rule that
looks for "start after end" using an order comparison will not fire on a missing start — but
its mirror image will fire on every row with a missing value, which is rarely intended.

> **The flip depends on which side is missing, not on which operator you wrote.** Check both
> directions of every order comparison against a row where each side in turn is missing.

### Two missing values are equal when they are the *same* missing

Missing is not one thing. A numeric cell can be missing as `.`, or as any of `.A` through `.Z`
and `._` — distinct missing values that a sponsor may use to mean distinct things.

```yaml
expression: LBSTRESN == LBORNRLO     # true if both hold the SAME missing, e.g. both .A
                                     # false if one is .A and the other is .B
```

Two missings of different identity are **not** equal, and they sort by their value byte.

### `== ""` is true only for an actual empty string

```yaml
expression: TSVAL == ""     # true for an empty string, FALSE for a missing value
```

This is why `empty()` exists and why it keeps its name: it is the missing-**or**-empty
predicate, and `== ""` is not.

### ⚠ `!=` against a literal fires on a missing value

This is the trap that ships broken rules:

```yaml
expression: AESEV != "MILD"      # TRUE — and reported — on every row where AESEV is missing
```

A missing severity is not `"MILD"`, so the comparison is true, so the row becomes a finding.
Almost always the requirement meant *"where a severity is recorded and it is not MILD"*:

```yaml
expression: not empty(AESEV) and AESEV != "MILD"
```

> **Every bare `!=` against a literal deserves a second look.** Ask what the rule should say
> about a row that has no value at all, then write that down explicitly.

## The five presence predicates

These are the only functions that treat an empty string as though it were missing. That is
their whole purpose — and `is_missing`, listed here because it is so easily mistaken for one of
them, is the one that does **not**.

| write | true when the value is | alias of |
|---|---|---|
| `empty(x)` | missing **or** `""` | — |
| `is_missing(x)` | missing **only** — `""` is present | — |
| `non_empty(x)` | anything else | — |
| `present(x)` | anything else | `non_empty` |
| `is_present(x)` | anything else | `non_empty` |

The aliases are exact: `present(TSVAL)` and `non_empty(TSVAL)` are the same call.

> **Prefer the positive name with `not`.** Write `not empty(AESEV)`, not `non_empty(AESEV)`. A
> negated name forces the reader to hold a double negative whenever it appears under a `not` or
> beside an `and not`, and the corpus reads more consistently when negation is spelled once, in
> one place. The two are interchangeable, so this is a style rule with no behavioural catch —
> `empty` and `not empty` are the pair to reach for.

> **`is_missing` means "missing" — and only that** (rulings register `D50`, owner 2026-10-05:
> *"is_missing() is for misisng and empty() is for missing or \"\""*). `is_missing("")` is false;
> `empty("")` is true. Until 2026-10-05 `is_missing` was an alias of `empty`; a rule that meant
> "missing or empty" writes `empty`.

## Every other function reads `""` literally

There is no "empty means missing" behaviour anywhere else. An empty string goes into a function
and is treated as the string it is:

```
len("")        →  0
upper("")      →  ""
contains("", "x")  →  false
```

So a check like `len(AETERM) < 3` is **true** for a row whose `AETERM` is `""`. If that row
should not be reported, exclude it yourself:

```yaml
Check:
  expression: not empty(AETERM) and len(AETERM) < 3
```

> **This is the single most common cause of a rule that over-reports.** A length, a pattern, a
> prefix test — each of them has an opinion about `""`, and that opinion is usually not the one
> the guidance had in mind. Lead with a presence predicate whenever the requirement says
> "when X is provided".

## A missing value is not a string

Pass one to a function and you do not get a default — you get a missing value back:

```
len(<missing>)      →  missing, not 0
len(.A)             →  .A          (the SAME missing — identity kept)
upper(<missing>)    →  missing
upper(.A)           →  .A          (the SAME missing — identity kept)
abs(.A)             →  .A
num(.A)             →  .A          (so abs(num(X)) keeps X's .A too)
concat(.A, "x")     →  .A          (one missing operand poisons the result)
concat(.A, .B)      →  .           (two different identities collapse to the plain missing)
coalesce(.A, "")    →  .A          (every operand skipped: the missing they carry)
```

Every value function that computes from its input's value hands a missing input's **own** missing
value through (rulings register `D85c`, identity kept; two or more distinct identities among the
operands of one call collapse to the plain missing, `D86a`). That holds for the string functions — `upper` / `lower`, `trim`, `normalize_space`,
`prefix` / `suffix`, `substring`, `concat` (register `D36`) — and for the others: `len`, `char`,
`num`, `abs` / `round` / `floor` / `ceil`, `year` / `month` / `day`, `earliest_possible` /
`latest_possible`, `coalesce` (once every operand is skipped), `split_by`, `dy` and `colref`. Only a
computed no-result is the plain missing: `substring` with a start past the end, `num("abc")`,
`char("")`, `year("")`, a `dy` whose dates are present but incomplete. A comparison sees the
difference — `abs(A) == abs(B)` is **false** when `A` is `.A` and `B` is `.B`, and a report prints
`.A`, not `.`. The exceptions count rather than compute: `count` / `size` answer `0` for a missing. And the two
date extremes `earliest_date` / `latest_date` follow the **extremes'** candidate rule, like
`min_date` / `max_date` over a group: a missing (or blank) argument is **skipped** and the other
argument decides alone — `earliest_date(.A, date("2020-01-01"))` is `2020-01-01` — and an identity
is carried only when **both** are missing (`.A` + `.A` is `.A`; `.A` + `.B` is the plain missing).
`vlm_value_length` reads the target cell too: a missing target under a matched value-level entry is
that cell (`.A` stays `.A`), and a record no value-level entry matches is the plain (computed) missing;
the other `vlm_*` value accessors read value-level metadata, not the cell.

It matches **no** regular expression, including one that matches the empty string. It equals no
string literal, including `""`, including inside a membership list.

That last point has teeth:

```yaml
expression: AESEV in ["MILD", "MODERATE", "SEVERE"]      # false for a missing AESEV
expression: AESEV not in ["MILD", "MODERATE", "SEVERE"]  # TRUE for a missing AESEV — it fires
```

Same shape as `!=`, same fix: lead with a presence predicate when the requirement is about
values that were actually recorded.

## Three authorings of one requirement

*"AEENDTC must be populated when AEENDY is populated."*

```yaml
# 1 — correct. Flags a missing AEENDTC and an AEENDTC of "".
expression: not empty(AEENDY) and empty(AEENDTC)

# 2 — finds nothing on the rows you care about.
#     `== ""` is false for a missing value, so a genuinely absent AEENDTC passes.
expression: not empty(AEENDY) and AEENDTC == ""

# 3 — over-reports. `!=` fires on a missing AEENDY too, so rows with neither
#     value are flagged, though the requirement never applied to them.
expression: AEENDY != "" and empty(AEENDTC)
```

Authoring 1 is what you want. The other two are both readable, both plausible, and both wrong
in a way no error message will tell you about — which is why this page is near the front of the
guide.

---

**Next:** [Types](types.md) — why a comparison between a number and text is an error, not a
coercion.
