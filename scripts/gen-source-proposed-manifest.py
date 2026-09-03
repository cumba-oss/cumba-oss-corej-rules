#!/usr/bin/env python3
"""Regenerates documentation/rule-inventory/source-proposed-manifest.tsv.

The plan's Phase 0 deliverable: the §0.1 taxonomy applied to EVERY rule in rules-src/checks,
one row per rule, so the populations are a LIST and not a count.  Run from the module root.

The classification here must agree with net.cumba.corej.core.SourceProposedCase; the Java side is
authoritative and SourceProposedGuardTest asserts the corpus against it.
"""
import os, re, glob, collections

FILES = {os.path.basename(f)[:-5]: f
         for f in sorted(glob.glob('rules-src/checks/**/*.yaml', recursive=True))}

GENERIC_ROW_SPLITS = dict(
    [(f'FDA-SD{n}', 'SD0002') for n in
     (9701, 9702, 9703, 9704, 9705, 9707, 9708, 9709, 9710, 9711, 9712)]
    + [('FDA-SE9701', 'SD0002'), ('FDA-SE9702', 'SD0002')]
    + [(f'PMDA-SD{n}', 'SD0002') for n in
       (9701, 9702, 9703, 9704, 9705, 9707, 9708, 9709, 9710, 9711, 9712)]
    + [('CDISC-CG9670', 'CG0014')]
    + [(f'CDISC-SEND-{n}', 'SEND12') for n in (9430, 9431, 9433, 9434, 9435, 9436)])

CORRECTION_RULES = set("""
CDISC-AD0167 CDISC-AD0222 CDISC-AD0346 CDISC-AD0552 CDISC-AD0565 CDISC-AD0566 CDISC-AD0567
CDISC-AD0568 CDISC-AD0569 CDISC-AD0583 CDISC-AD0587 CDISC-AD0749 CDISC-AD0794 CDISC-CG0348
CDISC-CG0625 CDISC-CG0634 CDISC-CG0637 CDISC-CG0647 CDISC-SEND-0293 PMDA-AD0201 PMDA-AD0482
PMDA-AD0483 PMDA-AD0484 PMDA-AD0485 PMDA-SD1086
""".split())

REG = 'documentation/rule-review-results/'
DOCS = ['SHEET-ISSUES-CDISC.md', 'SHEET-ISSUES-CORE.md', 'SHEET-ISSUES-FDA.md',
        'SHEET-ISSUES-PMDA.md', 'CDISC-METADATA-ISSUES.md']


def has_source(i):
    return any(l.startswith('Source:') for l in open(FILES[i], encoding='utf-8'))


def tail(i):
    m = re.search(r'(\d+)[A-Z]?(?:-[A-Z])?$', i)
    return int(m.group(1)) if m else -1


def read_entries():
    entries = {}
    for d in DOCS:
        text = open(REG + d, encoding='utf-8').read()
        for line in text.split('\n'):
            if line.startswith('|'):
                cells = [x.strip() for x in line.strip().strip('|').split('|')]
                if cells and re.fullmatch(r'\d+', cells[0]):
                    entries.setdefault(int(cells[0]), []).append(line)
        for m in re.finditer(r'^##\s*`?#(\d+)`?(.*?)(?=^## |\Z)', text, re.M | re.S):
            entries.setdefault(int(m.group(1)), []).append(m.group(0))
    return entries


def main():
    entries = read_entries()

    def issues(rule):
        return sorted(n for n, ls in entries.items()
                      if any(re.search(r'\b' + re.escape(rule) + r'\b', l) for l in ls))

    rows, seen = [], collections.Counter()
    for i in sorted(FILES):
        hs = has_source(i)
        parent = origin = issue = ''
        if i.startswith(('CORE-', 'DRAFT-')):
            case = 'out-of-scope'
        elif re.search(r'-[A-Z]$', i):
            case, parent, origin = 'split', i[:-2], 'generated'
        elif i in GENERIC_ROW_SPLITS:
            case, parent, origin = 'split', GENERIC_ROW_SPLITS[i], 'generated'
        elif tail(i) >= 9000 and not hs:
            case, origin = 'missing-row', 'authored'
        elif i in CORRECTION_RULES:
            case, origin = 'correction', 'authored'
        else:
            case = 'as-described'
        if case in ('split', 'missing-row', 'correction'):
            found = issues(i)
            if case == 'split' and not found and parent in FILES:
                found = issues(parent)
            issue = ','.join('#%d' % n for n in found)
        seen[case] += 1
        rows.append((i, case, parent, 'yes' if hs else 'no', issue, origin))

    out = 'documentation/rule-inventory/source-proposed-manifest.tsv'
    with open(out, 'w', encoding='utf-8') as fh:
        fh.write('core_id\tcase\tparent\thas_source\tsheet_issue\torigin\n')
        for r in rows:
            fh.write('\t'.join(r) + '\n')
    print('[manifest] %d rows -> %s' % (len(rows), out))
    print('[manifest] %s' % dict(seen))
    seeding = seen['split'] + seen['missing-row'] + seen['correction']
    print('[manifest] seeding population: %d' % seeding)
    assert sum(seen.values()) == len(FILES), 'taxonomy is not total'


if __name__ == '__main__':
    main()
