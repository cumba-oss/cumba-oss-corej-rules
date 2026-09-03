#!/usr/bin/env bash
#
# Regenerates documentation/findings-snapshot.tsv — one row per finding OCCURRENCE
# (and one status row per rule x dataset) of a full study-level run over the
# main-tree testdata/study at SDTMIG 3.4, every family. One-shot tool; NOT part
# of the default build and NOT a step of the corpus regeneration sequence: it
# censuses BEHAVIOUR, not the corpus. Capture it before a finding-moving change
# and DIFF it after (FindingsSnapshotDriftTest, -Dcorej.findingsSnapshot=true).
#
# Usage (from anywhere, MAIN tree only — testdata/ is untracked):
#   CDISC_PICKLE_CACHE_DIR=/data/cdisc.metadata.library-cache-pkl \
#   CDISC_API_CACHE=/data/cdisc.metadata.library-cache \
#   lib/corej-cdisc-rules/scripts/gen-findings-snapshot.sh
#
# WHY IT EXISTS (PLAN-leaf-scope-domain-inference.md §7a gap 1, owner ruling 13).
# The .cdt suites assert verdicts and violation COUNTS, the rulespec specs compare
# violation SETS; neither sees finding multiplicity or row numbers. A routing
# change that keeps every verdict but moves a finding from row 0 to the first
# firing row, or splits one finding into one per variable, passes both. This
# artifact is the only instrument that catches it.
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "${MODULE_DIR}"

if [[ -z "${CDISC_PICKLE_CACHE_DIR:-}" || -z "${CDISC_API_CACHE:-}" ]]; then
    echo "gen-findings-snapshot.sh: CDISC_PICKLE_CACHE_DIR and CDISC_API_CACHE must both be set —" >&2
    echo "  without them every library-dependent rule SKIPs and the snapshot is not comparable." >&2
    exit 2
fi

# THE GENERATOR MUST RUN AGAINST THIS TREE'S ENGINE, NOT AN INSTALLED JAR — see
# scripts/lib/corpus-gen-env.sh (it builds the reactor, resolves a tree-local
# classpath and refuses to start if any net.cumba.corej dependency resolves from
# ~/.m2). Do not reintroduce an `exec:java` invocation here: CorpusGenScriptGuardTest
# fails the build if any script under scripts/ carries that shape.
# shellcheck source=lib/corpus-gen-env.sh
source "${MODULE_DIR}/scripts/lib/corpus-gen-env.sh"

cgen_resolve_classpaths

cgen_assert_fresh_engine "${CGEN_CP_TEST}" "test-scope classpath"

# FindingsSnapshot is test-scope (a documentation tool, not shipped code), like
# RoutingCensus; it needs the local data-table manager and the Dataset-JSON
# provider, which corej-cdisc-rules declares as test dependencies for it.
cgen_run_main_test net.cumba.corej.core.doc.FindingsSnapshot \
    "${MODULE_DIR}/documentation/findings-snapshot.tsv"
