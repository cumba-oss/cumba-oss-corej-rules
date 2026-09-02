#!/usr/bin/env bash
#
# Applies documentation/rule-review-results/sheet-proposals.yaml to rules-src/checks/, writing each
# rule's root-level `Source_Proposed:` block from the side-car record that proposes it
# (net.cumba.cdisc.core.expr.convert.SourceProposedBackfill; plan
# plans/PLAN-source-proposed-block.md phase 3b).
#
# THE SIDE-CAR IS THE SOURCE OF TRUTH. `Source_Proposed` is hand-authored THERE and generated HERE;
# where the two disagree the side-car is right and the corpus is regenerated, never the reverse.
# SourceProposedGuardTest asserts the two agree, key for key.
#
# ⭐ RUN ORDER IS LOAD-BEARING:
#
#     scripts/apply-sheet-proposals.sh        # splice the Source_Proposed blocks
#     scripts/backfill-rules.sh               # stages 1-3; stage 3 canonicalises the result
#
# NEVER the reverse. Stage 3 (RuleCanonicalizer) reserialises every file from the parsed node, so
# style, key order and fold points are normalised AFTER this splice - which is why this tool is a
# plain line splicer and not a fancy writer. Reversed, the canonical pass would run before the block
# it is meant to normalise existed.
#
# ⚑ This tool and OriginalTextBackfill are independent: `stripSource` matches "Source:" at indent 0,
# which "Source_Proposed:" does not start with, and this tool matches "Source_Proposed:" exactly.
# Neither can eat the other's block (pinned by OriginalTextBackfillTest and SourceProposedBackfillTest).
#
# This edits rules-src/checks/ ONLY. To propagate into the generated rules/ corpus, run
# scripts/regen-rules-corpus.sh afterwards.
#
# Usage (from anywhere):
#   lib/corej-cdisc-rules/scripts/apply-sheet-proposals.sh [options]
#
# Options:
#   -n, --dry-run          report what would change without writing
#       --check            alias for --dry-run
#       --sidecar PATH     side-car (default documentation/rule-review-results/sheet-proposals.yaml)
#       --checks-root DIR  checks root (default rules-src/checks, module-relative)
#   -h, --help             show this help and exit
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() { sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'; }

DRY_RUN=false
PROPS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
    -n | --dry-run | --check)
        DRY_RUN=true
        PROPS+=("-Dproposals.dryRun=true")
        shift
        ;;
    --sidecar)
        PROPS+=("-Dproposals.sidecar=$2")
        shift 2
        ;;
    --checks-root)
        PROPS+=("-Dproposals.checksRoot=$2")
        shift 2
        ;;
    -h | --help)
        usage
        exit 0
        ;;
    *)
        echo "apply-sheet-proposals.sh: unknown argument '$1'" >&2
        usage >&2
        exit 2
        ;;
    esac
done

cd "${MODULE_DIR}"

# THE STAGE MUST RUN AGAINST THIS TREE'S ENGINE, NOT AN INSTALLED JAR - see the long note in
# scripts/backfill-rules.sh. corpus-gen-env.sh builds the reactor, resolves the classpath and
# refuses to start if any net.cumba.corej dependency still resolves from ~/.m2.
# Do NOT reintroduce an `exec:java` stage here: CorpusGenScriptGuardTest fails the build on it.
# shellcheck source=lib/corpus-gen-env.sh
source "${MODULE_DIR}/scripts/lib/corpus-gen-env.sh"

CGEN_JVM_OPTS=(${PROPS[@]+"${PROPS[@]}"})

cgen_resolve_classpaths
cgen_assert_fresh_engine "${CGEN_CP_TEST}" "test-scope classpath"

cgen_run_main_test net.cumba.cdisc.core.expr.convert.SourceProposedBackfill

if [[ "${DRY_RUN}" == false ]]; then
    echo
    echo "[proposals] rules-src/checks/ updated. Next, IN THIS ORDER:"
    echo "[proposals]   lib/corej-cdisc-rules/scripts/backfill-rules.sh"
    echo "[proposals]   lib/corej-cdisc-rules/scripts/regen-rules-corpus.sh"
fi
