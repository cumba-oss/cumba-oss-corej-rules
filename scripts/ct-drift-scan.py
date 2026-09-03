#!/usr/bin/env python3
"""CT drift scan — which rules are exposed to a codelist that moved after their sheet?

Commissioned by owner ruling `M2-D29` (2026-08-21, option D), recorded in
`documentation/rule-review-results-r3/DECISION-LANE-M2-CONVENTIONS.md`:

    "the tool can check when a CT that is used in a rule was last changed and
     notify if the CT should be revisited + for which rules."

It exists because of the standing policy ruled beside it (`M2-D29` option A):
**coreJ tracks the current CT package.** Where CT has moved past the authority
sheet, the corpus follows CT and a sheet issue records the divergence. This
scan is what finds those places. The first known instance —
`PMDA-SD1363`/`FDA-SD1363`, whose inline `["SCREEN FAILURE", "NOT ASSIGNED"]`
mirrors the `ARMNULRS` codelist that gained `SCREENING NOT COMPLETED` in the
2025-09-26 SDTM CT package — is the tool's acceptance case.

⛔ **NOT a build-gate tool.** It is a periodic scan: nothing in `pom.xml` calls
it, nothing should. It reads the corpus and the CT metadata cache read-only and
writes exactly one file, its report. Re-running it with unchanged inputs
rewrites byte-identical output (no timestamps).


Usage
-----

    python3 lib/corej-cdisc-rules/scripts/ct-drift-scan.py

Run it from anywhere; every path is resolved from this file's location. Options:

    --ct-cache DIR   CDISC pickle cache (default: $CDISC_PICKLE_CACHE_DIR, else
                     /data/cdisc.metadata.library-cache-pkl)
    --checks DIR     rules corpus (default: ../rules-src/checks beside this file)
    --out PATH       report path (default: ../documentation/ct-drift-scan-report.md)
    --tsv PATH       also write the per-row table as TSV (optional; off by default)
    --quiet          suppress the stdout summary

**How often.** After every CDISC CT package refresh — CT ships quarterly, so
quarterly is the natural cadence — and again before any rule-review wave that
touches a CT-bound family. It is cheap (~10 s: it reads 133 CT packages) and
read-only, so running it more often costs nothing.


What it answers
---------------

For every rule that is bound to a *named* CDISC codelist, it reports:

* which codelist (submission value + C-code),
* when that codelist last changed, from the local CT package history,
* the rule's own authority date — the publication date of the oldest sheet
  version the rule transcribes (`Standards[].References[].Origin` + `.Version`),
* a verdict, and for frozen literals the terms that appeared *after* the
  authority date.

**Verdicts**

* ``REVISIT`` — the codelist changed after the rule's authority sheet was
  written. The rule may encode a vocabulary that CT has since moved past. Read
  the *Binding* column before acting:
  - ``frozen-literal`` — the rule spells the terms out in its `Check`. These
    are the actionable ones: a term added or renamed in CT is **not** picked up.
  - ``live-named`` — the rule names the codelist but reads its terms from the
    library at run time (`codelist_terms` operation, `var_ccode(...) == "C…"`).
    Behaviour tracks CT automatically; what may need a revisit is the rule's
    *wording*, its `.cdt` scenarios, and the sheet row behind it.
* ``OK`` — the codelist has not changed since the authority date.
* ``UNKNOWN`` — the authority date is not recoverable (see the authority table
  in the report), or the codelist is not in the local CT cache. Neither
  direction is claimed.

Rules whose CT binding is resolved from the *data* at run time
(`var_codelist_coded_values("LIBRARY")` and friends, `get_codelist_attributes`)
cannot be attributed to one codelist. They are listed separately, as
``live-dynamic``, with no verdict: they are exposed to every codelist and track
CT by construction.


Data sources, and how coarse each one is
----------------------------------------

1. **CT change dates** — the local CDISC Library pickle cache
   (`$CDISC_PICKLE_CACHE_DIR`, normally `/data/cdisc.metadata.library-cache-pkl`).
   Each `sdtmct-YYYY-MM-DD.pkl` / `sendct-*` / `adamct-*` / `cdashct-*` holds
   one published CT package: `{'package': …, 'codelists': [ {conceptId,
   submissionValue, extensible, terms:[{conceptId, submissionValue, …}]}, … ]}`.
   ⚠ **There are no per-term dates in this data.** "Last changed" is derived by
   diffing consecutive packages: the newest package whose codelist fingerprint
   differs from its predecessor's. The fingerprint is
   ``(submissionValue, extensible, sorted (term C-code, term submission value))``
   — so a renamed codelist, a flipped extensible flag, and any added / removed /
   renamed term all count as a change; a reworded *definition* or preferred term
   does not.
   ⚠ **The date is therefore the package the change was first observed in, not
   the day CDISC made it.** The cache is not complete (e.g. SDTM CT 2024-12-20
   and 2025-06-27 are absent), so a change can be attributed to a package up to
   two quarters later than the one that really carried it. It is never
   attributed *earlier* than the truth, so a `REVISIT` is never manufactured by
   the gap — an `OK` could in principle hide one. Verdicts are *conservative in
   the safe direction*, and the report prints the package inventory it used.

2. **Variable → codelist binding** — `variable_codelist_maps.pkl` from the same
   cache: per IG version, variable name → CDISC codelist C-code(s). This is what
   turns an inline literal set on `ARMNRS` into "this rule is bound to C142179".

3. **Authority dates** — `AUTHORITY_DATES` below: seven `(Origin, Version)`
   pairs is the whole corpus, so they are a curated table rather than a parse,
   and each entry carries its provenance. ⚠ A *sheet* has no machine-readable
   publication date; four entries use the workbook's own `docProps` last-saved
   date, one uses the release filename, one is an explicit lower bound, and one
   (TIG) is unknown because no copy exists on this machine. When a new sheet
   version enters the corpus this table **must** be extended — otherwise every
   rule citing it reports `UNKNOWN`, which is the intended failure mode.


Limitations
-----------

* Single-literal comparisons on a CT-bound variable (`DTHFL == "Y"`) are
  **not** scanned. They select one term rather than enumerate a codelist, so a
  new term does not invalidate them; scanning them would add hundreds of rows
  with no action attached. The count is reported in the summary.
* A rule that enumerates a vocabulary on a variable with **no** library codelist
  binding (`ARMCD in ["SCRNFAIL", "NOTASSGN"]` — SDTMIG prose, not CT) is caught
  only by the term-set heuristic, and only when the literals are a subset of
  exactly one codelist in the cache. Ambiguous matches are dropped rather than
  guessed, so this signal under-reports by design.
* Comparison is against the *oldest* authority the rule cites, which is the
  conservative choice for a revisit prompt: a rule citing both FDA v1.6 (2022)
  and PMDA v6.0 (2025) is judged against 2022. The newest authority is shown in
  the table so the reader can discount accordingly.
* CT packages before the earliest one in the cache are invisible: a codelist
  present in the oldest package is treated as unchanged since then, not as
  changed on that date.
* Every date here is a *package* or *document* date. Nothing in this tool
  reports a per-term change date, because no source on this machine has one.
"""

