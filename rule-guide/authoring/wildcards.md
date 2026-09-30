# Wildcards and expansion

<!-- Grounded: Rule.java wildcards/wildcardExclude/wildcardPairCatalogue javadoc (token grammars
     y=(\d+) unbounded, xx/zz=(\d{2}), w=(\d); unknown token key = load error),
     WildcardFilter.java (inclusive min/max), ExpansionDirective.java + ExpansionSource.java
     (token/over/with/pattern/known_domain_only; over ∈ shared_variables, domain_from_variable). -->

One rule often has to say the same thing about many variables — every `--DTC` in a domain, every
`AVALy` index, every `*N` / `*C` pair. Wildcards are how a single rule reaches all of them.

## The tokens

| token | matches | grammar |
|---|---|---|
| `--` | the domain prefix — `AESTDTC`, `CESTDTC`, … | the dataset's own domain |
| `y` | an index of **any** length — `AVAL1`, `AVAL12` | one or more digits |
| `xx` | a two-digit index | exactly two digits |
| `zz` | a two-digit index | exactly two digits |
| `w` | a one-digit index | exactly one digit |
| `*` | a root name, in pairing templates — `*N` / `*C` | any root |
| `**` | a root name in the middle of a name — `**DECOD`, `**TERM` | any root |

`--STDTC` in a rule running on `AE` means `AESTDTC`. The rule is written once and expanded per
dataset.

## `wildcards` — bounding an index

```yaml
wildcards:
  y:
    min: 1
    max: 99
```

Bounds are **inclusive**, and either may be omitted for an open end. A candidate whose captured
index falls outside the range is dropped during expansion.

How much this actually constrains depends on the token's own grammar, and the difference matters:

- **`y` is an unbounded digit run**, so bounds on it do real work. `{y: {min: 1, max: 9}}`
  genuinely rejects `B10IND`.
- **`xx`, `zz` and `w` are already width-bounded**, so `{min: 1, max: 99}` on `xx` excludes only
  the zero index.

The one shape that constrains beyond the grammar is a bare lower bound — `{xx: {min: 2}}`, the
*"where xx > 01"* criterion the token vocabulary cannot otherwise express.

> **Write the bound the source states, even when it is redundant.** A `{min: 1, max: 99}` on `xx`
> filters almost nothing, and it records what the sheet said — which is what a reviewer is
> comparing against.

> ⛔ **A token key that appears in no wildcard in the rule is a load error.** If you bound `y` and
> the rule's variables use `xx`, the rule fails to load rather than quietly bounding nothing.

## A presence template needs a positive anchor

A wildcard inside a presence test — `var_exists("TRTxxP")` — does not ask *"is any `TRTxxP`
present?"*. It makes the rule a **template**: the engine runs it once per matching column
(`TRT01P`, `TRT02P`, …), and on a dataset where **no** column matches it does not run the rule at
all — it reports it **skipped**.

That is fatal when **every** presence template is negated. `not var_exists("TRTxxP")` is true
exactly when no `TRTxxP` exists — the one dataset the engine skips — and false whenever one does.
Such a rule can never report anything.

### The fix: `Requirements.Variables.None`

