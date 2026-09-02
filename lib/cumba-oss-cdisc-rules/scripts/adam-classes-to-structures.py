#!/usr/bin/env python3
"""Phase 3b of PLAN-adam-structure-set-valued.md — migrate the ADaM corpus's
``Scope.Classes`` block to ``Scope.Data_Structures``, FILE BY FILE.

Run from anywhere::

    python3 …/adam-classes-to-structures.py                  # census + manifests (== --simulate)
    python3 …/adam-classes-to-structures.py --simulate       # explicit no-write alias
    python3 …/adam-classes-to-structures.py --apply T1,T2    # + rewrite the YAML, named tranches only

Reads ``rules-src/checks`` (the AUTHORED corpus view, 3729 files) and the
``.cdt`` scenario tree (``src/test/resources/.../ruletestsuites``). Writes two
manifests beside this script:

* ``adam-classes-to-structures-manifest.tsv`` — one row per affected rule file:
  its before/after facet and its adjudication.
* ``adam-classes-to-structures-scenarios.tsv`` — one row per ``.cdt`` scenario
  covering an affected rule, with the predicted before/after scope verdict.

PER-RULE MIGRATION, NOT ONE WAVE (owner, 2026-08-10)
----------------------------------------------------
The unit of work is the RULE. ``--apply`` therefore takes an explicit tranche
list and moves *only* those files, leaving every other tranche where it is:

* ``T1`` — ``MEASURED SAFE``: covered by ≥1 ``.cdt`` scenario, all unchanged;
* ``T2`` — ``UNMEASURED``: no ``.cdt`` scenario covers the rule at all;
* ``T3`` — ``BLOCKED``: ≥1 scenario flips out of scope and/or ``install()``
  refuses. Unblocked by repairing the fixtures (Phase 3b.0), after which these
  files re-classify as ``T1`` on the next run;
* ``T4`` — ``HELD BACK``: ``ADAM OTHER`` sits in the token list, which B-G1
  forbids in ``Scope.Data_Structures``. Owner decision B-G2.
* ``T5A`` — ``JAVA-COVERED``: a Java test source *names* the rule, so the ``.cdt``
  simulation cannot predict it. A **grep**, not an instrument;
* ``T5B`` — ``INSTRUMENT-HELD``: an instrument outside the ``.cdt`` tree has
  **measured** a contradiction and the rule is listed in
  ``adam-classes-to-structures-instrument-holds.tsv``.

⚠⚠ ``T5A`` AND ``T5B`` ARE DIFFERENT KINDS OF EVIDENCE AND MUST BE SEPARATELY
APPLICABLE. Until wave 30 both mapped onto a single ``T5``, so ``--apply T5``
would have re-migrated every rule an instrument had already reddened — silently
undoing waves 28/29's holds. ``T5`` is still accepted and means "both", but a
lane auditing the Java probes wants ``T5A`` alone.

⚠ A tranche list is mandatory precisely because 3b.0 *moves* files between
tranches: after the fixture repair a bare "migrate everything" would silently
take T3 as well. Naming the tranches makes each wave's scope explicit.

THE RATCHET — TWO NUMBERS, ALWAYS
---------------------------------
``migrated`` (files whose ADaM scope is expressed as ``Scope.Data_Structures``)
and ``covered`` (how many of those an instrument actually exercises — a ``.cdt``
scenario **or** a Java test source, see ``java_covered_rule_ids``).
T2 has no fixture at all, so migrating it moves ``migrated`` and *not*
``covered``; reporting a single number would make the corpus read as
better-tested after the migration than before it, which is false. The
pre-migration state is enumerated in ``adam-classes-to-structures-ratchet-baseline.tsv``
(83 files, captured at 9d8db8596) so the ratchet is re-derivable rather than
bumped.

THE SCOPE MIRROR
----------------
``predict_*`` below is a hand-transcribed Python mirror of the two scope paths:

* ``AdamDataStructureDetector`` — ``detect`` / ``detectSpecific`` / ``detectAll``
  / ``structureTokenFromDeclaredClass`` / ``SUPERTYPES`` (post Phase 3a, i.e.
  ``DEVICE LEVEL ANALYSIS DATASET`` is a ROOT with no supertype);
* ``ScopeMatcher.describeClassMismatch`` / ``describeDataStructureMismatch``;
* ``ScenarioDeclaredScopeProvider.effectiveDeclaredStructure`` /
  ``needsClassDerivedDelivery`` / ``install`` and
  ``AbstractRuleTestSuitesFactory.describeScopeMismatch``;
* ``CdtLoader``'s ``CLASS_SHORTCUTS`` / ``STRUCTURE_SHORTCUTS`` / ``SUBCLASS_SHORTCUTS``.

⚠ A mirror can drift from the code it mirrors. It carries its own **positive
control**: ``--control`` replays the mirror over the 83 rules that ALREADY carry
``Scope.Data_Structures``, whose scenarios are green in the committed build. A
non-zero control count means the mirror is wrong, not that the corpus moved.
"""