import argparse
import collections
import datetime
import glob
import os
import pickle
import re
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.normpath(os.path.join(HERE, os.pardir))
CHECKS = os.path.join(MODULE, "rules-src", "checks")
DOCS = os.path.join(MODULE, "documentation")
REPORT = os.path.join(DOCS, "ct-drift-scan-report.md")
DEFAULT_CACHE = os.environ.get("CDISC_PICKLE_CACHE_DIR",
                               "/data/cdisc.metadata.library-cache-pkl")

sys.path.insert(0, os.path.join(HERE, "lib"))
import check_expr as ce  # noqa: E402  (path is set above, as the sibling tools do)


# --------------------------------------------------------------------------
# Constants — every classification threshold the docstring states in prose.
# --------------------------------------------------------------------------

#: CT package families the scan loads. Keys are the pkl filename prefixes.
CT_FAMILIES = ("sdtmct", "sendct", "adamct", "cdashct")

#: `Standards[].Standards[].Name` -> CT families that standard draws terms from.
STANDARD_CT_FAMILY = {
    "SDTMIG": ("sdtmct",),
    "SENDIG": ("sendct",),
    "SENDIG-AR": ("sendct",),
    "SENDIG-DART": ("sendct",),
    "SENDIG-GENETOX": ("sendct",),
    "ADaMIG": ("adamct",),
    "TIG": CT_FAMILIES,
}

#: `(Name, Version)` -> keys in `variable_codelist_maps.pkl`. A standard version
#: with no map of its own falls back to the nearest published one (noted here,
#: never guessed silently).
VARIABLE_MAP_KEY = {
    ("SDTMIG", "3.1.2"): ("sdtmig-3-1-2-codelists",),
    ("SDTMIG", "3.1.3"): ("sdtmig-3-1-3-codelists",),
    ("SDTMIG", "3.2"): ("sdtmig-3-2-codelists",),
    ("SDTMIG", "3.3"): ("sdtmig-3-3-codelists",),
    ("SDTMIG", "3.4"): ("sdtmig-3-4-codelists",),
    ("SENDIG", "3.0"): ("sendig-3-0-codelists",),
    ("SENDIG", "3.1"): ("sendig-3-1-codelists",),
    ("SENDIG", "3.1.1"): ("sendig-3-1-1-codelists",),
    ("SENDIG-AR", "1.0"): ("sendig-ar-1-0-codelists",),
    ("SENDIG-DART", "1.1"): ("sendig-dart-1-1-codelists",),
    # No `sendig-dart-1-2` map is published; DART 1.2 reuses the 1.1 bindings.
    ("SENDIG-DART", "1.2"): ("sendig-dart-1-1-codelists",),
    ("SENDIG-GENETOX", "1.0"): ("sendig-genetox-1-0-codelists",),
    ("ADaMIG", "1.0"): ("adam-adamig-1-0-codelists",),
    ("ADaMIG", "1.1"): ("adam-adamig-1-1-codelists",),
    ("ADaMIG", "1.2"): ("adam-adamig-1-2-codelists",),
    ("ADaMIG", "1.3"): ("adam-adamig-1-3-codelists",),
    ("TIG", "1.0"): ("tig-1-0-sdtm-codelists", "tig-1-0-send-codelists",
                     "tig-1-0-adam-codelists", "tig-1-0-cdash-codelists"),
}

#: `(Origin, Version)` -> (ISO date or None, precision, provenance). See the
#: module docstring: a sheet carries no machine-readable publication date, so
#: each entry names the artefact its date came from.
AUTHORITY_DATES = {
    ("FDA Validator Rules", "1.6"): (
        "2022-12-22", "document",
        "`specifications/FDA Validator Rules v1.6 December 2022_0.xlsx` "
        "docProps last-saved; the title's \"December 2022\" agrees"),
    ("FDA Business Rules", "1.5"): (
        "2019-05-24", "document",
        "`specifications/FDA Business Rules v1.5 June 2019.xlsx` docProps "
        "last-saved; the title says June 2019, so this is a few days early"),
    ("PMDA Validation Rules", "6.0"): (
        "2025-03-14", "release",
        "PMDA release filename `ValidationRules_20250314.xlsx` "
        "(`specifications/`, also inside `pmda_000274354.zip`); the workbook's "
        "docProps last-saved is 2025-02-25"),
    ("SDTM and SDTMIG Conformance Rules", "2.0"): (
        "2021-11-30", "document",
        "`SDTM_and_SDTMIG_Conformance_Rules_v2.0.xlsx` docProps last-saved; "
        "its Revision History tab's latest entry is 2021-07-11"),
    ("ADaM Conformance Rules", "5.0"): (
        "2023-09-27", "document",
        "`ADaM Conformance Rules v5.0.xlsx` docProps last-saved (by a CDISC "
        "author, so uncontaminated by local edits)"),
    ("SEND Conformance Rules", "5.0"): (
        "2023-06-21", "lower-bound",
        "the shipped workbook's docProps were overwritten by a local save, so "
        "its own date is unusable; `SEND_Conformance_Rules_v5.0_Public_Review_"
        "Comments.xlsx` is 2023-06-21 and the final is on or after that"),
    ("TIG Conformance Rules", "1.0"): (
        None, "unknown",
        "no copy of the TIG conformance-rules sheet exists on this machine"),
}