A negated template asks *"is no member of the family present?"*, and that is precisely what an
entry in [`Requirements.Variables.None`](requirements.md#none--no-entry-may-be-present) answers:
the rule is skipped when **any** matching column is present, and runs when none is. Move the
negated templates there — the template itself is the entry — and keep only the literal names in
the Check:

```yaml
Requirements:
  Variables:
    None: ["TRTxxP", "TRTxxA", "TRTPGy"]
Check:
  expression: 'not var_exists("TRTP") and not var_exists("TRTA")'
```

On a dataset carrying `TRT02P` the rule is skipped — a treatment variable exists, so there is
nothing to report. On a dataset carrying none of them it runs and reports. With no template left
in the Check, remove the rule's `wildcards` block as well — a bound on a token no name uses is a
load error (above).

<!-- Owner ruling 2026-09-28 ("Case-insensitive everywhere"; "if it is to be revised in the
     future, than it must be revised everywhere"), PLAN-case-insensitive-templates, register entry
     CIT §1. Until then a template entry was the one case-sensitive Requirements entry, and this page
     advised a /regex/ per template for that reason alone. -->
> ⭐ **Write the template, not a `/regex/`.** Column names match **ignoring case on every
> surface**, templates included: `"TRTxxP"` matches `trt01p` exactly as the Check's `var_exists`,
> a glob and a `/regex/` entry do, and the expansion reads the column under its actual name. A
> `/regex/` written only to gain case-insensitivity says less than the template and hides the
> family it stands for.
>
> ⛔ **This is a decision, not a default, and it is revised everywhere or nowhere.** Should the
> case rule ever change, it changes for every surface at once — templates, literals, globs,
> `/regex/` entries, the Check's column references and its template expansion — never for one of
> them alone (rulings register entry `CIT §1`). A column name a rule handles as a **value** —
> `varname()`, the list `get_column_order_from_dataset()` returns — is a string and is folded in
> the **rule** (`upper(…)`, register entry `CIT §3`), never by the engine; a `QNAM` cell is a value
> and compares exactly. That column names *should* be upper case is reported once, by the dedicated naming
> rules, not by every rule skipping or misfiring.

> ⚠ **The move is exact only when every negated template is a top-level conjunct** of a Check
> with a single level, as above. Under an `or` it is not: `None` skips the whole rule, so
> `not var_exists("TRTxxP") or AVAL > 100` would lose its `AVAL` branch.

> ⚠ **Naming the first instance instead (`TRT01P` for `TRTxxP`) is exact only where the IG
> guarantees the family starts at `01` / `1` in that dataset class** — ADSL. A BDS dataset copies
> ADSL variables **selectively**, so a period-2 dataset may carry `TRT02P` or `TR02PG1` without
> the first member, and the first-instance form would report it falsely.

### When a positive template is enough

A rule that really tests one member **per index** keeps its template and needs a **positive**
presence test beside the negated one:

```yaml
Check:
  expression: 'var_exists("TRTxxAN") and not var_exists("TRTxxA")'
```

With no `TRTxxAN` the condition is false anyway, so the skip loses nothing; with `TRT01AN` present
the rule runs for `xx = 01` and asks about `TRT01A`. The positive test is the rule's **anchor**.

> ⛔ **`WildcardUniversalAbsenceLintTest` fails the corpus build** on any rule whose presence
> templates are **all** negated — through `not`, `var_not_exists` and `not (… or …)` alike.
> `CDISC-AD0581`, which tests fourteen treatment variables for absence, spelled eight of them as
> templates and was skipped on exactly the datasets it exists to report.
>
> ⚠ **That is all it checks.** One positive presence template clears a rule, and two shapes still
> lose findings past it: a marker bound **only** by a negated template while another marker has
> the positive anchor, and a positive anchor under `or` beside a branch with no template
> (`var_exists("TRTxxP") or X == "Y"` is skipped on zero matches, and the `X` branch with it).
> Review those by hand.

## Pairing templates: `wildcardExclude` and `wildcardPairCatalogue`

Some ADaM rules are about **pairs** of variables that share a root — a numeric `*N` and its
character `*C`. A bare `*` template expands by finding those pairs.

**`wildcardExclude`** drops roots that must not be treated as a pairing partner:

```yaml
wildcardExclude: ["TRTPN", "*DTC"]
```

Each entry is a literal name or a pattern. Any secondary column matching one is excluded, so its
pair never forms. These lists usually come straight from an *"Exceptions:"* column on the source
sheet — copy them verbatim.

**`wildcardPairCatalogue`** restricts expansion to pairs the CDISC standards actually define:

```yaml
wildcardPairCatalogue: true
```

Use it when the requirement says to look *explicitly at variable pairs defined in the CDISC
standard documents*, rather than at whatever pairs a sponsor's data happens to contain.

## `Expansion` — repeating the check over a set of variables

Where a wildcard expands a *name*, `Expansion` repeats the whole check over a *set*:

```yaml
Expansion:
- token: "&VAR&"
  over: "all_variables"
```

**`token`** is the placeholder the check uses, written `&NAME&` — an `&`, upper-case letters and
digits starting with a letter, and a **closing `&`**. In the `Check` it is written bare, like any
column name: `not empty(&VAR&)`, `ADSL.&VAR&`. **`over`** names where the set comes from:

| `over` | the set |
|---|---|
| `all_variables` | **every column of the dataset under validation** |
| `all_numeric_variables` | every numeric column of it |
| `all_character_variables` | every character column of it |
| `shared_variables` | the variables the dataset under validation shares **by name** with the dataset named by `with`, minus the rule's own same-named join keys for that dataset (a merged key compared with itself can never differ) |
| `domain_from_variable` | the text at the token's position in each column name matching `pattern` — the domain code of a `<XX>SEQ`-style reference |

**`with`**, **`pattern`** and **`known_domain_only`** belong to those last two — the foreign
dataset, the column-name shape (which must contain the token exactly once), and a restriction to
domains the standard knows. The three `all_*` sources take none of them.

### Why the closing `&`

The closing `&` is where the token **ends**, so a token can be glued to the text around it. The
shipped `CDISC-AD0898` checks a parent `<XX>SEQ` column in the parent domain:

```yaml
Expansion:
- token: "&DOM&"
  over: "domain_from_variable"
  pattern: "&DOM&SEQ"
  known_domain_only: true
Check:
  expression: var_exists(&DOM&.&DOM&SEQ) and empty(&DOM&.&DOM&SEQ)
```

`&DOM&SEQ` is the token `&DOM&` followed by the literal `SEQ`; for `&DOM&` = `AE` the rule becomes
`var_exists(AE.AESEQ) and empty(AE.AESEQ)`. Without the delimiter the same text, `&DOMSEQ`, reads
like a token of its own — which it never was — and the name could only be written inside
backticks. That undelimited spelling is retired: in a rule declaring `Expansion:`, an
unterminated `&DOM` is a load error, and on any rule an `&X&` the rule does not declare is one
too. Put a space between a token and the operator `&&` (`&A& && &B&`); `&A&&B&` does not lex.

The gate reads every surface the expansion rewrites — a level's own `Message`, the `Description`
and `Outcome.Message` included — so in a rule declaring `Expansion:` any `&`+letter that does not
start a complete declared token is refused in that prose too (`R&D`, `&lt;`: write `and`). A
declared token may not appear in a `Scope` name list (`Domains`, `Datasets`, `Classes`,
`Data_Structures`, `Subclasses`), because scope is matched before the rule expands, nor inside a
`/regex/` literal in a `Check` level, the `Precondition` or a `Bindings` expression, which is never
substituted (a `Match_Datasets` `Filter` is substituted as text) — both are load errors. An expansion the engine has to drop after binding (a token
that survived substitution, a join key the dataset does not carry) is reported as a SKIPPED row
naming the dropped expansion, whether every expansion was dropped or only some.

### The token substitutes inside string literals too

This is what makes the `all_*` sources useful: the token is replaced in **quoted arguments**, not
only in bare names, so an expanded name reaches a function that takes a *name* rather than a
value:

```yaml
Expansion:
- token: "&VAR&"
  over: "all_character_variables"
Check:
  expression: 'var_length("&VAR&") > 200'
```

⚠ **A string literal that contains the token IS rewritten** — that is exactly what makes
`var_length("&VAR&")` work: every occurrence of a *declared* token is replaced, in names and in
quoted text alike. Substitution is still scoped to the token, never a global text replace: only the
complete `&NAME&` the rule declares is replaced, and the rest of the literal is left alone. A
metadata accessor's name operand must stay a string literal: the bare `&VAR&` is a column
*reference*, so `var_length(&VAR&)` means something else and does not compile.

### ⛔ `over: all_*` and the variable cursor are mutually exclusive

A rule can loop over variables in two ways, and it may use **one**:

| | |
|---|---|
| the **cursor** | the check reads `varname()` / `value()` and the engine loops per column |
| **`over: all_*`** | the rule is expanded into one rule per column before it runs |

A rule using both is **rejected at load** — a block, not a warning and not a precedence rule.
Choose the one that fits: the cursor when the check is about *the current variable's value*,
`over: all_*` when you want a separate rule, and a separate finding, per column.

> ⛔ A rule with `over: all_*` whose `Check` has no native expression form is also rejected at
> load, because the exclusivity test cannot be decided for it.

A [type suffix](requirements.md#demanding-a-type-as-well-as-presence) on a requirement survives
expansion — `TRTxxP:Num` becomes `TRT01P:Num`, tag intact.

> ⚠ **No rule in the corpus uses `Expansion` over the `all_*` sources yet.** The mechanism ships
> ahead of its first carrier, so you would be the first reader of anything it reports. Check first
> whether a wildcard expresses the same thing — a `--` or an indexed token is understood by every
> reviewer, and an expansion directive is not.

## Choosing between them

| you want | use |
|---|---|
| the same check on `--STDTC` in every domain | `--` |
| the same check on an indexed family, `AVAL1`…`AVAL99` | `y` with `wildcards` bounds |
| the same check on each `*N` / `*C` pair | a `*` pairing template |
| the check repeated over a computed set of variables | `Expansion` |

---

**Next:** [Outcome, severity and sensitivity](outcome.md) — what the rule reports.
