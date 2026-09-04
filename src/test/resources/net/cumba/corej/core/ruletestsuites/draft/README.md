# `draft/` — DRAFT-family scenario home

Scenario subtree for the 31 unpublished draft rules
(`rules-src/checks/DRAFT/DRAFT-*.yaml`), replayed by
`RuleTestSuitesDraftFactoryTest`. The sweep that fills it is under way — most rule
ids still have no directory here — and the home itself is declared and verified
(`RuleTestSuitesFamilyHomeTest`) so the factory cannot be a vacuous green while the
rest lands.

Layout and file naming follow the other families exactly
(`../README-CDT.md`): one directory per rule id, one `.cdt` per variant,
`{RULE_ID}-{valid|invalid}-{DOMAIN}.cdt`.

```
draft/
└── DRAFT-900021/
    ├── DRAFT-900021-valid-VS.cdt
    ├── DRAFT-900021-valid_extensible-VS.cdt
    └── DRAFT-900021-invalid-VS.cdt
```

## Two things that differ from `cdisc/`

- **`#library standard=… version=…` (or `#library-ref`) is mandatory.**
  `RuleTestSuitesDraftFactoryTest.requireDeclaredStandard()` returns `true`,
  as it does for `fda/` and `pmda/`. The draft rules are assembled into up
  to fifteen packages whose views differ, so an undeclared scenario would
  fall through to whichever package sorts last and the membership assertion
  would never run. Declare the standard+version the scenario means:

  | `standard=` | `version=` |
  |---|---|
  | `sdtmig` | `3-2`, `3-3`, `3-4` |
  | `sendig` | `3-0`, `3-1`, `3-1-1` |
  | `sendig-ar` | `1-0` |
  | `sendig-dart` | `1-1`, `1-2` |
  | `sendig-genetox` | `1-0` |
  | `adamig` | `1-0`, `1-1`, `1-2`, `1-3` |
  | `tig` | `1-0` |

  Not every draft rule is in every package — membership comes from each
  rule's `Standards` block; see the `rules-draft-*` entries in the generated
  `target/rules/packages.json` for the current per-package counts (e.g. the
  ADaM drafts reach only the `adamig` flavours). The
  membership check names the package when a declaration and a rule's
  `Standards` block disagree.

- **Draft rules never appear in a production package.** `DRAFT-*` ids are
  assembled into `rules-draft-*.json` only, never into `rules-cdisc-*`,
  `rules-core-*`, `rules-fda-*` or `rules-pmda-*`; conversely the
  `rules-draft-*` packages carry nothing but `DRAFT-*`. Both directions are
  pinned by `RuleTestSuitesFamilyHomeTest.draftRulesShipOnlyIntoDraftPackages`.
