# Provenance and citations

<!-- Grounded: rule-check.schema.json (Source / Source_Proposed declared as open objects; the
     retired Original_Description / Original_Condition / Outcome.Original_Message and Variants
     deleted, 0 carriers, PLAN-rule-unknown-keys-gate), ReleaseShapeTrimmer (strips Source /
     Source_Proposed from every package), rule-doc.schema.json (docs file = Core + Citations, both required),
     Citation.java (Cited_Guidance, Document, Item, Section), and the composite citation key
     observed in rules-src/docs (ORG|STANDARD|VERSION||RULEID|RULEVERSION). Source /
     Source_Proposed field sets measured across rules-src/checks 2026-09-21. -->

Nothing in this chapter is evaluated. It is what makes the rule **reviewable** — the record of
what the source actually said, kept so that a reader can judge whether the check matches it.

A rule whose provenance is missing or paraphrased cannot be reviewed at all. It can only be
believed.

## `Source` — the source's own words

```yaml
Source:
  Class: "TDM"
  Domain: "TS"
  Variable: "TSVAL"
  Condition: "TSPARMCD = 'AGEMAX' and TSVAL ^= null"
  Rule: "TSVAL conforms to ISO 8601"
```

The fields are those of the originating document, transcribed. Which ones appear depends on where
the rule came from:

| field | carries |
|---|---|
| `Class` · `Domain` · `Domains` · `Subclass` | the scope as the source states it |
| `Variable` · `Variable_or_Item` | the variable the source names |
| `Condition` · `Condition_Failure` | when the rule applies, in the source's words |
| `Rule` | the requirement itself |
| `Rule_Failure_Criteria` · `Natural_Language_Rule_Failure_Criteria` | how the source states failure |
| `Message` · `Description` · `Severity` | the source's own wording for those |
| `FDA_Validator_Rule_Message` · `FDA_Validator_Rule_Description` | the FDA sheet's columns |
| `Plain_Text_Explanation` | the source's prose explanation |

> ⛔ **Transcribe, do not improve.** `Source` is the thing the check is compared against. Tidying
> its grammar, expanding its abbreviations or resolving its ambiguity destroys the only
> independent record of what was required — and the tidied version will read as agreeing with a
> check that may not.

## `Source_Proposed` — when the source is wrong

Sometimes the published wording is ambiguous, self-contradictory, or plainly mistaken. The rule
still has to do the right thing.

```yaml
Source:
  Rule: "TSVAL conforms to ISO 8601"
Source_Proposed:
  Rule: "TSVAL conforms to ISO 8601 duration format"
  Sheet_Issue: "The published wording says 'ISO 8601' without naming the duration form; a date would satisfy it as written, which cannot be intended for a maximum age."
```

`Source_Proposed` takes the same fields as `Source`, plus **`Sheet_Issue`** — a statement of what
is wrong with the published text.

The rule implements the **corrected** reading. This block is what makes that deliberate and
visible, rather than looking like an authoring error when someone compares the check to `Source`
and finds they disagree.

> **`Sheet_Issue` is the field that earns the block.** A proposed correction with no stated
> problem is just a second opinion. Say what is wrong with the original and why the correction
> follows from it.

## Provenance stays in the authored file

`Source` and `Source_Proposed` belong to the rule file under `rules-src/checks` and nowhere else.
The build **strips both from every released package**: the engine never reads them, and a
package is what the engine loads.

> ⛔ **A package that carries either one fails to load.** An unknown key at any level of a rule is
> a load error, and in a package these two are unknown keys — the error names the key and says to
> delete it. This bites only when a package is written by hand from an authored file; the build
> never ships one.

> ⚑ **`Original_Description`, `Original_Condition` and `Outcome.Original_Message` are retired.**
> They held the source sheet's text before house rephrasing, and `Source` supersedes them. No rule
> carries them any more, the canonicaliser drops a straggler from a rule file, and in a package
> they are unknown keys like any other.

## The docs file — `Citations`

The second half of a rule is its citation sidecar:

```yaml
Core: "CDISC-CG0270"
Citations:
  CDISC|SDTMIG|3.4||CG0270|1:
  - Cited_Guidance: "When the trial summary parameter is AGEMAX, then TSVAL should have a value expressed as an ISO8601 time duration (e.g., P43Y for 43 years old or P6M for 6 months old)."
    Document: "IG v3.4"
    Section: "7.4.2.1"
```

Both keys are required: `Core` is the rule's identifier, and `Citations` maps a
standard-and-rule key to the guidance behind it.

### The citation key

```
ORGANISATION | STANDARD | VERSION | (substandard) | RULE_ID | RULE_VERSION
```

`CDISC|SDTMIG|3.4||CG0270|1` is CDISC's SDTMIG 3.4, rule `CG0270` version 1, with no substandard —
hence the empty field between the two `|`. The key ties each quotation to the exact
[`Standards`](standards.md) entry it justifies, which is why one rule citing three IG versions
carries three keys.

### The citation

| field | is |
|---|---|
| `Cited_Guidance` | the guidance text, **quoted** |
| `Document` | which document it is from — `IG v3.4` |
| `Section` | where in it — `7.4.2.1`, or `7.4\|Appendix C1` |
| `Item` | a finer locator where the section is not enough — `Assumption 3` |

> ⛔ **Quote; do not paraphrase.** `Cited_Guidance` is the evidence that the rule is authorised. A
> paraphrase is your reading of the guidance, which is the very thing a reviewer is checking. If
> the guidance is long, quote the part that carries the requirement — not a summary of it.

Different versions of a standard often word the same requirement differently, and sometimes the
difference is material. Cite each version separately rather than quoting the newest and assuming
the others agree.

---

**Next:** [Standards and release](standards.md) — where the rule ships.