#: Expression functions that resolve a codelist from the *data* at run time.
#: A rule using one of these is exposed to every codelist, not to a named one.
RUNTIME_CT_FUNCTIONS = {
    "var_codelist", "var_codelist_coded_values", "var_codelist_coded_codes",
    "var_codelist_extensible", "var_codelist_extended_values",
    "library_variable_codelist", "library_variable_codelist_coded_values",
    "library_variable_codelist_coded_codes", "library_variable_ccode",
    "library_variable_code_pair_matches", "define_variable_codelist",
    "define_variable_codelist_coded_values",
    "define_variable_codelist_coded_codes",
    "define_variable_codelist_extended_values", "define_variable_ccode",
    "define_variable_decode_matches", "var_ccode",
}

#: Operations whose codelist identity comes from the data, not from the rule.
RUNTIME_CT_OPERATORS = {"get_codelist_attributes", "valid_codelist_dates"}

#: Operation that names its codelists outright, in `codelists:`.
NAMED_CT_OPERATOR = "codelist_terms"

#: Functions transparent to "which column is being compared".
UNWRAPPERS = {"upper", "lower", "strip", "trim"}

#: Accessors whose C-code argument pins a codelist by identity.
CCODE_PINNING_FUNCTIONS = {"var_ccode", "library_variable_ccode",
                           "define_variable_ccode"}

#: A term-set match with no variable binding is accepted only when the literals
#: are a subset of exactly one codelist AND cover at least this share of it.
MIN_HEURISTIC_COVERAGE = 0.4

#: And only when the set enumerates at least this many distinct values.
MIN_HEURISTIC_TERMS = 2

CCODE_RE = re.compile(r"^C\d{4,6}$")
PACKAGE_DATE_RE = re.compile(r"(\d{4}-\d{2}-\d{2})")


class CtDataUnavailable(Exception):
    """Raised when the CT cache cannot support the comparison at all."""


# --------------------------------------------------------------------------
# CT metadata
# --------------------------------------------------------------------------

class CtIndex:
    """Codelist change history, derived by diffing consecutive CT packages."""

    def __init__(self):
        self.packages = {}      # family -> [date, ...]
        self.entries = {}       # (family, ccode) -> dict
        self.by_value = {}      # (family, submissionValue) -> ccode
        self.term_history = {}  # (family, ccode) -> [(date, frozenset terms)]

    @staticmethod
    def _fingerprint(codelist):
        terms = sorted((t.get("conceptId"), t.get("submissionValue"))
                       for t in codelist.get("terms") or [])
        return (codelist.get("submissionValue"), bool(codelist.get("extensible")),
                tuple(terms))

    @staticmethod
    def _package_files(cache_dir, family):
        found = glob.glob(os.path.join(cache_dir, family + "-*.pkl"))
        dated = []
        for path in found:
            match = PACKAGE_DATE_RE.search(os.path.basename(path))
            if match:
                dated.append((match.group(1), path))
        return sorted(dated)

    @classmethod
    def build(cls, cache_dir, families=CT_FAMILIES):
        """Load every package of every family; keep history + latest terms."""
        if not os.path.isdir(cache_dir):
            raise CtDataUnavailable(
                "CT metadata cache directory not found: %s\n"
                "Point --ct-cache (or $CDISC_PICKLE_CACHE_DIR) at a directory "
                "of CDISC Library package pickles (sdtmct-YYYY-MM-DD.pkl, …)."
                % cache_dir)
        index = cls()
        for family in families:
            files = cls._package_files(cache_dir, family)
            if not files:
                continue
            index.packages[family] = [date for date, _ in files]
            previous = {}
            for position, (date, path) in enumerate(files):
                with open(path, "rb") as handle:
                    package = pickle.load(handle)
                current = {}
                for codelist in package.get("codelists") or []:
                    ccode = codelist.get("conceptId")
                    if not ccode:
                        continue
                    fingerprint = cls._fingerprint(codelist)
                    current[ccode] = fingerprint
                    entry = index.entries.get((family, ccode))
                    if entry is None:
                        entry = {
                            "family": family,
                            "ccode": ccode,
                            "value": fingerprint[0],
                            "first_seen": date,
                            # A codelist that appears mid-history changed on the
                            # package that introduced it; one present in the
                            # oldest package is only known to be "unchanged
                            # since" that package.
                            "last_change": date,
                            "new_in_history": position > 0,
                            "latest": date,
                            "terms": frozenset(),
                            "extensible": fingerprint[1],
                        }
                        index.entries[(family, ccode)] = entry
                    elif previous.get(ccode) != fingerprint:
                        entry["last_change"] = date
                    entry["latest"] = date
                    entry["value"] = fingerprint[0]
                    entry["extensible"] = fingerprint[1]
                    entry["terms"] = frozenset(
                        (t.get("submissionValue") or "").upper()
                        for t in codelist.get("terms") or [])
                    entry["term_ccodes"] = frozenset(
                        t.get("conceptId") for t in codelist.get("terms") or [])
                previous = current
        if not index.entries:
            raise CtDataUnavailable(
                "no CT packages found in %s (looked for %s). The scan cannot "
                "answer its question without them." % (
                    cache_dir, ", ".join(f + "-*.pkl" for f in families)))
        for (family, ccode), entry in index.entries.items():
            index.by_value.setdefault((family, entry["value"]), ccode)
        return index

    def load_term_history(self, cache_dir, wanted):
        """Second pass: full term-set history for the codelists actually hit."""
        by_family = collections.defaultdict(set)
        for family, ccode in wanted:
            by_family[family].add(ccode)
        for family, ccodes in by_family.items():
            for date, path in self._package_files(cache_dir, family):
                with open(path, "rb") as handle:
                    package = pickle.load(handle)
                for codelist in package.get("codelists") or []:
                    ccode = codelist.get("conceptId")
                    if ccode in ccodes:
                        terms = frozenset(
                            (t.get("submissionValue") or "").upper()
                            for t in codelist.get("terms") or [])
                        self.term_history.setdefault(
                            (family, ccode), []).append((date, terms))

    def terms_added_since(self, family, ccode, cutoff):
        """Terms present now but not in the newest package on/before `cutoff`."""
        history = self.term_history.get((family, ccode))
        if not history or not cutoff:
            return None
        baseline = None
        for date, terms in history:
            if date <= cutoff:
                baseline = terms
        if baseline is None:
            return None
        return sorted(history[-1][1] - baseline)

    def lookup(self, families, ccode):
        """Entry for `ccode`, preferring the rule's own CT families."""
        for family in list(families) + [f for f in self.packages
                                        if f not in families]:
            entry = self.entries.get((family, ccode))
            if entry:
                return entry
        return None

    def resolve_value(self, families, value):
        """C-code for a codelist named by submission value."""
        for family in list(families) + [f for f in self.packages
                                        if f not in families]:
            ccode = self.by_value.get((family, value))
            if ccode:
                return ccode
        return None


