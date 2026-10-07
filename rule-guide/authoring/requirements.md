# Requirements

<!-- Grounded: Requirements.java (Variables/Datasets authored; Library/Define/Dictionary marked
     "Derived"), VariableRequirement.java (All/Any/None/All_Or_None semantics, entry vocabulary,
     the M2-D24 / M3-F.1 owner ruling, None is rare in the corpus), AnyGroupsJson.java (the
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

## Which columns to require

<!-- Grounded: the decision procedure of the authoring guidelines §4.4 (2026-10-07; owner decisions
     D1-D5 2026-10-05 and N1 2026-10-07), restated here in author language; the guide does not
     link out. MFCA Q6 + the two 2026-10-07 trigger rulings (Message_Folded); M2-D24 (the boundary);
     D-6 ("only skip on silence, never reduce number of findings"); R-5.25 (split a top-level or). -->

The rule above says what never to require. This section says how to decide everything else. Walk it
in order, column by column.

**First, name what each column is *for* in the rule.** Every column a check reads plays exactly one
of five parts:

| part | the column … | example |
|---|---|---|
| **subject** | is what the finding is about | `--STRESU` in *"`--STRESU` is populated when numeric results are"* |
| **trigger** | selects the records the obligation applies to | `AESER` in *"when `AESER` is `Y`, …"* |
| **exemption** | excuses the record when it is populated | `--STAT` in *"… unless the test is marked NOT DONE"* |
| **operand** | is compared with another column | both sides of `AGE != DM.AGE` |
| **key** | groups, or is a member of a unique set — a join's keys are declared with the join (below) | `USUBJID` in `is_unique_set([USUBJID, --SEQ])` |

That is a reading of the rule's intent, not of its syntax, and it is the one judgement no tool makes
for you.

**Then decide, part by part:**

1. **A subject whose absence is the defect is not required** (the rule above). Read it
   **ungated** and give the rule [`Message_Folded`](outcome.md#message_folded) — an absent or
   entirely blank subject then reports **once per dataset** instead of once per record. Two cases
   differ: where the source says *"X present in dataset"*, absence means there is nothing to check
   and the subject IS required; and where another rule already reports the absence once (a
   presence rule over the same column, in every package this rule ships into), require it and let
   that rule report.
2. **A subject the fold cannot reach** — tested by `!=`, `not in`, `!~`, a negated function, or
   through a joined dataset (`DM.X`) — is the one exception to point 1: `Message_Folded` folds only
   an `empty`-family test over the rule's own column. Require the subject on the rule, and report its
   absence with a small companion rule of `Sensitivity: "Dataset"` that fires once per dataset when
   the column is missing and the rest of the condition holds.
3. **A trigger or an exemption** whose absence the rule should read as "every record applies" or
   "nothing is excused" is **not required**. On a rule with `Message_Folded`, list it in
   [`Keep_Row_Findings`](outcome.md#keep_row_findings), so its absence keeps the per-record findings.
4. **A column read only by positive tests of the top-level `and`** — `AESEV == "SEVERE" and …` —
   belongs in `All`: the rule cannot fire without it, and a skip says so where silence would not.
   Gating it never blocks `Message_Folded`, because a positive test is not what the fold reacts to.
5. **Two columns compared with each other** (`AGE != DM.AGE`) are required on **both** sides when
   either may be absent: otherwise the absent side's constant compares unequal on every row.
6. **A top-level `or`** of conditions that could each be reported on their own is split into one rule
   per condition first. Where an `or` stays, never require a column only some of its branches read —
   that skips the branches that do not need it; columns every branch needs form one `Any` group.
7. **A grouping or unique-set key** is not required by default: an absent key simply drops out of
   the grouping, which is exact. Require it only where dropping it makes the rule fire where it
   otherwise would not — the only member of a unique set, or a merge that would manufacture
   duplicates. A **join key** is different: every keyed join declares its keys, as
   [Joins](joins.md#-declare-the-key--required-for-every-keyed-join) shows.
8. **A dataset** the check reads (`domain=`, a dotted name, `_matched_`, a join without keys) goes in
   `Datasets` when no part of the check says anything useful without it — and never when the rule
   reports that dataset's absence.

⛔ **One bound over all of it: a requirement may replace only findings that are artefacts of the
absence — never a finding the rule exists to report.** A gate is right where the column's absence
means there is nothing to check, or where the absence is reported once by something else (another
rule, a companion rule, `Message_Folded`). And after you
split a rule or rewrite its check, walk this list again for each resulting rule: the absent-column
exposure moves with the conditions.

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

> ⚠ **`None` is rare.** Measured 2026-09-25, 43 authored rules use it (e.g.
> `CDISC-SEND-0378-A`), against some 1 660 using `All`. Prefer `All` or `Any` where either
> expresses the same thing.

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
each side — `[["TRTxxP", "ADSL.TRTxxPN"]]` is not guaranteed to match the same names and is
rejected at load. Every pattern *matches* column names ignoring case (`CIT §1`), but this load-time
check compares the patterns as **written**: a glob may differ in case between the two sides; a
marker template or a `/regex/` must be spelled identically (a regex's escapes and inline flags make
its spelling significant, and a template's markers are what make it a template), and a template
with its markers upper-cased (`ADSL.TRTXXP` beside `TRTxxP`) is a literal name no dataset carries —
also rejected.

The load-time rules are `Any`'s — flat is one group, nested is several, two distinct entries per
group, no mixed shape, a warning on an entry repeated across groups — plus three of its own:

| you write | what happens |
|---|---|
| `None: ["VISITDY"]` and `All_Or_None: [["VISITDY", "TV.VISITDY"]]` | **load error** — `None` forbids it, so the group could only ever be all-absent |
| `All_Or_None: [["TRTxxP", "ADSL.TRTxxPN"]]` | **load error** — two pattern shapes in one group are not guaranteed to resolve to the same names |
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

> ⭐ **Every keyed join declares its key columns this way** — one group per key column, the first
> key also in `All` — and the corpus is held to it by `JoinKeyDeclarationLintTest`. The convention
> and its reasons are in [Joins — Declare the key](joins.md#-declare-the-key--required-for-every-keyed-join).
> The engine never applies it on its own: a rule without the declaration simply runs.

## How the four facets are evaluated

<!-- Confirmed semantics — owner, 2026-09-28 ("Requirements-template semantics (All / Any / None /
     All_Or_None, including "the rule runs when none of the variables is present") go into the SPEC
     and rule guide as confirmed semantics"), register entry CIT §2; stated from the engine's
     requirement gate as implemented, PLAN-case-insensitive-templates. -->
These are the confirmed semantics of the requirement gate (owner, 2026-09-28; rulings register
entry `CIT §2`), exactly as it evaluates a rule against a dataset:

- **Order: `All`, then `Any`, then `None`, then `All_Or_None`.** The first unmet `All` entry, the
  first unmet `Any` group, the first *present* `None` entry or the first unmet `All_Or_None` group
  decides — its reason is the skip reason, and nothing after it is inspected.
- **Every entry is an existence test, and every entry matches ignoring letter case.** A literal is
  a name lookup. A `--` entry is first resolved against the dataset's variable prefix whenever it
  has one, of any length: the domain code (`--SEQ` → `AESEQ`), nothing at all for a `SUPP` / `SQ`
  dataset (`--QNAM` → `QNAM`), or an associated-persons dataset's suffix (`APMH`: `--SEQ` →
  `MHSEQ`). Only a dataset with no domain to take a prefix from leaves it unresolved; it then
  matches nothing in `All`, `Any` and `None`, and is undecidable in `All_Or_None`. A glob, a
  `/regex/` and a [marker template](wildcards.md) such as `TRTxxP` are patterns, satisfied when
  **at least one** column matches them in full (`trt01p` and `TRT02P` both satisfy `TRTxxP`).
- **A template entry never chooses the expansion.** It asks whether *some* member of the family
  exists; it does not choose or restrict the `xx` / `y` / `w` the check expands over. The other
  way round does hold: when the check is itself a template, each expansion rewrites the entries
  (all four facets) from its own binding before they are evaluated:
  - an unqualified entry is rewritten only when it is the very template the check names — it
    becomes the column that expansion bound (`TRTxxP:N` becomes `TRT01P:N`); any other
    unqualified template entry keeps its "at least one member" meaning;
  - a qualified entry is rewritten when the check names the same template, and otherwise whenever
    the binding supplies every marker it carries: a check over `TRTxxP` with
    `All: [ADSL.TRTxxPN]` requires `ADSL.TRT02PN` for its `xx = 02` expansion — `ADSL.TRT01PN`
    does not satisfy it. A qualified entry whose markers the binding does not supply, or whose
    variable half is a glob or `/regex/`, stays as written.
- **`All`** — every entry must have at least one match, otherwise the rule is skipped, naming the
  first entry that has none.
- **`Any`** — evaluated per group, the groups ANDed; a group is met by its first satisfied entry
  and unmet only when none of its entries is satisfied — each one absent, present with the wrong
  type (a [type suffix](#demanding-a-type-as-well-as-presence)), or undecidable (the skip names
  the group and its position).
- **`None`** — the rule is skipped when any entry is present (a literal that exists, or a pattern
  that some column matches). **When none of the listed variables is present, the requirement is
  met and the rule runs.** That is why a negated family test belongs here (see
  [Wildcards](wildcards.md#a-presence-template-needs-a-positive-anchor)).
- **`All_Or_None`** — evaluated per group, the groups ANDed; a group is met when every entry is
  present or none is, and a partly present group skips the rule, naming both halves. Pattern
  entries are compared *resolved* (the same concrete names on every side); an entry that cannot
  be decided skips the group rather than counting as absent.
- **Qualified entries (`DS.VAR`)** keep every semantic above on their variable half and look in
  that dataset. In `All` and `Any` the dataset must exist **and** carry the variable — an absent
  dataset skips the rule, naming the dataset. In `None` an absent dataset excludes nothing: the
  requirement is met. Both are subject to the supplemental-qualifier pivot below. When the run
  cannot resolve datasets at all, a qualified entry is undecidable and skips the rule in every
  facet, `None` included.
- **Supplemental qualifiers count.** A qualified entry whose variable half is a plain name (not a
  glob, `/regex/` or template) is also found as a supplemental qualifier: a row of the entry's
  *own* dataset's **supplemental sources** — its `SUPP<dataset>` plus the domain-level members of
  its `SUPP<domain>` / `SQAP<xx>` family, exactly the datasets the [SUPP merge](#supp_merge) reads
  (`SUPPAE1` + `SUPPAE2` for `AE.AETRTEM` when SUPPAE ships split; for `LBCH.X` its `SUPPLBCH`
  plus any domain-level member of the family, never a sibling's `SUPPLBHE`), not the supplement
  of the dataset the rule runs on —
  whose `QNAM` **equals** the variable name, compared as a string, exactly (a `QNAM` cell is a
  value; rulings register `CIT §3`). It is looked for **before** a missing dataset counts as
  unavailable: `All: [AE.AETRTEM]` in a study with no `AE` but a `SUPPAE` row `QNAM = AETRTEM` is
  **met** and the rule runs, and `None: [AE.AETRTEM]` **skips** the rule whenever `SUPPAE` carries
  that `QNAM`, with or without `AE`. A qualified entry naming a split family (`SUPPAE.QNAM` on a
  study shipping `SUPPAE1` + `SUPPAE2`) is decided on the members' columns. A type suffix on such an
  entry is checked against the sources' `QVAL` type (every source must agree).
- **A type suffix** (`:N` / `:C`, on `All` and `Any` only) makes a present column of the wrong
  type a mismatch of its own; see [below](#demanding-a-type-as-well-as-presence).

## What an entry may be

The same vocabulary on all four facets:

| entry | means |
|---|---|
| `AESTDTC` | that column, literally |
| `--STDTC` | the domain-prefix placeholder, resolved to the dataset in hand |
| `SUPPAE.QNAM` | a **qualified** entry — that column in *that* dataset |
| `AE*` | a glob |
| `/^AE.*DTC$/` | a regular expression |
| `TRTxxP` | a [marker template](wildcards.md) — at least one column of the family (`TRT01P`, `trt02p`, …) |

Any of these may carry a [type suffix](#demanding-a-type-as-well-as-presence) — `AESTDY:N`,
`DM.AGE:N`, `"/^AE.*DTC$/:C"` — on the `All` and `Any` facets, and on those two only.

<!-- Owner ruling 2026-09-28 ("Case-insensitive everywhere"; "if it is to be revised in the
     future, than it must be revised everywhere"), PLAN-case-insensitive-templates, register entry
     CIT §1. -->
> ⭐ **Every entry matches a column name ignoring letter case** — a literal, a `--` placeholder, a
> qualified name, a glob, a `/regex/` and a
> [marker template](wildcards.md) such as `TRTxxP` alike:
> `trt01p` in a SAS dataset satisfies `TRTxxP` and `TRT01P` equally, and the rule then reads it
> under its actual name. The metadata lookups keyed by a column's name ignore case the same way —
> the Define-XML `ItemDef` and value-level (VLM) metadata behind `var_*("DEFINE")` / `vlm_*`, and
> the library's metadata for a variable the IG does not list. Write entries in the CDISC upper-case spelling regardless; never rely on
> case to tell two columns apart. A supplemental qualifier found through the SUPP-QNAM pivot is the
> exception: its `QNAM` is a cell value and is compared exactly (`CIT §3`). ⛔ This is a decision,
> not a default (owner, 2026-09-28; rulings register entry `CIT §1`): if the case rule is ever
> revised, it is revised for every surface at once — never for one kind of entry alone.

A qualified entry is how you require a column in a dataset other than the one the rule is running
on — usually one you are about to [join](joins.md).

> ⛔ **A bare entry is also how a joining rule says that a bare name means the primary.** When no
> dataset in the rule's scope defines `X` but a joined dataset does, a bare `X` in the check fails
> the corpus build unless `X` is declared: bare in `All`, bare in an `Any` group, or paired
> `[X, J.X]` in an `All_Or_None` group. `None`, and a qualified `J.X` alone, do not declare it.
> Usually the fix is to qualify the read instead — see
> [Joins](joins.md#-a-bare-name-only-the-join-carries-fails-the-build).

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
present, so requiring `LB` is satisfied by `LB1` and `LB2` without naming them, and a split
supplemental family counts as present — requiring `SUPPAE` is satisfied by `SUPPAE1` + `SUPPAE2`
(both `RDOMAIN = AE`), `SQAPMH` by `SQAPMH1` + `SQAPMH2`. A `--` entry names the supplemental
family of the primary's **domain**: `SUPP--` is `SUPPLB` on `LBCH` and `SQAPMH` on `APMH` (`SQAP--`
is its synonym). Name the family, never a split member.

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

A **wildcard template** only ([Wildcards](wildcards.md)): when `true`, the template is not expanded
for a variable the CDISC Library already defines for the dataset, so a sponsor does not receive the
same finding twice when a Library-driven check already covers that variable.

Set it on a template whose check restates something the Library defines. Leave it absent
otherwise. On a rule that is not a template — no `Check` level names a wildcard token, e.g. a rule
over the variable cursor `varname()` — nothing is expanded, so the field could never take effect:
`true` there is a **load error**.

## `Supp_Merge`

```yaml
Supp_Merge: false
```

Every rule reads its dataset's **supplemental qualifiers as values of the parent record**: a bare
name the domain does not carry resolves to the supplemental row that qualifies the record, from the
dataset's **SUPP sources** — its own `SUPP<dataset>` plus the domain-level members of its
`SUPP<domain>` family (an associated-persons dataset: its `SQAP<xx>` family). A family may ship
split: `SUPPAE1` + `SUPPAE2` (both `RDOMAIN = AE`) are the sources of `AE`, read together; a split
dataset `LBCH` reads its own `SUPPLBCH` and never a sibling's `SUPPLBHE`. The row is matched by
the IG's key: the same study (`STUDYID`), domain (`RDOMAIN`), subject (`USUBJID`; `POOLID` under
SEND, `APID`, `SPDEVID` where the dataset carries them) plus `IDVAR` = `IDVARVAL`, compared as text
(a numeric parent column canonicalised: `"01"` and `" 1"` find `1`). A blank `IDVAR` qualifies
every record of the subject, or of the pool when `USUBJID` is blank and `POOLID` populated. A
record-level row naming no subject qualifies every record its `IDVAR` = `IDVARVAL` matches under
SEND, and nothing under SDTM. SENDIG makes `USUBJID` and `POOLID` exclusive, so pooled records (blank
`USUBJID`) never read another pool's qualifiers, and a domain without pools reads exactly as it
would keyed on `USUBJID` alone. A split dataset reads both its own `SUPP<dataset>` and its
domain's `SUPP<domain>` — the family's domain-level members, never another member's own
`SUPP<member>`. A rule that needs another key writes
[`Supp_Keys`](joins.md#supp_keys--the-key-of-the-supp-merge); two supplemental rows qualifying one
record for one `QNAM` make the rules that read that `QNAM` error (and only those). The
merge serves the dataset the rule evaluates, never a dataset it reads through `domain=`: in
`read_value(DSTERM, domain=DS, filter=…)` a `SUPPDS` qualifier is not a column of `DS`. On `PC`
the check reads `PCCALCN` per record, `empty(PCCALCN)` is true for a record no `SUPPPC` row qualifies
(or whose `QVAL` is blank), `var_exists("PCCALCN")` is true as soon as any `SUPPPC` row carries the
name, the finding reports the qualifier's value, and a requirement naming the qualifier — bare, or
qualified such as `AE.AETRTEM` — is met when one of those sources delivers it: the same sources the
merge reads, so what a requirement or `var_exists` reports present is what the check reads. That is
the default, and it needs no key. On a rule that joins another dataset, that dataset's qualifiers are its
merged columns too: `DM.RACEOTH` reads `SUPPDM`'s qualifier of the joined `DM` record.

A qualifier is **not a variable of the domain**: the variable-inventory checks (column order,
variable count, lengths, the per-variable rules) see exactly the columns the domain file carries.
Supplementals are the place for non-standard variables (owner, 2026-09-29), and a rule about the
domain's variables must not report them.

Write `Supp_Merge: false` only when the rule must see the raw dataset — a rule *about* the
supplemental mechanism itself. It turns off both halves at once: the qualifier reads and the
existence pivot, so the rule then reads and reports exactly what the domain file carries. The
domain's own column always wins over a same-named qualifier; a `SUPP--` primary is never merged.

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
