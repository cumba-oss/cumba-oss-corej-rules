#!/usr/bin/env python3
"""Census of column-vs-literal NEGATIVE leaves in the native ``rules/`` corpus.

Re-derives the partition published in
``plans/PLAN-column-vs-literal-negative-guards-s5.md`` §3 (Phase 0, measured
2026-08-07) and enumerates the ``Req`` bucket by rule id.

The classifier is a faithful Python port of the Java pipeline

  * ``lib/corej-cdisc-core/.../expr/ExpressionLexer.java``
  * ``lib/corej-cdisc-core/.../expr/CheckExpressionParser.java``
  * ``lib/corej-cdisc-core/.../expr/OperandClassifier.java``
  * ``lib/corej-cdisc-core/.../expr/convert/NonEmptyGuardInliner.java``
    (``NEGATIVE_BINOPS`` / ``isGuardableNegative`` / ``isGuardableLiteral`` /
    ``isPrimaryColumn`` / ``isGeneratorWildcard`` /
    ``columnVsLiteralNegativeColumn``)

⚠ The published numbers were measured against ``rules/`` at ``56ea32001``
(wave 7) — i.e. BEFORE ``Fix #166`` baked the very guard this plan proposes
into the shipped corpus.  Pass ``--corpus`` a checkout of that tree to
reproduce them; pass the working tree to see today's state.

Usage::

    python3 column-vs-literal-negative-census.py \
        --corpus lib/corej-cdisc-rules/target/rules \
        --pickle /data/cdisc.metadata.library-cache-pkl/variables_metadata.pkl \
        --report text

Exit status is always 0; this is a measurement, not a gate.
"""

from __future__ import annotations

import argparse
import collections
import itertools
import json
import os
import pickle
import re
import sys

# ---------------------------------------------------------------------------
# Lexer / operand classification / AST + parser — the faithful Python port of
# the engine's expression pipeline lives in the shared module
# scripts/lib/check_expr.py (Fix #257); every Python corpus tool imports it.
# ---------------------------------------------------------------------------

_LIB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib")
if _LIB not in sys.path:
    sys.path.insert(0, _LIB)

from check_expr import (  # noqa: E402  (the sys.path bootstrap must run first)
    And, Binary, Call, LexError, Lit, Not, Or, ParseError, Ref, parse)


# ---------------------------------------------------------------------------
# NonEmptyGuardInliner predicates
# ---------------------------------------------------------------------------

NEGATIVE_BINOPS = {"NEQ", "NMATCH", "NOT_IN"}
POSITIVE_BINOPS = {"EQ", "MATCH", "IN", "LT", "GT", "LE", "GE"}
EMPTINESS = {"empty", "is_missing"}


def is_generator_wildcard(name):
    for c in name:
        if c == "*" or c == "{" or ("a" <= c <= "z"):
            return True
    return False


def is_primary_column(node):
    return (isinstance(node, Ref)
            and node.kind in ("COLUMN", "WILDCARD_COLUMN")
            and not is_generator_wildcard(node.name))


# Which literal kinds count as "a literal" for the column-vs-literal arm.
#
#   "shipped"   — NonEmptyGuardInliner.isGuardableLiteral as it stands at HEAD:
#                 STRING / REGEX / homogeneous LIST of those.  NUMBER and BOOL
#                 are excluded on purpose ("their absent-column truth is not the
#                 missing-vs-text story").
#   "published" — the definition the plan's §3 Phase-0 census actually used:
#                 ANY literal, numbers included.  §3.4's "6 leaves compare
#                 against a NUMERIC literal" is the residue this admits.
LITERAL_MODE = "published"


def is_guardable_literal(node):
    if not isinstance(node, Lit):
        return False
    if node.kind in ("STRING", "REGEX"):
        return True
    if node.kind in ("NUMBER", "BOOL"):
        return LITERAL_MODE == "published"
    if node.kind == "LIST":
        return bool(node.value) and all(is_guardable_literal(i) for i in node.value)
    return False


def is_guardable_negative(b):
    lc = is_primary_column(b.left)
    rc = is_primary_column(b.right)
    if lc and rc:
        return True
    return (lc and is_guardable_literal(b.right)) or (rc and is_guardable_literal(b.left))