def load_variable_maps(cache_dir):
    """`variable_codelist_maps.pkl` -> {map key: {variable: [ccode, ...]}}."""
    path = os.path.join(cache_dir, "variable_codelist_maps.pkl")
    if not os.path.exists(path):
        raise CtDataUnavailable(
            "variable_codelist_maps.pkl not found in %s. Without the "
            "variable -> codelist binding the scan cannot attribute an inline "
            "literal set to a codelist." % cache_dir)
    with open(path, "rb") as handle:
        raw = pickle.load(handle)
    maps = {}
    for key, mapping in raw.items():
        bindings = {}
        for variable, ccodes in mapping.items():
            if variable == "name" or not isinstance(ccodes, list):
                continue
            bindings[variable] = [c for c in ccodes if CCODE_RE.match(str(c))]
        maps[key] = bindings
    return maps


# --------------------------------------------------------------------------
# Corpus
# --------------------------------------------------------------------------

def _column_of(node):
    """Column name under any number of `upper()`/`lower()`/… wrappers."""
    while (isinstance(node, ce.Call) and node.name in UNWRAPPERS
           and len(node.args) == 1):
        node = node.args[0]
    if isinstance(node, ce.Ref) and node.kind == "COLUMN":
        return node.name
    return None


def _string_list(node):
    """The string values of a `["A", "B"]` literal, or None."""
    if not (isinstance(node, ce.Lit) and node.kind == "LIST"):
        return None
    values = []
    for item in node.value:
        if not (isinstance(item, ce.Lit) and isinstance(item.value, str)):
            return None
        values.append(item.value)
    return values or None


def walk_expression(node, enums, ccodes, functions, singles):
    """Collect the four expression-level CT signals in one pass."""
    if isinstance(node, (ce.And, ce.Or)):
        for part in node.parts:
            walk_expression(part, enums, ccodes, functions, singles)
    elif isinstance(node, ce.Not):
        walk_expression(node.inner, enums, ccodes, functions, singles)
    elif isinstance(node, ce.Binary):
        left, right = node.left, node.right
        if node.op in ("IN", "NOT_IN"):
            values = _string_list(right)
            column = _column_of(left)
            if column and values:
                enums.append((column, tuple(values)))
        elif node.op in ("EQ", "NEQ"):
            if (isinstance(left, ce.Call)
                    and left.name in CCODE_PINNING_FUNCTIONS
                    and isinstance(right, ce.Lit)
                    and isinstance(right.value, str)
                    and CCODE_RE.match(right.value)):
                ccodes.add(right.value)
            column = _column_of(left)
            if column and isinstance(right, ce.Lit) and isinstance(right.value, str):
                singles.append((column, right.value))
        walk_expression(left, enums, ccodes, functions, singles)
        walk_expression(right, enums, ccodes, functions, singles)
    elif isinstance(node, ce.Call):
        functions.add(node.name)
        for argument in node.args:
            walk_expression(argument, enums, ccodes, functions, singles)
        for argument in (node.kwargs or {}).values():
            if isinstance(argument, ce.Node):
                walk_expression(argument, enums, ccodes, functions, singles)


