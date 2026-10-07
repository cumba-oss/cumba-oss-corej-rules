# Outcome, severity and sensitivity

<!-- Grounded: Outcome.java (Message + Output_Variables), OutputVariableToken.java (the `!`
     exclusion grammar, ruled 2026-08-23; `!` alone and `!!X` are load errors; exclusions
     subtract from the DERIVED effective list), Rule.effectiveSeverity() (absent => ERROR,
     "Error" not a legal spelling), Severity enum (REJECT/ERROR/WARNING/INFO/NOTICE; NOTICE
     never authorable — LevelCheck.java), Sensitivity.java (Record/Dataset/Group/Study). -->

This group decides what a person actually sees. A correct rule with an unusable finding gets
ignored, so this is not the cosmetic part.

```yaml
Outcome:
  Message: "Invalid TSVAL value when TSPARMCD equals 'AGEMAX'; TSVAL must be in ISO 8601 duration format or empty."
Severity: "Warning"
Sensitivity: "Dataset"
```

No `Output_Variables` here, on purpose: the check reads `TSPARMCD` and `TSVAL`, so the engine
reports both — see below.

## `Message`

The sentence a data manager reads next to the flagged row. They have not seen your rule, will not
open it, and are deciding what to do about their data.

**Say what is wrong, and under what condition.** *"Invalid TSVAL value when TSPARMCD equals
'AGEMAX'"* tells them both. *"Invalid value"* tells them neither.

**Say what would be right.** A message that names only the fault leaves the reader to guess the
remedy; one that ends *"…must be in ISO 8601 duration format or empty"* does not.

**Do not name the rule, the engine, or the check.** The identifier is already on the finding, and
expression syntax means nothing to the reader.

> **Write the message before the check.** If you cannot state the violation in one sentence a
> data manager would act on, the requirement is not yet clear enough to author.

