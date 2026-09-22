# Identity and executability

<!-- Grounded: RuleCore.java (Id/Status/Version), Executability.java (5 values, @JsonValue),
     ExecutabilityHint.java (free-text Category + Detail; javadoc vocabulary differs from the
     corpus's — see "What to write in Category"). Status/Version/family distributions measured
     across rules-src/checks 2026-09-21. -->

This chapter covers the four blocks that say **what a rule is**: `Core`, `Description`,
`Executability` and `ExecutabilityHint`.

None of them changes what the rule computes. All of them change whether anyone can act on what it
reports — which is why a rule with a vague description and an optimistic executability flag is
worse than no rule at all.

## `Core` — the rule's identity

```yaml
Core:
  Id: "CDISC-CG0270"
  Status: "Published"
  Version: "1"
```

### `Id`

The permanent name of the rule. It appears in **every finding the rule ever raises**, so it is
what a data manager quotes back to you when something looks wrong, and what a sponsor cites in a
response to a regulator.

It is prefixed with the rule's family — the organisation the requirement comes from:

| prefix | source |
|---|---|
| `CDISC-` | CDISC conformance rules (SDTM, SEND, ADaM) |
| `FDA-` | FDA validator rules |
| `PMDA-` | PMDA validation rules |
| `DRAFT-` | house-authored proposals, not yet promoted |

The rest of the identifier is the published rule identifier from that source, so `CDISC-CG0270`
is CDISC's `CG0270`. A rule with no upstream identifier — a `DRAFT` — takes a minted number.

> ⛔ **An `Id` never changes once the rule is published.** Renaming it does not correct a
> mistake; it creates a second rule and silently orphans every finding, review record and
> scenario that referenced the first. If an identifier is wrong, that is a conversation, not an
> edit.

### `Status`

`Published` or `Draft`. `Draft` marks a rule that is authored and tested but not yet released —
the `DRAFT` family uses it while a proposal is being settled.

### `Version`

The rule's own version, as a string, starting at `"1"`. It goes up when the rule's **meaning**
changes — a different condition, a different population, a corrected comparison. It does not go
up for a reworded message or a new citation.

> **Bumping `Version` is a statement that findings before and after are not comparable.** If a
> sponsor re-runs conformance after a corpus update and a rule's version moved, they need to know
> the change was substantive. Do not bump it for tidying, and do not *fail* to bump it for a real
> change.

## `Description` — one sentence, describing the violation

```yaml
Description: "Raise an error when TSPARMCD is equal to 'AGEMAX' but TSVAL is not empty and TSVAL is not in ISO 8601 format for a time period (e.g. P80Y)."
```

Written for someone scanning a list of several thousand rules, deciding which are relevant to
their study.

Three things make a description usable:

**It describes the violation, not the requirement.** The check is authored in violation polarity,
and the description must match it. *"Raise an error when TSVAL is not a duration"*, not *"TSVAL
must be a duration"* — otherwise a reader comparing the two has to mentally invert one of them,
and that is exactly where review mistakes happen.

**It names the condition as well as the test.** A description that says only *"TSVAL must be an
ISO 8601 duration"* omits that the rule applies solely when `TSPARMCD` is `AGEMAX`, which makes
the rule look far broader than it is.

**It matches the check exactly.** If the check has three conjuncts, the description accounts for
all three. Where they disagree, the check is what runs — but the description is what people trust,
which is the worse failure.

> **The description is the rule's contract with someone who will never read its expression.** Most
> people who see a finding will read the message and, if they want detail, the description. They
> will not open the YAML.

## `Executability` — how completely the rule evaluates its requirement

```yaml
Executability: "Fully Executable"
```

One of exactly five values, spelled exactly as shown:

| value | meaning |
|---|---|
| `Fully Executable` | the check evaluates the requirement completely |
| `Partially Executable - Possible Underreporting` | it may **miss** real violations |
| `Partially Executable - Possible Overreporting` | it may **flag conforming data** |
| `Partially Executable` | incomplete in a way that is neither of those, or both |
| `Not Executable` | recorded for completeness; nothing is evaluated |

> **This field is documentary.** The engine does not consult it when deciding what to run — a
> `Not Executable` rule is not skipped because of this flag. It exists so that a human, and a
> downstream report, can tell how much weight a finding deserves.

### Choosing between the two partial values

This is the judgement that matters, and they are not interchangeable.

**Underreporting** means the rule is conservative: everything it flags is a genuine violation, but
some violations slip past. A sponsor reading a clean result cannot conclude the data is clean.

**Overreporting** means the rule is aggressive: it catches everything, but some of what it flags
conforms. A sponsor must review each finding rather than acting on it.

The usual cause of both is data the check cannot judge — a partial date, an unresolvable
reference, a value whose meaning depends on something the rule cannot see. Which way it falls
depends on what you did with those rows:

- excluded them from the check → **underreporting**
- judged them by a proxy that is sometimes wrong → **overreporting**

> **Say which, and say why.** *"Partially executable"* alone tells a reviewer that something is
> incomplete and nothing about how to respond to it.

## `ExecutabilityHint` — the reason, in prose

```yaml
ExecutabilityHint:
  Category: "partially executable"
  Detail: "A partial TSVAL such as 'P' cannot be compared to a full duration, so rows carrying one are not judged."
```

Write it whenever `Executability` is anything but `Fully Executable`. A rule that announces it is
incomplete without saying how is a rule nobody can act on, and nobody can fix.

**`Detail` is where the substance is.** It is free prose, read by a human, and it should answer
one question: *what would I have to know, or what would the engine have to do, for this rule to
be complete?* Name the shape of data that escapes the check, not the abstract limitation.

Good: *"A partial TSVAL such as 'P' cannot be compared to a full duration, so rows carrying one
are not judged."*

Not useful: *"Date handling is limited."*

### What to write in `Category`

`Category` is a **free-text string**. It is not an enum and nothing validates it, which means it
has drifted: the corpus today writes the executability level back into it (`partially
executable`, `not executable`, `fully executable`), while the field was originally introduced to
carry *what the engine must do* to run the rule at all.

**Write the executability level**, matching what the corpus does, and put everything that
actually informs the reader in `Detail`.

> **Do not invent a new category value.** Nothing rejects it, no tool reads it, and it will read
> as a typo to the next person. The information belongs in `Detail`, which is read.

---

**Next:** [Scope](scope.md) — which datasets the rule is offered.