import argparse
import collections
import csv
import os
import re
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.normpath(os.path.join(HERE, os.pardir))
CHECKS = os.path.join(MODULE, "rules-src", "checks")
SUITES = os.path.join(MODULE, "src", "test", "resources", "net", "cumba", "cdisc", "core",
                      "ruletestsuites")
MANIFEST = os.path.join(HERE, "adam-classes-to-structures-manifest.tsv")
SCENARIOS = os.path.join(HERE, "adam-classes-to-structures-scenarios.tsv")
# The 83 files that already carried Scope.Data_Structures before wave 28 touched
# anything (captured at 9d8db8596, the commit this lane branched from). The ratchet
# is re-derived against this list, never bumped.
RATCHET_BASELINE = os.path.join(HERE, "adam-classes-to-structures-ratchet-baseline.tsv")
# \u26a0\u26a0 Rules a THIRD instrument measured as contradicted by the move (the parity spec
# corpus). Unlike the Java scan below this cannot be derived by grepping, because the
# evidence is a RUN, not a reference \u2014 so the run's verdict is checked in here.
INSTRUMENT_HOLDS = os.path.join(HERE, "adam-classes-to-structures-instrument-holds.tsv")

# ---------------------------------------------------------------------------
# AdamDataStructureDetector — the vocabulary and the hierarchy, as of Phase 3a.
# ---------------------------------------------------------------------------
ADSL = "SUBJECT LEVEL ANALYSIS DATASET"
BDS = "BASIC DATA STRUCTURE"
OCCDS = "OCCURRENCE DATA STRUCTURE"
OTHER = "ADAM OTHER"
MDBDS = "MEDICAL DEVICE BASIC DATA STRUCTURE"
MDOCCDS = "MEDICAL DEVICE OCCURRENCE DATA STRUCTURE"
DLAD = "DEVICE LEVEL ANALYSIS DATASET"

STRUCTURE_TOKENS = {ADSL, BDS, OCCDS, OTHER, MDBDS, MDOCCDS, DLAD}

# ⚠⚠ ``ALL`` is a SCOPE WILDCARD, not a structure — it is deliberately kept OUT of
# STRUCTURE_TOKENS (which models the detector's vocabulary) but it *is* a legitimate migrated form.
# ``ScopeMatcher.describeDataStructureMismatch`` short-circuits its whole Include block on
# ``include.contains(ALL)``, so ``Data_Structures.Include: ["ALL"]`` matches every dataset,
# including one whose structure is undetermined.
#
# ⚑ Added 2026-08-10 after it bit: PMDA-AD0376 / AD1011 / AD1012A were rewritten to
# ``Data_Structures.Include: ["ALL"]`` and **silently vanished from every bucket** — the
# Scope.Classes population fell 390 -> 387 while ``already on Scope.Data_Structures`` stayed at
# 616, so the ratchet under-reported by 3 and the tracked total dropped 1006 -> 1003 with no error.
# Same family as Fix #191: a ratchet that cannot see one of the forms it ratchets is worse than none.
ALL = "ALL"
MIGRATED_TOKENS = STRUCTURE_TOKENS | {ALL}
# ⚠ DLAD is deliberately ABSENT — owner decision 2026-08-09, shipped in Phase 3a.
SUPERTYPES = {MDBDS: BDS, MDOCCDS: OCCDS}
BDS_INDICATORS = {"PARAMCD", "PARAM", "AVAL", "AVALC"}
OCCDS_SUFFIXES = ("TERM", "TRT", "DECOD")
DEVICE_IDENTIFIER = "SPDEVID"
ADAM_NAME_PREFIXES = ("AD", "AX")

# CdtLoader
CLASS_SHORTCUTS = {"ADSL": ADSL, "BDS": BDS, "OCCDS": OCCDS,
                   "MD-BDS": MDBDS, "MD-OCCDS": MDOCCDS}
STRUCTURE_SHORTCUTS = {"ADSL": ADSL, "BDS": BDS, "OCCDS": OCCDS, "OTHER": OTHER}


def normalize(name):
    """ScopeMatcher.normalize — keep ASCII alphanumerics, upper-case."""
    return "".join(c for c in (name or "").upper() if c.isascii() and c.isalnum())


