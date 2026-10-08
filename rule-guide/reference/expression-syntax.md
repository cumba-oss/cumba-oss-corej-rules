# Expression syntax

<!-- The rosters in this document are the complete inventory generated from the engine's own
     registries (FunctionRegistry, CompilerDispatchedCalls, MetadataAttribute, BuiltinRegistry,
     the sealed Expr AST) — 276 tokens. There is no operation category: runbook W8 deleted the
     Operation surface, and every former operation is a function of the same name.
     Grammar and precedence: CheckExpressionParser.java (or > and > not > comparison > sum >
     product > postfix `~ /pattern/replacement/` > atom). Lexer: ExpressionLexer.java (double-quoted strings, /regex/, `quoted refs`,
     bare &NAME& expansion tokens inside a name (grammar: ExpansionTokens), && and || accepted). Types: ExprType.java (6 value types + 3 parameter-only; `missing` is a
     bottom inhabiting every type, `unknown` is stage-A's not-yet-known).
     The six comparison tokens: ExprCompiler.comparisonMarkers() = CONVERSIONS {num,date,time}
     u TAGS {date_part,time_part} u STR_MARKER {str}. None is a FunctionRegistry entry; they are
     recognised in binary-comparison handling. `date` moved tag -> conversion in phase 3b.
     Operand spellings folded: OperandKind COLUMN / WILDCARD_COLUMN / DOTTED_REF are one operand
     (a column reference) for an author; MATCHED_FLAG and OPERATION_REF stay distinct.

     COVERAGE MAP — the inventory's internal enum identifiers are covered here under their
     AUTHORING names, deliberately; a roster check must map, not string-match:
       literal-kind STRING/NUMBER/BOOL/REGEX/LIST -> "Literals" (string, number, boolean,
                                                     regex, list)
       operand-kind COLUMN           -> "Operands", row "column"
       operand-kind WILDCARD_COLUMN  -> "Operands", row "wildcard column"
       operand-kind DOTTED_REF       -> "Operands", row "dotted reference"
       operand-kind MATCHED_FLAG     -> "Operands", row "matched flag"
       operand-kind OPERATION_REF    -> "Operands", row "binding result"
       operand-kind BUILTIN          -> "Operands", row "builtin"
     Every other token appears verbatim: the callables — the UNION of the inventory's `function`,
     `boolean-call` and `compiler-call` rows (106 + 28 + 21; the figures are the generated
     inventory's). All are presented as ONE surface: every callable carries a FunctionDescriptor
     in FunctionRegistry — a compiler-dispatched one (CompilerDispatchedCalls) with `fn == null`.
     Plus 26 metadata-accessors, 64 builtin-operands, 14 binary-operators, 6 comparison-markers.

     ⚠ The inventory's three callable categories are a DISPATCH split, not an authoring one, and
     the split the TSV names is itself stale: `HARDCODED_BOOLEAN_CALLS` is RETIRED. Every
     compiler-dispatched callable now carries a real FunctionDescriptor in FunctionRegistry
     (CompilerDispatchedCalls, phase 7 / D119h) with `fn == null` meaning "compiles through a
     dedicated ExprCompiler plan"; ExprCompiler.isBooleanCall answers from the registry alone.
     Hence one "Functions" section here. -->

Everything a [`Check`](../authoring/the-check.md), a `Precondition` or a
[`Bindings`](../authoring/bindings.md) expression can contain.

A check expression is **boolean** and is evaluated **once per row**. Where it is true, the row is
a violation.

## The shape of an expression

```
AESTDTC > AEENDTC
not empty(AESEV) and AESEV not in ["MILD", "MODERATE", "SEVERE"]
date(--STTPT) < date(--DTC)
```

**Precedence, loosest to tightest:**

```
or   →   and   →   not   →   comparison / membership / regex   →   +  -   →   *  /   →   operand
```

Parentheses `( … )` group. Square brackets `[ … ]` make a **list** — they are not
interchangeable.

> **A bare boolean stands alone.** A comparison operator is optional: `var_exists("AESEV")` is a
> complete expression. You do not write `== true`. But it must genuinely *be* a boolean — see
> [Logical](#logical): a number or a plain column is not a condition.

## Operands

Four kinds of thing can appear where a value is expected.

### 1. A column reference

The common case, and the one with three spellings — all of them the same kind of operand. Each
denotes a **column**, and in value position each reads that column's value for the row in hand.

```yaml
AESTDTC          # plain — a column of the dataset the rule is running on
DM.RFSTDTC       # qualified — a column of a joined dataset
--STDTC          # wildcarded — a name resolved per dataset
`ODD NAME`       # backtick-quoted — a name that would not otherwise lex
&DOM&.&DOM&SEQ   # templated — an expansion token, replaced before the rule runs
```

The differences are about **naming**, not about what the operand is:

**Qualification says which dataset.** `DM.RFSTDTC` reads the column from a dataset brought in by
[`Match_Datasets`](../authoring/joins.md). An unqualified name always means the primary dataset —
a join does not change that. On a row that found no join partner, a qualified reference reads as
[missing](../semantics/values-and-missing.md).

**A wildcard says which name.** A name carrying a wildcard marker — `--`, `*`, `**`, or an ADaM
capture letter `xx` / `zz` / `y` / `w` — is resolved against the dataset in hand, so `--STDTC` is
`AESTDTC` on `AE`. See [Wildcards and expansion](../authoring/wildcards.md).

The two combine: a qualified name may carry a wildcard.

**An expansion token says which name, too — by substitution.** A rule that declares
`Expansion:` may write its token, `&NAME&` (`NAME` upper-case letters and digits, starting with a
letter, the closing `&` mandatory), bare inside any name: `&VAR&`, `ADSL.&VAR&`,
`&DOM&.&DOM&SEQ`. The engine emits one concrete rule per binding with the token replaced, so the
template itself never runs. The closing `&` is where the token ends: `&DOM&SEQ` is `&DOM&`
followed by `SEQ`. An unterminated `&DOM` or a lower-case `&dom&` does not lex, and a declared
token inside a `/regex/` literal in a `Check` level, the `Precondition` or a `Bindings` expression
is a load error (a regex literal is never substituted; a `Match_Datasets` `Filter` is substituted
as text). See
[Wildcards and expansion](../authoring/wildcards.md#expansion--repeating-the-check-over-a-set-of-variables).

> **A name is not a string.** `AESTDTC` unquoted is a column; `"AESTDTC"` quoted is the text. A
> few functions take a *name* rather than a value — see
> [parameter-only types](#parameter-only-types) — and passing a quoted string where a name is
> wanted is a type error, not a convenience.

### 2. A binding result

```yaml
$subject_visit_count
```

The value computed by a [binding](../authoring/bindings.md), referenced by its `$`-name.

### 3. A join-match flag

```yaml
AE._matched_
```

Dotted like a qualified column, but it is **not** a column: it is a boolean, true on a row that
found at least one partner in that dataset under the join's keys. The `_matched_` suffix is
reserved.

### 4. A builtin

```yaml
variable_label
```

A value the engine supplies directly, without a call — see
[Builtin operands](#builtin-operands).

## Literals

| kind | written | notes |
|---|---|---|
| string | `"MILD"` | **double quotes only** — a single-quoted `'MILD'` is not a string |
| number | `18`, `-3.5` | |
| boolean | `true`, `false` | |
| regex | `/^AE.*$/` | between slashes |
| substitution | `/^(.*)DTC$/$1/` | a pattern and a replacement, three slashes; only ever the right side of [`~`](#substitution) |
| list | `["Y", "N"]` | **square** brackets; used with `in` / `not in`. ⛔ A `$` binding is never an element — `AETERM in ["A", $codes]` is a load error (it would read as the text `"$codes"`); write `$codes` alone as the operand. The one exception is a **splice** of column names into [`record_count`](functions.md#record_count)'s `group=`, [`is_unique_set`](functions.md#is_unique_set)'s members and [`values`](functions.md#values)' columns (a `$` holding a dataset-level list of names — a name-list binding `$keys: [STUDYID, USUBJID]` or a string list — or that expression written in place; see [bindings](../authoring/bindings.md)) |

> ⚠ **Single quotes are not string delimiters.** You will see them in `Source` blocks, because
> that is how the source sheets write SQL-ish prose — but a `Check` expression that uses them is
> a syntax error, not a variant spelling.

## Value types

Six types a **value** can have:

| type | is |
|---|---|
| `string` | text |
| `number` | numeric |
| `boolean` | true / false |
| `date` | an ISO-8601 calendar value, **partial precision retained** |
| `time` | a time of day, partial precision retained |
| `regex` | a pattern — **literal only**: never computed, never stored in a column. Written `/…/`; a computed regex fails the load. The one place a `"…"` string literal is a regex is `record_count`'s `regex=` |

Plus `list<T>` and `set<T>` for the collection-valued positions: a list is ordered and may repeat,
a set is not and may not.

`date` and `time` are **not** strings. A partial date such as `2019-03` keeps its precision, which
is why the date predicates can tell a complete value from an incomplete one — something no string
comparison can do.

> ⛔ **A type mismatch is a rule ERROR, not a coercion.** Comparing a character column to a number
> does not silently parse it. If you mean the numeric reading of text, say so with `num(…)`; if
> you mean the text reading, `str(…)`.

### Type errors are load errors

When both sides of a conflict are **known when the rule loads**, the rule fails to load and reports
**ERROR** — in every `Check` level, in the `Precondition` and in every binding (since 2026-10-01):
a wrongly typed argument at any function parameter; comparison operands of different types
(`len(AETERM) > "200"`); arithmetic on a string; a `Check` or `Precondition` — or an `and` / `or` /
`not` operand — that is not a condition; a single value as the right operand of `in`; `date(5)`;
a string literal given to `earliest_date` / `latest_date`; `colref(3)`; a computed regex. The
message names the place and quotes the expression:

```text
stage A: PARAMETER_TYPE: Check (ERROR): comparison operands disagree: number > string (len(AETERM) > "200")
```

The place is `Check (<level>)`, `Precondition` or `binding $name`. A conflict that depends on the
data — a column's type — is settled per dataset instead (below, `unknown`). A date against a
string has its own rule — see [Comparison](../semantics/comparison.md#dates-and-times--the-hull-rule).


### Parameter-only types

Three more types exist, and you will only ever meet them in an **argument position**. No column
holds one, and no expression computes one:

| type | is | where you see it |
|---|---|---|
| `column-reference` | a **name**, not the value behind it | a function that takes a column rather than its contents |
| `dataset-reference` | a **dataset name**, written bare (`domain=DM`) or quoted (`domain="DM"`) | the `domain=` of the grouped functions and `read_value` |
| `metadata-level` | the closed set `DATA` · `DEFINE` · `LIBRARY` | a parameter selecting which metadata to read |

**`column-reference` is deliberately not a string.** A bare column operand, a backtick-quoted
name and `varname()` all have this type, and a quoted `"AETERM"` at a column parameter is a load
error, not a convenience. In a value position it dereferences implicitly — and to
`unknown`, because which type the cell holds is a per-dataset fact the engine can only settle once
it has the dataset.

**`metadata-level` is deliberately not a string either.** It is a closed three-value enum, so a
misspelling is caught rather than passed through as text nobody matches.

### Two things that are not types

**`missing` is not a type.** It is a bottom value that inhabits *every* type, which is why a
missing value can turn up in any position without a type error, and why no function signature
needs a "missing" variant. See [Values and missing values](../semantics/values-and-missing.md).

**`unknown` is not a type either.** It is the checker saying *"not statically known"* — the type
of a dereferenced column before the rule is bound to a dataset. It is compatible with everything,
so only known-against-known conflicts are reported early; the rest are settled at bind time.

### Conversions and comparison modes

Six call-shaped tokens decide how a value is read or compared, in two groups that behave
differently.

**Conversions — `num` · `str` · `date` · `time`.** These are ordinary registered
[functions](#values-and-references) (since 2026-09-30). A conversion is a property of the
**value**: it means the same thing wherever it is written, and it produces a real converted value.

```yaml
# --STRESC is the CHARACTER standardised result; --STRESN is its numeric twin.
# Comparing them means reading the character one as a number.
expression: 'not empty(--STRESN) and is_numeric(--STRESC) and --STRESN != num(--STRESC)'

expression: 'date(--STTPT) < date(--DTC)'
```

**`num` reads text as a number.** It is for a **character** column that carries a numeric value —
`--STRESC`, `TSVAL`, `--ORNRHI` — not for a column that is already numeric. Converting `AGE`, or
any `--STRESN`, says nothing and reads as a misunderstanding.

> **Guard the conversion with `is_numeric`, as the rule above does.** `num` on text that is not a
> number yields a [missing value](../semantics/values-and-missing.md) rather than an error, and a
> missing value then behaves by the missing-value rules — so an unguarded conversion does not
> fail, it quietly changes which rows the comparison selects.

**Missing and empty** — A genuinely missing input yields **that missing value**, identity kept:
`num(.A)` is `.A` (rulings register `D85c`), so `abs(num(X))` keeps `X`'s `.A` as well. `""` and
text that is not a number yield the plain missing (`MIS`).

**`date` and `time` are *interpretive*.** The temporal value's carrier is the ISO string itself,
so partial precision survives the conversion instead of being flattened into a number. That is
what lets a partial date stay partial through a comparison.

**Comparison modes — `date_part` · `time_part`.** These are written like calls but are **not
functions** — neither is in the function registry; the compiler recognises them in comparison
position. They do not convert anything. They select
*how* the comparison is performed — it reads the full operands and compares the requested part —
and they are erased once that choice is made.

```yaml
expression: 'date_part(AESTDTC) == date_part(AEENDTC)'
```

**`str` renders a value as text** — a cell's own text, a computed number in plain notation — so
`str(A) == str(B)` is an ordinary string equality of two converted values, whatever the two
columns' types. (Until 2026-09-30 `str` was a comparison marker that selected type-insensitive
equality; the function answers the same on every column operand — a number written inside `str` on
the left side, which the marker read as `"3.0"` and the function reads as `"3"`, is an open owner
question, `PLAN-dynamic-column-functions` F3.)

> **Use one whenever the operands' own types do not already make the intended reading
> unambiguous** — and prefer a conversion to a mode where both would express the requirement,
> since a conversion means the same thing wherever it appears.

## Operators

### Logical

| operator | |
|---|---|
| `and` · `&&` | all operands true |
| `or` · `\|\|` | at least one true |
| `not` | negation |

Prefer the word forms; `&&` and `||` are accepted and nothing in the corpus uses them.

> ⚠ **Keep `&&` apart from an expansion token.** `&A&&B&` and `COL&&&A&` do not lex — *"separate
> an expansion token from '&' / '&&' by a space"*. Write `&A& && &B&`, or better `&A& and &B&`.

#### Their operands must already be boolean

> ⛔ **There is no truthiness.** A number is not a boolean, and neither is a string. `AGE and …`
> is a rule **error** — *"an operand of 'and' must be boolean, not number"* — not a test for
> "non-zero". Nothing is implicitly converted to a condition.

The same holds for `not`, and for the expression as a whole: **the `Check` root must itself be
boolean.** `Check: expression: 'AGE'` is an error for the same reason.

What *is* boolean, and can therefore stand on its own or be combined:

| | example |
|---|---|
| a comparison | `AESTDY < 1` |
| a boolean-valued [function](#functions) | `empty(AESEV)` · `var_exists("AESEV")` · `is_unique_value(USUBJID)` |
| the [join-match flag](#3-a-join-match-flag) | `AE._matched_` |
| a boolean [metadata accessor](#the-metadata-accessors-take-a-level) | `var_has_codelist` |
| a boolean literal | `true` · `false` |

Because these are already boolean, you never write `== true`:

```yaml
expression: 'var_exists("AESEV") and not empty(AESEV)'     # ✅
expression: 'var_exists("AESEV") == true and ...'          # ⛔ noise
```

#### A plain data column is not a condition

`DTHFL` on its own is a column reference, not a flag the engine can test. Its type is not known
until the rule binds to a dataset, so the error surfaces at bind time rather than immediately —
which makes it worth stating plainly here:

```yaml
expression: 'DTHFL'                # ⛔ not a condition
expression: 'DTHFL == "Y"'         # ✅ say what you mean
expression: 'not empty(DTHFL)'    # ✅ if presence is the question
```

A `Y`/`N` flag is text. Compare it.

### Comparison

| operator | |
|---|---|
| `==` | equal |
| `!=` | not equal |
| `<` · `<=` · `>` · `>=` | ordered comparison — **numeric or temporal operands only** |

These are **total** — a comparison involving a [missing value](../semantics/values-and-missing.md)
is true or false, never unknown.

⚠ **What they accept and how they behave differs per type**, and ordered comparison is *not*
available on text. [Comparison](../semantics/comparison.md) is the full account — read it before
relying on any of these, especially on dates.

### Membership

| operator | |
|---|---|
| `in` | the value is in the set |
| `not in` | it is not — **and this is true for a missing value** |

**The list decides how the comparison is made — never the left operand.**

```yaml
AESEV    in ["MILD", "MODERATE"]   # textual membership
VISITNUM in [1, 2, 3]              # NUMERIC membership
```

- An **all-numeric** list literal runs the membership **numerically** — the probe is parsed, so
  `"10.0"` and `"01"` both match the member `10`. A character column against a numeric list is an
  **error**: write `num(--STRESC) in [10, 20]`.
- An **all-string** list literal runs it **textually**, and a numeric column against it is an
  error.
- A **mixed** list literal — a number and a string together — is a **load error**.
- A **dynamic set** — the thing on the *right* being a `$`-binding, a `${*}` wildcard, an inline
  function call (`X in distinct(VISITNUM, domain=SV, group=[USUBJID])`) or a per-row grouped result — is
  always **textual** and is not type-checked. Its contents are not statically known, so no
  classification is possible.

> **The left operand never chooses the comparison.** `$max_value in [10, 20]` runs **numerically**,
> because the *list* is all-numeric — a `$`-binding on the left changes nothing. What makes a
> membership textual is a dynamic set on the **right**.

`upper(X) in [...]` is the case-insensitive surface; it is always string membership, since
numbers have no case.

A **column name a rule handles as a value** — `varname()`, the list
`get_column_order_from_dataset()` returns — is a string and compares exactly like one (owner,
2026-09-28: *"a String that contains a column name is still a string in the engine"*; rulings
register `CIT §3`). A rule that wants such a name to match regardless of letter case folds
**both sides** — the owner's *"compare the upper case version of a name against the upper case
version of a list of strings"*: `upper(varname()) in $required_variables` (the case-insensitive
membership surface upper-cases the list as well as the probe), `equalsIgnoreCase(varname(),
var_name("LIBRARY"))`, and for two lists a sibling binding per side —
`$dataset_variables_upper: upper($dataset_variables)`, `$required_variables_upper:
upper($required_variables)` — read by `contains_all` / `is_ordered_subset_of` /
`shares_elements_with`; [`upper`](functions.md#upper) and [`lower`](functions.md#lower) fold a list
element by element — a `$`-list, a per-row list, and the values of several columns read with
[`values`](functions.md#values) (`upper(values([X, Y]))`; `upper([X, Y])` with bare names fails to
load, since `[X, Y]` is a list of column *names*). The sixteen element-wise **predicates**
(`is_valid_date`, `is_numeric`, … — [`functions.md`](functions.md#quantifiers-over-joined-rows)) lift
the same way and answer a list of booleans for `any` / `all` / `none`:
`any(is_valid_date(values([--STDTC, --ENDTC])))`. A name **test** folds the name it tests: `ends_with(upper(varname()), "FL")`,
`upper(varname()) =~ /^ANL\d\dFL$/`, `prefix(upper(varname()), 2)`, with the literal or pattern
spelled in upper case. A `QNAM` / `IDVAR` **cell** is a value like any other and is compared
exactly (`QNAM == "AESOSP"`); `IDVAR not in $rdomain_variables and IDVAR not in
$rdomain_variables_upper` compares the exact `IDVAR` cell with the referenced dataset's column list
in both spellings: a lower-case `IDVAR` value does not name an upper-case variable, but it does
name a column spelled exactly like it (an all-lowercase study).

`tuple(a, b) in distinct([a, b], domain="D")` tests a **whole row-tuple** against another
dataset's tuples, rather than element by element.

> ⭐ **Ask for a date and you get one** (2026-09-21). An **untagged** probe is still text:
> `--DTC in ["2020-01-01"]` does not match `2020-01-01T09:15`. A **`date(…)`** probe against a
> **written-out list** applies the
> [hull rule](../semantics/comparison.md#dates-and-times--the-hull-rule) to every member, exactly
> as `date(A) == date(B)` does — so `date(--DTC) in [date("2020-01-01")]` **does** match
> `2020-01-01T09:15`. ⛔ Mixing the two is a load error, not a reinterpretation: convert the
> members with `date(…)`. ⛔ Against a **dynamic** set (a `$`-binding, a `${*}` wildcard, a grouped
> result) a date probe is still compared as **text** — a stated limit, not an oversight.
>
> ⛔ A **`tuple(…)`** probe and its list target must be in the same order — a permutation of the
> same names, or a length mismatch, is a load error.

### Regular expression

| operator | |
|---|---|
| `=~` | matches |
| `!~` | does not match |

```yaml
expression: 'USUBJID !~ /^[A-Z0-9-]+$/'
```

> ⚠ **It is a *find*, not a full match.** `X =~ /AB/` is true for `"CABD"`. Anchor with `^` and
> `$` when you mean the whole value — as the example above does.

**The subject must be a character column.** A numeric column is an **error**, deliberately: a
regex asserts a property of the *text*, and a number's text form is a formatting decision
(`1.2e-10` or `0.00000000012`), so matching one by accident is worse than refusing.

A missing value matches **no** regex, including one that would match the empty string.

Date columns *are* character, so a regex reads their raw ISO text — which makes
`--DTC =~ /^\d{4}$/` a legitimate precision test. Prefer the date predicates
(`is_complete_date`, `is_partial_date`) where one says the same thing.

### Substitution

| operator | |
|---|---|
| `~` | replace the first match: `X ~ /pattern/replacement/` |

The one operator that hands on what a regular expression **captures** — the part of a value a
pattern matches, or the groups inside it — as a **string** you can compare, convert or aggregate:

```yaml
expression: 'AESTDTC ~ /(?i)^(.*)DTC$/$1/ == "AEST"'          # "AESTDTC" -> "AEST"
expression: 'num(MIDS ~ /^.*?([0-9]+)$/$1/) > 0'              # "HYPO3" -> 3
expression: 'coalesce(MIDS ~ /-/ /, MIDS) == "HYPO 3"'       # replace the first "-" by a space, keep MIDS when there is none
```

| aspect | rule |
|---|---|
| form | `X ~ /pattern/replacement/` — the right side is always one substitution literal, written directly after `~` |
| pattern | exactly the regex literal of `=~`: the same escapes (`\/` for a slash, `\\` for a backslash, every other backslash sequence verbatim), Java syntax, inline flags such as `(?i)` |
| matching | a **find** of the **first** match, as `=~` — anchor with `^` and `$` to extract from the whole value |
| replacement | Java `Matcher` replacement syntax: `$1` … `$n` and `${name}` insert a group, `$0` the whole match, `\$` a literal dollar, `\/` a literal slash; an empty replacement `/a//` deletes the match. Only the first match is replaced — there is no global form |
| result | a string: the value with its first match replaced |
| no match | **missing** — never the input, so an extract never passes a `not empty(…)` guard by returning its subject. Replace-or-keep is `coalesce(X ~ /a/b/, X)` |
| missing subject | missing (identity kept) |
| subject | character only — a numeric column is an **error**, exactly as for `=~` |
| list subject | **element-wise**: `$list ~ /re/repl/`, `split_by(X, ",") ~ /re/repl/` and `values([A, B]) ~ /re/repl/` apply to every element and answer a list of the same length; a missing element, or an element the pattern does not match, is missing at its position |
| precedence | tighter than everything else, a postfix on its subject: `X ~ /a/b/ == "b"` is `(X ~ /a/b/) == "b"`, `X ~ /a/b/ + 1` adds to the result; parenthesise a computed subject, `(A + B) ~ /x/y/`. Chains left to right: `X ~ /a/b/ ~ /b/c/` |
| load errors | an invalid pattern; a `$n` / `${name}` the pattern does not define; a bare `$` or a dangling `\` in the replacement; a right side that is not a substitution literal (`X ~ "a"`, and `X ~ /re/` without the replacement part) |

### Arithmetic

`+` · `-` · `*` · `/`

## Functions

**One surface.** Every function is written the same way — `name(args)` — and every one is
declared with an ordered, typed parameter list.

The signature names every parameter; `[, x]` marks an optional one and `…` a keyword-argument tail
listed under [Arguments](#arguments). **Every parameter can be given positionally or by name.**

**Returns** is what the call yields: `boolean` — a condition you can use on its own or combine with
`and` / `or` / `not`; `value` — a value whose type follows from its input and the
[value types](#value-types) above.

### Presence and emptiness

| function | returns | what it does |
|---|---|---|
| [`available(op)`](functions.md#available) | boolean | True when the named binding produced a result at all |
| [`coalesce(a, b[, c])`](functions.md#coalesce) | value | The first operand that is neither missing nor `""` |
| [`ds_exists(x)`](functions.md#ds_exists) | boolean | True when the named dataset is present in the run. A negated spelling `ds_not_exists(x)` exists; prefer `not ds_exists(x)`. |
| [`empty(x)`](functions.md#empty) | boolean | True when the value is missing or `""`. (`is_missing` is true only for a genuine missing.) The positive spellings `non_empty` / `present` / `is_present` exist; prefer `not empty(x)`. |
| [`var_exists(x)`](functions.md#var_exists) | boolean | True when the named column exists; accepts a `--` wildcard, a dotted cross-dataset name, a SUPP QNAM pivot, a `${…}` substitution and a computed name (`var_exists(concat("ADSL.TRT", printf("%02d", APERIOD), "P"))` — per row; a missing name makes `var_exists` and `var_not_exists` both false, so `not var_exists(…)` is true there: guard it with `not empty(…)`). Through a `$`-binding it answers as the binding's expression written in place. A negated spelling `var_not_exists(x)` exists; for a written name prefer `not var_exists(x)`. |
| [`var_is_empty(x)`](functions.md#var_is_empty) | boolean | True when the column is absent, or present and blank (`""` or missing) on every record (renamed from `var_is_null`) |
| [`var_is_single_value(x)`](functions.md#var_is_single_value) | boolean | True when every row holds the same value in the column, over at least one row |
| [`var_is_all_missing(x)`](functions.md#var_is_all_missing) | boolean | True when every cell of the column is a missing value (`""` is not missing) |

### Text

| function | returns | what it does |
|---|---|---|
| [`concat(a, b[, c])`](functions.md#concat) | value | Joins two or three values into one string; a missing operand makes the result that missing (`D36`) |
| [`contains(x, needle)`](functions.md#contains) | boolean | True when `needle` occurs anywhere in `x`; `needle` may vary per row. A negated spelling `does_not_contain(x, needle)` exists; prefer `not contains(x, needle)`. |
| [`count(x)`](functions.md#count) | value | Number of elements in a list-valued operand — 1 for a present scalar, 0 for missing or `""`. Also spelled `size`. |
| [`does_not_equal_string_part(name, value, regex=)`](functions.md#does_not_equal_string_part) | boolean | True when `value` differs from capture-group 1 of `regex=` applied to `name` |
| [`ends_with(x, needle)`](functions.md#ends_with) | boolean | True when `x` ends with `needle`; `needle` may vary per row |
| [`equalsIgnoreCase(x, y)`](functions.md#equalsignorecase) | boolean | Case-insensitive string equality; two empties match |
| [`has_alpha(x)`](functions.md#has_alpha) | boolean | True when `x` contains any ASCII letter |
| [`has_digit(x)`](functions.md#has_digit) | boolean | True when `x` contains any ASCII digit |
| [`has_equal_length(name, n)`](functions.md#has_equal_length) | boolean | True when the value's length equals `n`. A negated spelling `has_not_equal_length(name, n)` exists; prefer `not has_equal_length(name, n)`. `n` may be a number, numeric text or a per-row column. |
| [`imatches(x, /re/)`](functions.md#imatches) | boolean | Case-insensitive unanchored regex search |
| [`is_valid_name(x)`](functions.md#is_valid_name) | boolean | True when `x` is a valid uppercase SAS name of 1–8 characters |
| [`is_valid_testcd(x)`](functions.md#is_valid_testcd) | boolean | True when `x` is 1–8 characters starting with a letter or underscore |
| [`len(x[, unit=, encoding=])`](functions.md#len) | value | Length of the value in characters; `len("") = 0`. Also spelled `length`. `unit="bytes"` counts the value encoded in `encoding=` (default: the table's own `Encoding`; none or `MIXED` ⇒ missing) |
| [`lower(x)`](functions.md#lower) | value | The value lower-cased. Also spelled `lowcase`. |
| [`max_value_length([name][, unit=, encoding=])`](functions.md#max_value_length) | value | The longest value stored in the column, across all rows — characters, or with `unit="bytes"` bytes once encoded (`encoding=`, default the table's own `Encoding`; none or `MIXED` ⇒ missing) |
| [`normalize_space(x)`](functions.md#normalize_space) | value | Trims the value and collapses each run of internal whitespace to a single space |
| [`prefix(x, n)`](functions.md#prefix) | value | The first `n` characters; a shorter value is returned whole |
| [`prefix_matches(x, /re/[, n])`](functions.md#prefix_matches) | boolean | True when the first `n` characters match the regex, anchored; without `n`, the whole value |
| [`split_by(x, "delimiter")`](functions.md#split_by) | value | Splits the value on a literal delimiter into a list of tokens |
| [`starts_with(x, needle)`](functions.md#starts_with) | boolean | True when `x` begins with `needle` |
| [`substring(x, start[, length])`](functions.md#substring) | value | The substring starting at `start` for `length` characters; 1-based, as in SAS |
| [`suffix(x, n)`](functions.md#suffix) | value | The last `n` characters; a shorter value is returned whole |
| [`suffix_matches(x, /re/[, n])`](functions.md#suffix_matches) | boolean | True when the last `n` characters match the regex, anchored; without `n`, the whole value |
| [`trim(x)`](functions.md#trim) | value | The value with leading and trailing whitespace removed |
| [`upper(x)`](functions.md#upper) | value | The value upper-cased. Also spelled `upcase`. |

### Numbers and arithmetic

| function | returns | what it does |
|---|---|---|
| [`abs(x)`](functions.md#abs) | value | The absolute value of a number |
| [`between(x, lo, hi)`](functions.md#between) | boolean | True when `lo <= x <= hi`, inclusive |
| [`ceil(x)`](functions.md#ceil) | value | The smallest integer not less than `x` |
| [`floor(x)`](functions.md#floor) | value | The largest integer not greater than `x` |
| [`is_integer(x)`](functions.md#is_integer) | boolean | True when the value is a finite whole number. A negated spelling `is_not_integer(x)` exists; prefer `not is_integer(x)`. |
| [`is_numeric(x)`](functions.md#is_numeric) | boolean | True when the value is a decimal number; there is no `is_not_numeric` — write `not is_numeric(x)` |
| [`minus(value, subtract=)`](functions.md#minus) | value | The set difference of two dataset-level lists — the members of `value` not in `subtract`; a tuple member compares structurally |
| [`union(first, second, …)`](functions.md#union) | value | The set union of two or more dataset-level lists, in order of first occurrence; lists only, one element shape |
| [`round(x)`](functions.md#round) | value | The value rounded to the nearest integer, a half rounded up toward +∞ (`round(-2.5)` is `-2`) |

### Dates and times

| function | returns | what it does |
|---|---|---|
| [`date_contains(outer, inner)`](functions.md#date_contains) | boolean | True when every instant `inner` could denote lies inside `outer`'s range |
| [`date_diff_days(name, reference)`](functions.md#date_diff_days) | value | The days from a reference date to a record's date (no `+1`); a grouped reference is a named `min_date(…)` / `max_date(…)`, an offset is `+ n` |
| [`date_overlaps(a, b)`](functions.md#date_overlaps) | boolean | True when the two date ranges share at least one possible instant |
| [`day(x)`](functions.md#day) | value | The day component of an ISO value, or missing |
| [`dy(name, reference)`](functions.md#dy) | value | The SDTM study day of a record's date relative to a reference date read through the declared join; no day 0 |
| [`earliest_date(a, b)`](functions.md#earliest_date) | value | The earlier of two dates, with `min_date`'s candidate and determinability rules; a missing or blank argument is skipped |
| [`earliest_possible(x)`](functions.md#earliest_possible) | value | The earliest instant an incomplete value could denote |
| [`interval_uncertainty_precision_mismatch(name, delimiter=)`](functions.md#interval_uncertainty_precision_mismatch) | boolean | True when the two halves of an ISO `a/b` interval of uncertainty are stated at different precisions |
| [`is_complete_date(x)`](functions.md#is_complete_date) | boolean | True when every date component is present |
| [`is_complete_date_part(x)`](functions.md#is_complete_date_part) | boolean | True when the leading `YYYY-MM-DD` portion is complete, ignoring any time. A negated spelling `is_not_complete_date_part(x)` exists; prefer `not is_complete_date_part(x)`. |
| [`is_partial_date(x)`](functions.md#is_partial_date) | boolean | True when the value is calendar-valid but has missing components. Also spelled `is_incomplete_date`. |
| [`is_valid_date(x)`](functions.md#is_valid_date) | boolean | True when the value is a calendar-valid ISO date at any precision. A negated spelling `invalid_date(x)` exists; prefer `not is_valid_date(x)`. |
| [`is_valid_duration(x)`](functions.md#is_valid_duration) | boolean | True when the value is a valid ISO-8601 duration. A negated spelling `invalid_duration(x[, negative=])` exists; prefer `not is_valid_duration(x)`. `negative=` decides whether a negative duration counts. |
| [`latest_date(a, b)`](functions.md#latest_date) | value | The later of two dates, with `max_date`'s candidate and determinability rules; a missing or blank argument is skipped |
| [`latest_possible(x)`](functions.md#latest_possible) | value | The latest instant an incomplete value could denote |
| [`month(x)`](functions.md#month) | value | The month component of an ISO value, or missing |
| [`time_contains(outer, inner)`](functions.md#time_contains) | boolean | True when every time-of-day `inner` could denote lies inside `outer`'s range |
| [`time_overlaps(a, b)`](functions.md#time_overlaps) | boolean | True when the two time-of-day ranges share at least one possible instant |
| [`year(x)`](functions.md#year) | value | The year component of an ISO value, or missing |

### Aggregates over rows and groups

| function | returns | what it does |
|---|---|---|
| [`distinct(name, domain=, filter=, group=, keep_missings=)`](functions.md#distinct) | value (a list) | The distinct values of a column, or the distinct row tuples of a list of columns — one list for the dataset, or each row's group's list with `group=` |
| [`empty_within_except_last_row(name, group, ordering=, keep_missings=)`](functions.md#empty_within_except_last_row) | boolean | True when a row other than the ordered-last one is unpopulated |
| [`has_mixed_emptiness_within_group(name, group=, qualifying_any_populated=, keep_missings=)`](functions.md#has_mixed_emptiness_within_group) | boolean | Per row: the group mixes populated and unpopulated values of the subject column; column references, key identity, KEEP-missing default |
| [`is_last_in_group(ordering=, group=, keep_missings=)`](functions.md#is_last_in_group) | boolean | Per row: the row's ordering value is identical to its group's maximum (every tied row is last); no target, column references, DROP-missing default |
| [`max(name, …)`](functions.md#max) | value | The largest value of a column, over the dataset or per group |
| [`max_date(name, …)`](functions.md#max_date) | value | The latest date in a column; a value that cannot be positioned yields no value |
| [`min_date(name, …)`](functions.md#min_date) | value | The earliest date in a column; a value that cannot be positioned yields no value |
| [`present_on_multiple_rows_within(name, within=)`](functions.md#present_on_multiple_rows_within) | boolean | True for every member of a group that has two or more rows |
| [`record_count(…)`](functions.md#record_count) | value | The number of rows; with `group=`, the count for each row's group |
| [`row_max(name_pattern=)`](functions.md#row_max) | value | The largest populated value, per row, among the columns whose names match a regex; values rank by their own type |
| [`tuple(c1, c2, …)`](functions.md#tuple) | value | Builds a composite key from two to six columns, for whole-tuple membership |
| [`variable_count(name \| name_pattern=)`](functions.md#variable_count) | value | A count of variables: the current dataset's columns matching `name_pattern=`, or the study's datasets carrying the `"--"` template `name` |

### Uniqueness and consistency

| function | returns | what it does |
|---|---|---|
| [`has_multiple_values_for(name, key, within=, include_empty=)`](functions.md#has_multiple_values_for) | boolean | True when one `key` maps to more than one `name` — a functional-dependency violation; a blank key or value is excluded unless `include_empty=true` |
| [`has_same_values(name)`](functions.md#has_same_values) | boolean | True on every row when the column has at most one distinct value |
| [`inconsistent_enumerated_columns(name)`](functions.md#inconsistent_enumerated_columns) | boolean | True when the numbered series `name`, `name1`, `name2` … has a gap |
| [`is_inconsistent_across_dataset(name, keys=[…], include_empty=)`](functions.md#is_inconsistent_across_dataset) | boolean | True on the minority rows of a group that disagrees; blank targets excluded unless `include_empty=true` |
| [`is_unique_relationship(a, b)`](functions.md#is_unique_relationship) | boolean | True when `a` and `b` are in a strict one-to-one relationship. A negated spelling `is_not_unique_relationship(a, b)` exists; prefer `not is_unique_relationship(a, b)`. A blank key or value is excluded. |
| [`is_unique_set([V1, V2, …])`](functions.md#is_unique_set) | boolean | True when the tuple of the listed columns occurs exactly once. A negated spelling `is_not_unique_set([…])` exists; prefer `not is_unique_set([…])`. An absent member is dropped, `""` is a real component. |
| [`is_unique_value(name)`](functions.md#is_unique_value) | boolean | True when the value occurs exactly once in the column. A negated spelling `is_not_unique_value(name)` exists; prefer `not is_unique_value(name)`. |

### Sets and ordering

| function | returns | what it does |
|---|---|---|
| [`contains_all(source, keys=[…])`](functions.md#contains_all) | boolean | True when the source's distinct values include every value in `keys=`. A negated spelling `not_contains_all(source, keys=[…])` exists; prefer `not contains_all(source, keys=[…])`. |
| [`has_next_corresponding_record(name, value, within=, ordering=, keep_missings=, relation=)`](functions.md#has_next_corresponding_record) | boolean | True when a following record in the group matches under `relation=`; authored under `not` |
| [`is_ordered_subset_of(name, $b)`](functions.md#is_ordered_subset_of) | boolean | True when the column's values appear in `$b` in the same order. A negated spelling `is_not_ordered_subset_of(name, $b)` exists; prefer `not is_ordered_subset_of(name, $b)`. |
| [`is_sorted_by(target, by=[asc(col)[, nulls=]], within=)`](functions.md#is_sorted_by) | boolean | True when the target is sorted by `by=`; authored under `not`, it fires the whole unsorted group |
| [`shares_elements_with($a, $b)`](functions.md#shares_elements_with) | boolean | True when the two lists have at least one member in common. A negated spelling `shares_no_elements_with($a, $b)` exists; prefer `not shares_elements_with($a, $b)`. |

### Quantifiers over joined rows

The three quantifiers fold a **list of booleans** to one boolean per row. Two things produce such
a list: `joined(c)` — the answers of a condition over the current record's joined rows — and an
**element-wise predicate over a list** — `is_valid_date(values([X, Y]))`, one answer per cell (the
sixteen lifting predicates are marked in [`functions.md`](functions.md#quantifiers-over-joined-rows);
also over a per-row list such as `split_by(…)` or a `$`-list). The list may be bound and quantified
later (`$ok: is_numeric(values([A, B]))`, `all($ok)`); anywhere else — a condition on its own, a
comparison, `in`, another function's argument, `Output_Variables` — it fails the rule's load. A
quantifier over a scalar predicate is that predicate (`any(p(X))` ≡ `p(X)`, `none(p(X))` ≡
`not p(X)`); `any([])` is false, `all([])` and `none([])` are true.

| function | returns | what it does |
|---|---|---|
| [`any(joined(c))`](functions.md#any) | boolean | Some evaluation row of the current record satisfies `c` — the rows a `Match_Datasets` entry added for this record. Legal only in a Check level expression (bare, or under `and` / `or` / `not`). |
| [`all(joined(c))`](functions.md#all) | boolean | Every evaluation row of the current record satisfies `c`. |
| [`none(joined(c))`](functions.md#none) | boolean | No evaluation row of the current record satisfies `c`; the same as `not any(joined(c))`. |
| [`any(p(values([X, Y])))`](functions.md#any) | boolean | Some element of the per-row list satisfies the element-wise predicate `p` — `any(is_valid_date(values([--STDTC, --ENDTC])))`: at least one is a valid date; `all` / `none` likewise. |
| [`joined(c)`](functions.md#joined) | list of booleans | The answers of `c` over the current record's rows; legal only as the direct argument of `any` / `all` / `none`, in a rule with a keyed or RELREC `Match_Datasets` entry. Its condition is written bare: `joined(A == 1 and empty(B))`. |

### Dataset and domain metadata

| function | returns | what it does |
|---|---|---|
| [`dataset_class_from_library()`](functions.md#dataset_class_from_library) | value | The CDISC Library's class name of the current dataset's domain |
| [`dataset_names()`](functions.md#dataset_names) | value | The names of every dataset in the study, upper-cased, as a list (split members as themselves) |
| [`domain_is_custom()`](functions.md#domain_is_custom) | value (boolean) | Whether the CDISC Library declares the current dataset's domain custom |
| [`ds_class([x,] level)`](functions.md#ds_class) | value | The dataset's observation class; available at every level |
| [`ds_domain("DATA")`](functions.md#ds_domain) | value | The dataset's domain as `Scope.Domains` resolves it — `SUPPLB`, not the parent, on a SUPP dataset; `DATA` only |
| [`ds_label([x,] level)`](functions.md#ds_label) | value | The dataset's label; available at every level |
| [`ds_name([x,] level)`](functions.md#ds_name) | value | The dataset's name; available at every level |
| [`ds_structure([x,] level)`](functions.md#ds_structure) | value | The dataset's ADaM data structure; `DEFINE` and `LIBRARY` only |
| [`extract_metadata(name)`](functions.md#extract_metadata) | value | One dataset-metadata value of the current dataset, by its key |
| [`referenced_dataset_variables(name)`](functions.md#referenced_dataset_variables) | value (a list) | The variable names of the dataset **named in a column value**, per row (the former `distinct(IDVAR, value_is_reference=true)`) |
| [`referenced_domain_class(name)`](functions.md#referenced_domain_class) | value | The CDISC Library class of the domain **named in a column value**, per row |
| [`split_sibling_length_mismatch()`](functions.md#split_sibling_length_mismatch) | value | The variables whose declared length differs across the dataset's split-family members, as a list (library-independent) |
| [`standard_domains()`](functions.md#standard_domains) | value | The domain codes the standard publishes, as a list; SKIPPED when the Library publishes none |
| [`study_domains()`](functions.md#study_domains) | value | The distinct `DOMAIN` codes present in the study, as a list (a split domain once; the no-domain datasets contribute `""`) |

### Variable metadata

| function | returns | what it does |
|---|---|---|
| [`column_series_metadata([name,] name_pattern=, min_length=)`](functions.md#column_series_metadata) | value (boolean) | Whether a numbered column series (`COVAL1..n`) is incomplete |
| [`cross_dataset_variable_metadata(name, domain=)`](functions.md#cross_dataset_variable_metadata) | value (per variable) | Per variable, one metadata attribute of the same-named variable in another dataset (or `"*"`) |
| [`duplicate_label_variables()`](functions.md#duplicate_label_variables) | value | The dataset's variables whose declared label another of its columns shares, as a list |
| [`expected_variables()`](functions.md#expected_variables) | value | The variables the CDISC Library marks Expected for this dataset, as a list |
| [`get_column_order_from_dataset()`](functions.md#get_column_order_from_dataset) | value | The dataset's own column order, as a list of names |
| [`get_column_order_from_library()`](functions.md#get_column_order_from_library) | value | The column order the CDISC Library publishes for this dataset, as a list; SKIPPED when it has none |
| [`get_dataset_filtered_variables(key_name=, key_value=)`](functions.md#get_dataset_filtered_variables) | value | Library variables of the current dataset filtered by metadata (static string literals), intersected with present columns |
| [`get_model_column_order()`](functions.md#get_model_column_order) | value | The column order of the standard's model for the dataset's class, as a list; SKIPPED when it has none |
| [`get_model_filtered_variables(key_name=, key_value=, model_class=)`](functions.md#get_model_filtered_variables) | value | Model variables filtered by metadata (static string literals), not intersected; `model_class=` walks the **named** general-observation class's model table — SKIPPED when that class has no variables |
| [`get_parent_model_column_order(name)`](functions.md#get_parent_model_column_order) | value | Per row, the model column order of the parent domain the column names (`RDOMAIN`); a split parent is the union of its members |
| [`get_permitted_column_order()`](functions.md#get_permitted_column_order) | value | The model column order with the permitted additional variables of a special-purpose dataset placed by role (SDTMIG 4.1.1.4); SKIPPED when the model order is absent or empty |
| [`natural_key_variables()`](functions.md#natural_key_variables) | value | The natural-key-forming role variables present in the dataset, as a list |
| [`required_variables()`](functions.md#required_variables) | value | The variables the CDISC Library marks Required for this dataset, as a list |
| [`var_ccode(x, "DEFINE")`](functions.md#var_ccode) | value | The NCI C-code of the bound codelist; `DEFINE` and `LIBRARY` only |
| [`var_codelist([x,] level)`](functions.md#var_codelist) | value | The name of the codelist bound to the variable; `DEFINE` and `LIBRARY` only |
| [`var_codelist_coded_codes(x, level)`](functions.md#var_codelist_coded_codes) | value | The codes of the bound codelist's terms, as a list; `DEFINE` and `LIBRARY` only |
| [`var_codelist_coded_values(x, level)`](functions.md#var_codelist_coded_values) | value | The submission values of the bound codelist's terms, as a list; `DEFINE` and `LIBRARY` only |
| [`var_codelist_extended_values(x, "DEFINE")`](functions.md#var_codelist_extended_values) | value | The sponsor-added values of an extended codelist, as a list; `DEFINE` only |
| [`var_codelist_extensible(x, "LIBRARY")`](functions.md#var_codelist_extensible) | value | Whether the bound codelist is extensible; `LIBRARY` only |
| [`var_core([x,] level)`](functions.md#var_core) | value | The variable's core designation — Required, Expected or Permissible; `DEFINE` and `LIBRARY` only |
| [`var_external_dictionary(x, "DEFINE")`](functions.md#var_external_dictionary) | value | The external dictionary the variable is coded against; `DEFINE` only |
| [`var_external_dictionary_version(x, "DEFINE")`](functions.md#var_external_dictionary_version) | value | The version of that external dictionary; `DEFINE` only |
| [`var_format([x,] level)`](functions.md#var_format) | value | The variable's display format; `DATA` and `DEFINE` only |
| [`var_has_codelist(x, "DEFINE")`](functions.md#var_has_codelist) | value | Whether the variable binds a codelist at all; `DEFINE` only |
| [`var_has_comment(x, "DEFINE")`](functions.md#var_has_comment) | value | Whether Define-XML attaches a comment to the variable; `DEFINE` only |
| [`var_has_method(x, "DEFINE")`](functions.md#var_has_method) | value | Whether Define-XML attaches a derivation method to the variable; `DEFINE` only |
| [`var_has_value_list(x, "DEFINE")`](functions.md#var_has_value_list) | value | Whether Define-XML documents the variable per value (a `def:ValueListRef`); `DEFINE` only |
| [`var_label([x,] level)`](functions.md#var_label) | value | The variable's label; available at every level |
| [`var_length([x,] level)`](functions.md#var_length) | value | The variable's declared length; available at every level |
| [`var_mandatory(x, "DEFINE")`](functions.md#var_mandatory) | value | Whether Define-XML marks the variable mandatory; `DEFINE` only |
| [`var_name([x,] level)`](functions.md#var_name) | value | The variable's name; available at every level |
| [`var_ordinal([x,] level)`](functions.md#var_ordinal) | value | The variable's position in the declared column order; available at every level |
| [`var_origin_type(x, "DEFINE")`](functions.md#var_origin_type) | value | The variable's Define-XML origin type — Collected, Derived and so on; `DEFINE` only |
| [`var_role([x,] level)`](functions.md#var_role) | value | The variable's role — identifier, topic, qualifier and so on; `DEFINE` and `LIBRARY` only |
| [`var_type([x,] level)`](functions.md#var_type) | value | The variable's data type; available at every level |
| [`variable_names()`](functions.md#variable_names) | value | Every variable name the standard publishes, as a list; SKIPPED when the Library publishes none |

### Define-XML and value-level metadata

| function | returns | what it does |
|---|---|---|
| [`codelist_decode([x])`](functions.md#codelist_decode) | value | Per record, the decode the variable's Define-XML codelist gives the record's value; missing when there is none |
| [`define_available()`](functions.md#define_available) | boolean | True when a Define-XML was supplied to the run (parsed or not); authorable, needs no define — the presence rule's `not define_available()` |
| [`define_dataset_names()`](functions.md#define_dataset_names) | value | The dataset names the Define-XML declares, upper-cased, as a list; needs the Define-XML |
| [`define_key_variables()`](functions.md#define_key_variables) | value | The Define-XML key variables of the domain, as a list; SKIPPED when it declares none |
| [`define_variable_decode_matches([x])`](functions.md#define_variable_decode_matches) | value | Whether the record's code and decode agree at variable level, per the Define-XML |
| [`define_variable_names()`](functions.md#define_variable_names) | value | The Define-XML ItemDef names of the domain, as a list; needs the Define-XML |
| [`vlm_codelist_coded_codes(X)`](functions.md#vlm_codelist_coded_codes) | value | The codes of the codelist bound by the value-level match, as a list |
| [`vlm_codelist_coded_values(X)`](functions.md#vlm_codelist_coded_values) | value | The submission values of the codelist bound by the value-level match, as a list |
| [`vlm_codelist_extensible(X)`](functions.md#vlm_codelist_extensible) | value | Whether the codelist bound by the value-level match is extensible |
| [`vlm_data_type(X)`](functions.md#vlm_data_type) | value | The `@DataType` of the Define-XML item matched at value level |
| [`vlm_decode_matches(X)`](functions.md#vlm_decode_matches) | value | Whether the record's code and decode agree at value level |
| [`vlm_has_codelist(X)`](functions.md#vlm_has_codelist) | value | Whether the value-level match binds a codelist |
| [`vlm_has_comment(X)`](functions.md#vlm_has_comment) | value | Whether the value-level match carries a comment (`def:CommentOID`); missing when unmatched |
| [`vlm_has_method(X)`](functions.md#vlm_has_method) | value | Whether the value-level match carries a derivation method (`MethodOID`); missing when unmatched |
| [`vlm_length(X)`](functions.md#vlm_length) | value | The `@Length` of the Define-XML item matched at value level |
| [`vlm_mandatory(X)`](functions.md#vlm_mandatory) | value | Whether the Define-XML item matched at value level is mandatory |
| [`vlm_origin_type(X)`](functions.md#vlm_origin_type) | value | The origin type of the value-level match (`def:Origin/@Type`, else `@Origin`); `""` when it declares none, missing when unmatched |
| [`vlm_type_conforms(X)`](functions.md#vlm_type_conforms) | value | Whether the record's value conforms to the matched `@DataType` |
| [`vlm_value_length(X)`](functions.md#vlm_value_length) | value | The stored length of the record's value under the matched type |

### Controlled terminology and dictionaries

| function | returns | what it does |
|---|---|---|
| [`codelist_terms(codelists=, level=, returntype=)`](functions.md#codelist_terms) | value | The terms of the named CT codelists in the selected representation, as a list; SKIPPED when they resolve none |
| [`dictionary_available(type)`](functions.md#dictionary_available) | boolean | The external-dictionary availability gate the loader injects for an inline dictionary call (the operation form was deleted by wave 1) |
| [`dictionary_has_decode(name, external_dictionary_type=)`](functions.md#dictionary_has_decode) | boolean | Any-decode presence per record; code lookup case-sensitive by default, `case_sensitive=false` folds it |
| [`get_codelist_attributes(name, version, ct_attribute=)`](functions.md#get_codelist_attributes) | value | One CT-package attribute (e.g. `"Term CCODE"`) over every package the rows name — a list for the dataset; SKIPPED without a usable Library answer |
| [`library_available()`](functions.md#library_available) | boolean | `RulePackageLoader` injects this gate at load for a call that needs the CDISC Library; a not-met Precondition ⇒ SKIPPED |
| [`library_variable_code_pair_matches([x])`](functions.md#library_variable_code_pair_matches) | value | Whether the record's code and its paired decode map to the same CDISC CT term |
| [`valid_codelist_dates(ct_package_types=)`](functions.md#valid_codelist_dates) | value | The published CT-package dates of the applicable package families, as a list |
| [`valid_external_dictionary_code(name, external_dictionary_type=, dictionary_term_type=)`](functions.md#valid_external_dictionary_code) | boolean | Dictionary code validity per record; one function with `_value` under two names |
| [`valid_external_dictionary_code_term_pair(name, external_dictionary_term_variable, external_dictionary_type=, case_sensitive=)`](functions.md#valid_external_dictionary_code_term_pair) | boolean | Code↔decode pairing against an external dictionary, both columns as references; case-sensitive by default, `case_sensitive=false` folds both |
| [`valid_external_dictionary_hierarchy(name, dictionary_parent, external_dictionary_type=, case_sensitive=)`](functions.md#valid_external_dictionary_hierarchy) | boolean | Hierarchy ancestor test against an external dictionary, both columns as references; case-sensitive by default, `case_sensitive=false` folds both |
| [`valid_external_dictionary_value(name, external_dictionary_type=, dictionary_term_type=)`](functions.md#valid_external_dictionary_value) | boolean | Dictionary term validity per record; the rule SKIPs when the dictionary is not loaded; preferred case by default, `case_sensitive=false` accepts any case |

### Supplemental qualifiers and trial summary

| function | returns | what it does |
|---|---|---|
| [`read_value(name, domain=, filter=, mode=, group=, keep_missings=)`](functions.md#read_value) | value | One value of another dataset: the `name` column of `domain`, from the rows `filter` selects, by `mode` (`FIRST` · `ONLY` · `MIN` · `MAX`) — per group of `domain` with `group=`; no match ⇒ the column's type default |

### Values and references

| function | returns | what it does |
|---|---|---|
| [`char(x)`](functions.md#char) | value | The first character of the value as its numeric code point |
| [`colref(x)`](functions.md#colref) | value | The value of the column *named by* `x` — bare or `DS.X`, typed; a list of names gives a list of values |
| [`values(columns)`](functions.md#values) | value | The per-row list of the named columns' values, in the written order — `values([X, Y])`; a `$`-binding of names splices as in `group=` |
| [`date(x)`](functions.md#date) | date | The value as an ISO-8601 date, partial precision kept; makes a comparison temporal |
| [`find_vars(entry)`](functions.md#find_vars) | list | The names of the columns one `Requirements.Variables` entry selects (template, glob, `DS./regex/`, literal) |
| [`lpad(x, width, fill)`](functions.md#lpad) | text | `x` left-padded to `width`, never truncated |
| [`num(x)`](functions.md#num) | number | The value as a number |
| [`printf(format, args…)`](functions.md#printf) | text | Root-locale formatting: `%s %d %f %e %x %%` |
| [`str(x)`](functions.md#str) | text | The value as text |
| [`time(x)`](functions.md#time) | time | The value as an ISO-8601 time of day, partial precision kept; makes a comparison temporal |
| [`value()`](functions.md#value) | value | The value of the variable currently being iterated, for this row |
| [`varname()`](functions.md#varname) | value | The name of the variable currently being iterated |

### The metadata accessors take a level

`ds_*` (dataset scope) and `var_*` (variable scope) read metadata, and each takes the **level** to
read it from:

| level | the metadata comes from |
|---|---|
| `DATA` | the delivered dataset itself |
| `DEFINE` | the sponsor's Define-XML |
| `LIBRARY` | the CDISC Library |

```yaml
expression: 'var_label("DEFINE") != var_label("LIBRARY")'
```

⛔ **Not every attribute exists at every level, and asking for one that does not is a load error.**
`var_role` has no `DATA` reading; `var_format` has no `LIBRARY` reading. That is a support matrix,
not a runtime miss — the rule fails to load rather than silently answering missing.

**With no level an accessor reads `DATA`** — `ds_name()` is `ds_name("DATA")`, `var_label()` the
`DATA` label of the variable being iterated, `var_label("AETERM")` that of `AETERM`. In the
one-argument form a string is the variable's **name** unless it is a level (`"DATA"`, `"DEFINE"`,
`"LIBRARY"`); the name is always a string — `var_label(AETERM)` fails to load, naming
`var_label("AETERM")`. An attribute with no `DATA` reading must name its level (`var_role()` fails to
load, naming `var_role("DEFINE")` and `var_role("LIBRARY")`), and a `ds_*` accessor given a dataset
name needs the level too (`ds_name("DM")` fails to load, naming `ds_name("DM", "DATA")`).

## Builtin operands

A builtin operand is a bare name you use where a value is expected, with **no call and no
arguments**:

```yaml
expression: 'variable_label != library_variable_label'
```

**42 of the 64 are a [metadata accessor](#the-metadata-accessors-take-a-level) with the level
baked into the name** — `<level-prefix><scope>_<attribute>`, with no prefix for `DATA`, `define_`
for Define-XML and `library_` for the CDISC Library. Their rows below name the equivalent call
rather than repeating its description.

> **Prefer the accessor call when the level is the point.** `var_label("DEFINE")` says which level
> it reads and changes by editing one argument; `define_variable_label` bakes the answer into the
> identifier. The bare form reads better when the level never varies.

The other 22 are their own things: the `define_vlm_*` family (value-level metadata, a scope of its
own), the per-row value accessors, two list variants, the `*_decode_matches` /
`*_code_pair_matches` pair — which are also callable — plus `dataset_metadata` and `record_count`.

| operand | type | what it is |
|---|---|---|
| `dataset_class` | string | The `ds_class` accessor at the `DATA` level — `ds_class("DATA")` |
| `dataset_domain` | string | The `ds_domain` accessor at the `DATA` level — `ds_domain("DATA")` |
| `dataset_label` | string | The `ds_label` accessor at the `DATA` level — `ds_label("DATA")` |
| `dataset_metadata` | value | The current dataset's metadata record |
| `dataset_name` | string | The `ds_name` accessor at the `DATA` level — `ds_name("DATA")` |
| `define_dataset_class` | string | The `ds_class` accessor at the `DEFINE` level — `ds_class("DEFINE")` |
| `define_dataset_label` | string | The `ds_label` accessor at the `DEFINE` level — `ds_label("DEFINE")` |
| `define_dataset_name` | string | The `ds_name` accessor at the `DEFINE` level — `ds_name("DEFINE")` |
| `define_variable_ccode` | string | The `var_ccode` accessor at the `DEFINE` level — `var_ccode("DEFINE")` |
| `define_variable_codelist` | string | The `var_codelist` accessor at the `DEFINE` level — `var_codelist("DEFINE")` |
| `define_variable_codelist_coded_codes` | string | The `var_codelist_coded_codes` accessor at the `DEFINE` level — `var_codelist_coded_codes("DEFINE")` |
| `define_variable_codelist_coded_values` | string | The `var_codelist_coded_values` accessor at the `DEFINE` level — `var_codelist_coded_values("DEFINE")` |
| `define_variable_codelist_extended_values` | string | The `var_codelist_extended_values` accessor at the `DEFINE` level — `var_codelist_extended_values("DEFINE")` |
| `define_variable_core` | string | The `var_core` accessor at the `DEFINE` level — `var_core("DEFINE")` |
| `define_variable_data_type` | value | The `var_type` accessor at the `DEFINE` level — `var_type("DEFINE")` |
| `define_variable_decode_matches` | value | Operand form of the §5 decode matcher |
| `define_variable_external_dictionary` | string | The `var_external_dictionary` accessor at the `DEFINE` level — `var_external_dictionary("DEFINE")` |
| `define_variable_external_dictionary_version` | string | The `var_external_dictionary_version` accessor at the `DEFINE` level — `var_external_dictionary_version("DEFINE")` |
| `define_variable_has_codelist` | boolean | The `var_has_codelist` accessor at the `DEFINE` level — `var_has_codelist("DEFINE")` |
| `define_variable_has_comment` | boolean | The `var_has_comment` accessor at the `DEFINE` level — `var_has_comment("DEFINE")` |
| `define_variable_has_method` | boolean | The `var_has_method` accessor at the `DEFINE` level — `var_has_method("DEFINE")` |
| `define_variable_label` | string | The `var_label` accessor at the `DEFINE` level — `var_label("DEFINE")` |
| `define_variable_length` | number | The `var_length` accessor at the `DEFINE` level — `var_length("DEFINE")` |
| `define_variable_name` | string | The `var_name` accessor at the `DEFINE` level — `var_name("DEFINE")` |
| `define_variable_ordinal` | number | The `var_ordinal` accessor at the `DEFINE` level — `var_ordinal("DEFINE")` |
| `define_variable_origin_type` | string | The `var_origin_type` accessor at the `DEFINE` level — `var_origin_type("DEFINE")` |
| `define_variable_role` | string | The `var_role` accessor at the `DEFINE` level — `var_role("DEFINE")` |
| `define_vlm_codelist_coded_codes` | value | The `vlm_codelist_coded_codes` value-level match, without a call |
| `define_vlm_codelist_coded_values` | value | The `vlm_codelist_coded_values` value-level match, without a call |
| `define_vlm_codelist_extensible` | value | The `vlm_codelist_extensible` value-level match, without a call |
| `define_vlm_data_type` | value | Operand form of `vlm_data_type` (§7) |
| `define_vlm_decode_matches` | value | The `vlm_decode_matches` value-level match, without a call |
| `define_vlm_has_codelist` | value | The `vlm_has_codelist` value-level match, without a call |
| `define_vlm_length` | value | The `vlm_length` value-level match, without a call |
| `define_vlm_mandatory` | value | The `vlm_mandatory` value-level match, without a call |
| `define_vlm_type_conforms` | value | The `vlm_type_conforms` value-level match, without a call |
| `library_dataset_class` | string | The `ds_class` accessor at the `LIBRARY` level — `ds_class("LIBRARY")` |
| `library_dataset_label` | string | The `ds_label` accessor at the `LIBRARY` level — `ds_label("LIBRARY")` |
| `library_dataset_name` | string | The `ds_name` accessor at the `LIBRARY` level — `ds_name("LIBRARY")` |
| `library_variable_ccode` | string | The `var_ccode` accessor at the `LIBRARY` level — `var_ccode("LIBRARY")` |
| `library_variable_code_pair_matches` | value | Operand form of the §7 CT pair predicate |
| `library_variable_codelist` | string | The `var_codelist` accessor at the `LIBRARY` level — `var_codelist("LIBRARY")` |
| `library_variable_codelist_coded_codes` | string | The `var_codelist_coded_codes` accessor at the `LIBRARY` level — `var_codelist_coded_codes("LIBRARY")` |
| `library_variable_codelist_coded_values` | string | The `var_codelist_coded_values` accessor at the `LIBRARY` level — `var_codelist_coded_values("LIBRARY")` |
| `library_variable_codelist_extensible` | boolean | The `var_codelist_extensible` accessor at the `LIBRARY` level — `var_codelist_extensible("LIBRARY")` |
| `library_variable_core` | string | The `var_core` accessor at the `LIBRARY` level — `var_core("LIBRARY")` |
| `library_variable_data_type` | value | The `var_type` accessor at the `LIBRARY` level — `var_type("LIBRARY")` |
| `library_variable_data_type_values` | value | The data types the CDISC Library allows for the variable, as a list |
| `library_variable_label` | string | The `var_label` accessor at the `LIBRARY` level — `var_label("LIBRARY")` |
| `library_variable_label_values` | value | The labels the CDISC Library allows for the variable, as a list |
| `library_variable_length` | number | The `var_length` accessor at the `LIBRARY` level — `var_length("LIBRARY")` |
| `library_variable_name` | string | The `var_name` accessor at the `LIBRARY` level — `var_name("LIBRARY")` |
| `library_variable_ordinal` | number | The `var_ordinal` accessor at the `LIBRARY` level — `var_ordinal("LIBRARY")` |
| `library_variable_role` | string | The `var_role` accessor at the `LIBRARY` level — `var_role("LIBRARY")` |
| `record_count` | number | The number of rows in the dataset, with no call |
| `variable_data_type` | value | The `var_type` accessor at the `DATA` level — `var_type("DATA")` |
| `variable_format` | string | The `var_format` accessor at the `DATA` level — `var_format("DATA")` |
| `variable_label` | string | The `var_label` accessor at the `DATA` level — `var_label("DATA")` |
| `variable_length` | number | The `var_length` accessor at the `DATA` level — `var_length("DATA")` |
| `variable_max_size` | value | The longest value stored in the current variable |
| `variable_name` | string | The current variable's name — the operand form of `varname()` |
| `variable_size` | value | The declared length of the current variable |
| `variable_value` | value | The current variable's value for this row — the operand form of `value()` |
| `variable_value_length` | value | The length of the current variable's value for this row |

## Arguments

**Every parameter can be given positionally or by name.** There are no positional-only and no
keyword-only parameters — one binder serves every function.

```yaml
expression: 'substring(AETERM, 1, 3)'                  # all positional
expression: 'substring(AETERM, start=1, length=3)'     # name the optional ones
expression: 'substring(text=AETERM, start=1, length=3)' # all named
```

Four rules govern a call:

1. **Positional arguments bind in declaration order** — the first argument fills the first
   parameter, and so on.
2. **Once a named argument appears, no positional may follow.** `f(a, x=1, b)` is rejected when
   the expression is parsed.
3. **A parameter filled positionally may not also be named.** `substring(AETERM, text=X)` is an
   error, and a distinct one from rule 2.
4. **An optional parameter you leave out takes its documented default.** Omitting it is not the
   same as passing a missing value.

Three shapes are errors, each with its own message: too many positional arguments, a required
parameter left unbound, and an argument name the function does not declare.

> ⛔ **An unrecognised argument name is an error, deliberately.** A silently-dropped parameter is
> the worst failure this language can have: the rule runs, computes something other than what you
> wrote, and under-reports with no signal.

Two tokens look like functions inside an argument but are not: **`asc(…)` and `desc(…)`** inside
`by=[…]` are sort-key descriptors, read structurally by the function that takes them.

**A boolean handed to a function needs its own parentheses** — `filter=(TSPARMCD == "SPECIES")` —
with one exception: `joined(…)` takes a condition by declaration, so its argument is written bare,
`any(joined(EPOCH == SE.EPOCH and not empty(SE.SEENDTC)))`.

## What is not in the language

- **No assignment.** A name is given a value by a [binding](../authoring/bindings.md), not inside
  an expression.
- **No `if` / `then` / `else`.** A conditional requirement is written as a conjunction:
  *"the condition holds **and** the requirement fails"*.
- **No null.** A value is a value or a [missing value](../semantics/values-and-missing.md), never
  null, and every function result is one or the other.
- **No three-valued logic.** Nothing evaluates to unknown.
- **No `operator:` / `name:` / `value:` form.** A rule using it fails to load.

---

**Next:** [Values and missing values](../semantics/values-and-missing.md) — how every operator
above behaves when the data is not there.
