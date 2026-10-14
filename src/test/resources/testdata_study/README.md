# Test study fixture — provenance and licence

The Java-resident rule suites read this folder (`AbstractSdtmRuleTest`, `AbstractAdamRuleTest`): each `define.xml`
is opened as a library and its archive-location leaves name the `.dsjc` datasets beside it. Per the project owner
(2026-10-14), the third-party test files of this project were taken from three repositories:
`cdisc-org/DataExchange-DatasetJson`, `cdisc-org/sdtm-adam-pilot-project` and `phuse-org/phuse-scripts`.

- `adam/` comes from **`cdisc-org/DataExchange-DatasetJson`** (MIT; 2026-10-14, `PLAN-mit-fixture-resourcing`). It
  replaced the earlier ADaM fixture, which was a modified copy of the CDISC pilot ADaM data.
- `sdtm/` is the CDISC **pilot** SDTM data (`cdisc-org/sdtm-adam-pilot-project`), kept as a recorded risk: see
  [The pilot data's terms](#the-pilot-datas-terms--accepted-risk-and-pending-request).

md5 is of the file as committed. **identical** = byte-identical to the named source file; **derived** = made from
the source content, as described; **own** = written for this project. Comparisons were made on 2026-10-14 against the
GitHub copies at the commits named; "(inference)" marks what was not measured.

## `adam/` — from `cdisc-org/DataExchange-DatasetJson`

| Path | md5 | Relationship to the source |
|---|---|---|
| `adam/adsl.dsjc` | `45f8a7c04dcdd7fa5ee58acc260e8fbd` | **identical** to `examples/adam/adsl.dsjc` |
| `adam/adae.dsjc` | `8f2cdec712034e4856110ce9afc3b2ff` | **identical** to `examples/adam/adae.dsjc` |
| `adam/adlbc.dsjc` | `3ee0ba19e49194128e8d4f5d56583760` | **derived** from `examples/adam/adlbc.dsjc` (md5 `f4095b77a8de8bc72a8babd2d5378177`) by `add_adlbc_iso_columns.py`: two empty ISO 8601 text columns `AXDTM` (length 20) and `AXTM` (length 8) appended to the metadata and to every row; every upstream row is otherwise byte-identical (measured: the decompressed rows with the two appended cells removed equal the source's decompressed row lines, md5 `d4490b00dbd6301b9c7b8435058a290a`); the script refuses any input whose md5 is not the source's |
| `adam/define.xml` | `4e0976c10132de4d064357365213ec4c` | **derived** from `examples/adam/define.xml` (Define-XML 2.1, md5 `053254bebd18b4d2316854cb0b409f00`; the driver refuses any other input) by `PruneDefineXmlDriver` (`-Dtest=PruneDefineXmlDriver#pruneAdamDefine -Dprune.defines=true -Dprune.adam.source=<checkout>/examples/adam/define.xml`): pruned to `IG.ADSL`, `IG.ADAE`, `IG.ADLBC` with the item, code-list, method, comment, value-list and where-clause definitions they use, re-serialised (attribute order, layout); the analysis results display `RD.Table14.3.01` removed (it analyses `IG.ADADAS`, which is not kept; `RD.Table14.5.02`, on `IG.ADAE`, stays), so none of `ADADAS`'s definitions survive; the driver checks that every reference resolves and no definition is left unreferenced; the three dataset leaves renamed from `<dataset>.xpt` to `<dataset>.dsjc` (`href` and title); `ItemDef`s and `ItemRef`s `IT.ADLBC.AXDTM` / `IT.ADLBC.AXTM` added for the two columns above (origin `Assigned` by the sponsor, since a `Derived` origin needs a method, `DEFINE-XML-0073`; the driver checks their OID, name, label and length against `adlbc.dsjc`); the `ODM` attributes `SourceSystem` / `SourceSystemVersion` removed (they name the tool that wrote the upstream file, not this one). The leaves `LF.Suppdoc` (`adrg.pdf`) and `LF.Suppdoc2` (`complex-algorithms-example.pdf`) stay because document references point at them; those PDFs are not copied here |
| `adam/add_adlbc_iso_columns.py` | `2a942cb6ad2ebfff0db44781fe60226f` | **own**: the step that makes `adlbc.dsjc` from the upstream file (usage in its docstring) |

**Why `AXDTM` / `AXTM`.** The ADaM template tests need a `*DTM` / `*TM` column pair on a BDS dataset to build their
`CDISC-AD0044` cases, and `CDISC-AD0369`'s `AXDTF` case follows from `AXDTM`; the harness fills in the values each case
needs. The pair was first added to the earlier pilot-derived `adlbc.dsjc` (commit `e5e06f61`, 2026-09-17) and is
re-applied here. Without it `AdamTemplateDeclaredStructureProbeTest.caseInventory_matchesTheRecordedList` reds on the
missing `CDISC-AD0369-AXDTF@ADLBC` case (measured 2026-10-14).

**How this data differs from the pilot ADaM data it replaced** (measured 2026-10-14; the DatasetJson define describes
it as *"Test datasets created by updating existing CDISCPILOT ADaM datasets"*, ADaMIG 1.1). The ADaM suites' results
did not move: the same 472 template cases with the same outcomes, the same 22-case `EXPECTED_CASES` roster, and only
column *names* differed in the loaded schemas.

- Dates are Dataset-JSON `date` values (ISO 8601 text) instead of bare SAS day numbers; they load as the same numbers.
- `ADSL` (254 rows, same subjects): `TRTDUR` is `TRTDURD` (same values); `DCREASCD` is `DCSREAS` (empty for the 110
  completers, which said `Completed`), and `EOSSTT` is new.
- `ADAE` (1 191 rows): `TRTEMFL` is empty in 65 rows that said `N`.
- `ADLBC`: 37 132 rows, not 74 264 — the 18 "change from previous visit" parameters (`PARAMCD` `_ALB` … `_URATE`) are
  absent; dates are about ten years later than the pilot's (for example `TRTSDT` 2024-01-03 instead of 2014-01-02), so
  `ADLBC.TRTSDT` / `TRTEDT` do not equal `ADSL`'s for the same subject; analysis values are `decimal` (string-encoded).

## `sdtm/` — from `cdisc-org/sdtm-adam-pilot-project` (recorded risk)

| Path | md5 | Relationship to the source |
|---|---|---|
| `sdtm/define.xml` | `487a6d98c8bc99e6fdcea26040dbad22` | **derived** from `updated-pilot-submission-package/900172/m5/datasets/cdiscpilot01/tabulations/sdtm/define.xml` (md5 `ecbefff4cf1879b6ebc606e76706fc87`): pruned to the six domains AE, DM, DS, LB, RELREC and TA with their item, code-list, method and comment definitions, re-serialised (attribute order, layout), and the six archive-location leaves renamed from `<domain>.xpt` to `<domain>.dsjc`. Every one of its 208 identified elements is present in the source; 194 are equal in attributes and content, the other 14 differ only by those leaf names (measured, element-wise); produced by `PruneDefineXmlDriver` (inference: from the full pilot define held outside git under `testdata/study/`) |
| `sdtm/ae.dsjc` | `e891a7f1bf622b85c528470230bf29a0` | **derived** from `…/tabulations/sdtm/ae.xpt` (md5 `e7de1f518101c5670789db31cafa824e`) |
| `sdtm/dm.dsjc` | `df620c0c66dc390f93dc53ece340fb92` | **derived** from `…/tabulations/sdtm/dm.xpt` (md5 `9c8ddfc5f7a1fa233667ea889f420775`) |
| `sdtm/ds.dsjc` | `6e172b1e3d7ccbd50deb088949902491` | **derived** from `…/tabulations/sdtm/ds.xpt` (md5 `5f2a52a8dbfe0c25e42fd34faee14624`) |
| `sdtm/lb.dsjc` | `705feb261774d486ca0fda5af2b1741f` | **derived** from `…/tabulations/sdtm/lb.xpt` (md5 `c31ba158fe500d68d22453aa8d64a44b`) |
| `sdtm/relrec.dsjc` | `aaa026c646f9170d4e9a44f944aacc84` | **derived** from `…/tabulations/sdtm/relrec.xpt` (md5 `e26c8fbed053faf54fc19b909bb528ac`) |
| `sdtm/ta.dsjc` | `9125ee7bf427573ce252f917a0bde68f` | **derived** from `…/tabulations/sdtm/ta.xpt` (md5 `4bb67aa45093fab8e6f39c7260f99a50`) |

The six datasets are the pilot's SAS XPORT files converted to zlib-compressed Dataset-JSON 1.1 (`sourceSystem`
`CumbaDataTable`). The values are unchanged: same rows in the same order, same columns, and every cell equal once
trailing blanks are trimmed and an empty character value is read as missing (measured 2026-10-14 against the `.xpt`
files at `667511d4`, read with pandas).

CDISC's pilot repository itself publishes these six domains as Dataset-JSON 1.1:
`…/tabulations/sdtm/{ae,dm,ds,lb,relrec,ta}.json` (commits `8646d29` "Add Dataset-JSON" and `b8068a0`, both
2024-12-18). Our values equal those too: same columns, same rows in the same order, every cell equal once trailing
blanks are trimmed, with `LB`'s floating-point results equal to the last bit (largest relative difference 2.2e-16;
measured 2026-10-14 at `667511d4`). So the format conversion is CDISC's own; what remains our modification is the
domain subset, the pruned define and our `.dsjc` serialisation of the same values.