def is_adam_dataset_name(name):
    return name.strip().upper().startswith(ADAM_NAME_PREFIXES)


def detect(name, columns):
    """AdamDataStructureDetector.detect(String, Collection) — the Python mirror."""
    if name is not None and name.strip().upper() == "ADSL":
        return ADSL
    if name is not None and not is_adam_dataset_name(name):
        return OTHER
    if columns:
        occds = False
        for col in columns:
            if col is None:
                continue
            upper = col.upper()
            if upper in BDS_INDICATORS:
                return BDS
            if not occds and any(upper.endswith(s) for s in OCCDS_SUFFIXES):
                occds = True
        if occds:
            return OCCDS
    return OTHER


def detect_specific(name, columns):
    """AdamDataStructureDetector.detectSpecific — the Phase 3a.1 device refinement."""
    base = detect(name, columns)
    if name is not None and not is_adam_dataset_name(name):
        return base
    if not columns or not any(c and c.strip().upper() == DEVICE_IDENTIFIER for c in columns):
        return base
    return {BDS: MDBDS, OCCDS: MDOCCDS, OTHER: DLAD}.get(base, base)


def structure_token_from_declared_class(declared):
    if declared is None:
        return None
    canonical = declared.strip().upper()
    return canonical if canonical in STRUCTURE_TOKENS else None


def detect_all(name, columns, declared_class, define_first=True):
    """AdamDataStructureDetector.detectAll — the set, most specific first."""
    declared = structure_token_from_declared_class(declared_class)
    if define_first and declared is not None:
        token = declared
    else:
        heuristic = detect_specific(name, columns)
        token = declared if (heuristic == OTHER and declared is not None) else heuristic
    supertype = SUPERTYPES.get(token)
    return [token] if supertype is None else [token, supertype]


def _first_normalized_entry(entries, name):
    target = normalize(name)
    for entry in entries or ():
        if normalize(entry) == target:
            return entry
    return None


def describe_data_structure_mismatch(include, exclude, detected):
    """ScopeMatcher.describeDataStructureMismatch, set-valued (Fix #179)."""
    if include and "ALL" not in include:
        if not detected:
            return "dataset data structure undetermined but rule has a Data_Structures scope"
        if not any(_first_normalized_entry(include, d) for d in detected):
            return "data structure %s not in Scope.Data_Structures.Include %s" % (
                detected[0], include)
    for d in detected:
        entry = _first_normalized_entry(exclude, d)
        if entry:
            return "data structure %s matches Scope.Data_Structures.Exclude entry %s" % (d, entry)
    return None


_FINDINGS_ABOUT = normalize("FINDINGS ABOUT")
_FINDINGS = normalize("FINDINGS")


def _first_matching_class_entry(patterns, name):
    target = normalize(name)
    for pattern in patterns or ():
        if pattern == "ALL":
            return pattern
        if pattern == "NONE":
            continue
        if "--" in pattern:
            prefix = pattern.replace("--", "")
            if name is not None and len(name) == len(prefix) + 2 \
                    and normalize(name[:len(prefix)]) == normalize(prefix):
                return pattern
        elif normalize(pattern) == target:
            return pattern
    return None


def describe_class_mismatch(include, exclude, class_name):
    """ScopeMatcher.describeClassMismatch, including Fix #41 strict-on-null."""
    if not include and not exclude:
        return None
    if class_name is None:
        return "dataset class undetermined but rule has a Classes scope"
    is_findings_about = normalize(class_name) == _FINDINGS_ABOUT
    if include and _first_matching_class_entry(include, class_name) is None \
            and not (is_findings_about and any(normalize(i) == _FINDINGS for i in include)):
        return "class %s not in Scope.Classes.Include %s" % (class_name, include)
    if exclude:
        entry = _first_matching_class_entry(exclude, class_name)
        if entry is None and is_findings_about:
            entry = next((x for x in exclude if normalize(x) == _FINDINGS), None)
        if entry:
            return "class %s matches Scope.Classes.Exclude entry %s" % (class_name, entry)
    return None


# ---------------------------------------------------------------------------
# Corpus
# ---------------------------------------------------------------------------
def load_rules():
    rules = {}
    for dirpath, _, names in os.walk(CHECKS):
        for n in sorted(names):
            if not n.endswith(".yaml"):
                continue
            path = os.path.join(dirpath, n)
            with open(path, encoding="utf-8") as fh:
                doc = yaml.safe_load(fh) or {}
            rid = (doc.get("Core") or {}).get("Id")
            if rid:
                rules[rid] = {"path": path,
                              "rel": os.path.relpath(path, CHECKS).replace(os.sep, "/"),
                              "scope": doc.get("Scope") or {}}
    return rules


