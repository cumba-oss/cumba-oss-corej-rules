# Requirements

<!-- Grounded: Requirements.java (Variables/Datasets authored; Library/Define/Dictionary marked
     "Derived"), VariableRequirement.java (All/Any/None/All_Or_None semantics, entry vocabulary,
     the M2-D24 / M3-F.1 owner ruling, None has zero corpus carriers), AnyGroupsJson.java (the
     full parse table shared by Any and All_Or_None, gate R4 arms), ScopeMatcher.java
     (describeAllOrNoneGroup: presence per entry, pattern entries compared resolved),
     Rule.java (Variable_Universe default DATA, skipIfLibraryDefined). -->

`Requirements` says what must exist **before the rule is answerable at all**.

If a requirement is not met the rule is **skipped** for that dataset. A skip is its own outcome —
not a pass, not a finding, not an error — and it is reported as such.

```yaml
Requirements:
  Variables:
    All: ["AESTDTC"]
```

Where [`Scope`](scope.md) answers *"is this rule about this dataset?"*, `Requirements` answers
*"can this rule say anything useful about it?"*

## ⛔ The one rule that matters more than the syntax

**A requirement is for a thing whose absence means there is nothing to check — never for a thing
whose absence *is* the defect.**

A rule that reports a missing `AEENDTC` must **not** require `AEENDTC`. Requiring it skips the
rule on exactly the datasets it exists to judge, and a skip looks like a clean result.

```yaml
# ⛔ WRONG — this rule can never fire
Requirements:
  Variables:
    All: ["AEENDTC"]
Check:
  expression: 'empty(AEENDTC)'
```

Nothing errors. Nothing warns. The rule simply never reports, on every study, forever. This is
the most expensive mistake in this chapter, and it is invisible from the outside.

Require what the check needs in order to **ask** its question. Never what the check is asking
*about*.

## `Variables` — four facets, ANDed

```yaml
Requirements:
  Variables:
    All:  ["AESTDTC", "AEENDTC"]
    Any:  ["AEDECOD", "AETERM"]
    None: ["AEENDY"]
    All_Or_None: [["VISITDY", "TV.VISITDY"]]
```

All four, and `Scope`, must hold together.

### `All` — every entry must be present

The common case. Use it for the columns the check reads in order to function.

### `Any` — an AND of ORs

`Any` is a list of **groups**. Each group is satisfied when at least one of its entries is
present, and all the groups must be satisfied.

```yaml
Any: ["AEDECOD", "AETERM"]                      # ONE group: either will do
Any: [["AEDECOD", "AETERM"], ["AESTDTC", "AESTDY"]]   # TWO groups: one from each
```

A flat list is **one** group. A list of lists is one group per inner list. That distinction is
the whole feature, and it is easy to write the wrong one by accident.

Four shapes are rejected at load rather than interpreted:

| you write | what happens |
|---|---|
| `Any: ["A", ["B","C"]]` | **load error** — flat and nested mixed in one list |
| `Any: ["A"]` | **load error** — a group needs two or more entries; a one-entry OR is an `All` |
| `Any: []` | **load error** — zero groups is unsatisfiable |
| `Any: AESEV` | **load error** — a bare scalar, not a list |

> **A group's failure names the group, not a variable.** A group is unmet only when *every*
> entry in it is absent, so no single entry is at fault — which is why the skip reason reads
> differently from an `All` miss.

### `None` — no entry may be present

The rule runs only when none of the listed columns exists. Use it for a rule whose question is
meaningless once a richer column is available.

> ⚠ **`None` has no rules using it today.** The engine supports it and it is tested, but nothing
> in the corpus exercises it, so you are the first reader of any error it produces. Prefer `All`
> or `Any` where either expresses the same thing.

### `All_Or_None` — every entry present, or none of them

`All_Or_None` is a list of **groups**, spelled exactly like `Any`. A group is satisfied when
**all** of its entries are present **or none** is. A group that is only partly present skips the
rule, and the reason names both halves:

```
Requirements.Variables.All_Or_None group 1 [VISITDY, TV.VISITDY] is only partly present — present: [TV.VISITDY], absent: [VISITDY]
```

It exists for one shape: a **join key that is optional on both sides**. `VISITDY` is permissible
in SV and in TV, so a valid study can carry it on both sides, on neither, or on one. Joined on
both, or dropped on neither, the rule is right. Carried on one side only, no row matches and every
planned visit is reported. Neither `All` (skips the "neither" study, which should run) nor leaving
the column undeclared (floods the one-sided study) can say what this facet says:

