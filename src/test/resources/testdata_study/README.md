# Trimmed pilot study fixture — provenance and licence

The two `define.xml` files here are third-party CDISC Define-XML 1.0 documents of the CDISC pilot study
`CDISCPILOT01`, trimmed by `PruneDefineXmlDriver` to the datasets the Java-resident rule tests read. Per the project owner (2026-10-14), the third-party test files of this project were taken from three repositories:
`cdisc-org/DataExchange-DatasetJson`, `cdisc-org/sdtm-adam-pilot-project` and `phuse-org/phuse-scripts`.
The `.dsjc` datasets beside them are not covered by this note.

md5 is of the file as committed. **identical** = byte-identical to the named source file; **derived** = made from
the source content, as described; **not identified** = the exact source could not be shown. Comparisons were made on
2026-10-14 against the GitHub copies at the commits named; "(inference)" marks what was not measured.

| Path | md5 | Relationship to the source |
|---|---|---|
| `sdtm/define.xml` | `487a6d98c8bc99e6fdcea26040dbad22` | **derived** from `updated-pilot-submission-package/900172/m5/datasets/cdiscpilot01/tabulations/sdtm/define.xml` (`sdtm-adam-pilot-project`, md5 `ecbefff4cf1879b6ebc606e76706fc87`): pruned to the six domains AE, DM, DS, LB, RELREC and TA with their item, code-list, method and comment definitions, re-serialised (attribute order, layout), and the six archive-location leaves renamed from `<domain>.xpt` to `<domain>.dsjc`. Every one of its 208 identified elements is present in the source; 194 are equal in attributes and content, the other 14 differ only by those leaf names (measured, element-wise); produced by `PruneDefineXmlDriver` (inference: from the full pilot define held outside git under `testdata/study/`) |
| `adam/define.xml` | `343f9b3f71b56c6d366f2003cfd1a4bf` | **derived** from `updated-pilot-submission-package/900172/m5/datasets/cdiscpilot01/analysis/adam/datasets/define.xml` (`sdtm-adam-pilot-project`, md5 `a52260c0e742b1017802549f360fc7a8`; `phuse-scripts` `data/adam/cdisc/define.xml` holds the same bytes): pruned by `PruneDefineXmlDriver` to ADSL, ADAE and ADLBC, re-serialised, the three archive leaves renamed from `<dataset>.xpt` to `<dataset>.dsjc`, and two ItemDefs `ADLBC.AXDTM` / `ADLBC.AXTM` with their ItemRefs added (commit `e5e06f61`, 2026-09-17). 587 of its 597 identified elements are equal to the source's (measured, element-wise) |

## Source

### `cdisc-org/sdtm-adam-pilot-project`

- **Repository:** <https://github.com/cdisc-org/sdtm-adam-pilot-project>; HEAD of `master` at retrieval was
  `667511d4b183871d74392ba691c935c38d431d39` (committed 2024-12-20), retrieved 2026-10-14. The files used were last
  changed in commit `2f1a3eb6` (2019-05-23).
- **Content:** the CDISC SDTM/ADaM pilot submission package, study `CDISCPILOT01` (example data, not real study data).
- **Terms:** the repository has **no** `LICENSE` file. Its `README.md` ("DISCLAIMER AND LIABILITY", "Terms of Use")
  says, in the part bearing on redistribution: *"By downloading / using this data, you agree to the Terms of use. …
  You shall not (and will not allow or assist any third party to), under any circumstances: … (b) disassemble,
  reverse engineer, decompile, modify, or alter any part of the Data; … (e) sublicense or distribute the data for a
  fee, or (f) sublicense or distribute the data without an attribution back to CDISC."* This section is that
  attribution: the data is CDISC's, from the CDISC SDTM/ADaM pilot project (study `CDISCPILOT01`).
