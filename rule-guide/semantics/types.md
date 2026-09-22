# Types

<!-- Grounded: ExprType.Primitive (6 value types + 2 parameter-only; missing is a bottom
     inhabiting every type, Unknown is stage-A's not-yet-known), ColumnTypeGate (kindOf,
     requireNumericRead / requireCharacterRead and their messages; R12 — there is no char()
     conversion), TypeExpectations.binary (arithmetic and ordered comparison numeric;
     EQ/NEQ type-directed; MATCH/NMATCH character subject; membership via the list),
     ExprCompiler CONVERSIONS {num,date,time}, VariableRequirement (the :N / :C suffix). -->

A rule is authored against columns whose types it does not control. This page is about what
happens when a type is not what the check assumed.

The short version: **the engine does not guess.** A type mismatch is a rule **ERROR**, not a
silent coercion and not a quiet false.

## The types a value can have

| type | is |
|---|---|
| `string` | text |
| `number` | numeric |
| `boolean` | true / false |
| `date` | an ISO-8601 calendar value, partial precision retained |
| `time` | a time of day, partial precision retained |
| `regex` | a pattern — literal only, never computed or stored |

A column is one of two **kinds** — character or numeric — and that is what the engine classifies
it as. `date` and `time` are not column kinds: a `--DTC` column is *character*, and becomes a date
only where a check reads it as one.

## Where a type is required

| you write | the engine requires |
|---|---|
| `+` `-` `*` `/` | both operands **numeric** |
| `<` `>` `<=` `>=` | both **numeric** — or temporal, if either side is `date()` / `time()` |
| `=~` `!~` | the subject **character** |
| `X in [1, 2]` | the probe **numeric** (an all-numeric literal list) |
| `X in ["A", "B"]` | the probe **character** (an all-string literal list) |
| `abs` `round` `floor` `ceil` `between` | numeric operands |
| `substring` `prefix` `suffix` `prefix_matches` `suffix_matches` `has_equal_length` | a numeric length or position |

Everything else takes what it is given.

> ⛔ **There is no ordering on text.** `<` and its siblings are numeric-or-temporal. Comparing two
> character columns with `<` is a type error, not an alphabetical comparison.

## `==` and `!=` are type-directed

Equality has no fixed expectation — whichever side states a type fixes it for the other:

```yaml
AESEV == "MILD"        # a string literal → AESEV is read as character
VISITNUM == 1          # a number literal → VISITNUM is read as numeric
AESTDY == AEENDY       # neither states a type → the two must agree
AVAL != BASE / 2       # arithmetic on one side → all of it numeric
```

## When the types do not line up

The rule raises an **ERROR** naming the column, the type it has and the type the position wanted.
It does not fire, and it does not quietly pass.

That is deliberate. The alternative — parsing the text and carrying on — is how a rule reports
findings that are an artefact of formatting rather than of the data.

### Converting on purpose

**`num(x)`** reads a character value as a number:

```yaml
expression: 'not empty(--STRESN) and is_numeric(--STRESC) and --STRESN != num(--STRESC)'
```

`num` on text that is not a number yields a [missing value](values-and-missing.md), not an error —
so it does not fail loudly, it quietly changes which rows the comparison selects. Guard it with
`is_numeric(x)` when the column may hold non-numeric text, as that rule does.

**`str(x)`** forces a comparison into textual mode.

> ⛔ **There is no `char()` conversion.** A numeric column cannot be read as text, because a
> number's text form is a formatting decision — `1.2e-10` and `0.00000000012` are the same number.
> A rule that needs one is defective as authored, and the engine says so rather than picking a
> rendering.

## Demanding a type instead of erroring

An error is right when the rule *should* apply and the data is wrong. It is the wrong answer when
the rule was simply never applicable to a column of that type.

For that, say so in [`Requirements`](../authoring/requirements.md#demanding-a-type-as-well-as-presence):

```yaml
Requirements:
  Variables:
    All: ["--STRESN:N"]
```

An unmet type then **skips** the rule for that dataset, with a reason naming both types — an
honest "not applicable" instead of an error.

> **Choose by what the mismatch means.** If a character `--STRESN` is a data defect, let the check
> error. If it just means this rule is about a different flavour of the domain, require the type.

## Two types you meet only in arguments

`column-reference` and `metadata-level` are real types that no column holds and no expression
computes — they type *parameters*. A bare column name is a `column-reference`, and **a name is
deliberately not a string**; `metadata-level` is the closed set `DATA` · `DEFINE` · `LIBRARY`, so a
misspelling is caught rather than passed through as unmatched text.

## Two things that are not types

**`missing` is not a type.** It is a value that inhabits every type, which is why a missing value
can appear in any position without a type error — and why no function signature needs a "missing"
variant.

**`unknown` is not a type either.** It is the checker saying *"not statically known"* — the type of
a column before the rule binds to a dataset. It is compatible with everything, so a mismatch
involving it surfaces at bind time rather than immediately.

---

**Next:** [Absent columns](absent-columns.md) — what happens when the column is not there at all.