def scan_rule(path):
    """Read one rule file into the facts the scan needs. Never raises on YAML."""
    with open(path, encoding="utf-8") as handle:
        document = yaml.safe_load(handle) or {}
    rule = {
        "id": (document.get("Core") or {}).get("Id") or
              os.path.splitext(os.path.basename(path))[0],
        "family": os.path.basename(os.path.dirname(path)),
        "path": path,
        "authorities": [],
        "ct_families": set(),
        "map_keys": [],
        "enums": [],
        "ccodes": set(),
        "named": [],
        "runtime": False,
        "singles": [],
        "parse_error": None,
    }
    for organisation in document.get("Standards") or []:
        for standard in organisation.get("Standards") or []:
            name, version = standard.get("Name"), str(standard.get("Version"))
            rule["ct_families"].update(STANDARD_CT_FAMILY.get(name, ()))
            rule["map_keys"].extend(VARIABLE_MAP_KEY.get((name, version), ()))
            for reference in standard.get("References") or []:
                origin = reference.get("Origin")
                sheet = str(reference.get("Version"))
                if origin:
                    rule["authorities"].append((origin, sheet))
    rule["authorities"] = sorted(set(rule["authorities"]))
    rule["map_keys"] = list(dict.fromkeys(rule["map_keys"]))
    # A tuple, not the set: set-of-str iteration order varies with the hash
    # seed, and `CtIndex.lookup` resolves a shared C-code by family order.
    rule["ct_families"] = tuple(sorted(rule["ct_families"]) or CT_FAMILIES)

    expression = (document.get("Check") or {}).get("expression")
    if expression:
        try:
            tree = ce.parse(expression)
        except Exception as error:                      # noqa: BLE001
            rule["parse_error"] = "%s: %s" % (type(error).__name__, error)
        else:
            functions = set()
            walk_expression(tree, rule["enums"], rule["ccodes"], functions,
                            rule["singles"])
            if functions & RUNTIME_CT_FUNCTIONS:
                rule["runtime"] = True

    for operation in document.get("Operations") or []:
        operator = operation.get("operator")
        expression = operation.get("expression") or ""
        if not operator:
            # Form B (PLAN-retire-corpus-transforms phase 8): the operator is the leading
            # function name of the expression.
            match = re.match(r"\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(", expression)
            operator = match.group(1) if match else None
        if operator == NAMED_CT_OPERATOR:
            codelists = operation.get("codelists")
            if codelists is None:
                # Form B: codelists=[...] kwarg in the expression text.
                m = re.search(r"codelists=\[([^\]]*)\]", expression)
                codelists = ([t.strip().strip('"') for t in m.group(1).split(",")]
                             if m else [])
            rule["named"].extend(codelists or [])
        if operator in RUNTIME_CT_OPERATORS:
            rule["runtime"] = True
        # An operation filter can freeze a vocabulary exactly as an inline
        # `in [...]` does — field form `filter: {DSDECOD: [TERMINAL SACRIFICE, ...]}`, or
        # Form B `filter=filter(DSDECOD=["TERMINAL SACRIFICE", ...])` (the corpus's only
        # form since PLAN-retire-corpus-transforms phase 8; 4 list-valued filter kwargs
        # measured at the migration).
        for variable, values in (operation.get("filter") or {}).items():
            if (isinstance(values, list) and len(values) >= 1
                    and all(isinstance(v, str) for v in values)):
                rule["enums"].append((variable, tuple(values)))
        for fm in re.finditer(r"filter=filter\(([^)]*)\)", expression):
            for vm in re.finditer(r"([A-Za-z0-9_.-]+)=\[([^\]]*)\]", fm.group(1)):
                values = [t.strip().strip('\"') for t in vm.group(2).split(",")]
                if values and all(values):
                    rule["enums"].append((vm.group(1), tuple(values)))
    return rule


def scan_corpus(checks_dir):
    rules = []
    for directory, _, filenames in os.walk(checks_dir):
        for filename in sorted(filenames):
            if filename.endswith(".yaml"):
                rules.append(scan_rule(os.path.join(directory, filename)))
    rules.sort(key=lambda r: r["id"])
    return rules


# --------------------------------------------------------------------------
# Binding resolution
# --------------------------------------------------------------------------

def variable_ccodes(rule, maps):
    """Codelist C-codes bound to a variable, preferring the rule's own IGs."""
    preferred = collections.defaultdict(set)
    fallback = collections.defaultdict(set)
    for key, bindings in maps.items():
        target = preferred if key in rule["map_keys"] else fallback
        for variable, ccodes in bindings.items():
            target[variable].update(ccodes)
    return preferred, fallback


def heuristic_match(ct, literals):
    """Unique codelist whose current terms contain every literal, or None."""
    wanted = {value.upper() for value in literals}
    if len(wanted) < MIN_HEURISTIC_TERMS:
        return None
    candidates = {}
    for (family, ccode), entry in ct.entries.items():
        if entry["terms"] and wanted <= entry["terms"]:
            coverage = len(wanted) / float(len(entry["terms"]))
            if coverage >= MIN_HEURISTIC_COVERAGE:
                best = candidates.get(ccode)
                if best is None or coverage > best[1]:
                    candidates[ccode] = (family, coverage)
    if len(candidates) != 1:
        return None
    ccode, (family, _) = next(iter(candidates.items()))
    return family, ccode


def resolve_bindings(rules, ct, maps):
    """Attach every attributable codelist binding to each rule."""
    for rule in rules:
        preferred, fallback = variable_ccodes(rule, maps)
        bindings = {}

        for variable, literals in rule["enums"]:
            ccodes = preferred.get(variable) or fallback.get(variable)
            matched = False
            for ccode in sorted(ccodes or ()):
                entry = ct.lookup(rule["ct_families"], ccode)
                if entry:
                    key = (entry["family"], ccode)
                    slot = bindings.setdefault(key, {
                        "kind": "frozen-literal", "variable": variable,
                        "literals": set(), "source": "variable binding"})
                    slot["literals"].update(literals)
                    matched = True
            if matched:
                continue
            guess = heuristic_match(ct, literals)
            if guess:
                slot = bindings.setdefault(guess, {
                    "kind": "frozen-literal", "variable": variable,
                    "literals": set(), "source": "term-set heuristic"})
                slot["literals"].update(literals)

        for value in rule["named"]:
            ccode = ct.resolve_value(rule["ct_families"], value)
            if ccode:
                entry = ct.lookup(rule["ct_families"], ccode)
                bindings.setdefault((entry["family"], ccode), {
                    "kind": "live-named", "variable": "",
                    "literals": set(), "source": "codelist_terms operation"})
            else:
                bindings.setdefault(("?", value), {
                    "kind": "live-named", "variable": "",
                    "literals": set(),
                    "source": "codelist_terms operation (codelist not in cache)"})

        for ccode in sorted(rule["ccodes"]):
            entry = ct.lookup(rule["ct_families"], ccode)
            family = entry["family"] if entry else "?"
            bindings.setdefault((family, ccode), {
                "kind": "live-named", "variable": "",
                "literals": set(), "source": "C-code literal in Check"})

        rule["bindings"] = bindings
    return rules