A [severity-ladder](the-check.md#the-severity-ladder) level may carry its own `Message`. A level
that does not falls back to this one.

## `Output_Variables`

The columns whose values are carried into the finding — and **the engine derives them**. At load
it reads the check and the bindings and builds the list itself: every column the check reads,
every `$`-binding it uses, and on a per-variable rule `variable_name` first. Most rules therefore
author no `Output_Variables` at all; the rule above reports `TSPARMCD` and `TSVAL` without naming
them.

**List only what the engine cannot derive.** An entry the derivation produces anyway is a copy of
a guarantee — it adds nothing to the finding, and the corpus build rejects it
(`RedundantOutputVariableLintTest`). What is left to author is the extra context a reader needs
that the check never touched, in the order it should appear:

```yaml
Output_Variables: ["RDOMAIN"]      # read by no condition, but it tells the reader which domain
```

The entries you author come first, in the order written, and the derived columns follow in the
order in which the check reads them — with one exception: on a per-variable rule (and on one that
reads value-level metadata) `variable_name` is moved to the front, and a derived `variable_value`
directly after it, ahead of your entries. Your `!X` exclusions come off last, after that move.
The results view's rule definition shows this effective list, with the entries you authored marked
and your `!X` exclusions struck through; names appear as written in the rule — wildcards
(`--TESTCD`, `&NAME&`) resolve per dataset.

> **Never report a variable the rule did not read.** A finding showing a column the check never
> touched invites the reader to conclude something the rule never checked — and that conclusion
> will be wrong in exactly the cases that matter. Context columns are the one deliberate
> exception, and they should be rare.

> ℹ **An entry with no value is reported the same way whether you wrote it or the engine derived
> it:** a char attribute prints `""`, a numeric, boolean or list one prints `.`, an absent column
> is left out, and a present column's missing cell prints its own missing marker. So there is
> never a reason to author an entry the engine derives — the build fails on one.
>
> A **`$`-binding** with no value prints by the type of its expression the same way: `""` for a
> string (or a date / time, which are text), `.` for a number, boolean or list. Every function
> has a result type for this — `dataset_class_from_library()` is a string, `variable_count(…)` a
> number, `domain_is_custom()` a boolean, `extract_metadata("dataset_size")` a number while its
> other documented keys are strings — except the few whose result is the type of the column they
> read (`read_value`, `max`, `row_max`, `coalesce`, `colref`, `value()`), which print by that
> column's type — `coalesce` only when one of its arguments reads a column, is a literal or is a
> function call with a known result type, `max` only when its target does. A binding whose type cannot be known at all — `extract_metadata`
> of a key other than the documented ones, or a `coalesce` of two such keys — **cannot be
> reported**: listing it in `Output_Variables`, or letting the engine derive it, is a load error
> that names it. Remove an authored entry, exclude a derived one with `!$name`, or bind a function
> whose result type is known (for `extract_metadata`, one of the typed keys). A binding the engine
> does not derive is never reported unless you list it, so reading such a binding in the Check
> alone is always fine — also in a rule with no `Output_Variables`, whose reported names the
> engine infers from the Check.

### Excluding with `!`

An entry may begin with `!` to **subtract** from the list the engine otherwise derives:

| entry | meaning |
|---|---|
| `TSVAL` | include `TSVAL` |
| `--STDTC` | include the wildcard variable — `--` never means exclusion |
| `!TSVAL` | exclude `TSVAL` |
| `!--STDTC` · `!$count` · `!DM.RFSTDTC` | exclude a wildcard variable, a binding, or a joined column |

Everything after the `!` is the name, verbatim. Two shapes are load errors: `!` on its own, and a
doubled `!!X`.

Use an exclusion when the derived list would carry a column that is noise in the finding — not as
a way to hand-write the whole list, which the derivation already does.

## `Message_Folded`

The message of a **record-level** rule when its findings are **folded or collapsed to one
dataset-level finding** — and the opt-in for **both** folds: the present-but-empty collapse and the
absent-column fold.

A column that is **present but blank on every record** makes an `empty`-family leaf (`empty`,
`is_missing`, `non_empty` / `not empty`, `present`, `is_present`) answer the same on every row: the
rule's verdict is a fact about the dataset, not about a record. A rule that declares
`Message_Folded` reports that fact **once**, under this message, instead of once per record; when
no record fires it reports nothing. The row evaluation is unchanged — only the reporting collapses.

```yaml
Check:
  expression: 'not empty(AEBODSYS) and empty(AEBDSYCD)'
Outcome:
  Message: AEBDSYCD is not populated for a coded AEBODSYS.
  Message_Folded: AEBDSYCD is not populated on any record of the dataset.
```

Over an AE whose `AEBDSYCD` is blank on all 961 records: one finding under `Message_Folded`,
anchored at the first firing record, the columns listed by name without row values (the shape of
every dataset-level finding). Without the key: 961 findings under `Message`.

**Rules of the key.**

- It is **opt-in, for both folds**. A rule without it never collapses, and — since
  `PLAN-message-folded-corpus-alignment` (owner, 2026-10-05: *"I would expect the absent fold does
  not fire anymore if no Message_Folded is available. Like the all empty fold."*) — never folds on an
  **absent** column either: it is evaluated per record, the absent column reading as its type default
  ([absent columns](../semantics/absent-columns.md)). Nothing moves until an author writes the key.
- With the key, an absent column the Check tests folds to **one** dataset-level finding under
  `Message_Folded` — when the absent column decides the whole Check (`X != "N"` over an absent `X`
  is true on every record), or when an `empty`-family leaf over the absent column is a collapse
  trigger exactly as over a column blank on every record (it reads as all-missing) and every live
  leaf of the Check is decided (next bullet). ⛔ Any **other**
  operator over an absent column with a per-record residual stays per record: an absent *guard*
  column (`X != "N"` in an `or`-branch, a qualifier the domain never carries) must not fold the
  findings the other conditions locate per record.
- **Only a polarity-live leaf triggers, and only when every one is decided** (owner, 2026-10-07):
  a leaf is live when its column being absent or blank on every record can make the Check true —
  `empty(X)` is, `not empty(X)` never is (it is false over a blank or absent `X`), and a column
  merely absent elsewhere in the Check triggers nothing; nor does a leaf in a branch a presence fact
  kills for the whole dataset (`not var_exists("SVPRESP") and empty(SVUPDES)` with `SVPRESP`
  present). The rule collapses only when **every** live
  leaf is decided that way; while one live leaf's column is still filled on some record, it reports
  per record (`FDA-SD0021` over an AE whose `AEENDTC` is filled on some records: per record; over an
  MH with no end column at all: once).
- A dataset-constant verdict (a metadata-only Check, `var_not_exists(X)` alone) is not a fold of
  record findings: it reports under `Message`, with or without the key.
- Only a **plain** column reference triggers the collapse: a joined `DM.X` or a SUPP-delivered
  qualifier is read per record and never folds.
- Write the message for the **column(s) whose blankness or absence decides** the rule — a column
  whose blankness makes the Check false (a `not empty(X)` guard) can never print it. The corpus
  generates and reviews its texts per rule (`scripts/gen-message-folded.py`,
  `registers/derivation/message-folded-review.tsv`); `MessageFoldedCoverageLintTest` demands the key
  on every record-level rule the absent fold can decide or the collapse can report.
- A dataset with **no rows** never folds or collapses ([zero-row datasets](../semantics/zero-rows.md)).
- `is_missing` is strictly a missing value: over a column of `""` it folds to *false* (no finding);
  over `""` mixed with missings it is row-dependent and does not fold.
- The key is a **load error** on a rule that can never fold or collapse: no `empty`-family leaf over
  a plain column (outside `Keep_Row_Findings`) **and** every column the check reads is required
  present by `Requirements.Variables` (`All`, or a one-member `Any` group); a `Sensitivity` other
  than `Record`; or a check that reads the variable cursor (`varname()`, `value()`).

## `Keep_Row_Findings`

The columns a rule with `Message_Folded` still wants reported **per record** — the opt-out for a
column whose blankness is a finding on every row (the Type 1 verdict of the authoring guidelines).

```yaml
Outcome:
  Message_Folded: --STAT is not populated on any record.
  Keep_Row_Findings: [--STAT]
```

`--` resolves like any variable reference. An entry the check does not read is a load error. The
list is inert on a rule without `Message_Folded`.

A kept column's leaf **neither triggers nor blocks**: blank or absent on every record it does not
collapse the rule, and filled on some records it does not hold back a collapse the other live leaves
decide. `FDA-SD1320` — `empty(--STAT) and empty(--STRESC)`, `Keep_Row_Findings: [--STAT]` — reports
one finding when `--STRESC` is blank on every record and some record has `--STAT` empty, however
`--STAT` is filled elsewhere; while `--STRESC` is filled on some record it reports per record.

## `Severity`

```yaml
Severity: "Warning"
```

**An absent `Severity` means ERROR.** Most rules therefore carry no `Severity` key at all.

> ⛔ **`Severity: "Error"` is not a legal spelling.** It is not merely redundant — it is stripped
> on load. Write nothing when you mean an error.

The authored values are `Warning` and `Reject`:

- **`Reject`** — the submission cannot be accepted with this violation present.
- **`Warning`** — the violation should be reviewed, but does not by itself block.

`NOTICE` is never authorable.

> **Severity is a statement about consequence, not confidence.** A rule that might be wrong is not
> a warning — it is an [`Executability`](identity.md) problem, and belongs there. Downgrading a
> shaky rule to `Warning` hides the shakiness in a field nobody reads it in.

## `Sensitivity`

```yaml
Sensitivity: "Dataset"
```

What the finding is **about** — and therefore what gets reported:

| value | the finding is about |
|---|---|
| `Record` | the row (the default) |
| `Dataset` | the dataset as a whole |
| `Group` | a [group](grouping.md) |
| `Study` | the study |

A dataset-sensitive rule reports the dataset once rather than naming rows. That is the right shape
when the violation is a property of the whole table — *"this dataset has no records"*, *"this
dataset declares a variable it never populates"* — and naming a row would be arbitrary.

> **Match sensitivity to the subject, not to the volume.** `Dataset` is not a way to collapse a
> noisy row-level rule into one finding; if a row-level rule reports too much, the check is too
> broad. Collapsing it hides which rows were wrong.

---

**Next:** [Provenance and citations](provenance.md) — why the rule exists.