def classify(rules):
    """Split the corpus into the migration populations.

    Returns ``(migrate, hold, done)`` — the files still on ``Scope.Classes`` that
    may move, the ones B-G2 holds back, and the ones already expressed as
    ``Scope.Data_Structures`` (the ratchet's numerator).
    """
    migrate, hold, done = {}, {}, {}
    for rid, r in rules.items():
        structures = r["scope"].get("Data_Structures")
        if structures:
            tokens = list(structures.get("Include") or []) + list(structures.get("Exclude") or [])
            # MIGRATED_TOKENS, not STRUCTURE_TOKENS: an ``ALL`` wildcard is a migrated form too.
            if any(t in MIGRATED_TOKENS for t in tokens):
                r["tokens"] = tokens
                done[rid] = r
                continue
        classes = r["scope"].get("Classes")
        if not classes:
            continue
        tokens = list(classes.get("Include") or []) + list(classes.get("Exclude") or [])
        adam = [t for t in tokens if t in STRUCTURE_TOKENS]
        if not adam:
            continue
        r["tokens"] = tokens
        r["adam"] = adam
        r["sdtm"] = [t for t in tokens if t not in STRUCTURE_TOKENS]
        # B-G1 / B-G2: a file carrying ADAM OTHER would put ADAM OTHER into
        # Scope.Data_Structures, which the gate forbids. Held back for the owner.
        if OTHER in tokens:
            hold[rid] = r
        else:
            migrate[rid] = r
    return migrate, hold, done


# ---------------------------------------------------------------------------
# .cdt scenarios
# ---------------------------------------------------------------------------
_ATTR = re.compile(r'(\w+)=("[^"]*"|\S+)')


