# Census artifacts — provenance (the W39-B1 owner ruling, carried here since wave 43)

The committed census artifacts beside this file — `d3-population-b-map.csv`,
`d3-population-b-summary.json`, `variable-core-impact.csv`/`.json`,
`variable-core-impact-v2.csv`/`.json` — are **frozen one-off measurements**. Their generators
(`d3-population-b-census.py`, `variable-core-impact.py`, `variable-core-impact-v2.py`) were
**retired at the wave-43 flip** (`PLAN-rules-src-expression-flip.md` §7b: legacy-Check parsers
with no ongoing protection), so the artifacts are **no longer reproducible** and must be read as
historical measurements of the pre-flip corpus (rule counts cite the corpus size of their day,
e.g. 3 729).

**The W39-B1 owner ruling (2026-08-13), previously carried in the generators' docstrings — the
artifacts themselves cannot hold it (CSV has no comment syntax; a JSON key would be wiped by the
next `json.dump`):**

- The `Fix #245` SEND-id rewrite of these artifacts is **KEPT** (owner: keep the rewrite plus a
  provenance note).
- `variable-core-impact-v2` once asserted its v1 sibling was *"left BYTE-UNCHANGED"* — **that
  claim was falsified by `Fix #245`**: both sides moved by the same substitution, so the v1↔v2
  **diff is still valid**, but the byte-unchanged guarantee **must not be used as an integrity
  check**.

If a new measurement is ever needed, author a new tool against the expression corpus (the shared
reader is `scripts/lib/check_expr.py`); do not resurrect the deleted parsers.
