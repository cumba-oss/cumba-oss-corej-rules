#!/usr/bin/env python3
"""Fingerprint testdata/study — the UNTRACKED input the findings snapshot is generated from.

testdata/study is gitignored (.gitignore:84): ~53 MB of public CDISC pilot sample data
(CDISCPILOT01). The snapshot OUTPUT is committed; without this the INPUT is unversioned, so a
change in the study would move the snapshot with no git trace and read as a code regression.

⚠ This does NOT make the snapshot reproducible elsewhere — only detectable when its input moves.
"""
import hashlib, os, sys

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "..", "testdata", "study")
OUT = os.path.join(os.path.dirname(__file__), "..", "documentation",
                   "findings-snapshot-input-fingerprint.tsv")

def main() -> int:
    root = os.path.normpath(ROOT)
    if not os.path.isdir(root):
        print(f"[fingerprint] study fixture not found: {root}", file=sys.stderr)
        print("[fingerprint] the findings snapshot cannot be generated or verified without it",
              file=sys.stderr)
        return 2
    rows = []
    for dp, _, fs in os.walk(root):
        for fn in sorted(fs):
            p = os.path.join(dp, fn)
            with open(p, "rb") as fh:
                h = hashlib.sha256(fh.read()).hexdigest()
            rows.append((os.path.relpath(p, root), os.path.getsize(p), h))
    rows.sort()
    agg = hashlib.sha256("".join(f"{a}{b}{c}" for a, b, c in rows).encode()).hexdigest()
    with open(os.path.normpath(OUT), "w", encoding="utf-8") as f:
        f.write("# Fingerprint of testdata/study — the UNTRACKED input the findings snapshot is generated from.\n")
        f.write("#\n# PROVENANCE (read from dm.json / define.xml, 2026-08-26):\n")
        f.write("#   studyOID            CDISCPILOT01\n")
        f.write("#   originator          CDISC Data Exchange Standards Team\n")
        f.write("#   datasetJSONVersion  1.1.0\n")
        f.write("#   sourceSystem        SAS on X64_10PRO 9.0401M7\n")
        f.write("#   dbLastModified      2012-04-04T22:16:21   (DM: 306 records)\n")
        f.write("#\n")
        f.write("# UPSTREAM SOURCE — verified 2026-08-26, all 23 files byte-identical (sha256):\n")
        f.write("#   https://github.com/MiBiMiFlo/cdisc-data-examples\n")
        f.write("#          /tree/main/sdtm-adam-pilot-project/sdtm/json\n")
        f.write("#   raw:  https://raw.githubusercontent.com/MiBiMiFlo/cdisc-data-examples/main\n")
        f.write("#          /sdtm-adam-pilot-project/sdtm/json/<file>\n")
        f.write("#   Checked file-by-file against the hashes below: 23 identical, 0 differing,\n")
        f.write("#   0 missing upstream. This directory can therefore RESTORE testdata/study exactly.\n")
        f.write("#\n")
        f.write("# WRONG DIRECTORY IN THE SAME REPO — do not re-check it:\n")
        f.write("#   .../DataExchange-DatasetJson/sdtm/json is a DIFFERENT release of the same family.\n")
        f.write("#   Every file differs (dm.json 77866 vs 7984 B; ae.json 421104 vs 22830 B; define.xml\n")
        f.write("#   253380 vs 608169 B) and the domain sets are not nested (it has dd/di/ec/fa/ft/ie/\n")
        f.write("#   oe/qsph/qssl/rs/suppec; ours has qs/sc/suppae/suppds/supplb). The correct one is\n")
        f.write("#   sdtm-adam-pilot-project/, above.\n")
        f.write("# testdata/study is gitignored (.gitignore:84) because it is ~53 MB of public CDISC pilot\n")
        f.write("# sample data (CDISCPILOT01). The snapshot OUTPUT is committed; this pins the INPUT so a\n")
        f.write("# snapshot claim can name the data it was taken against, and so a change in the study is\n")
        f.write("# detectable instead of surfacing as an unexplained snapshot diff that reads like a code\n")
        f.write("# regression.\n#\n")
        f.write("# Regenerate:  python3 lib/corej-cdisc-rules/scripts/fingerprint-testdata-study.py\n")
        f.write("# Verify:      same command, then `git diff` this file — a non-empty diff means the INPUT\n")
        f.write("#              moved, and every byte-identity claim against the snapshot is void.\n#\n")
        f.write(f"# files\t{len(rows)}\ttotal_bytes\t{sum(r[1] for r in rows)}\n")
        f.write(f"# aggregate_sha256\t{agg}\n")
        f.write("path\tbytes\tsha256\n")
        for a, b, c in rows:
            f.write(f"{a}\t{b}\t{c}\n")
    print(f"[fingerprint] {len(rows)} files, {sum(r[1] for r in rows)} bytes, aggregate {agg}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