def column_vs_literal_negative_column(node):
    """Port of NonEmptyGuardInliner.columnVsLiteralNegativeColumn."""
    if not isinstance(node, Binary) or node.op not in NEGATIVE_BINOPS:
        return None
    if not is_guardable_negative(node):
        return None
    lc = is_primary_column(node.left)
    if lc and is_primary_column(node.right):
        return None          # column-vs-column arm
    return node.left if lc else node.right


# ---------------------------------------------------------------------------
# Leaf walk + absent-column sensitivity
# ---------------------------------------------------------------------------

def leaves(node):
    """Boolean leaves — everything that is not And / Or / Not."""
    if isinstance(node, And) or isinstance(node, Or):
        for p in node.parts:
            yield from leaves(p)
    elif isinstance(node, Not):
        yield from leaves(node.inner)
    else:
        yield node


def render(node):
    if isinstance(node, Ref):
        return node.name
    if isinstance(node, Lit):
        if node.kind == "STRING":
            return '"%s"' % node.value
        if node.kind == "REGEX":
            return "/%s/" % node.value
        if node.kind == "LIST":
            return "[%s]" % ", ".join(render(i) for i in node.value)
        return str(node.value)
    if isinstance(node, Call):
        parts = [render(a) for a in node.args]
        parts += ["%s=%s" % (k, render(v)) for k, v in node.kwargs.items()]
        return "%s(%s)" % (node.name, ", ".join(parts))
    if isinstance(node, Binary):
        sym = {"EQ": "==", "NEQ": "!=", "LT": "<", "GT": ">", "LE": "<=",
               "GE": ">=", "MATCH": "=~", "NMATCH": "!~", "IN": "in",
               "NOT_IN": "not in", "ADD": "+", "SUB": "-", "MUL": "*",
               "DIV": "/"}[node.op]
        return "%s %s %s" % (render(node.left), sym, render(node.right))
    if isinstance(node, Not):
        return "not %s" % render(node.inner)
    if isinstance(node, And):
        return " and ".join(render(p) for p in node.parts)
    if isinstance(node, Or):
        return "(%s)" % " or ".join(render(p) for p in node.parts)
    return "?"


EXIST_CALLS = {"var_exists", "variable_exists", "exists", "has_variable"}
NONEMPTY_CALLS = {"non_empty", "not_empty"}


def _names_column(arg, col):
    """A call argument that designates column `col` — bare Ref or quoted Lit."""
    if isinstance(arg, Ref):
        return arg.name == col
    if isinstance(arg, Lit) and arg.kind == "STRING":
        return arg.value == col
    return False


def absent_truth(leaf, col):
    """Truth of `leaf` when column `col` is ABSENT, or None when unknown.

    Absent-column policy (EC-43 / Fix #139): an absent column is a column whose
    values are all missing.
    """
    if isinstance(leaf, Call):
        if leaf.name in EXIST_CALLS and len(leaf.args) == 1 \
                and _names_column(leaf.args[0], col):
            return False
        if leaf.name in EMPTINESS and len(leaf.args) == 1 \
                and _names_column(leaf.args[0], col):
            return True
        if leaf.name in NONEMPTY_CALLS and len(leaf.args) == 1 \
                and _names_column(leaf.args[0], col):
            return False
        return None
    if isinstance(leaf, Binary):
        lc = isinstance(leaf.left, Ref) and leaf.left.name == col
        rc = isinstance(leaf.right, Ref) and leaf.right.name == col
        other = leaf.right if lc else (leaf.left if rc else None)
        if other is None:
            return None
        if not is_guardable_literal(other):
            return None
        if leaf.op in POSITIVE_BINOPS:
            return False
        if leaf.op in NEGATIVE_BINOPS:
            return True
    return None


def evaluate(node, forced, env):
    """Three-valued evaluation.  `forced` maps id(leaf) -> bool/None pin."""
    if isinstance(node, And):
        vals = [evaluate(p, forced, env) for p in node.parts]
        if any(v is False for v in vals):
            return False
        return None if any(v is None for v in vals) else True
    if isinstance(node, Or):
        vals = [evaluate(p, forced, env) for p in node.parts]
        if any(v is True for v in vals):
            return True
        return None if any(v is None for v in vals) else False
    if isinstance(node, Not):
        v = evaluate(node.inner, forced, env)
        return None if v is None else (not v)
    key = id(node)
    if key in forced:
        return forced[key]
    return env.get(key)


