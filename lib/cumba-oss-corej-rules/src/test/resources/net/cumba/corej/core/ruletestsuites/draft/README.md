# `draft/` — DRAFT-family scenario home

Scenario subtree for the 31 unpublished draft rules
(`lib/corej-cdisc-rules/rules-src/checks/DRAFT/DRAFT-*.yaml`), replayed by
`RuleTestSuitesDraftFactoryTest`. Empty until the sweep phases of
`plans/done/PLAN-scenario-coverage-completion.md` fill it — the home is declared
and verified now (`RuleTestSuitesFamilyHomeTest`) so the factory cannot be a
vacuous green while it waits.

Layout and file naming follow the other families exactly
(`../README-CDT.md`): one directory per rule id, one `.cdt` per variant,
`{RULE_ID}-{valid|invalid}-{DOMAIN}.cdt`.

```
draft/
└── DRAFT-110004/
    ├── DRAFT-110004-valid-AE.cdt
    └── DRAFT-110004-invalid-AE.cdt
```

## Two things that differ from `cdisc/` and `core/`

- **`#library standard=… version=…` (or `#library-ref`) is mandatory.**
  `RuleTestSuitesDraftFactoryTest.requireDeclaredStandard()` returns `true`,
  as it does for `fda/` and `pmda/`. The 31 draft rules are assembled into up
  to thirteen packages whose views differ, so an undeclared scenario would
  fall through to whichever package sorts last and the membership assertion
  would never run. Declare the standard+version the scenario means:

  | `standard=` | `version=` |
  |---|---|
  | `sdtmig` | `3-2`, `3-3`, `3-4` |
  | `sendig` | `3-0`, `3-1`, `3-1-1` |
  | `sendig-ar` | `1-0` |
  | `sendig-dart` | `1-1`, `1-2` |
  | `sendig-genetox` | `1-0` |
  | `adamig` | `1-2`, `1-3` |
  | `tig` | `1-0` |

  Not every draft rule is in every package — membership comes from each
  rule's `Standards` block; see the `rules-draft-*` entries in
  `lib/corej-cdisc-rules/rules/packages.json` for the current per-package
  counts (e.g. the ADaM drafts reach only the `adamig` flavours). The
  membership check names the package when a declaration and a rule's
  `Standards` block disagree.

- **Draft rules never appear in a production package.** `DRAFT-*` ids are
  assembled into `rules-draft-*.json` only, never into `rules-cdisc-*`,
  `rules-core-*`, `rules-fda-*` or `rules-pmda-*`; conversely the
  `rules-draft-*` packages carry nothing but `DRAFT-*`. Both directions are
  pinned by `RuleTestSuitesFamilyHomeTest.draftRulesShipOnlyIntoDraftPackages`.