## Sources

### `cdisc-org/DataExchange-DatasetJson`

- **Repository:** <https://github.com/cdisc-org/DataExchange-DatasetJson>; HEAD of `master` at retrieval was
  `a379f49a3f43c2aaed63bdeca761bdb7140df2c3` (committed 2025-12-06), retrieved 2026-10-14. The files used:
  `examples/adam/adsl.dsjc`, `adae.dsjc`, `adlbc.dsjc` (last changed in commit `ab3173d`, 2025-06-19) and
  `examples/adam/define.xml` (last changed in commit `23b6435`, 2023-06-22).
- **Content:** CDISC's Dataset-JSON examples of the ADaM data of the CDISC pilot study `CDISCPILOT01`, updated by
  the CDISC ADaM MSG team (example data, not real study data).
- **Licence:** the repository's `LICENSE`, the MIT License with the copyright line *"Copyright (c) 2022 cdisc"*,
  reproduced in full below. Nothing in that repository excludes `examples/` from it (inference: the repository-wide
  licence covers the example files).

```text
MIT License

Copyright (c) 2022 cdisc

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

The MIT condition requires this notice in all copies or substantial portions; this section carries it for the
`adam/` files.

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
  attribution: the `sdtm/` data is CDISC's, from the CDISC SDTM/ADaM pilot project (study `CDISCPILOT01`).

#### The pilot data's terms — accepted risk and pending request

The `sdtm/` files are a format conversion (SAS XPORT → Dataset-JSON) and a domain subset of the pilot data, and the
define is trimmed to those domains; clause (b) forbids modifying the data. The pilot repository publishes the same six
domains as Dataset-JSON 1.1 itself (see the `sdtm/` section), so the modification that remains is the subset, the
pruned define and our serialisation of the values. The project owner accepted this as a
recorded risk (2026-10-14). DatasetJson's own SDTM example is a different study (SDTM-MSG v2, other subjects and
columns), so it cannot replace these files without rewriting the SDTM suites' expectations. A request to CDISC for
permission to redistribute the converted, subset data with attribution is **pending**: drafted 2026-10-14 for the
owner to send; its answer will be recorded here. If CDISC refuses, the SDTM fixture is re-sourced under a new plan.
