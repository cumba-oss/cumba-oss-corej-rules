#!/usr/bin/env python3
"""One-shot: strip redundant Scope.Domains.Include: ["ALL"] across all rule
families under rules-src/checks/ (CORE, FDA, PMDA, CDISC).

Domains.Include: ["ALL"] admits every domain, so it is redundant whenever the
rule's dataset set is ALREADY limited by either:
  1. a limiting Classes entry (Include OR Exclude), or
  2. a sibling Domains.Exclude list.
In those cases strip it: drop the whole Domains: key, unless a Domains.Exclude
is present, in which case keep just the Exclude.

Never touches a rule whose only scope is Domains.Include: ["ALL"] (there the ALL
IS the scope), nor a rule with include_split_datasets set (the ALL is NOT
redundant then -- removing it flips "all datasets" -> "splits only").

Minimal textual edit -- preserves all other bytes. Run from the module root.
See plans/PLAN-strip-domains-include-all.md.
"""
import sys, glob, yaml

INCLUDE_CLASS_EXCLUDE = True   # a Classes.Exclude limit also makes Include ALL redundant
INCLUDE_DOMAIN_EXCLUDE = True  # a sibling Domains.Exclude also makes Include ALL redundant


def has_class_limit(cls):
    if cls.get("Include"):
        return True
    return INCLUDE_CLASS_EXCLUDE and bool(cls.get("Exclude"))


def split_flag(dom):
    # accept either spelling seen in source
    v = dom.get("include_split_datasets")
    return dom.get("Include_Split_Datasets") if v is None else v


def is_target(path):
    sc = (yaml.safe_load(open(path)) or {}).get("Scope") or {}
    cls, dom = sc.get("Classes") or {}, sc.get("Domains") or {}
    inc = dom.get("Include")
    if not inc or not any(str(x).upper() == "ALL" for x in inc):
        return False
    if [str(x).upper() for x in inc] != ["ALL"]:
        sys.exit(f"UNEXPECTED Include shape in {path}: {inc!r}")
    if split_flag(dom) is not None:          # Exception: split flag set -> NOT redundant
        return False
    limited = has_class_limit(cls) or (INCLUDE_DOMAIN_EXCLUDE and bool(dom.get("Exclude")))
    return limited


def empty_scope_rules():
    """Goal rule 3: a rule with no Classes limit and no Domains entry must carry an
    explicit Domains.Include: ["ALL"] so its scope is never empty."""
    out = []
    for p in glob.glob("rules-src/checks/*/*.yaml"):
        sc = (yaml.safe_load(open(p)) or {}).get("Scope") or {}
        cls, dom = sc.get("Classes") or {}, sc.get("Domains") or {}
        has_class = bool(cls.get("Include")) or bool(cls.get("Exclude"))
        has_dom = bool(dom.get("Include")) or bool(dom.get("Exclude"))
        if not has_class and not has_dom:
            out.append(p)
    return out


def add_all_marker(path):
    """Insert `Scope: {Domains: {Include: ["ALL"]}}` into a rule that has no
    Classes/Domains scope. Preserves alphabetical top-level key order (Scope sorts
    before Sensitivity/Standards). Merges into an existing empty/partial Scope block
    when one is present; otherwise inserts a fresh top-level Scope key."""
    lines = open(path).read().splitlines(keepends=True)
    block = ["Scope:\n", "  Domains:\n", "    Include:\n", '    - "ALL"\n']
    # If a top-level `Scope:` line already exists (e.g. `Scope:` with only Use_Case),
    # we don't expect that here (empty means no Classes/Domains); handle the common
    # case: no Scope key at all -> insert before the first top-level key that sorts
    # after "Scope" (Sensitivity, Standards, ...).
    if any(ln.startswith("Scope:") for ln in lines):
        sys.exit(f"{path}: unexpected existing Scope: block for empty-scope add; handle manually")
    # insert before the first column-0 key that sorts alphabetically after "Scope"
    insert_at = None
    for i, ln in enumerate(lines):
        if ln[:1].isalpha():
            key = ln.split(":", 1)[0]
            if key > "Scope":
                insert_at = i
                break
    if insert_at is None:
        sys.exit(f"{path}: could not find insertion point for Scope")
    open(path, "w").write("".join(lines[:insert_at] + block + lines[insert_at:]))


def _under_domains(lines, idx):
    """True if the nearest preceding 2-space key is 'Domains:'."""
    for k in range(idx - 1, -1, -1):
        s = lines[k]
        if s.startswith("  ") and not s.startswith("   ") and s.rstrip().endswith(":"):
            return s.strip() == "Domains:"
    return False


_ALL_ITEM = {'- "ALL"', "- 'ALL'", "- ALL"}


def strip(path):
    lines = open(path).read().splitlines(keepends=True)
    out, i, removed = [], 0, False
    while i < len(lines):
        ln = lines[i]
        if ln.rstrip("\n") == "    Include:" and _under_domains(lines, i):
            j = i + 1
            item = lines[j].rstrip("\n")
            assert item.startswith("    ") and item.strip() in _ALL_ITEM, \
                f"{path}: expected sole ALL item at line {j+1}, got {lines[j]!r}"
            i = j + 1
            removed = True
            # drop a now-empty "  Domains:" key (no 4-space child follows)
            if out and out[-1].rstrip("\n") == "  Domains:" and \
               (i >= len(lines) or not lines[i].startswith("    ")):
                out.pop()
            continue
        out.append(ln)
        i += 1
    assert removed, f"{path}: nothing removed"
    open(path, "w").write("".join(out))


def main():
    # Goal rule 3: give any empty-scope rule an explicit Domains.Include: ["ALL"].
    added = empty_scope_rules()
    for p in added:
        add_all_marker(p)
    print(f"added ALL marker: {len(added)}  {[p.split('/')[-1] for p in added]}")
    # Goal rules 1-2: strip redundant Domains.Include: ["ALL"].
    targets = sorted(p for p in glob.glob("rules-src/checks/*/*.yaml") if is_target(p))
    print(f"strip targets: {len(targets)}")
    for p in targets:
        strip(p)
    print("done")


if __name__ == "__main__":
    main()
