# Comparison

<!-- Grounded: TypeExpectations.binary:270-311 (ADD/SUB/MUL/DIV and LT/GT/LE/GE numeric;
     EQ/NEQ type-directed via equality(); MATCH/NMATCH left CHARACTER; IN/NOT_IN via
     membership:337 — homogeneous static list literals only), TEMPORAL_CALLS {date,time,
     date_part,time_part}; IsoDateComparison (the hull rule, the six forall tests, the
     complete-vs-complete fast path and the owner ruling 2026-08-13, saturating bounds,
     isIsoText year re-routing), IsoDateBounds (hull by shape: partial, masked, UTC offset),
     IsoTimeComparison (the time mirror), Primitives.compareWithMissing/orderWithMissing/
     equalsWithMissing (the single total order), Primitives.datePartComparison. -->

Comparison is where most surprises live, because the same operator does a different thing
depending on what it is comparing. This page says what each operator accepts and how it behaves
per type.

Read [Values and missing values](values-and-missing.md) first — everything here assumes it.

## One rule holds everywhere

**A comparison is total: true or false, never unknown.** There is no third truth value, no
indeterminate, and no row silently dropped because a value could not be compared. Every operator
answers.

That is a design choice, and it has a consequence worth keeping in mind for the rest of this
page: when the engine cannot establish that something is true, it answers **false** — and the
negation of a false is not always the intuitive opposite.

## Which types each operator accepts

