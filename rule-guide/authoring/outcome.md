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

Derived entries follow the order in which the check reads them; an authored entry comes first,
in the order written. The list is where you put context columns the engine cannot derive; they
lead, and the derived columns follow in walk order.

> **Never report a variable the rule did not read.** A finding showing a column the check never
> touched invites the reader to conclude something the rule never checked — and that conclusion
> will be wrong in exactly the cases that matter. Context columns are the one deliberate
> exception, and they should be rare.

> ⚠ **One case still wants an authored entry the engine would derive:** on a rule that iterates
> variables (`varname()`, `value()`, a `var_*` accessor), an *authored* entry whose value is
> missing keeps a `null`-valued key where a *derived* one is silently dropped. Until the engine
> unifies that, such entries (other than `variable_name`) stay authored, and the build keeps the
> list of them in `src/test/resources/ovsweep/q2-residual.tsv` — a new one must be added there
> deliberately.

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