MAX_FREE = 14


# ---------------------------------------------------------------------------
# Syntactic guard detection (the primary classifier)
#
# G1  "already guarded"   — a CONJUNCT ancestor sibling carries a presence test
#                           on the same column: not empty(C) / non_empty(C) /
#                           var_exists(C) / exists(C).  False when C is absent
#                           ⇒ the branch dies ⇒ the injected guard is a no-op.
# G2  "OR-neutralised"    — a DISJUNCT ancestor sibling carries not var_exists(C)
#                           (or empty(C)).  True when C is absent ⇒ the OR is
#                           true either way ⇒ the injected guard is a no-op.
#
# ⚠ G2 has a DEPTH parameter and it is the whole difference between the plan's
# published §3 numbers and this re-derivation:
#   "shallow" — only the immediate enclosing Or's own branches, un-nested.
#               This is what the published census did; §3.4 recorded the
#               consequence as "6 exposed leaves have a DEEPER hand-enumerated
#               not var_exists(<same col>) OR branch (--DRVFL, ECOCCUR) that
#               probably makes them no-ops ⇒ the set may really be 80".
#   "deep"    — every disjunct ancestor at any depth.  Confirms that hunch.
OR_DEPTH = "deep"


def _ancestor_siblings(root, target):
    """(conjunct siblings, disjunct siblings, immediate-Or siblings) of `target`."""
    conj, disj, imm = [], [], []

    def walk(node, chain, parent):
        if isinstance(node, (And, Or)):
            for p in node.parts:
                walk(p, chain + [(node, p)], node)
        elif isinstance(node, Not):
            walk(node.inner, chain, parent)
        elif node is target:
            for par, child in chain:
                for s in par.parts:
                    if s is child:
                        continue
                    (conj if isinstance(par, And) else disj).append(s)
            if isinstance(parent, Or):
                imm.extend(s for s in parent.parts if s is not target)

    walk(root, [], None)
    return conj, disj, imm


def _mentions(node, col, want_call_sets):
    """Is there a `col`-naming call from `want_call_sets` under polarity `want_neg`?"""
    hit = [False]

    def w(n, neg):
        if isinstance(n, (And, Or)):
            for p in n.parts:
                w(p, neg)
        elif isinstance(n, Not):
            w(n.inner, not neg)
        elif isinstance(n, Call) and len(n.args) == 1 and _names_column(n.args[0], col):
            for names, polarity in want_call_sets:
                if n.name in names and neg == polarity:
                    hit[0] = True

    w(node, False)
    return hit[0]


PRESENCE = ((EXIST_CALLS, False), (NONEMPTY_CALLS, False), (EMPTINESS, True))
ABSENCE = ((EXIST_CALLS, True), (EMPTINESS, False))


def leaf_guard_state(root, leaf, col):
    """'guarded' (G1) / 'neutralised' (G2) / 'open'."""
    conj, disj, imm = _ancestor_siblings(root, leaf)
    if any(_mentions(s, col, PRESENCE) for s in conj):
        return "guarded"
    branches = disj if OR_DEPTH == "deep" else imm
    if any(_mentions(s, col, ABSENCE) for s in branches):
        return "neutralised"
    return "open"


def leaf_is_open(root, leaf, col):
    """Does injecting `var_exists(col)` on `leaf` change the rule's firing?

    True  ⇒ OPEN (the guard would change behaviour).
    False ⇒ already silenced / OR-neutralised (the guard is a no-op).
    """
    pins = {}
    free = []
    for lf in leaves(root):
        if lf is leaf:
            continue
        t = absent_truth(lf, col)
        if t is None:
            free.append(lf)
        else:
            pins[id(lf)] = t
    # Distinct leaf *texts* share a truth value.
    groups = collections.OrderedDict()
    for lf in free:
        groups.setdefault(render(lf), []).append(lf)
    keys = list(groups)
    if len(keys) > MAX_FREE:
        keys = keys[:MAX_FREE]          # recorded by the caller as truncated
    for combo in itertools.product((False, True), repeat=len(keys)):
        env = {}
        for k, v in zip(keys, combo):
            for lf in groups[k]:
                env[id(lf)] = v
        unguarded = dict(pins)
        unguarded[id(leaf)] = True      # absent column ⇒ negative leaf is TRUE
        guarded = dict(pins)
        guarded[id(leaf)] = False       # var_exists(col) is FALSE ⇒ term dies
        if evaluate(root, unguarded, env) != evaluate(root, guarded, env):
            return True
    return False


