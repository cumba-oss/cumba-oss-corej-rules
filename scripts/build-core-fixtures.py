#!/usr/bin/env python3
"""Build the curated engine-test fixture corpus (PLAN-engine-rules-decoupling §4).

Reads the full corpus in this module (rules-src/checks + the GENERATED target/rules/)
and materialises
the engine's fixtures under corej-cdisc-core/src/test/resources/fixtures/rules/:

  checks/<FAMILY>/<id>.yaml         raw-yaml copies for the B1 probe tests
  packages/packages.json            trimmed manifest (family entries with >=1 kept rule)
  packages/rules-<fam>-<std>-<ver>.json   per-family packages filtered to the B2 ids
  manifest.json                     the id lists this run materialised (integrity pin)

Deterministic: id lists are pinned below (PLAN §4.1/§4.2). A declared id absent from a
package is silently skipped (keep = intersection) — some ids span only a subset of the
declared standard versions; the consuming engine test is the backstop if a regen drops a
pinned rule entirely. The sdtmig-3-2 sample for
the package-wide load-clean smoke is the first SAMPLE_32 ids of each family file in
sorted order. Re-run after corpus changes that touch a fixture id, then re-run the
engine's FixtureCorpusIntegrityTest and the rules module's RulesFixtureSyncTest.

Usage: python3 scripts/build-core-fixtures.py [--corpus-root DIR] [--engine-root DIR]
"""

import argparse
import json
import pathlib
import shutil
import sys

# --- B1: tests that load a single rules-src/checks/<FAM>/<id>.yaml (PLAN §4.1) ---
B1_IDS = [
    "CORE-000712",
    "CDISC-AD0899", "PMDA-AD0127", "PMDA-AD0258",
    "CDISC-SEND-0277", "PMDA-SD1142",
    "CDISC-SEND-0272", "FDA-SD0062",
    "FDA-SE2280", "FDA-SE2280-A", "FDA-SE2280-B",
    "PMDA-AD0314",
    # PmdaAdC3WithinDatasetProbeTest (14 pairs)
    *[f"PMDA-AD0{n}{s}" for n in (280, 281, 283, 284, 287, 288, 289, 290,
                                  293, 294, 295, 296, 310, 311) for s in ("", "B")],
    "PMDA-AD0084", "PMDA-AD0098", "PMDA-AD0099", "PMDA-AD0651", "PMDA-AD0652",
    "PMDA-AD0299", "PMDA-AD0303", "PMDA-AD0312", "PMDA-AD0313",
    "PMDA-AD0102", "PMDA-AD0103", "PMDA-AD0104", "PMDA-AD0605", "PMDA-AD0607",
    "PMDA-AD0609", "PMDA-AD0611", "PMDA-AD0613", "PMDA-AD0615",
    "CDISC-AD0204", "CDISC-AD0205", "CDISC-AD0206", "CDISC-AD0207",
    "CDISC-AD0208", "CDISC-AD0209", "CDISC-AD0210", "CDISC-AD0367",
    "CDISC-SEND-0324", "CDISC-SEND-0326", "CDISC-SEND-0327",
]

# --- B2: tests that loadCombined() a package and filter by id (PLAN §4.2) ---
# (standard, version) -> ids that must be present in the trimmed family union.
B2_BY_TUPLE = {
    ("sdtmig", "3-4"): [
        "CORE-000929", "CORE-000699", "CORE-001081",
        "FDA-SD0054", "PMDA-SD1152",
        "FDA-SD1324", "FDA-SD0059", "FDA-SD0060",
    ],
    # RuleLoadValidationNonExecutableOperatorTest: package-wide loads-clean smoke —
    # no id filter; a deterministic per-family sample is included instead (below).
    ("sdtmig", "3-2"): [],
    ("adamig", "1-0"): ["CDISC-AD0059", "CDISC-AD0131", "CDISC-AD0148", "CDISC-AD0735"],
    ("adamig", "1-1"): ["CDISC-AD0734"],
    ("adamig", "1-2"): [
        "CDISC-AD0131", "CDISC-AD0735",
        "CDISC-AD0053", "CDISC-AD0204", "CDISC-AD0210", "CDISC-AD0367", "CDISC-AD0591",
        "CDISC-AD0084", "CDISC-AD0353", "CDISC-AD0581", "CDISC-AD0597",
        "CDISC-AD0614", "CDISC-AD0615",
    ],
    ("adamig", "1-3"): [
        "CDISC-AD0044", "CDISC-AD0045", "CDISC-AD0048",
        "CDISC-AD0078", "CDISC-AD0079",
        "CDISC-AD0102", "CDISC-AD0103", "CDISC-AD0104", "CDISC-AD0500",
        "CDISC-AD0706", "CDISC-AD0707",
        "CDISC-AD0131", "CDISC-AD0735", "CDISC-AD0897",
        "CDISC-AD0498", "CDISC-AD0592", "CDISC-AD0598", "CDISC-AD0604", "CDISC-AD0605",
        "CDISC-AD0640", "CDISC-AD0641", "CDISC-AD0642", "CDISC-AD0643",
        "CDISC-AD0644", "CDISC-AD0645", "CDISC-AD0646",
        "CDISC-AD0053", "CDISC-AD0204", "CDISC-AD0210", "CDISC-AD0367", "CDISC-AD0591",
    ],
}

