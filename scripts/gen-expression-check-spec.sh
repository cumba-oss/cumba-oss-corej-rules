#!/usr/bin/env bash
#
# Regenerates the `expression-syntax-rosters` region of
# documentation/CORE-EXPRESSION-CHECK-SPECIFICATION.md and the machine-readable
# documentation/expression-syntax-inventory.tsv — the derived half of the
# expression-check specification (plans/done/PLAN-expression-docs-restructure.md §4).
# One-shot tool; NOT part of the default build.
#
# Usage (from anywhere):
#   lib/corej-cdisc-rules/scripts/gen-expression-check-spec.sh
#
# ExpressionCheckSpecDriftTest fails the build whenever the committed region or
# TSV stops matching this generator's output, and separately asserts every
# roster token carries a documented, marker-bearing entry in the hand-written
# chapters — so neither half can quietly go stale.
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "${MODULE_DIR}"

# Same stance as gen-rule-doc-census.sh: the census IS a transcript of the code
# registries, so it must run against this tree's classes, never a stale jar.
# corpus-gen-env.sh builds the reactor, resolves the classpath from this tree's
# target/classes and refuses to start if any net.cumba.corej dependency still
# resolves from ~/.m2. Do not reintroduce `exec:java` (CorpusGenScriptGuardTest).
# shellcheck source=lib/corpus-gen-env.sh
source "${MODULE_DIR}/scripts/lib/corpus-gen-env.sh"

cgen_resolve_classpaths

# Fail fast BEFORE the generator runs — an abort here leaves the document untouched.
cgen_assert_fresh_engine "${CGEN_CP_TEST}" "test-scope classpath"

# Test-scope: the census is a documentation tool, not shipped code.
cgen_run_main_test net.cumba.corej.core.doc.ExpressionCheckSpecCensus \
    "${MODULE_DIR}/documentation/CORE-EXPRESSION-CHECK-SPECIFICATION.md"
