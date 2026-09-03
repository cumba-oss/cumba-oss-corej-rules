#!/usr/bin/env python3
"""Census of ADaM ``y`` index-placeholder rules against the 8-character variable-name cap.

Answers the question queue row 2 (``GRy -> Gy``) was stuck on: for every rule carrying a
``wildcards.y`` range, which of its name templates are still legal when ``y`` expands to two
digits, and does the rule's declared range agree with the range its own ``Source`` text states?

Run from ``lib/corej-cdisc-rules/rules-src/checks``::

    python3 ../../scripts/adam-y-index-legality-census.py

Three cuts are reported:

A. the declared ``wildcards.y.max`` disagrees with the ``[1-N]`` in ``Source.Variable_or_Item``
B. capped at 9, ships to ADaMIG 1.1+, and has at least one leaf that IS legal at two digits
   -- the under-report set: a sponsor may legally write the variable and no rule expands to it
C. mixed legality -- ONE range governs leaves that differ in 8-char legality at two digits.
   This is the structural finding: the index range is rule-scoped, the length constraint is
   leaf-scoped, so for these rules no single range can be correct.

.. warning::
   Parse the sheet range with ``\\[1-(\\d+)``, never ``\\[1-(9|99)``. Python's alternation is
   ordered, so ``(9|99)`` matches the ``9`` inside ``[1-99]`` and silently reports every
   two-digit sheet range as a one-digit one. That bug inverted this census on its first run:
   it reported 58 rules where the corpus was WIDER than the sheet, when in fact 20 rules are
   NARROWER and none is wider.

.. note::
   A name is legal iff ``len(template) - 1 + 2 <= 8`` (the ``y`` placeholder is one character
   and expands to two digits). Wildcard-root templates (``*GRy``) are excluded from the legality
   cuts: their length depends on the matched root, so legality is per-variable, not per-template.
"""

from __future__ import annotations

import glob
import os
import re
from typing import Any

import yaml

NAME_RE = re.compile(r"\b([A-Z*][A-Z0-9*]*y[A-Z0-9]*)\b")
SHEET_RANGE_RE = re.compile(r"\[1-(\d+)")
NAME_CAP = 8


def _templates(doc: dict[str, Any]) -> list[str]:
    """Every ``y``-bearing name template mentioned anywhere in the rule."""
    return sorted({n for n in NAME_RE.findall(yaml.dump(doc)) if "y" in n})


def _sheet_range(doc: dict[str, Any]) -> int | None:
    """The upper bound the rule's own ``Source.Variable_or_Item`` states, if any."""
    source = doc.get("Source")
    if not isinstance(source, dict):
        return None
    match = SHEET_RANGE_RE.search(source.get("Variable_or_Item", ""))
    return int(match.group(1)) if match else None


def _versions(doc: dict[str, Any]) -> list[str]:
    return sorted(
        {
            inner.get("Version")
            for outer in (doc.get("Standards") or [])
            for inner in (outer.get("Standards") or [])
        }
    )


def census() -> list[dict[str, Any]]:
    rows = []
    for path in glob.glob("*/*.yaml"):
        try:
            doc = yaml.safe_load(open(path, encoding="utf8"))
        except yaml.YAMLError:
            continue
        if not isinstance(doc, dict):
            continue
        wildcards = doc.get("wildcards") or {}
        if "y" not in wildcards:
            continue
        names = _templates(doc)
        concrete = [n for n in names if not n.startswith("*")]
        rows.append(
            {
                "rule": os.path.basename(path)[: -len(".yaml")],
                "max": wildcards["y"].get("max"),
                "sheet": _sheet_range(doc),
                "versions": _versions(doc),
                "legal": [n for n in concrete if len(n) + 1 <= NAME_CAP],
                "illegal": [n for n in concrete if len(n) + 1 > NAME_CAP],
                "wildcard": [n for n in names if n.startswith("*")],
            }
        )
    return sorted(rows, key=lambda r: r["rule"])


def main() -> None:
    rows = census()
    print(f"rules carrying a 'y' index wildcard: {len(rows)}")
    print(f"  no parseable Source range: {sum(1 for r in rows if r['sheet'] is None)}")

    cut_a = [r for r in rows if r["sheet"] and r["max"] != r["sheet"]]
    print(f"\n== A. declared range != the range the rule's own Source states: {len(cut_a)}")
    for r in cut_a:
        print(f"  {r['rule']:16s} ours[1-{r['max']}] sheet[1-{r['sheet']}] "
              f"ADaMIG {','.join(r['versions']):18s} legal@2={r['legal']} illegal@2={r['illegal']}")

    cut_b = [r for r in rows
             if r["max"] == 9 and any(v != "1.0" for v in r["versions"]) and r["legal"]]
    print(f"\n== B. capped at 9, ships to 1.1+, has a leaf legal at two digits: {len(cut_b)}")
    print(f"     of which the Source ALSO says [1-9]: {sum(1 for r in cut_b if r['sheet'] == 9)}")
    print(f"     of which the rule also has an illegal leaf: "
          f"{sum(1 for r in cut_b if r['illegal'])}")

    cut_c = [r for r in rows if r["legal"] and r["illegal"]]
    at_99 = [r for r in cut_c if r["max"] == 99]
    print(f"\n== C. mixed legality under ONE range: {len(cut_c)}"
          f"  (capped at 9: {sum(1 for r in cut_c if r['max'] == 9)}, at 99: {len(at_99)})")
    print("     capped at 99 -- the illegal leaf is being expanded today:")
    for r in at_99:
        print(f"       {r['rule']:16s} ADaMIG {','.join(r['versions']):18s} "
              f"legal={r['legal']} illegal={r['illegal']}")


if __name__ == "__main__":
    main()