# ---------------------------------------------------------------------------
# Library metadata (Required) resolution
# ---------------------------------------------------------------------------

FILE_RE = re.compile(r"^rules-(?P<family>[a-z]+)-(?P<std>adamig|sdtmig|sendig|tig)-"
                     r"(?P<ver>.+)\.json$")

# rules/ package file -> variables_metadata.pkl key.  Documented substitutions:
#   * sendig dart-1-2 has NO cache entry — dart-1-1 substituted (plan §3.4).
#   * tig-1-0 carries four sub-standards; every one is consulted.
PICKLE_ROOT = "library_variables_metadata"


def metadata_keys(fname):
    m = FILE_RE.match(fname)
    if not m:
        return [], None
    std, ver = m.group("std"), m.group("ver")
    if std == "tig":
        return ["%s/tig/%s/%s" % (PICKLE_ROOT, ver, s)
                for s in ("sdtm", "send", "adam", "cdash")], None
    if std == "adamig":
        return ["%s/adam/adamig-%s" % (PICKLE_ROOT, ver)], None
    if std == "sdtmig":
        return ["%s/sdtmig/%s" % (PICKLE_ROOT, ver)], None
    if std == "sendig":
        if ver == "dart-1-2":
            return ["%s/sendig/dart-1-1" % PICKLE_ROOT], "dart-1-2->dart-1-1"
        return ["%s/sendig/%s" % (PICKLE_ROOT, ver)], None
    return [], None


# Corpus domain token -> Library dataset name.  The supplemental-qualifier
# families are authored as a wildcard (`SUPP--` / `SQ--`) but the Library carries
# one SUPPQUAL row; without this alias every RDOMAIN leaf resolves to
# "no-such-dataset" and is scored EXPOSED, which is exactly how the published
# census lost 6 rules out of its Req bucket.
DOMAIN_ALIAS = {"SUPP--": "SUPPQUAL", "SQ--": "SUPPQUAL", "SUPPQUAL": "SUPPQUAL"}


def in_scope_domains(rule, meta_domains):
    sc = rule.get("Scope") or {}
    dom = sc.get("Domains")
    if not dom:
        return None                      # class / structure scoped — unresolvable
    inc = dom.get("Include")
    exc = set(dom.get("Exclude") or [])
    if inc and "ALL" not in inc:
        return [d for d in inc if d not in exc]
    return [d for d in meta_domains if d not in exc]


def resolve_core(meta, keys, domains, colname):
    """Return the multiset of `core` values for `colname` over the in-scope
    (metadata-key, domain) pairs.  A `--` wildcard is substituted per domain."""
    out = []
    for key in keys:
        pkg = meta.get(key)
        if not pkg:
            continue
        doms = domains if domains is not None else list(pkg)
        for d in doms:
            d = DOMAIN_ALIAS.get(d, d)
            ds = pkg.get(d)
            if ds is None:
                out.append((key, d, colname, "no-such-dataset"))
                continue
            col = colname
            if col.startswith("--"):
                col = d + col[2:]
            v = ds.get(col)
            out.append((key, d, col, (v or {}).get("core", "unknown") if v else "unknown"))
    return out


# ---------------------------------------------------------------------------
# Main census
# ---------------------------------------------------------------------------

def family_of(rid):
    return rid.split("-", 1)[0].upper()


CROSS_CHECK = False
cross_check_disagreements = []


