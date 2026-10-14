# Rule fixtures — provenance and licence of the third-party file

This note covers `define-v21-msg-sdtm.xml`, a third-party CDISC example Define-XML document (example metadata, no
study data). The other files in this folder are small Define-XML fixtures written for the rule tests; none carries a
CDISC example header or a CDISC example study identifier (measured by search). Per the project owner (2026-10-14), the third-party test files of this project were taken from three repositories:
`cdisc-org/DataExchange-DatasetJson`, `cdisc-org/sdtm-adam-pilot-project` and `phuse-org/phuse-scripts`.

md5 is of the file as committed. **identical** = byte-identical to the named source file; **derived** = made from
the source content, as described; **not identified** = the exact source could not be shown. Comparisons were made on
2026-10-14 against the GitHub copies at the commits named; "(inference)" marks what was not measured.

| Path | md5 | Relationship to the source |
|---|---|---|
| `define-v21-msg-sdtm.xml` | `cba534dfd19fc379fe40050fbc47824e` | **derived** from `examples/sdtm/define.xml` as of commits `925754def9` / `de5dc15f` (md5 `e695e993…`): 57 of 11 789 lines differ (measured `diff`): the 28 `def:leaf` archive locations and titles name `<dataset>.xml` instead of `<dataset>.json` (56 lines), and `IT.EX.EXROUTE` carries an added `def:CommentOID="COM.DTC1"`. Against HEAD (`.xpt` leaves since `bd3a79ad`) it also differs in `CreationDateTime`/`AsOfDateTime` and `def:Context` (`Other` here, `Submission` there). The `.xml` leaves are the original MSG v2 (Dataset-XML) naming, so this may be an earlier release of the same CDISC example (inference); no published copy with these exact bytes was found |

## Source

### `cdisc-org/DataExchange-DatasetJson`

- **Repository:** <https://github.com/cdisc-org/DataExchange-DatasetJson>; HEAD of `master` at retrieval was
  `a379f49a3f43c2aaed63bdeca761bdb7140df2c3` (committed 2025-12-06), retrieved 2026-10-14.
- **File:** the CDISC Metadata Submission Guidelines v2 example Define-XML 2.1 (header: *"File: defineV21-MSGv2.xml,
  Author: CDISC MSG v2 Team"*, `Originator="CDISC MSG Team"`). It entered the repository in commit `925754def9`
  (2021-06-25) as `examples/Metadata_Submission_Guide_2_0/Jozef/define.xml` (md5 `e695e993c264aef6edf6e07dc022ef16`),
  was moved unchanged to `examples/sdtm/define.xml` in commit `de5dc15f` (2022-04-01), and was edited in commit
  `bd3a79ad` (2023-06-28; md5 at HEAD `1d20c8a430457af2c50a820b22e40e1e`).
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

The MIT condition requires this notice in all copies or substantial portions; this section carries it for the files
derived from this repository.
