#!/usr/bin/env python3
"""Repair relative markdown links that a file move broke - and NEVER touch a generated region.

Why this exists.  Moving plans into `plans/done/` (2026-08-31) broke 58 links at once, in two
shapes that only appear when moves happen in successive batches:

  * `../PLAN-x.md` from a file already in `done/`, written when the target was still in `plans/`
    - correct at the time, wrong once the target moved too;
  * `done/PLAN-x.md` from a file in `plans/`, written before the *source* itself moved.

Rewriting per batch produces exactly this: every step correct, the accumulation wrong.  The fix is
to recompute each link from where the target actually is now, which is what this script does.

⛔⛔ THE GENERATED-REGION RULE, and the incident that motivated it.  An earlier ad-hoc version of
this repair walked every `.md` blindly and rewrote a plan path INSIDE the
`<!-- BEGIN GENERATED: rule-doc-census -->` region of `CORE-RULES-SPECIFICATION.md`.  That region is
owned by `RuleDocCensus`, so the edit reddened `RuleDocCensusDriftTest` on the next gate - and
regenerating would have re-broken the link, because the generator hardcoded the old path.  A link
inside a generated region is the GENERATOR's bug and must be fixed in the generator's source.
This script therefore skips those regions entirely and REPORTS them instead.

Dry-run by default; pass --apply to write.  Idempotent.

    python3 lib/corej-cdisc-rules/scripts/repair-md-links.py            # report only
    python3 lib/corej-cdisc-rules/scripts/repair-md-links.py --apply
"""
import os, re, subprocess, sys

SKIP_DIRS = {'target', 'node_modules', '.git', '.venv-py-parity', '__pycache__'}
LINK = re.compile(r'\]\((?!https?:|mailto:)([^)\s]+\.md)((?:#[^)]*)?)\)')
BEGIN = re.compile(r'<!--\s*BEGIN GENERATED')
END = re.compile(r'<!--\s*END GENERATED')


def repo_root():
    return subprocess.run(['git', 'rev-parse', '--show-toplevel'],
                          capture_output=True, text=True).stdout.strip() or '.'


def generated_spans(text):
    """Character ranges owned by a generator. An unclosed BEGIN protects to EOF, deliberately:
    the conservative direction is to edit too little, never too much."""
    spans = []
    for b in BEGIN.finditer(text):
        e = END.search(text, b.end())
        spans.append((b.start(), e.end() if e else len(text)))
    return spans


def index_markdown(root):
    """basename -> [paths]. A basename with two homes is ambiguous and is never auto-rewritten."""
    idx = {}
    for base, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for n in names:
            if n.endswith('.md'):
                idx.setdefault(n, []).append(os.path.normpath(os.path.join(base, n)))
    return idx


def main():
    apply_changes = '--apply' in sys.argv[1:]
    root = repo_root()
    os.chdir(root)
    idx = index_markdown('.')

    files = sorted({f for f in subprocess.run(['git', 'ls-files', '*.md'],
                                              capture_output=True, text=True).stdout.split()
                    if os.path.isfile(f)}
                   | {p for ps in idx.values() for p in ps if os.path.isfile(p)})

    fixed = skipped_generated = ambiguous = unresolvable = 0
    touched, in_generated = [], []

    for f in files:
        try:
            text = open(f, encoding='utf-8').read()
        except (UnicodeDecodeError, OSError):
            continue
        spans = generated_spans(text)
        out, last, changed = [], 0, False
        for m in LINK.finditer(text):
            href, frag = m.group(1), m.group(2)
            if os.path.exists(os.path.normpath(os.path.join(os.path.dirname(f), href))):
                continue                                    # already resolves
            if any(s <= m.start() < e for s, e in spans):    # ⛔ generator's territory
                skipped_generated += 1
                in_generated.append('%s: %s' % (f, href))
                continue
            cands = idx.get(os.path.basename(href), [])
            if len(cands) > 1:
                ambiguous += 1
                continue
            if not cands:
                unresolvable += 1
                continue
            rel = os.path.relpath(cands[0], os.path.dirname(f) or '.')
            out.append(text[last:m.start()])
            out.append('](%s%s)' % (rel, frag))
            last, changed, fixed = m.end(), True, fixed + 1
        if changed:
            out.append(text[last:])
            touched.append(f)
            if apply_changes:
                open(f, 'w', encoding='utf-8').write(''.join(out))

    print('%s: %d link(s) in %d file(s)' %
          ('repaired' if apply_changes else 'WOULD repair (dry run; pass --apply)',
           fixed, len(touched)))
    print('  target does not exist anywhere : %d' % unresolvable)
    print('  basename ambiguous (2+ homes)  : %d' % ambiguous)
    print('  ⛔ inside a GENERATED region    : %d  <- fix these in the GENERATOR, not here'
          % skipped_generated)
    for s in in_generated:
        print('       %s' % s)
    return 0


if __name__ == '__main__':
    sys.exit(main())