```yaml
Requirements:
  Variables:
    All: ["VISITNUM", "VISIT", "TV.VISITNUM", "TV.VISIT"]
    All_Or_None: [["VISITDY", "TV.VISITDY"]]
Match_Datasets:
- Name: "TV"
  Keys: ["VISITNUM", "VISIT", "VISITDY"]
```

Write the primary's side bare and the joined side qualified, as `All` does it. Groups may have
more than two entries; a three-entry group needs all three or none. An entry may sit in `All`
**and** in its group — the convention for a join's first key puts it in both, so `All` guarantees
the bare side and the group decides the joined side.

| study | `All: [VISITDY, TV.VISITDY]` | undeclared | `All_Or_None: [[VISITDY, TV.VISITDY]]` |
|---|---|---|---|
| both sides carry it | runs | runs | runs |
| neither side carries it | **skips** | runs | runs |
| one side only | skips | **floods** | skips |

**Wildcard entries are compared resolved.** A glob, a `/regex/` or a marker template resolves to
the set of concrete column names it matches, and an all-present group additionally needs those
sets to be equal — `[["TRTxxP", "ADSL.TRTxxP"]]` runs when no `TRTxxP` exists anywhere or when the
same ones exist on both sides, and skips when ADSL has `TRT01P` only while the primary has
`TRT01P` and `TRT02P`. A literal entry takes part by presence only, so two differently named
literals can be paired. Pattern entries in one group must therefore be the **same pattern** on
each side — `[["TRTxxP", "ADSL.TRTxxPN"]]` could never match the same names and is rejected at
load.

The load-time rules are `Any`'s — flat is one group, nested is several, two distinct entries per
group, no mixed shape, a warning on an entry repeated across groups — plus three of its own:

| you write | what happens |
|---|---|
| `None: ["VISITDY"]` and `All_Or_None: [["VISITDY", "TV.VISITDY"]]` | **load error** — `None` forbids it, so the group could only ever be all-absent |
| `All_Or_None: [["TRTxxP", "ADSL.TRTxxPN"]]` | **load error** — two pattern shapes in one group can never resolve to the same names |
| `All_Or_None: [["VISITDY:N", "TV.VISITDY"]]` | **load error** — the facet decides presence, and a column present with the wrong type is neither present nor absent |

An entry shared with `Any` is legal: "these travel together" and "one of these is present" are
independent claims. The facet is evaluated after `All`, `Any` and `None`, so a rule whose other
facets are unmet keeps reporting exactly the reason it always did.

> ⛔ **A group column with no `All`/`Any` partner is not a declaration for a filter or
> `_matched_`.** When both sides are absent the rule *runs* — that is the point — so a
> `Match_Datasets` filter or a `_matched_` test that reads a column declared **only** in a group
> reaches the bind with the column missing, and the engine reports the unresolvable binding as an
> error rather than skipping. Declaring the column in `All` or `Any` is what makes such a read
> skip — and in the first-key shape above (the key in `All` *and* in its group) it does, because
> the requirement skip runs before the bind is reached. A group alone is the wrong tool for a
> column the check must read.

> ⚠ **No shipped rule uses `All_Or_None` yet.** Whether a rule adopts it is an authoring decision
> taken per join key, not something the engine applies on its own.

## What an entry may be

The same vocabulary on all four facets:

| entry | means |
|---|---|
| `AESTDTC` | that column, literally |
| `--STDTC` | the domain-prefix placeholder, resolved to the dataset in hand |
| `SUPPAE.QNAM` | a **qualified** entry — that column in *that* dataset |
| `AE*` | a glob |
| `/^AE.*DTC$/` | a regular expression |

