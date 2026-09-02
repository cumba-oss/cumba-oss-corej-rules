#!/usr/bin/env bash
#
# Regenerates documentation/rule-inventory/rule-equivalence.yaml — the rule
# equivalence registry (classes, relations, unlinked, anomalies) plus the
# verdict_overlay section that reports how the D36 side-car was applied.
# One-shot tool; NOT part of the default build.
#
# Usage (from anywhere):
#   lib/corej-cdisc-rules/scripts/regen-rule-equivalence.sh
#
# RuleEquivalenceDriftTest fails the build whenever the committed registry stops
# matching this generator's output, so a stale registry cannot ship quietly.
#
# ⚠ This does NOT regenerate the .tsv files or the detail/ tree — those come from
#   documentation/rule-inventory/generate-inventory.py, which reads rules-src/
#   only and is unaffected by the engine. Nor does it rebuild
#   EQUIVALENCE-OVERVIEW.md; that is documentation/rule-inventory/build-overview.py
#   and is a SEPARATE step. Omitting it has shipped a stale doc before.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

# ---------------------------------------------------------------------------
# ⚠⚠ THE GENERATOR MUST RUN AGAINST THIS TREE'S ENGINE, NOT AN INSTALLED JAR.
#
# This script exists because the recipe it replaces —
#
#     cd lib/corej-cdisc-rules && mvn -P main exec:java \
#         -Dexec.classpathScope=test -Dexec.mainClass=…RuleEquivalenceInventory
#
# — resolves every net.cumba.corej dependency from ~/.m2. On 2026-08-04 that
# shape ran a STALE engine against the corpus, threw NoSuchMethodError after 54
# files had already been rewritten, and left the corpus partially corrupted.
# `-am` is NOT the fix either: prepending only core/target/classes leaves seven
# modules stale.
#
# scripts/lib/corpus-gen-env.sh builds the reactor, resolves the classpath from
# this tree's target/classes, and REFUSES TO START if any net.cumba.corej
# dependency still resolves from ~/.m2.
#
# Do not reintroduce an `exec:java` invocation here: CorpusGenScriptGuardTest
# fails the build if any script under scripts/ carries that shape.
# ---------------------------------------------------------------------------
# shellcheck source=lib/corpus-gen-env.sh
source "${MODULE_DIR}/scripts/lib/corpus-gen-env.sh"

cgen_resolve_classpaths

# Fail fast BEFORE the generator runs — an abort here leaves the registry untouched.
cgen_assert_fresh_engine "${CGEN_CP_TEST}" "test-scope classpath"

# ⚠ RuleEquivalenceInventory resolves its default input and output paths RELATIVE
#   TO THE WORKING DIRECTORY. Run from the repo root it silently reads zero rules
#   and writes an empty registry into a stray documentation/ tree. It prints
#   `rules=0` when wrong and `rules=3707 …` when right — but by then it has
#   already written. Pinning cwd here removes the trap rather than documenting it.
cd "${MODULE_DIR}"

# RuleEquivalenceInventory is test-scope (a documentation tool, not shipped code).
cgen_run_main_test net.cumba.cdisc.core.expr.convert.RuleEquivalenceInventory

cat <<'EOF'

⚑ Registry regenerated. Two things this script deliberately did NOT do:
   1. EQUIVALENCE-OVERVIEW.md  -> documentation/rule-inventory/build-overview.py
   2. the .tsv files + detail/ -> documentation/rule-inventory/generate-inventory.py

⚠ Check the `verdict_overlay` line above: a non-zero `unmatched` means a class's
  membership moved and a hand-authored verdict has silently reverted to pending.
EOF