# Per-family sample size for tuples with a package-wide (no-id-filter) consumer.
SAMPLE_32 = 5

FAMILIES = ("CORE", "CDISC", "FDA", "PMDA", "ADAM")


def find_check(corpus: pathlib.Path, rid: str) -> pathlib.Path:
    for fam in FAMILIES:
        p = corpus / "rules-src" / "checks" / fam / f"{rid}.yaml"
        if p.exists():
            return p
    raise SystemExit(f"fixture id has no rules-src source: {rid}")


def main() -> None:
    ap = argparse.ArgumentParser()
    module_root = pathlib.Path(__file__).resolve().parents[1]
    ap.add_argument("--corpus-root", type=pathlib.Path, default=module_root)
    # ⚑ The GENERATED corpus lives under target/ and is no longer committed
    # (plans/PLAN-rules-corpus-build-integration.md). Build corej-cdisc-rules first --
    # `mvn -P main -pl lib/corej-cdisc-rules -am process-test-classes` is enough.
    ap.add_argument("--generated-corpus", type=pathlib.Path, default=None,
                    help="the generated rules/ corpus (default: <corpus-root>/target/rules)")
    ap.add_argument("--engine-root", type=pathlib.Path,
                    default=module_root.parent / "corej-cdisc-core")
    args = ap.parse_args()

    corpus, engine = args.corpus_root, args.engine_root
    generated = args.generated_corpus or (corpus / "target" / "rules")
    if not (generated / "packages.json").is_file():
        raise SystemExit(
            f"no generated corpus at {generated}. It is produced by the corej-cdisc-rules "
            "build and is not committed: run "
            "`mvn -P main -pl lib/corej-cdisc-rules -am process-test-classes` first, "
            "or pass --generated-corpus.")
    fixtures = engine / "src" / "test" / "resources" / "fixtures" / "rules"
    if fixtures.exists():
        shutil.rmtree(fixtures)
    (fixtures / "packages").mkdir(parents=True)

    # B1 yaml copies
    for rid in sorted(set(B1_IDS)):
        src = find_check(corpus, rid)
        dst = fixtures / "checks" / src.parent.name / src.name
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)

    # B2 trimmed packages + manifest
    real_manifest = json.loads((generated / "packages.json").read_text())
    kept_entries = []
    for entry in real_manifest["packages"]:
        std = entry["standard"].lower().replace(".", "-")
        ver = entry["version"].replace(".", "-")
        ids = B2_BY_TUPLE.get((std, ver))
        if ids is None:
            continue
        pkg = json.loads((generated / entry["file"]).read_text())
        rules = pkg.get("rules", {})
        keep = {rid: rules[rid] for rid in sorted(rules) if rid in set(ids)}
        if not ids:  # package-wide smoke tuple: deterministic per-family sample
            for rid in sorted(rules)[:SAMPLE_32]:
                keep[rid] = rules[rid]
        if not keep:
            continue
        out = dict(pkg)
        out["rules"] = keep
        (fixtures / "packages" / entry["file"]).write_text(
            json.dumps(out, indent=2, sort_keys=False) + "\n")
        new_entry = dict(entry)
        new_entry["ruleCount"] = len(keep)
        kept_entries.append(new_entry)

    (fixtures / "packages" / "packages.json").write_text(json.dumps(
        {"generatedFrom": "build-core-fixtures.py (trimmed)", "packages": kept_entries},
        indent=2) + "\n")

    manifest = {
        "b1_ids": sorted(set(B1_IDS)),
        "b2_by_tuple": {f"{s}-{v}": sorted(ids) for (s, v), ids in B2_BY_TUPLE.items()},
        "sample_per_family_for_package_wide_tuples": SAMPLE_32,
        "pending_revival_no_source": [
            "ADAM-ADD-100019", "ADAM-ADD-100025", "ADAM-ADD-100026",
            "CDISC-AD0048-TRTPRES-twin:CDISC-AD9001-TRTPRES",
        ],
    }
    (fixtures / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    n_yaml = len(list(fixtures.glob("checks/*/*.yaml")))
    n_pkg = len(list(fixtures.glob("packages/rules-*.json")))
    print(f"fixtures built: {n_yaml} yaml, {n_pkg} trimmed packages, "
          f"{len(kept_entries)} manifest entries", file=sys.stderr)


if __name__ == "__main__":
    main()