def authority_of(rule):
    """(oldest, newest) authority dates as (date, origin, version) triples."""
    dated, undated = [], []
    for origin, version in rule["authorities"]:
        date, _, _ = AUTHORITY_DATES.get((origin, version), (None, None, None))
        if date:
            dated.append((date, origin, version))
        else:
            undated.append((origin, version))
    dated.sort()
    if not dated:
        return None, None, undated
    return dated[0], dated[-1], undated


def build_rows(rules, ct):
    rows = []
    for rule in rules:
        oldest, newest, undated = authority_of(rule)
        for (family, ccode), binding in sorted(rule["bindings"].items()):
            entry = ct.entries.get((family, ccode))
            last_change = entry["last_change"] if entry else None
            value = entry["value"] if entry else ccode
            if not entry or not oldest:
                verdict = "UNKNOWN"
            elif last_change > oldest[0]:
                verdict = "REVISIT"
            else:
                verdict = "OK"
            added = None
            if entry and oldest and verdict == "REVISIT":
                added = ct.terms_added_since(family, ccode, oldest[0])
            rows.append({
                "rule": rule["id"],
                "family": rule["family"],
                "codelist": value,
                "ccode": ccode,
                "ct_family": family,
                "variable": binding["variable"],
                "kind": binding["kind"],
                "source": binding["source"],
                "literals": sorted(binding["literals"]),
                "last_change": last_change,
                "first_seen": entry["first_seen"] if entry else None,
                "new_in_history": entry["new_in_history"] if entry else None,
                "authority": oldest,
                "authority_newest": newest,
                "undated_authorities": undated,
                "verdict": verdict,
                "terms_added": added,
                "drift_days": _days_between(oldest[0] if oldest else None,
                                            last_change),
            })
    return rows


def _days_between(start, end):
    if not start or not end:
        return None
    fmt = "%Y-%m-%d"
    return (datetime.datetime.strptime(end, fmt)
            - datetime.datetime.strptime(start, fmt)).days


# --------------------------------------------------------------------------
# Report
# --------------------------------------------------------------------------

def _escape(text):
    return str(text).replace("|", "\\|")


def _authority_cell(row):
    if not row["authority"]:
        names = ", ".join("%s v%s" % pair for pair in row["undated_authorities"])
        return "*(no dated authority: %s)*" % (names or "none cited")
    date, origin, version = row["authority"]
    cell = "%s v%s · %s" % (origin, version, date)
    if row["authority_newest"] and row["authority_newest"][0] != date:
        cell += " *(newest %s)*" % row["authority_newest"][0]
    return cell