def parse_cdt(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    sc = {"tests": [], "datasets": {}, "carrier": False}
    current = None
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("#test "):
            body = s[6:]
            attrs = {k: v.strip('"') for k, v in _ATTR.findall(body)}
            attrs["_id"] = body.split()[0]
            sc["tests"].append(attrs)
        elif s.startswith("#define") or s.startswith("#library"):
            sc["carrier"] = True
        elif s.startswith("dataset "):
            rest = s[8:].strip()
            name = rest.split()[0]
            current = {"name": name,
                       "attrs": {k: v.strip('"')
                                 for k, v in _ATTR.findall(rest[len(name):])},
                       "cols": []}
            sc["datasets"][name.upper()] = current
        elif s.startswith("col ") and current is not None:
            current["cols"].append(s[4:].split()[0])
    return sc


# ---------------------------------------------------------------------------
# ⚠⚠ THE SECOND INSTRUMENT — added wave 28 after the .cdt-only classifier misled us.
#
# Wave 27 (and wave 28's own first pass) classified every rule against the `.cdt`
# scenario tree ALONE. That tree is not the only thing that executes an ADaM rule:
# `**/src/test/**/*.java` carries hand-written integration probes that build a
# MockTable and call RuleRunner directly. Measured 2026-08-10: **20 Java test files
# name 92 migrated ADaM rules, and 63 of those 92 had been labelled UNMEASURED —
# "no scenario covers this rule at all".** Migrating them turned 23 assertions red.
#
# ⇒ "UNMEASURED" must mean "no instrument covers it", not "no .cdt covers it".
# JAVA_ID matches both the qualified form ("CDISC-AD0148") and the bare suffix
# ("AD0098") the PMDA probes use; a bare id is credited to BOTH families, which
# over-includes on purpose — the safe direction for a ratchet is fewer migrations.
# ---------------------------------------------------------------------------
JAVA_ID = re.compile(r'"((?:CDISC-|PMDA-)?AD\d+[A-Z]?)"')
REPO = os.path.normpath(os.path.join(MODULE, os.pardir, os.pardir))

_JAVA_CACHE = None


def java_covered_rule_ids():
    """Every ADaM rule id named by a Java test source anywhere in the repo."""
    global _JAVA_CACHE
    if _JAVA_CACHE is None:
        found = set()
        for dirpath, _, names in os.walk(REPO):
            d = dirpath.replace(os.sep, "/") + "/"
            if "/target/" in d or "/.git/" in d or "/src/test/" not in d:
                continue
            for n in names:
                if not n.endswith(".java"):
                    continue
                with open(os.path.join(dirpath, n), encoding="utf-8",
                          errors="replace") as fh:
                    for m in JAVA_ID.finditer(fh.read()):
                        tok = m.group(1)
                        if "-" in tok:
                            found.add(tok)
                        else:
                            found.add("CDISC-" + tok)
                            found.add("PMDA-" + tok)
        _JAVA_CACHE = found
    return _JAVA_CACHE


_CDT_CACHE = None


def all_scenarios():
    """Every parsed ``.cdt`` under the suites tree, as ``(path, parsed)``, parsed once."""
    global _CDT_CACHE
    if _CDT_CACHE is None:
        _CDT_CACHE = []
        for dirpath, _, names in os.walk(SUITES):
            for n in sorted(names):
                if n.endswith(".cdt"):
                    path = os.path.join(dirpath, n)
                    _CDT_CACHE.append((path, parse_cdt(path)))
        _CDT_CACHE.sort()
    return _CDT_CACHE


def covering_rule_ids():
    """Every ``Core.Id`` that at least one ``.cdt`` scenario exercises."""
    return {t["_id"] for _, sc in all_scenarios() for t in sc["tests"]}


def scenario_rows(rules, target_ids, facet):
    """Predict the scope verdict for every scenario covering ``target_ids``.

    ``facet`` is ``"Classes"`` (the control: replay today's corpus) or
    ``"Data_Structures"`` (the migration: same token list, other facet).
    """
    rows = []
    for path, sc in all_scenarios():
        for t in sc["tests"]:
            rid = t["_id"]
            if rid not in target_ids:
                continue
            ds = sc["datasets"].get((t.get("domain") or "").upper())
            if ds is None:
                continue
            raw_class = ds["attrs"].get("class")
            raw_structure = ds["attrs"].get("structure")
            dataset_class = CLASS_SHORTCUTS.get(raw_class, raw_class) if raw_class else None
            declared_structure = (STRUCTURE_SHORTCUTS.get(raw_structure, raw_structure)
                                  if raw_structure else None)
            # ScenarioDeclaredScopeProvider.effectiveDeclaredStructure
            effective = declared_structure if declared_structure is not None \
                else structure_token_from_declared_class(dataset_class)
            subclass = ds["attrs"].get("subclass")
            scope = rules[rid]["scope"]
            src = scope.get(facet) or scope.get("Classes") or {}
            include, exclude = src.get("Include"), src.get("Exclude")
            detected = detect_all(ds["name"], ds["cols"], effective)
            # BEFORE: the harness judges the class axis ONLY when the scenario
            # declares class= (AbstractRuleTestSuitesFactory:565-573).
            before = None if dataset_class is None \
                else describe_class_mismatch(include, exclude, dataset_class)
            after = describe_data_structure_mismatch(include, exclude, detected)
            # ScenarioDeclaredScopeProvider.install refusal: the migrated rule now
            # reads the declared tier, so a non-redundant class=-derived declaration
            # needs a #define/#library carrier block or install() throws.
            declares_adam = declared_structure is not None or subclass is not None
            redundant = detect_all(ds["name"], ds["cols"], None) == detected
            needs_delivery = (not declares_adam) and effective is not None and not redundant
            refuse = (declares_adam or needs_delivery) and not sc["carrier"]
            rows.append({
                "scenario": os.path.relpath(path, SUITES).replace(os.sep, "/"),
                "rule": rid,
                "expect": t.get("expect", "violation"),
                "dataset": ds["name"],
                "declared_class": dataset_class or "",
                "declared_structure": declared_structure or "",
                "carrier": "yes" if sc["carrier"] else "no",
                "detected_structures": "|".join(detected),
                "include": "|".join(include or []),
                "before_in_scope": "yes" if before is None else "no",
                "after_in_scope": "yes" if after is None else "no",
                "install_refusal": "yes" if refuse else "no",
                "after_reason": after or "",
            })
    return rows


# ---------------------------------------------------------------------------
# Apply
# ---------------------------------------------------------------------------
_SCOPE_CLASSES = re.compile(r"(?m)^(Scope:\n(?:[ \t].*\n|\n)*?)^  Classes:$")


def apply_migration(rule):
    """Rename the file's whole ``Scope.Classes`` key to ``Scope.Data_Structures``.

    The unit of work is the FILE and the edit is the KEY, never a token: moving a
    single token would turn the ``Classes`` list's OR into an AND across the two
    facets (RuleGenerator.describeScopeSkip is a sequential early-return), which
    is the refuted wave-20 tranche shape.
    """
    with open(rule["path"], encoding="utf-8") as fh:
        text = fh.read()
    new, count = _SCOPE_CLASSES.subn(lambda m: m.group(1) + "  Data_Structures:", text)
    if count != 1:
        raise SystemExit("%s: expected exactly one Scope.Classes key, found %d"
                         % (rule["rel"], count))
    with open(rule["path"], "w", encoding="utf-8") as fh:
        fh.write(new)


# ---------------------------------------------------------------------------
def load_instrument_holds():
    """{rule id: reason} for rules an instrument outside the .cdt simulation contradicted."""
    if not os.path.exists(INSTRUMENT_HOLDS):
        return {}
    with open(INSTRUMENT_HOLDS, encoding="utf-8") as fh:
        return {row["rule"]: "%s: %s" % (row["instrument"], row["reason"])
                for row in csv.DictReader(fh, delimiter="\t")}


def spec_covered_rule_ids():
    """Every ADaM rule id named by a rulespec spec (`rulespec/specs`).

    \u26d1 Coverage here is *verifying*, not merely nominal: ``SpecRunner.statusMatches``
    compares expected against observed execution status with both defaulting to
    EXECUTED, so a rule that silently falls out of scope always flips
    EXECUTED \u2192 SKIPPED and reds its spec. **The parity suite cannot pass vacuously
    on a scope regression**, which is why a green parity run IS evidence for the rules
    it covers \u2014 and why only the rules it actually reddened are held, rather than all
    of them.
    """
    # ⚠ if this path moves, this degrades SILENTLY (the isdir guard returns the empty set and
    # ADaM rules lose their spec-covered attribution with no error) — see
    # PLAN-parity-suite-reclassification §7.2. Verified non-empty after the wave-41 rename.
    specs = os.path.join(MODULE, "rulespec", "specs")
    found = set()
    if not os.path.isdir(specs):
        return found
    pat = re.compile(r"\b((?:CDISC|PMDA|FDA|CORE)-AD\d+[A-Z]?)\b")
    for n in sorted(os.listdir(specs)):
        if n.endswith(".yaml"):
            with open(os.path.join(specs, n), encoding="utf-8", errors="replace") as fh:
                found.update(m.group(1) for m in pat.finditer(fh.read()))
    return found


def load_ratchet_baseline():
    """The rule ids that already carried ``Scope.Data_Structures`` before wave 28."""
    if not os.path.exists(RATCHET_BASELINE):
        return None
    with open(RATCHET_BASELINE, encoding="utf-8") as fh:
        return {row["rule"] for row in csv.DictReader(fh, delimiter="\t")}


def report_ratchet(done, covered_ids):
    """⚠ TWO numbers, never one — see the module docstring.

    ``migrated`` counts files; ``covered`` counts the subset a ``.cdt`` scenario
    actually exercises. A migration that moves only ``migrated`` has added scope
    coverage the corpus cannot verify, and saying so is the point.
    """
    baseline = load_ratchet_baseline()
    migrated = sorted(done)
    covered = sorted(rid for rid in done if rid in covered_ids)
    print("\nRATCHET (ADaM rules whose scope is expressed as Scope.Data_Structures)")
    print("  migrated: %d files" % len(migrated))
    print("  covered:  %d of those are exercised by an instrument \u2014 a .cdt scenario, a "
          "Java test or a parity spec (%d by none)"
          % (len(covered), len(migrated) - len(covered)))
    if baseline is not None:
        gained = sorted(set(migrated) - baseline)
        lost = sorted(baseline - set(migrated))
        print("  baseline: %d (adam-classes-to-structures-ratchet-baseline.tsv) -> +%d, -%d"
              % (len(baseline), len(gained), len(lost)))
        if lost:
            print("  \u26a0\u26a0 RATCHET WENT BACKWARDS for %d rule(s): %s"
                  % (len(lost), ", ".join(lost[:10])), file=sys.stderr)
    return len(migrated), len(covered)


TRANCHES = ("T1", "T2", "T3", "T4", "T5", "T5A", "T5B")
TRANCHE_OF = {"MEASURED SAFE": "T1", "UNMEASURED": "T2", "BLOCKED": "T3",
              "HELD BACK": "T4", "JAVA-COVERED": "T5A", "INSTRUMENT-HELD": "T5B"}


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apply", metavar="TRANCHES",
                    help="rewrite the YAML of the named tranches only, e.g. --apply T1,T2. "
                         "One of %s. Never migrates a tranche you did not name." % (TRANCHES,))
    ap.add_argument("--simulate", action="store_true",
                    help="explicit alias for the default: census and manifests, no writes")
    ap.add_argument("--control", action="store_true",
                    help="replay the mirror over the rules that already carry "
                         "Scope.Data_Structures; a non-zero result means the mirror is wrong")
    args = ap.parse_args()

    wanted = set()
    if args.apply:
        wanted = {x.strip().upper() for x in args.apply.split(",") if x.strip()}
        bad = wanted - set(TRANCHES)
        if bad:
            print("unknown tranche(s): %s (expected any of %s)"
                  % (", ".join(sorted(bad)), ", ".join(TRANCHES)), file=sys.stderr)
            return 2
        if "T5" in wanted:
            # Back-compatible alias: "T5" means both halves. Naming T5A or T5B
            # alone is the whole point of the split — see the module docstring.
            wanted |= {"T5A", "T5B"}
        if "T4" in wanted:
            print("T4 is HELD BACK by B-G2 (ADAM OTHER would land in Scope.Data_Structures, "
                  "which B-G1 forbids). It needs an owner decision, not a flag.", file=sys.stderr)
            return 2

    rules = load_rules()
    migrate, hold, done = classify(rules)
    print("rules-src/checks: %d files" % len(rules))
    print("ADaM Scope.Classes: %d entries across %d files"
          % (sum(len(r["adam"]) for r in list(migrate.values()) + list(hold.values())),
             len(migrate) + len(hold)))
    print("  migratable: %d files" % len(migrate))
    print("  held back (carry ADAM OTHER \u2014 B-G1/B-G2): %d files" % len(hold))
    print("  already on Scope.Data_Structures: %d files" % len(done))

    if args.control:
        rows = scenario_rows(done, set(done), "Data_Structures")
        # \u26a0 `expect=skipped` scenarios are AUTHORED out of scope \u2014 that is their contract
        # (AbstractRuleTestSuitesFactory:283-291). Only an asserting scenario predicted out of
        # scope would mean the mirror disagrees with the committed green build.
        broken = [r for r in rows
                  if r["after_in_scope"] == "no" and r["expect"] in ("violation", "noViolation")]
        print("CONTROL: %d rules, %d scenarios (%d authored expect=skipped), "
              "%d asserting scenarios predicted out of scope (must be 0)"
              % (len(done), len(rows),
                 sum(1 for r in rows if r["expect"] not in ("violation", "noViolation")),
                 len(broken)))
        for r in broken[:20]:
            print("   ", r["scenario"], r["after_reason"])
        return 0 if not broken else 1

    rows = scenario_rows(rules, set(migrate), "Classes")
    by_rule = collections.defaultdict(list)
    for r in rows:
        by_rule[r["rule"]].append(r)

    flip_out = [r for r in rows if r["before_in_scope"] == "yes" and r["after_in_scope"] == "no"]
    refusals = [r for r in rows if r["install_refusal"] == "yes"]
    blocked = {r["rule"] for r in flip_out} | {r["rule"] for r in refusals}

    print("\n.cdt scenarios covering a migratable rule: %d" % len(rows))
    print("  flip_out            (in scope BEFORE, OUT of scope AFTER): %d (%d rules)"
          % (len(flip_out), len({r['rule'] for r in flip_out})))
    print("  install_refusals    (ScenarioDeclaredScopeProvider.install throws): %d (%d rules)"
          % (len(refusals), len({r['rule'] for r in refusals})))
    print("  unchanged: %d" % (len(rows) - len(flip_out) - len(refusals)))
    print("\nmigratable rules: %d covered by a scenario, %d uncovered"
          % (len(by_rule), len(migrate) - len(by_rule)))
    print("  T3 BLOCKED by a scenario movement: %d" % len(blocked))
    print("  T1 measured safe:                  %d" % (len(by_rule) - len(blocked)))
    print("  T2 unmeasured:                     %d" % (len(migrate) - len(by_rule)))
    print("  T4 held back:                      %d" % len(hold))
    print("  (T5 java-covered is carved out of T1/T2 below \u2014 see the manifest)")

    java_ids = java_covered_rule_ids()
    spec_ids = spec_covered_rule_ids()
    holds = load_instrument_holds()
    covered_ids = covering_rule_ids() | java_ids | spec_ids
    print("\n\u26a1 instruments beyond the .cdt tree:")
    print("   Java test sources name %d ADaM rule id(s) (%d still on Scope.Classes)"
          % (len(java_ids & set(rules)), len(java_ids & set(migrate))))
    print("   parity specs name       %d ADaM rule id(s) (%d still on Scope.Classes)"
          % (len(spec_ids & set(rules)), len(spec_ids & set(migrate))))
    print("   measured contradictions held back: %d" % len(holds))
    report_ratchet(done, covered_ids)

    tranche_of_rule = {}
    with open(MANIFEST, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, delimiter="\t", lineterminator="\n")
        w.writerow(["file", "rule", "before_facet", "before_tokens", "after_facet",
                    "after_tokens", "scenarios", "flip_out", "install_refusals",
                    "adjudication"])
        baseline = load_ratchet_baseline() or set()
        allrows = list(migrate.items()) + list(hold.items()) + list(done.items())
        for rid, r in sorted(allrows, key=lambda kv: kv[1]["rel"]):
            tokens = "|".join(r["tokens"])
            covering = by_rule.get(rid, [])
            fo = sum(1 for x in covering
                     if x["before_in_scope"] == "yes" and x["after_in_scope"] == "no")
            ir = sum(1 for x in covering if x["install_refusal"] == "yes")
            if rid in done:
                # Already on the target facet: either pre-existing or migrated by a wave.
                n = sum(1 for _, sc in all_scenarios() for t in sc["tests"] if t["_id"] == rid)
                before = "Scope.Data_Structures" if rid in baseline else "Scope.Classes"
                after_facet, after_tokens = "Scope.Data_Structures", tokens
                verdict = ("PRE-EXISTING \u2014 carried Scope.Data_Structures before wave 28"
                           if rid in baseline
                           else "MIGRATED \u2014 moved to Scope.Data_Structures") \
                    + (", %d .cdt scenario(s)" % n if n else ", NO .cdt scenario (uncovered)")
                w.writerow([r["rel"], rid, before, tokens, after_facet, after_tokens,
                            n, 0, 0, verdict])
                continue
            if rid in hold:
                after_facet, after_tokens = "Scope.Classes", tokens
                verdict = ("HELD BACK \u2014 B-G2: the whole-file move would write ADAM OTHER into "
                           "Scope.Data_Structures, which B-G1 forbids. Owner decision required.")
            elif fo or ir:
                after_facet, after_tokens = "Scope.Classes", tokens
                verdict = ("BLOCKED \u2014 %d scenario(s) fall out of scope, %d install() refusal(s)"
                           % (fo, ir))
            elif rid in holds:
                after_facet, after_tokens = "Scope.Classes", tokens
                verdict = "INSTRUMENT-HELD \u2014 %s" % holds[rid]
            elif rid in java_ids:
                # \u26a0\u26a0 The SECOND instrument. A Java probe builds its own MockTable and calls
                # RuleRunner directly; the .cdt simulation above cannot see it, so neither
                # "measured safe" nor "unmeasured" is a claim we are entitled to make here.
                after_facet, after_tokens = "Scope.Classes", tokens
                verdict = ("JAVA-COVERED \u2014 a Java test names this rule; the .cdt simulation "
                           "cannot predict it. Needs the Java probes audited first (3b.0b).")
            elif covering:
                after_facet, after_tokens = "Scope.Data_Structures", tokens
                verdict = "MEASURED SAFE \u2014 %d scenario(s), all unchanged" % len(covering)
            else:
                after_facet, after_tokens = "Scope.Data_Structures", tokens
                verdict = ("UNMEASURED \u2014 no .cdt scenario and no Java test covers this rule")
            tranche_of_rule[rid] = TRANCHE_OF[verdict.split(" \u2014")[0]]
            w.writerow([r["rel"], rid, "Scope.Classes", tokens, after_facet, after_tokens,
                        len(covering), fo, ir, verdict])
    print("\nwrote %s" % os.path.relpath(MANIFEST, os.path.join(MODULE, os.pardir, os.pardir)))

    with open(SCENARIOS, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, delimiter="\t", lineterminator="\n",
                           fieldnames=list(rows[0].keys()))
        w.writeheader()
        for r in sorted(rows, key=lambda x: (x["rule"], x["scenario"])):
            w.writerow(r)
    print("wrote %s" % os.path.relpath(SCENARIOS, os.path.join(MODULE, os.pardir, os.pardir)))

    if wanted:
        moved = collections.Counter()
        for rid, r in sorted(migrate.items(), key=lambda kv: kv[1]["rel"]):
            tranche = tranche_of_rule.get(rid)
            if tranche in wanted:
                apply_migration(r)
                moved[tranche] += 1
        print("\nmigrated %d file(s): %s"
              % (sum(moved.values()),
                 ", ".join("%s=%d" % (t, moved[t]) for t in TRANCHES if moved[t])))
        left = {t: sum(1 for x in tranche_of_rule.values() if x == t) - moved[t]
                for t in TRANCHES}
        print("left in place: %s, T4=%d (B-G2)"
              % (", ".join("%s=%d" % (t, left[t]) for t in ("T1", "T2", "T3")), len(hold)))
        print("\u26a0 re-run without --apply to re-derive the manifest and the ratchet")
    return 0


if __name__ == "__main__":
    sys.exit(main())
