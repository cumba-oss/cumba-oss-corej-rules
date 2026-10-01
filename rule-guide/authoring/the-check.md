# The check

<!-- Grounded: RuleCheck.java (plain vs level map; mixed map and unknown level are load errors),
     LevelCheck.java (ladder order REJECT>ERROR>WARNING>INFO, NOTICE never authorable, Message
     falls back to Outcome.Message), CheckCondition sealed interface + CheckConditionDeserializer
     (keys all/any/not/expression; "operator" rejected), RuleRunner:1287 (Precondition). -->

The check is the rule. Everything else decides what it sees and what happens to what it finds.

```yaml
Check:
  expression: >-
    TSPARMCD == "AGEMAX" and not empty(TSVAL)
```

It is evaluated **once per row**, unless [`Grouping`](grouping.md) changes the unit. Where it
evaluates **true**, that row is a violation.

## Polarity: you write the problem

The single thing to keep in your head. A rule for *"TSVAL must be an ISO 8601 duration"* is
authored as *"TSVAL is **not** an ISO 8601 duration"*.

Everything else in the rule follows this: the `Description` describes the violation, the
`Message` describes the violation, the scenarios named `violation` contain data that makes the
check true.

> **When a rule reports nothing on data you know is wrong, check the polarity first.** It is the
> most common cause, and it is invisible in review because an inverted check reads perfectly well
> as a statement of the requirement.

## The plain form

One expression, under the key `expression`:

```yaml
Check:
  expression: 'AESTDTC > AEENDTC'
```

The `>-` YAML block form is used throughout the corpus for anything longer than a line, because
it lets an expression wrap without introducing escapes:

```yaml
Check:
  expression: >-
    TSPARMCD == "AGEMAX" and not empty(TSVAL)
    and invalid_duration(TSVAL, negative=false)
```

Everything the expression may contain — operators, functions, accessors, literals — is in
[Reference](../reference/expression-syntax.md). This chapter is about the shape around it.

## Composite conditions: `all`, `any`, `not`

A condition does not have to be a single expression. Three composite forms nest:

```yaml
Check:
  all:
  - expression: 'not empty(AESTDTC)'
  - any:
    - expression: 'AEENDTC < AESTDTC'
    - expression: 'AEENDY < AESTDY'
```

| form | satisfied when |
|---|---|
| `all: [ … ]` | every listed condition holds |
| `any: [ … ]` | at least one holds |
| `not: <condition>` | the inner condition does not hold |

### When to use a composite rather than `and` / `or`

Most of the time, **don't**. `and` and `or` inside one expression read better and keep the rule on
one screen:

```yaml
# preferred
Check:
  expression: 'not empty(AESTDTC) and AEENDTC < AESTDTC'

# same thing, harder to read
Check:
  all:
  - expression: 'not empty(AESTDTC)'
  - expression: 'AEENDTC < AESTDTC'
```

Reach for a composite when the structure is genuinely a tree — several independent groups, each
internally an OR — and flattening it into one expression would need parentheses deep enough that
nobody can see the shape any more.

> ⛔ **`operator:` / `name:` / `value:` is not a check.** A rule written that way **fails to
> load**, with an error naming the rule.

## The severity ladder

Instead of one condition, a check may be a map from **level** to its own condition — so one rule
can report the same subject at different strengths.

```yaml
Check:
  ERROR:
    expression: >-
      date(SE.SESTDTC) <= date(SVSTDTC) and EPOCH != SE.EPOCH
  INFO:
    expression: >-
      not empty(earliest_possible(SE.SESTDTC))
      and date(earliest_possible(SE.SESTDTC)) <= latest_possible(SVSTDTC)
      and EPOCH != SE.EPOCH
    Message: "EPOCH in SV does not match an SE element whose date range may contain the visit start date (partial date/time precision leaves the containment undetermined)."
```

The levels, strictest first:

```
REJECT  →  ERROR  →  WARNING  →  INFO
```

- They are evaluated in that order.
- A level may carry its own `Message`. A level that does not **falls back to
  [`Outcome.Message`](outcome.md)** — the rule message is not copied into each level.
- `NOTICE` is never authorable.

### When a ladder is the right answer

When a weaker form of the same finding is genuinely worth reporting. The usual case is
**partial precision**: a complete date lets you say *"these disagree"*; a partial one lets you say
only *"these may disagree"*. Reporting the second as an error would be wrong, and dropping it
would lose a real signal.

A second case is **a reference that is out of date**. `FDA-SD0058` asks whether a variable is
allowed in a dataset: in neither the SDTM model list nor the IG's list it is a sponsor variable
(`ERROR`); in the IG's list but not the model's, the IG has simply run ahead of the model version it
links to (`WARNING`). Its two rungs are written as **disjoint** conditions, so neither relies on the
engine's rule that a finding a stricter level already reported is not reported again by a weaker one.

Do not use a ladder to express two different requirements. That is two rules.

### Two shapes that are load errors

| you write | why it fails |
|---|---|
| `Check: {ERROR: …, expression: …}` | a **mixed** map — either all keys are levels, or none are |
| `Check: {SEVERE: …}` | an unknown level name |

A `Check` whose keys are *all* level names is a ladder; a `Check` with *no* level key is a plain
condition. There is no in-between.

## `Precondition`

A second condition, evaluated **before** the check, deciding whether the check is attempted at
all.

```yaml
Precondition:
  expression: 'DOMAIN == "TS"'
Check:
  expression: 'invalid_duration(TSVAL, negative=false)'
```

It takes exactly the same forms as a check — `expression`, `all`, `any`, `not` — and it is
type-checked at load exactly as the check is: a Precondition that is not a condition, or a wrongly
typed argument inside it, fails the rule's load with a message naming it
(`stage A: PARAMETER_TYPE: Precondition: …`). A rule expanded from a wildcard or a token keeps its
Precondition.

### Precondition, requirement, or conjunct?

Three ways to narrow what a rule judges, and they are not interchangeable:

| use | when | what happens to the excluded rows |
|---|---|---|
| [`Requirements`](requirements.md) | a **column** is missing, so the question cannot be asked | the whole rule is **skipped** for the dataset |
| `Precondition` | the **data** is outside the question's premise | the check is not attempted |
| a conjunct in the check | the row is in premise but conforming | the row is judged and found conforming |

The practical difference is what a reader is told. A skipped rule says *"not applicable"*; a
conjunct says *"checked, fine"*. Choose the one that is true.

> **Most rules need none of the first two.** A leading conjunct — `TSPARMCD == "AGEMAX" and …` —
> is the plainest way to express a condition, and it keeps the whole rule in one expression where
> a reviewer can see it.

---

**Next:** [Bindings](bindings.md) — naming a sub-result so the check stays readable.