def render(rows, rules, ct, cache_dir, stats):
    revisit = [r for r in rows if r["verdict"] == "REVISIT"]
    ok = [r for r in rows if r["verdict"] == "OK"]
    unknown = [r for r in rows if r["verdict"] == "UNKNOWN"]
    revisit.sort(key=lambda r: (-(r["drift_days"] or 0), r["codelist"], r["rule"]))
    ok.sort(key=lambda r: (r["codelist"], r["rule"]))
    unknown.sort(key=lambda r: (r["codelist"], r["rule"]))

    out = []
    add = out.append
    add("# CT drift scan — rules exposed to a codelist that moved after their sheet")
    add("")
    add("Generated by `scripts/ct-drift-scan.py` (re-run it; the output is a pure")
    add("function of the corpus and the CT cache, so an unchanged input rewrites this")
    add("file byte-identically). ⛔ **This tool is not part of the build gate** — it is a")
    add("periodic scan, run after a CDISC CT refresh and before a CT-touching review wave.")
    add("")
    add("It implements owner ruling `M2-D29` (option D, 2026-08-21). The policy it serves")
    add("is `M2-D29` option A: **coreJ tracks the current CT package**, and where CT has")
    add("moved past the authority sheet the corpus follows CT and a sheet issue records")
    add("the divergence.")
    add("")

    add("## 1. How to read a verdict")
    add("")
    add("| Verdict | Meaning |")
    add("|---|---|")
    add("| `REVISIT` | the codelist changed **after** the oldest sheet this rule "
        "transcribes. Read *Binding* before acting. |")
    add("| `OK` | the codelist has not changed since that date. |")
    add("| `UNKNOWN` | the authority date is not recoverable, or the codelist is "
        "not in the local CT cache. No claim either way. |")
    add("")
    add("| Binding | Meaning |")
    add("|---|---|")
    add("| `frozen-literal` | the rule spells the terms out in its `Check` (or an "
        "operation filter). **A CT change is not picked up.** These are the "
        "actionable rows. |")
    add("| `live-named` | the rule names the codelist but reads its terms from the "
        "library at run time. Behaviour tracks CT; the rule's *wording*, its "
        "`.cdt` scenarios and the sheet row may still need a look. |")
    add("| `live-dynamic` | the codelist is resolved from the data at run time — not "
        "attributable to one codelist. Listed in §6, no verdict. |")
    add("")

    add("## 2. Data sources and their granularity")
    add("")
    add("**CT change dates** — CDISC Library package pickles under `%s`."
        % _escape(cache_dir))
    add("There are **no per-term dates** in that data. \"Last changed\" is derived by")
    add("diffing consecutive packages: the newest package whose codelist fingerprint")
    add("(`submissionValue`, `extensible`, sorted term C-code + submission value)")
    add("differs from its predecessor's. The date is therefore *the package the change")
    add("was first observed in*, not the day CDISC made it — and the cache has gaps, so")
    add("a change can be attributed up to a couple of quarters late. It is never dated")
    add("**early**, so no `REVISIT` is manufactured by a gap.")
    add("")
    add("| CT family | Packages | Oldest | Newest | Codelists |")
    add("|---|---:|---|---|---:|")
    for family in sorted(ct.packages):
        dates = ct.packages[family]
        count = sum(1 for (f, _) in ct.entries if f == family)
        add("| `%s` | %d | %s | %s | %d |"
            % (family, len(dates), dates[0], dates[-1], count))
    add("")
    add("**Variable → codelist binding** — `variable_codelist_maps.pkl` from the same")
    add("cache (per IG version, variable name → codelist C-code).")
    add("")
    add("**Authority dates** — a curated table; a sheet carries no machine-readable")
    add("publication date, so every entry names the artefact its date came from.")
    add("")
    add("| Origin | Version | Date | Precision | Provenance |")
    add("|---|---|---|---|---|")
    for (origin, version), (date, precision, provenance) in sorted(
            AUTHORITY_DATES.items()):
        add("| %s | %s | %s | %s | %s |"
            % (_escape(origin), version, date or "*unknown*", precision,
               provenance))
    add("")
    add("⚠ When a new sheet version enters the corpus, extend `AUTHORITY_DATES` in the")
    add("script. Until then every rule citing it reports `UNKNOWN` — the intended")
    add("failure mode, never a guessed date.")
    add("")

    add("## 3. Summary")
    add("")
    add("| Population | Count |")
    add("|---|---:|")
    add("| Rules in the corpus | %d |" % stats["rules"])
    add("| Rules with any CT binding | %d |" % stats["ct_rules"])
    add("| … attributable to a named codelist (§4/§5) | %d |" % stats["attributable"])
    add("| … resolved from the data at run time only (§6) | %d |" % stats["dynamic_only"])
    add("| Rule × codelist rows | %d |" % len(rows))
    add("| **`REVISIT`** | **%d** (%d rules) |"
        % (len(revisit), len({r["rule"] for r in revisit})))
    add("| … of them `frozen-literal` | %d (%d rules) |"
        % (sum(1 for r in revisit if r["kind"] == "frozen-literal"),
           len({r["rule"] for r in revisit if r["kind"] == "frozen-literal"})))
    add("| `OK` | %d |" % len(ok))
    add("| `UNKNOWN` | %d |" % len(unknown))
    add("| Distinct codelists touched | %d |" % len({r["ccode"] for r in rows}))
    add("| Single-literal comparisons on CT-bound variables, e.g. `DTHFL == \"Y\"` "
        "— deliberately not scanned (occurrences) | %d |" % stats["single_literals"])
    add("| Rule files that failed to parse | %d |" % stats["parse_errors"])
    add("")

    add("## 4. `REVISIT` — most-drifted first")
    add("")
    if not revisit:
        add("*None.*")
    else:
        add("| Rule | Codelist | CT last changed | Drift | Terms added since the "
            "authority date | Authority | Binding | Rule's frozen values |")
        add("|---|---|---|---:|---|---|---|---|")
        for row in revisit:
            added = row["terms_added"]
            if added is None:
                added_cell = "*(no package at or before the authority date)*"
            elif added:
                added_cell = ", ".join("`%s`" % _escape(t) for t in added)
            else:
                added_cell = "*(no term added; codelist metadata changed)*"
            literals = ", ".join("`%s`" % _escape(v) for v in row["literals"])
            add("| `%s` | `%s` (%s, %s) | %s | %s d | %s | %s | %s | %s |"
                % (row["rule"], _escape(row["codelist"]), row["ccode"],
                   row["ct_family"], row["last_change"],
                   row["drift_days"] if row["drift_days"] is not None else "?",
                   added_cell, _authority_cell(row), row["kind"],
                   literals or "*(runtime)*"))
    add("")

    add("## 5. `OK` and `UNKNOWN`")
    add("")
    add("### 5a. `OK` — codelist unchanged since the authority date")
    add("")
    if not ok:
        add("*None.*")
    else:
        add("| Rule | Codelist | CT last changed | Authority | Binding |")
        add("|---|---|---|---|---|")
        for row in ok:
            add("| `%s` | `%s` (%s) | %s | %s | %s |"
                % (row["rule"], _escape(row["codelist"]), row["ccode"],
                   row["last_change"], _authority_cell(row), row["kind"]))
    add("")
    add("### 5b. `UNKNOWN` — no comparison possible")
    add("")
    if not unknown:
        add("*None.*")
    else:
        add("| Rule | Codelist | CT last changed | Authority | Why |")
        add("|---|---|---|---|---|")
        for row in unknown:
            why = ("codelist not in the CT cache" if not row["last_change"]
                   else "no dated authority sheet")
            add("| `%s` | `%s` (%s) | %s | %s | %s |"
                % (row["rule"], _escape(row["codelist"]), row["ccode"],
                   row["last_change"] or "*n/a*", _authority_cell(row), why))
    add("")

    add("## 6. `live-dynamic` — CT binding resolved from the data")
    add("")
    add("These rules read the codelist of whatever variable they land on")
    add("(`var_codelist_coded_values(\"LIBRARY\")` and friends, or")
    add("`get_codelist_attributes`). They are exposed to **every** codelist and track CT")
    add("by construction, so no single codelist can be named and no verdict is given.")
    add("")
    dynamic = sorted(r["id"] for r in rules if r["runtime"])
    also_named = sorted(r["id"] for r in rules if r["runtime"] and r["bindings"])
    if not dynamic:
        add("*None.*")
    else:
        add("%d rules use a runtime-resolved CT accessor%s:"
            % (len(dynamic),
               "" if not also_named else
               " (%d of them additionally pin a named codelist, so they also "
               "appear above: %s)" % (len(also_named),
                                      ", ".join("`%s`" % r for r in also_named))))
        add("")
        add(", ".join("`%s`" % r for r in dynamic))
    add("")

    add("## 7. Limitations")
    add("")
    add("* Every date here is a **package** or **document** date. No source on this")
    add("  machine carries a per-term change date.")
    add("* CT cache gaps push a change date *later*, never earlier. A change made in a")
    add("  package the cache does not hold is still seen — it surfaces on the next cached")
    add("  package — but it is dated to that later package. So the *date* can be too late")
    add("  by up to a couple of quarters, and a borderline `REVISIT` can read as `OK`")
    add("  only when the authority date falls inside that window.")
    add("* Single-literal comparisons (`DTHFL == \"Y\"`) on CT-bound variables are not")
    add("  scanned: they select one term rather than enumerate a codelist, so a new term")
    add("  does not invalidate them.")
    add("* A rule enumerating a vocabulary on a variable with **no** library codelist")
    add("  binding (`ARMCD in [\"SCRNFAIL\", \"NOTASSGN\"]` — SDTMIG prose, not CT) is")
    add("  caught only by the term-set heuristic, and only when the literals are a subset")
    add("  of exactly one cached codelist covering ≥ %d%% of it. Ambiguous matches are"
        % int(MIN_HEURISTIC_COVERAGE * 100))
    add("  dropped rather than guessed, so this signal under-reports by design.")
    add("* The verdict compares against the **oldest** authority a rule cites — the")
    add("  conservative choice for a revisit prompt. The newest is shown beside it.")
    add("* A codelist present in the oldest cached package is treated as *unchanged")
    add("  since* that package, not as *changed on* it.")
    add("* A reworded term definition or preferred term is not a change here; only the")
    add("  submission value, the extensible flag and the term list are fingerprinted.")
    add("")
    return "\n".join(out) + "\n"