def census(corpus, meta):
    rows = []            # one per (package file, rule id, leaf)
    parse_failures = []
    entries = 0
    for fname in sorted(os.listdir(corpus)):
        if not fname.startswith("rules-") or not fname.endswith(".json"):
            continue
        keys, subst = metadata_keys(fname)
        meta_domains = []
        for k in keys:
            meta_domains += list(meta.get(k) or {})
        with open(os.path.join(corpus, fname)) as fh:
            pkg = json.load(fh)["rules"]
        for rid, rule in pkg.items():
            entries += 1
            expr = (rule.get("Check") or {}).get("expression")
            if not expr:
                continue
            try:
                root = parse(expr)
            except (LexError, ParseError) as exc:
                parse_failures.append((fname, rid, str(exc)))
                continue
            cands = []
            for lf in leaves(root):
                colref = column_vs_literal_negative_column(lf)
                if colref is not None:
                    cands.append((lf, colref))
            if not cands:
                continue
            # `Requirements.Variables.All` ONLY: this bucket means "the column is
            # GUARANTEED present, so the negative leaf is already coreJ-safe". `Any`
            # promises only that ONE of its entries is present and `None` guarantees
            # ABSENCE, so folding either in would silently shrink the exposed bucket.
            required_all = set(((rule.get("Requirements") or {}).get("Variables") or {})
                               .get("All") or [])
            domains = in_scope_domains(rule, meta_domains)
            for lf, colref in cands:
                col = colref.name
                state = leaf_guard_state(root, lf, col)
                open_ = state == "open"
                if CROSS_CHECK:
                    kleene = leaf_is_open(root, lf, col)
                    if kleene != open_:
                        cross_check_disagreements.append(
                            (fname, rid, col, state, kleene, expr))
                cores = resolve_core(meta, keys, domains, col)
                rows.append({
                    "file": fname,
                    "rule": rid,
                    "family": family_of(rid),
                    "col": col,
                    "op": lf.op,
                    "leaf": render(lf),
                    "state": state,
                    "expr": expr,
                    "open": open_,
                    "required_var": col in required_all,
                    "precondition": "Precondition" in rule,
                    "domains_resolvable": domains is not None,
                    "cores": cores,
                    "subst": subst,
                })
    return rows, parse_failures, entries


SHAPE = {"NEQ": "!=", "NMATCH": "!~", "NOT_IN": "not in"}

# ⚠ PRECEDENCE — the published §3 table is a PARTITION (32 + 0 + 53 + 86 = 171),
# so every open rule lands in exactly one bucket and the order matters.
#
#   Requirements.Variables.All
#                    first — such a rule is already coreJ-safe and belongs to
#                    PLAN-fork-readable-scope-guards.md P-β, not here.  (Formerly
#                    `Scope.Variables.Include`; the bucket label changed with it.)
#   Req              second — verified, not inherited: the 4 Precondition-bearing
#                    rules (FDA/PMDA-SD0095[A]) all carry a Req column (RDOMAIN in
#                    SUPPQUAL), and the published table reports Precondition = 0,
#                    which is only reachable if Req outranks Precondition.
#                    ⚠ §3.3's prose says they were "counted as exposed" — that
#                    cannot be true, because the published exposed count is 86 and
#                    adding them would make it 90.  The prose is wrong; the number
#                    says Req.
#   Precondition     third (empty in practice — kept so the bucket stays visible).
BUCKET_ORDER = ("requirements_variables", "req", "precondition", "exposed")


def summarise(rows):
    by_rule = collections.defaultdict(list)
    for r in rows:
        by_rule[r["rule"]].append(r)
    carriers = set(by_rule)
    open_rules = {rid for rid, rs in by_rule.items() if any(r["open"] for r in rs)}
    silenced = carriers - open_rules

    buckets = {}
    for rid in sorted(open_rules):
        rs = [r for r in by_rule[rid] if r["open"]]
        if any(r["required_var"] for r in rs):
            buckets[rid] = "requirements_variables"
        elif any(c[3] == "Req" for r in rs for c in r["cores"]):
            buckets[rid] = "req"
        elif any(r["precondition"] for r in rs):
            buckets[rid] = "precondition"
        else:
            buckets[rid] = "exposed"
    return by_rule, carriers, open_rules, silenced, buckets


