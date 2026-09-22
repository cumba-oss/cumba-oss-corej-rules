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
- token: "&VAR"
  over: "all_variables"
```

**`token`** is the placeholder the check uses. **`over`** names where the set comes from:

| `over` | the set |
|---|---|
| `all_variables` | **every column of the dataset under validation** |
| `all_numeric_variables` | every numeric column of it |
| `all_character_variables` | every character column of it |
| `shared_variables` | variables shared across the datasets in play |
| `domain_from_variable` | the domains named by a variable's values |

**`with`**, **`pattern`** and **`known_domain_only`** narrow it further — an explicit list, a name
pattern, and a restriction to domains the standard knows.

### The token substitutes inside string literals too

This is what makes the `all_*` sources useful: the token is replaced in **quoted arguments**, not
only in bare names, so an expanded name reaches a function that takes a *name* rather than a
value:

```yaml
Expansion:
- token: "&VAR"
  over: "all_character_variables"
Check:
  expression: 'var_length("&VAR") > 200'
```

⚠ Substitution is scoped to the token, never a global text replace — a string literal that merely
*contains* the token's characters is not rewritten.

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