def write_tsv(path, rows):
    columns = ["verdict", "kind", "rule", "family", "codelist", "ccode",
               "ct_family", "variable", "source", "last_change", "drift_days",
               "authority_date", "authority_origin", "authority_version",
               "terms_added", "literals"]
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\t".join(columns) + "\n")
        for row in rows:
            authority = row["authority"] or ("", "", "")
            handle.write("\t".join([
                row["verdict"], row["kind"], row["rule"], row["family"],
                row["codelist"], row["ccode"], row["ct_family"],
                row["variable"], row["source"], row["last_change"] or "",
                str(row["drift_days"]) if row["drift_days"] is not None else "",
                authority[0], authority[1], authority[2],
                ";".join(row["terms_added"] or []),
                ";".join(row["literals"]),
            ]) + "\n")


# --------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Report rules exposed to a codelist that changed after "
                    "their authority sheet was written. Periodic tool — "
                    "never run in the build gate.")
    parser.add_argument("--ct-cache", default=DEFAULT_CACHE,
                        help="CDISC pickle cache directory (default: "
                             "$CDISC_PICKLE_CACHE_DIR or %s)" % DEFAULT_CACHE)
    parser.add_argument("--checks", default=CHECKS,
                        help="rules-src/checks directory")
    parser.add_argument("--out", default=REPORT, help="report path")
    parser.add_argument("--tsv", default=None,
                        help="also write the rows as TSV to this path")
    parser.add_argument("--quiet", action="store_true",
                        help="no stdout summary")
    args = parser.parse_args()

    if not os.path.isdir(args.checks):
        print("error: rules corpus not found: %s" % args.checks, file=sys.stderr)
        return 2

    try:
        ct = CtIndex.build(args.ct_cache)
        maps = load_variable_maps(args.ct_cache)
    except CtDataUnavailable as error:
        print("CT drift scan cannot run: %s" % error, file=sys.stderr)
        print("Nothing was written.", file=sys.stderr)
        return 3

    rules = scan_corpus(args.checks)
    resolve_bindings(rules, ct, maps)

    wanted = set()
    for rule in rules:
        for family, ccode in rule["bindings"]:
            if (family, ccode) in ct.entries:
                wanted.add((family, ccode))
    ct.load_term_history(args.ct_cache, wanted)

    rows = build_rows(rules, ct)

    single_literals = 0
    bound_variables = set()
    for bindings in maps.values():
        bound_variables.update(bindings)
    for rule in rules:
        single_literals += sum(1 for variable, _ in rule["singles"]
                               if variable in bound_variables)

    stats = {
        "rules": len(rules),
        "attributable": len({r["rule"] for r in rows}),
        "dynamic_only": sum(1 for r in rules if r["runtime"] and not r["bindings"]),
        "ct_rules": sum(1 for r in rules if r["bindings"] or r["runtime"]),
        "single_literals": single_literals,
        "parse_errors": sum(1 for r in rules if r["parse_error"]),
    }

    report = render(rows, rules, ct, args.ct_cache, stats)
    with open(args.out, "w", encoding="utf-8") as handle:
        handle.write(report)
    if args.tsv:
        write_tsv(args.tsv, rows)

    if not args.quiet:
        counts = collections.Counter(r["verdict"] for r in rows)
        print("CT drift scan — %d rules, %d CT-bound, %d rule x codelist rows"
              % (stats["rules"], stats["ct_rules"], len(rows)))
        print("  REVISIT %d   OK %d   UNKNOWN %d"
              % (counts["REVISIT"], counts["OK"], counts["UNKNOWN"]))
        print("  report: %s" % args.out)
        if args.tsv:
            print("  tsv:    %s" % args.tsv)
    return 0


if __name__ == "__main__":
    sys.exit(main())