Any of these may carry a [type suffix](#demanding-a-type-as-well-as-presence) — `AESTDY:N`,
`DM.AGE:N`, `"/^AE.*DTC$/:C"` — on the `All` and `Any` facets, and on those two only.

A qualified entry is how you require a column in a dataset other than the one the rule is running
on — usually one you are about to [join](joins.md).

## Demanding a type as well as presence

An `All` or `Any` entry may end in a **type suffix**, so the entry demands that the column is
numeric or character as well as present:

```yaml
Requirements:
  Variables:
    All:
    - "AESTDY:N"      # must exist AND be numeric
    - "AETERM:C"      # must exist AND be character
```

Four spellings, case-insensitive, and the short and long forms mean exactly the same thing:

| suffix | demands |
|---|---|
| `:N` · `:Num` | a numeric column |
| `:C` · `:Char` | a character column |

**An unmet type is a SKIP, not a finding.** The rule stands down for that dataset and the reason
names both the demanded and the actual type. This is the whole point of the feature: without it, a
rule inapplicable to a character `--ORRES` had only one behaviour available, and it was an
**ERROR**.

> **Use it when the rule's arithmetic or comparison only makes sense on one type.** A check that
> does `num(--STRESN) > 0` is not applicable to a character `--STRESN`; saying `--STRESN:N` turns
> that from an error into an honest "not applicable".

### The type is the dataset's own

The suffix reads **`DataTableColumnMeta`'s declared type** — what the delivered data says.

> ⛔ **Never the CDISC Library's type, and never Define-XML's.** Those are different questions and
> the language names them apart: `var_type("LIBRARY")` and `var_type("DEFINE")`. If your rule is
> about what was *promised* rather than what was *delivered*, the suffix is the wrong tool.

**A type the engine cannot classify does not block.** The entry fails only on a type that
positively contradicts it, so an unclassifiable column is still met.

Two cases resolve the type elsewhere:

- **A qualified entry** — `DM.AGE:N` — takes the type from the **qualifier's** dataset. For a
  split domain, it is the type every member carrying the column agrees on; where the members
  disagree, the entry makes no type demand rather than guessing.
- **A variable delivered through the SUPP-QNAM pivot** takes that `SUPPxx` table's **`QVAL`**
  type, because that is how its values actually arrive.

### ⛔ `None` and `All_Or_None` do not accept a suffix

```yaml
None: ["AESTDY:N"]                       # load error
All_Or_None: [["AESTDY:N", "DM.AGE"]]    # load error
```

In `None` it would have to mean *"no numeric variable of that name may be present"* — which a
variable of the **other** type also satisfies, making the requirement meaningless. In
`All_Or_None` the facet decides presence, and a column that is present with the wrong type is
neither present nor absent. The loader rejects both by name.

### ⚠ Two entries differing only by suffix are one entry

```yaml
Any: ["AVAL:N", "AVAL:C"]     # load error
```

An `Any` group needs two or more **distinct** entries, and the suffix is folded when distinctness
is counted — so this is the degenerate one-column group in disguise. Read it aloud and it is just
*"AVAL is present"*, which is an `All` entry.

### Malformed suffixes are rejected, not guessed

`X:`, `X:Z`, `X:NN`, `X:Numeric`, `X:Character` and `A:B:C` are all **load errors**. Only a valid
trailing tag is stripped, so a colon-bearing entry is never silently read as a column named `X`.

The tag is removed before the entry's regex test and before the qualifier split, so it composes
with every other entry form — `"/^AE.*DTC$/:C"` and `"TRTxxPN:N"` both work.

### An entry without a suffix is unchanged

Adding the feature changed nothing for entries that do not use it — same matching, same skip
reasons, same message text.

## `Datasets` — a whole dataset must be present

```yaml
Requirements:
  Datasets: ["DM"]
```

The named datasets must be present in the run. Presence is **widened**: a split domain counts as
present, so requiring `LB` is satisfied by `LB1` and `LB2` without naming them.

Use it when the check reaches into another dataset — a [join](joins.md), or a cross-dataset
reference — and cannot answer anything without it.

## `Variable_Universe` — which variables the rule iterates

```yaml
Variable_Universe: "Define"
```

`Data` (the default when the key is absent) or `Define`.

It decides which set of variables a rule iterates when it walks variables rather than rows:

- **`Data`** — the columns the dataset actually has. The right choice when the rule is about
  delivered data.
- **`Define`** — the variables Define-XML *declares*. The right choice when the rule is about
  what was promised: a variable declared and not delivered is visible to a `Define` rule and
  invisible to a `Data` one.

> **Choose `Define` only when the mismatch between promise and delivery is the point.** A rule
> that checks values cannot use it — Define-XML declares variables, not data.

## `skipIfLibraryDefined`

```yaml
skipIfLibraryDefined: true
```

When `true`, the rule stands down if the CDISC Library already enforces the same constraint, so a
sponsor does not receive the same finding twice from two authorities.

Set it when your rule restates something the Library defines. Leave it absent otherwise.

## Not yours to write

```
Requirements.Library      Requirements.Define      Requirements.Dictionary
```

These three record whether the rule needs a CDISC Library provider, a sponsor Define-XML overlay,
or an external dictionary. **The loader derives them** from what your check actually references.

Writing them by hand is at best redundant and at worst a claim that contradicts what the rule
does. State a dependency by referencing the thing, not by declaring the requirement.

---

**Next:** [The check](the-check.md) — the expression itself.