def leaf_keys(rows, pred):
    """Distinct leaves, keyed (rule, column, operator).

    ⚑ This is the unit the published census used: it reproduces §3.1's "433
    leaves" exactly.  Keying on the rendered leaf TEXT instead gives 446 —
    `ARMCD != "SCRNFAIL" and ARMCD != "NOTASSGN"` is two texts but one
    (rule, column, operator).  Naming the unit is not optional.
    """
    return {(r["rule"], r["col"], r["op"]) for r in rows if pred(r)}


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", default="lib/corej-cdisc-rules/target/rules")
    ap.add_argument("--pickle",
                    default="/data/cdisc.metadata.library-cache-pkl/variables_metadata.pkl")
    ap.add_argument("--report", choices=("text", "json"), default="text")
    ap.add_argument("--literal-mode", choices=("published", "shipped"),
                    default="published",
                    help="which literal kinds count (see LITERAL_MODE)")
    ap.add_argument("--or-depth", choices=("deep", "shallow"), default="deep",
                    help="'shallow' replicates the published census's "
                         "OR-neutralisation matcher; 'deep' corrects it")
    ap.add_argument("--cross-check", action="store_true",
                    help="also run the independent three-valued absent-column "
                         "evaluation and report any leaf the two disagree on")
    args = ap.parse_args(argv)
    global LITERAL_MODE, OR_DEPTH, CROSS_CHECK
    LITERAL_MODE = args.literal_mode
    OR_DEPTH = args.or_depth
    CROSS_CHECK = args.cross_check

    with open(args.pickle, "rb") as fh:
        meta = pickle.load(fh)

    rows, failures, entries = census(args.corpus, meta)
    by_rule, carriers, open_rules, silenced, buckets = summarise(rows)

    if args.report == "json":
        json.dump({"rows": [{k: v for k, v in r.items() if k != "cores"}
                            | {"cores": r["cores"]} for r in rows],
                   "buckets": buckets,
                   "parse_failures": failures}, sys.stdout, indent=1)
        return 0

    print("corpus              : %s" % args.corpus)
    print("literal mode        : %s   OR-neutralisation depth: %s"
          % (LITERAL_MODE, OR_DEPTH))
    print("package entries     : %d" % entries)
    print("parse failures      : %d" % len(failures))
    print("carrier rules       : %d  (distinct leaves %d)"
          % (len(carriers), len(leaf_keys(rows, lambda r: True))))
    print("  guarded leaves    : %d   (G1: conjunct presence test)"
          % len(leaf_keys(rows, lambda r: r["state"] == "guarded")))
    print("  neutralised leaves: %d   (G2: OR branch, %s)"
          % (len(leaf_keys(rows, lambda r: r["state"] == "neutralised")), OR_DEPTH))
    print("  all silenced/neut : %d rules" % len(silenced))
    print("  >=1 open leaf     : %d rules  (open leaves %d)"
          % (len(open_rules), len(leaf_keys(rows, lambda r: r["open"]))))
    for b in BUCKET_ORDER:
        rids = sorted(r for r, v in buckets.items() if v == b)
        nleaf = len(leaf_keys(rows, lambda r, b=b: r["open"]
                              and buckets.get(r["rule"]) == b))
        print("    %-16s: %3d  (open leaves %d)" % (b, len(rids), nleaf))
    print()
    for b in ("req", "exposed"):
        print("%-8s by family  : %s" % (b, dict(sorted(collections.Counter(
            family_of(r) for r, v in buckets.items() if v == b).items()))))
        print("%-8s by shape   : %s" % (b, dict(sorted(collections.Counter(
            SHAPE[k[2]] for k in leaf_keys(
                rows, lambda r, b=b: r["open"] and buckets.get(r["rule"]) == b)
        ).items()))))
    if CROSS_CHECK:
        print()
        print("cross-check disagreements (syntactic vs three-valued): %d"
              % len(cross_check_disagreements))
        for d in cross_check_disagreements[:20]:
            print("  ", d[:5])
    for b in ("req", "exposed", "requirements_variables", "precondition"):
        print()
        print("== %s bucket (%d rules) ==" % (b, sum(1 for v in buckets.values() if v == b)))
        for rid in sorted(r for r, v in buckets.items() if v == b):
            rs = [r for r in by_rule[rid] if r["open"]]
            cols = sorted({r["col"] for r in rs})
            shapes = sorted({SHAPE[r["op"]] for r in rs})
            vals = collections.Counter(c[3] for r in rs for c in r["cores"])
            uniform = "REQ-uniform" if set(vals) == {"Req"} else "mixed:" + \
                ",".join("%s=%d" % kv for kv in sorted(vals.items()))
            cls = "" if rs[0]["domains_resolvable"] else "  [CLASS-SCOPED]"
            print("  %-18s %-14s %-8s %s%s"
                  % (rid, ",".join(cols), ",".join(shapes), uniform, cls))
    if failures:
        print()
        print("== parse failures ==")
        for f in failures[:40]:
            print("  ", f)
    return 0


if __name__ == "__main__":
    sys.exit(main())
