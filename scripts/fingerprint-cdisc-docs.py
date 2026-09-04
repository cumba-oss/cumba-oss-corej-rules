#!/usr/bin/env python3
"""Fingerprints the conformance-workbook directory — the UNTRACKED, UN-PINNED workbook inputs.

The directory is named by $CDISC_DOCS_DIR and has no default: the workbooks are a licensed
external document set, not part of this repository and with no location inside it.

WHY THIS EXISTS (finding P0-F1, plans/PLAN-source-proposed-block.md §3.1).
STANDING-RULINGS.md §1 baseline condition 4 says a workbook refresh can turn a green gate red with zero
git change, and offers the mtime as the signal.  The mtime was NOT a usable signal: every
`WorkbookFactory.create(File)` call opened the workbook READ_WRITE, so POI touched it on close and
a write-free `--dry-run` census moved all five mtimes it read.  Those three call sites are now
read-only, and this script replaces the mtime with content.

⛔ Unlike testdata/study, these workbooks are NOT pinned to a restorable upstream and are in no
git tree.  A drift here is unrecoverable, so the fingerprint is the only record that they are the
same bytes the corpus was transcribed from.

Writes documentation/cdisc-docs-fingerprint.tsv.  Run from the module root.
"""
import hashlib
import os
import sys

DOCS = os.environ.get('CDISC_DOCS_DIR')
OUT = os.path.join('documentation', 'cdisc-docs-fingerprint.tsv')


def main():
    if not DOCS:
        print('[docs-fingerprint] $CDISC_DOCS_DIR is not set — set it to the directory holding '
              'the conformance workbooks. There is no default: they are a licensed external '
              'document set, not part of this repository.', file=sys.stderr)
        return 2
    if not os.path.isdir(DOCS):
        print('[docs-fingerprint] %s is not mounted — nothing to do' % DOCS, file=sys.stderr)
        return 2
    rows, agg, total = [], hashlib.sha256(), 0
    for name in sorted(os.listdir(DOCS)):
        if not name.endswith('.xlsx'):
            continue
        path = os.path.join(DOCS, name)
        digest = hashlib.sha256()
        with open(path, 'rb') as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b''):
                digest.update(chunk)
        size = os.path.getsize(path)
        rows.append((name, str(size), digest.hexdigest()))
        agg.update(digest.digest())
        total += size
    with open(OUT, 'w', encoding='utf-8') as fh:
        fh.write('# Fingerprint of %s — the UNTRACKED workbook inputs the Source blocks are\n' % DOCS)
        fh.write('# transcribed from. They are in no git tree and are pinned to no upstream, so\n')
        fh.write('# this file is the only record that they have not drifted.  See finding P0-F1.\n')
        fh.write('#\n')
        fh.write('# aggregate_sha256\t%s\n' % agg.hexdigest())
        fh.write('# files\t%d\n' % len(rows))
        fh.write('# bytes\t%d\n' % total)
        fh.write('file\tbytes\tsha256\n')
        for r in rows:
            fh.write('\t'.join(r) + '\n')
    print('[docs-fingerprint] %d files, %d bytes, aggregate %s -> %s'
          % (len(rows), total, agg.hexdigest(), OUT))
    return 0


if __name__ == '__main__':
    sys.exit(main())
