#!/usr/bin/env python3
"""Phase 3b step 0 - repair the Rule_Identifier citation on the 33 rules that need one.

Owner rulings, plans/PLAN-source-proposed-block.md:
  * open question 1, reading (a) - FDA-SE2280-A / -B cite their PARENT's row SE2280
    ("it is a split rule so it should cite its original sheet row").
  * ruling I - the 31 minted-band rules cite the GENERIC published row they operationalise
    (FDA/PMDA SD0002, SDTMIG CG0014, SEND SEND12).

Both are SCOPED LIFTS of the plan's "no Standards edits" non-goal, and nothing else is touched:
the script inserts a References block ONLY into a Name/Version entry that has none, and only in
the rule's OWN-organization Standards block.  Idempotent.
"""
import os, re, sys

ORIGIN = {'FDA': ('FDA Validator Rules', '1.6'),
          'PMDA': ('PMDA Validation Rules', '6.0'),
          'CDISC-CG': ('SDTM and SDTMIG Conformance Rules', '2.0'),
          'CDISC-SEND': ('SEND Conformance Rules', '5.0')}

TARGETS = {}
for n in (9701, 9702, 9703, 9704, 9705, 9707, 9708, 9709, 9710, 9711, 9712):
    TARGETS['FDA-SD%d' % n] = ('FDA', 'SD0002')
    TARGETS['PMDA-SD%d' % n] = ('PMDA', 'SD0002')
TARGETS['FDA-SE9701'] = ('FDA', 'SD0002')
TARGETS['FDA-SE9702'] = ('FDA', 'SD0002')
TARGETS['CDISC-CG9670'] = ('CDISC-CG', 'CG0014')
for n in (9430, 9431, 9433, 9434, 9435, 9436):
    TARGETS['CDISC-SEND-%d' % n] = ('CDISC-SEND', 'SEND12')
TARGETS['FDA-SE2280-A'] = ('FDA', 'SE2280')
TARGETS['FDA-SE2280-B'] = ('FDA', 'SE2280')
assert len(TARGETS) == 33, len(TARGETS)


def own_org(rule_id):
    return 'FDA' if rule_id.startswith('FDA-') else \
           'PMDA' if rule_id.startswith('PMDA-') else 'CDISC'


def repair(path, rule_id):
    fam, sheet_id = TARGETS[rule_id]
    origin, ver = ORIGIN[fam]
    lines = open(path, encoding='utf-8').read().split('\n')
    out, i, added, in_std, in_own = [], 0, 0, False, False
    while i < len(lines):
        line = lines[i]
        if line.startswith('Standards:'):
            in_std = True
        elif in_std and line and not line.startswith(' ') and not line.startswith('-'):
            in_std = False
        if in_std and line.startswith('- Organization:'):
            in_own = ('"%s"' % own_org(rule_id)) in line
        out.append(line)
        if in_std and in_own and re.match(r'^  - Name: ', line):
            # copy the Version line, then insert References if the entry has none
            j = i + 1
            body = []
            while j < len(lines) and re.match(r'^    \S', lines[j]):
                body.append(lines[j]); j += 1
            has_ref = any(b.startswith('    References:') for b in body)
            if has_ref:
                out.extend(body)
            else:
                for b in body:
                    out.append(b)
                out.extend(['    References:',
                            '    - Origin: "%s"' % origin,
                            '      Rule_Identifier:',
                            '        Id: "%s"' % sheet_id,
                            '        Version: "1"',
                            '      Version: "%s"' % ver])
                added += 1
            i = j
            continue
        i += 1
    if added:
        open(path, 'w', encoding='utf-8').write('\n'.join(out))
    return added


def main(root, dry):
    total = files = 0
    missing = []
    for rule_id in sorted(TARGETS):
        hits = [os.path.join(dp, f) for dp, _, fs in os.walk(root)
                for f in fs if f == rule_id + '.yaml']
        assert len(hits) <= 1, (rule_id, hits)
        if not hits:
            missing.append(rule_id)
            continue
        if dry:
            n = open(hits[0], encoding='utf-8').read().count('  - Name: ')
            print('[cite] %-18s would touch %s' % (rule_id, hits[0]))
        else:
            n = repair(hits[0], rule_id)
            if n:
                files += 1
                print('[cite] %-18s +%d References block(s)' % (rule_id, n))
        total += n
    print('[cite] %d rules, %d files changed, %d blocks inserted' % (len(TARGETS), files, total))
    if missing:
        print('[cite] NOT FOUND (%d): %s' % (len(missing), ', '.join(missing)))
    return missing


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'rules-src/checks', '--dry-run' in sys.argv)
