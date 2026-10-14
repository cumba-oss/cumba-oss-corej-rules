#!/usr/bin/env python3
"""Build this folder's adlbc.dsjc from CDISC DataExchange-DatasetJson's examples/adam/adlbc.dsjc.

The source is gzip-compressed NDJSON (Dataset-JSON 1.1: one metadata line, then one row per line).
The only change is two ISO 8601 text columns appended at the end, AXDTM (length 20) and AXTM
(length 8), empty in every row. The ADaM template tests need a *DTM / *TM column pair on a BDS
dataset to build their CDISC-AD0044 cases (and AD0369's AXDTF case follows from AXDTM); the
template harness writes the values it needs into its own fixture copy. The define.xml beside this
file declares the same two columns (PruneDefineXmlDriver); keep both in step.

Row text is copied verbatim (only ',"",""' is inserted before each row's closing bracket), so every
upstream value keeps its exact JSON spelling. The output is gzip with a zero timestamp and no file
name, so the same input always gives the same bytes.

    python3 add_adlbc_iso_columns.py <DataExchange-DatasetJson>/examples/adam/adlbc.dsjc adlbc.dsjc
"""
import gzip
import json
import sys

ADDED = [
    {"itemOID": "IT.ADLBC.AXDTM", "name": "AXDTM", "label": "Analysis Datetime (ISO)", "dataType": "string",
     "length": 20},
    {"itemOID": "IT.ADLBC.AXTM", "name": "AXTM", "label": "Analysis Time (ISO)", "dataType": "string",
     "length": 8},
]


def main(src, dst):
    with gzip.open(src, "rt", encoding="utf-8", newline="\n") as f:
        lines = f.read().split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    meta = json.loads(lines[0])
    names = {c["name"] for c in meta["columns"]}
    if any(c["name"] in names for c in ADDED):
        sys.exit("AXDTM/AXTM already present in " + src)
    meta["columns"].extend(ADDED)
    rows = lines[1:]
    if len(rows) != meta["records"]:
        sys.exit("records %d != rows %d in %s" % (meta["records"], len(rows), src))
    out = [json.dumps(meta, separators=(",", ":"), ensure_ascii=False)]
    for row in rows:
        if not (row.startswith("[") and row.endswith("]")):
            sys.exit("not an NDJSON row: " + row[:80])
        out.append(row[:-1] + ',"",""]')
    data = ("\n".join(out) + "\n").encode("utf-8")
    with open(dst, "wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0,
                                               compresslevel=9) as gz:
        gz.write(data)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
