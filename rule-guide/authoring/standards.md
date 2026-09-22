# Standards and release

<!-- Grounded: Authority.java (Organization, Standards, Rule_Ids), AuthorityStandard.java (Name,
     Version, Substandard, References), Reference.java (Origin, Version, Rule_Identifier,
     Citations — lifted to the docs sidecar in the authored split), RuleIdentifier.java
     (Id, Version). Authored key is `Standards:`; the shipped package spells it `Authorities`. -->

`Standards` says which standards the rule belongs to, and under which identifier in each. It is
what decides **where the rule ships** — a rule missing a standard is a rule nobody receives.

```yaml
Standards:
- Organization: "CDISC"
  Standards:
  - Name: "SDTMIG"
    Version: "3.4"
    References:
    - Origin: "SDTM and SDTMIG Conformance Rules"
      Rule_Identifier:
        Id: "CG0270"
        Version: "1"
      Version: "2.0"
```

## The three levels

**Organisation** — who publishes the standard. One rule may belong to several: a requirement that
CDISC and PMDA both state is one rule with two organisation entries, not two rules.

| field | is |
|---|---|
| `Organization` | `CDISC`, `FDA`, `PMDA` |
| `Standards` | the standards of that organisation this rule belongs to |
| `Rule_Ids` | identifiers at organisation level, where the organisation numbers rules itself |

**Standard** — which standard, and which version of it.

| field | is |
|---|---|
| `Name` | `SDTMIG`, `SENDIG`, `ADaMIG`, … |
| `Version` | `3.4`, `1.3`, … — a string, not a number |
| `Substandard` | a narrower standard within that one, where there is one |
| `References` | what ties this rule to the published rule list |

**Reference** — the published rule this one implements.

| field | is |
|---|---|
| `Origin` | the document the rule list comes from — `SDTM and SDTMIG Conformance Rules` |
| `Version` | the version of **that list**, not of the standard — `2.0` |
| `Rule_Identifier` | `Id` and `Version` of the published rule — `CG0270` version `1` |

> ⚠ **Three different `Version` fields sit within a few lines of each other**, and they mean
> different things: the version of the *standard* (`3.4`), the version of the *rule list* (`2.0`),
> and the version of the *published rule* (`1`). Getting them crossed is the easiest mistake in
> this block, and nothing detects it — the rule still loads, still runs, and is filed under a
> standard version it does not belong to.

## One rule, several versions

Most rules belong to several versions of the same standard:

```yaml
Standards:
- Organization: "CDISC"
  Standards:
  - Name: "SDTMIG"
    Version: "3.4"
    References: [ ... ]
  - Name: "SDTMIG"
    Version: "3.3"
    References: [ ... ]
  - Name: "SDTMIG"
    Version: "3.2"
    References: [ ... ]
```

Each version listed is a version the rule will be **offered to**. Listing one it does not apply to
ships a rule that will report findings a sponsor on that version should never see; omitting one it
does apply to silently withholds the check from those sponsors.

> **List a version only if you have checked the guidance for that version.** Each one gets its own
> [citation](provenance.md#the-docs-file--citations), and if the wording of the requirement
> changed between versions, the check has to be right for all of them — or the rule has to be
> split.

## A note on the shipped form

The authored key is `Standards:`. In the generated packages the same tree appears under
`Authorities`, and the `Citations` arrays that live in the [docs sidecar](provenance.md) are
folded back into their `References`.

You never write that form — but if you are reading a shipped package and looking for the block you
authored, that is where it went.

---

**Next:** back to [Anatomy of a rule](../getting-started/anatomy-of-a-rule.md), or on to
[Scenarios](../testing/scenarios.md) to prove the rule works.