| operator | accepts | behaviour |
|---|---|---|
| `+` `-` `*` `/` | **numeric only**, both sides | ordinary arithmetic |
| `<` `>` `<=` `>=` | **numeric**, both sides — or **temporal** when either side is `date()` / `time()` / `date_part()` / `time_part()` | numeric: ordinary ordering · temporal: the [hull rule](#dates-and-times--the-hull-rule) |
| `==` `!=` | string · number · boolean · date · time | **type-directed** — see below |
| `=~` `!~` | left operand **character**; right a regex literal | matches / does not match |
| `in` `not in` | element type of the list | a list of numbers expects a numeric operand; a list of strings, a character one |

> ⛔ **There is no ordering on text.** `<`, `>`, `<=` and `>=` expect numeric or temporal
> operands. Comparing two character columns with `<` is a **type error**, not an alphabetical
> comparison. If the column holds a number as text, convert it — `num(--STRESC)`; if it holds a
> date, `date(--DTC)`.

### How `==` and `!=` decide the type

Equality is **type-directed**: whichever side states a type fixes the expectation for the other.

```yaml
AESEV == "MILD"        # the literal is a string  → AESEV is read as character
VISITNUM == 1          # the literal is a number  → VISITNUM is read as numeric
AESTDY == AEENDY       # neither states a type    → both must agree
```

Two further routings:

- if **either side is arithmetic**, both sides are numeric — `AVAL != BASE / 2` reads all three
  as numbers;
- if **either side is temporal**, the comparison goes to the temporal family, whatever the other
  side looks like.

A mismatch is a rule **ERROR**, not a silent coercion.

### How `in` and `not in` decide the type

A **static list literal** states the expectation, and only if it is homogeneous:

```yaml
AESEV   in ["MILD", "MODERATE"]   # character expected
VISITNUM in [1, 2, 3]             # numeric expected
```

A mixed list, or a set computed at run time, states nothing and is not type-gated.

## Numbers

Ordinary ordering and equality. The operand must be numeric — a character column holding digits
is **not** numeric until you convert it with `num(…)`, and `num` on text that is not a number
yields a [missing value](values-and-missing.md) rather than an error.

## Text

**Equality and regex only.** `==`, `!=`, `in`, `not in`, `=~`, `!~` — and the functions that test
a property of the text (`starts_with`, `contains`, `len`, …). Ordering is not available; see the
warning above.

## Booleans

`==` and `!=` against `true` / `false`. In practice you rarely write either: a boolean-valued
operand — `AE._matched_`, `var_exists("X")`, any predicate — **stands alone** as a complete
condition, and `== true` adds nothing.

## Dates and times — the hull rule

This is the part worth reading twice.

### A partial date is a range, not a point

SDTM and SEND permit incomplete `--DTC` values, and the engine treats each as the **set of
instants it could denote** — its *hull*, from earliest possible completion to latest:

| value | denotes |
|---|---|
| `2012-06-15` | that day |
| `2012-06` | any day in June 2012 |
| `2012` | any day in 2012 |
| `2012-06--` | a masked day — any day in June 2012 |
| `----06-15` | a masked year — the 15th of June, any year |

UTC offsets are normalised before any of this.

### A comparison quantifies over the range

**`A op B` means "A op *every* candidate of B".** The same ∀ rule for all six operators, and the
answer is always true or false:

| you write | is true when |
|---|---|
| `A < B` | `upper(A) < lower(B)` |
| `A > B` | `lower(A) > upper(B)` |
| `A <= B` | `upper(A) <= lower(B)` |
| `A >= B` | `lower(A) >= upper(B)` |
| `A == B` | **both hulls are points, and equal** |
| `A != B` | the hulls are **disjoint** |

### ⚠ The consequence: opposites are not opposites

Because every operator demands the relation hold for the *whole* range, two overlapping partial
dates satisfy **neither** an operator nor its apparent negation.

Take `A = 2012-06` and `B = 2012-06-15`:

```
A == B   → false   (A's hull is not a point)
A != B   → false   (the hulls overlap — B is inside A)
A <  B   → false   (A's upper, 2012-06-30, is not < 2012-06-15)
A >= B   → false   (A's lower, 2012-06-01, is not >= 2012-06-15)
```

All four false, on data that is perfectly valid.

> ⛔ **Do not write `not (A < B)` and expect `A >= B`.** On complete dates they agree. On partial
> ones they do not, and a rule built on that assumption reports on exactly the rows whose dates
> were too imprecise to judge — the opposite of what it intended.
>
> Write the condition you mean directly, and if the requirement genuinely has two strengths —
> *"these disagree"* versus *"these may disagree"* — that is what the
> [severity ladder](../authoring/the-check.md#the-severity-ladder) is for.

### Two complete dates take a fast path

When **both** operands are calendar-complete — day precision or finer — and both are readable,
the hull rule is skipped and the values are compared as points, **at the coarser of the two
precisions**.

This matters because a day-precision hull spans `T00:00:00` to `T23:59:59`, so a naive ∀ reading
would make `2026-01-17 >= 2026-01-17` **false**. A complete date is a known point at its own
granularity; only an *incomplete* value's precision is uncertainty.

### Junk answers false

An operand that cannot be read as a date at any precision — blank, malformed, a stacked
decoration like `2012-06-15ZZ` — has no hull, and **all six operators answer false** for it. It
does not error, and it does not fire.

> **That is why a date rule usually guards its operands.** `is_complete_date(--DTC)`,
> `is_valid_date(…)` or a `not empty(…)` in front of the comparison states which rows you meant
> to judge, rather than relying on a silent false.

### A year is a date, not a number

`2026` on the right of a date comparison is read as a **year-precision date**, not as the number
2026. It reaches the hull rule like any other partial value.

### `time` mirrors `date`

The same hull rule, on times of day.

### `duration` — the hull rule on durations

`duration(X)` on either side makes the comparison read both operands as ISO-8601 **durations**, and
the same ∀ rule applies (runbook W1b DO, `PLAN-engine-gap-dur-order`). The exact designators have
one length each — a week is 7 days, a day 86 400 seconds (a duration carries no calendar, so no
DST), hours, minutes and seconds exact, a fraction on the lowest component — so every spelling of
the same length is the same **point**: `PT90M == PT1H30M`, `P1W == P7D`, `P0D == PT0S`, and
`P2D > PT5H` holds although `"P2D" < "PT5H"` as text. A **month or year** has no one length, so it
is a **range** — `P1M` is [28 d, 31 d], `P1Y` is [365 d, 366 d] (`P2M` is [56 d, 62 d]) — and the
comparison quantifies over it:

```
duration("P1M") <  duration("P32D")   → true    (every candidate is shorter)
duration("P1M") == duration("P30D")   → false   (P1M is not a point)
duration("P1M") != duration("P30D")   → false   (the hulls overlap)
duration("P1M") != duration("P1Y")    → true    (the hulls are disjoint)
```

A leading `-` negates the whole hull. Junk — `""`, `P`, a date — answers false to all six
operators (and `!=` fires), exactly as for dates; `is_valid_duration` is the same test as "has a
hull". The family is selected by the **written marker** only: `--STINT > --ENINT` without it is a
text comparison, and `relation=` on `has_next_corresponding_record` reads durations only where both
operands are written `duration(COL)` — the engine never guesses from a cell's content.
`duration_between(start, end)` is a value of this family (each instant its own hull, so a range
`lower/upper` — a point only for two instants at second precision), and `earliest_possible` / `latest_possible` over a
`duration(…)` operand are the hull's ends as point durations (`P1M` ⇒ `P28D` / `P31D`).

### `date_part` and `time_part` compare one part

`date_part(X) == date_part(Y)` compares only the date portion of two ISO values, and
`time_part` only the time portion. They read the full operands and compare the requested part
after UTC normalisation — they do **not** convert anything, and they are not a way to truncate a
value for other purposes.

## Membership — the list decides the comparison

`in` and `not in` are not one operator; **the thing on the right chooses which comparison runs.**
The left operand never does.

| the list is | the comparison is | a mismatched column |
|---|---|---|
| all numbers, as a literal | **numeric** — the probe is parsed, so `"10.0"` and `"01"` both match `10` | a character column is an **error** — write `num(X) in [10, 20]` |
| all strings, as a literal | **textual** | a numeric column is an **error** |
| a number *and* a string | — | **load error**; mixed literal lists are rejected |
| dynamic — the **set** is a `$`-binding, a `${*}` wildcard, an inline function call or a grouped result | `==`'s own comparison, member by member | not type-checked at all — and neither is `==` on a dynamic operand |

That last row is worth pausing on: a set computed at run time cannot state a static type, so it
is never gated. The safety you get from a literal list is not there — but note that a **comparison**
against a dynamic operand is not gated either (`X == $thing` states no type), so membership is not
the weaker surface here. It is the same partiality in both.

> **Read the table by the right-hand side.** `$max_value in [10, 20]` is the **first** row, not the
> last: the list is an all-numeric literal, so the comparison is numeric, and a `$`-binding on the
> left makes no difference. A membership goes textual only when the **set itself** is dynamic.
>
> ⭐ **Corrected 2026-09-21.** This paragraph used to warn that in `X in $number_list` the members
> are compared as text, so `10` would not match `10.0`. **It does match.** Membership runs `==`'s
> own per-member decision tree, so a numeric-typed probe enters numeric mode against each member
> and the numeric tolerance applies — exactly as `X == "10.0"` would. What a dynamic set still does
> not give you is the **type gate**: a character probe against numeric-looking members compares as
> text rather than erroring. So prefer a literal list where you can — for the gate, not for the
> arithmetic.

`upper(X) in [...]` is the case-insensitive surface, and is always textual — numbers have no
case.

> ⭐ **A column name handled as a value is a string** (owner, 2026-09-28; rulings register
> `CIT §3`). Column names match ignoring case wherever the engine looks a column *up* (`CIT §1`),
> but a `varname()`, a `QNAM` cell or an element of `get_column_order_from_dataset()` is a value,
> and a value compares as ruled here — exactly. A rule that compares or tests a NAME folds it,
> and folds **both sides** of a comparison (the owner's *"compare the upper case version of a name
> against the upper case version of a list of strings"*): `upper(varname()) in $x` (the membership
> surface above folds the list too), `equalsIgnoreCase(varname(), …)`, `upper($dataset_variables)`
> against `upper($required_variables)`; and a name **test** folds the name it tests —
> `ends_with(upper(varname()), "DTC")`, `upper(varname()) =~ /^ANL\d\dFL$/` — against a literal
> or pattern authored in upper case. A `QNAM` / `IDVAR` / `DOMAIN` cell is left as it is; the
> engine never treats a string specially because it holds a column name. Where such an exact cell
> meets a folded name (or a folded name list), the rule compares **both spellings** —
> `IDVAR not in $rdomain_variables and IDVAR not in $rdomain_variables_upper`, or
> `prefix(varname(), 2) not in $domain_list and prefix(upper(varname()), 2) not in $domain_list` —
> so a cell that names a column in exactly the column's own spelling (an all-lowercase study) still
> matches. The naming rules (`varname() =~ /.*[a-z]/`, `varname() !~ /^[A-Z0-9_]+$/` and kin) test
> the letter case or the characters of the name on purpose and are never folded. A template
> carve-out is different: the label rules recognise a variable whose name contains an index on the
> **library** name the column resolved to, never on the dataset's own spelling, which a lowercase
> study spells in lower case throughout. A column named after a naming template (`TRT01P`, or
> `trt01p`) resolves to the template itself, which keeps its lower-case index (`TRTxxP`), and
> `label_template("LIBRARY")` serves the template's label with the index substituted (`Planned
> Treatment for Period 01`). Each rule keeps its own authority's exception (ruling `M2-D15`):
> `CDISC-AD0018` excludes every index name, `var_name("LIBRARY") !~ /[a-z]/`; `CDISC-AD0709`
> excludes the indexes `w`, `y` and `zz` only, `var_name("LIBRARY") !~ /[wyz]/`, and judges an
> `xx` name with `matches_label_template(var_label("DATA"), label_template("LIBRARY"))`;
> `PMDA-AD0018` states no exception and judges every index name that way. A library name is the
> standard's spelling, not the data's, so testing it needs no fold, and the resolution itself
> ignores letter case.

### Dates — ask for one, and you get one

⭐ **Rewritten 2026-09-21.** This section used to read *"Dates are not dates here — membership is
equality on the text, and nothing routes a date list into the temporal family."* That is now true
only of the **untagged** spelling.

**An untagged probe is still text.** A bare column against string members compares as text, because
nothing in the expression says the values are dates:

```yaml
--DTC in ["2020-01-01"]     # the STRING "2020-01-01" and nothing else
```

**A `date(…)` probe against a written-out list is a date**, and the
[hull rule](#dates-and-times--the-hull-rule) applies to every member, exactly as it does to
`date(A) == date(B)`:

```yaml
date(--DTC) in [date("2020-01-01")]     # matches 2020-01-01T09:15 too — same day, same point
```

**So is a `date(…)` probe against a dynamic set** — a `$`-binding, an inline function call, a
per-row grouped result: membership follows `==` for every set shape (`D81`, closed 2026-10-08). The
set is read as a value — once for a dataset-level list, per row for a `group=` result — and every
member is compared by the hull rule, so a date-only probe matches a datetime member of the same day:

```yaml
# $ex_dates: distinct(EXSTDTC, domain="EX", group=[USUBJID])
date(PCRFTDTC) not in $ex_dates     # 2020-01-01 is a member of {2020-01-01T09:15}
```

`date(X) in $s` answers exactly `date(X) == m1 or date(X) == m2 …` over the members of `$s`; a member
that is not ISO-shaped is simply not a member. `time(…)` takes the time comparator the same way. Only
a `${*}` wildcard set stays textual.

⛔ **And it is `date(…)`/`time(…)` only.** A `date_part(…)` or `time_part(…)` probe is *not* routed
to its comparison family in a membership — it is still compared as text, even over a written-out
list. If you need it, compare instead of testing membership.

Two consequences to read together:

- a partial `2020-01` is a **span**, not a point, so it does not match a day inside it — the same
  answer the comparison gives;
- ⚠ **mixing the two is NOT refused at load today, and it is a reinterpretation**: the stage-A
  kind that sees the pair (`MIXED_DATE_STRING_COMPARISON`) is observe-only, so
  `date(--DTC) in ["2020-01-01"]` loads, and its string members are read as **dates** — it answers
  exactly as `in [date("2020-01-01")]` does (a `2020-01-01T10:00` matches; measured 2026-10-01).
  Write the conversion anyway — `[date("2020-01-01")]`, the same way you would convert the
  right-hand side of a comparison: it is the spelling SPEC §5.2 reserves, and the one that stays
  legal once the kind is armed.

## Regular expressions

**The subject must be character.** A numeric column is an **error**, and deliberately so: a regex
asserts a property of the *text form*, and a number's text form is a formatting decision —
`1.2e-10` and `0.00000000012` are the same number. Matching one by accident is worse than
refusing.

> ⚠ **`=~` is a find, not a full match.** `X =~ /AB/` is true for `"CABD"`. Anchor it — `/^AB$/` —
> whenever you mean the whole value. An unanchored pattern is the quiet way a validation rule
> accepts more than it meant to.

A missing value matches **no** pattern, including one that would match an empty string — so `!~`
**fires** on a missing value, like every other negative form.

Date columns are character, so a regex reads the raw ISO text. `--DTC =~ /^\d{4}$/` is a
legitimate precision test. Where a date predicate says the same thing — `is_partial_date`,
`is_complete_date` — prefer it: it survives a change of ISO spelling, a regex does not.

## Missing values, across every operator

Missing is not a type — it is a value that can turn up in any position. One total order governs
it everywhere, so equality and ordering can never contradict each other:

```
missing values (by their own value)  <  ""  <  every other string
```

- a missing value sorts **below** every present one;
- two missings are equal only if they are the **same** missing;
- `== ""` is **false** for a missing value;
- `!=` against a literal is **true** for a missing value — it fires;
- `not in [...]` is **true** for a missing value — it fires;
- a missing value matches **no** regex, including one that matches the empty string.

The full account is in [Values and missing values](values-and-missing.md).

---

**Next:** [Types](types.md) — what happens when the types do not line up.
