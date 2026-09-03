#!/usr/bin/env bash
#
# Backfills the per-rule provenance onto every generated check file under
# rules-src/checks/ in TWO stages (plan plans/done/PLAN-source-block-migration.md):
#
#   Stage 1 - OriginalTextBackfill
#     (net.cumba.corej.core.expr.convert.OriginalTextBackfill)
#     Splices a single root-level `Source:` block (immediately before `Standards:`)
#     holding the verbatim OWN-SHEET columns of the CDISC / FDA / PMDA conformance-
#     rule spreadsheet row the rule was generated from, so a rule can be reviewed
#     without opening the Excel workbook. The same pass removes the three legacy
#     provenance keys (Original_Description / Original_Condition at root,
#     Outcome.Original_Message at indent 2) from EVERY matched file, including
#     draft / unlisted rules with no source row. The Source block is verbatim text
#     only - it carries no cross-family linkage keys.
#
#   Stage 2 - StandardsLinkageBackfill
#     (net.cumba.corej.core.expr.convert.StandardsLinkageBackfill)
#     Injects / overwrites the CORE-style cross-family `References[]` linkage inside
#     each check's `Standards[]`: FDA Publisher=CDISC -> a whole `Organization:
#     "CDISC"` block of SDTM/SDTMIG Conformance Rules (CG) references; FDA
#     Publisher=FDA -> an `FDA Business Rules` reference added into each identity-FDA
#     standard entry; PMDA-SD/CT -> a one-hop `Organization: "FDA"` block of FDA
#     Validator Rules references. Runs a conformance oracle against the existing
#     CDISC blocks first and logs unresolvable / dangling references to
#     documentation/rule-review-results/CG-linkage-gaps.md.
#
#   Stage 3 - RuleCanonicalizer
#     (net.cumba.corej.core.expr.convert.RuleCanonicalizer)
#     Normalises the final Source+linkage result: a defined top-level key order
#     (Source second-to-last, before Standards), uniform double-quoted / folded YAML
#     style and a single trailing newline (comments are stripped by the reserialize -
#     the rationale lives in documentation/). Runs LAST. A hard round-trip data-safety
#     check guarantees it is semantically inert. With --dry-run it is report-only
#     (lists files not yet canonical).
#
# Stages 1-2 are textual splices; stage 3 is a full reserialize. All are idempotent,
# so re-running is safe.
#
# WHICH STAGES RUN: all three by default; --stage narrows it. Note that
# -b/--binding filters STAGE 1 ONLY - StandardsLinkageBackfill and
# RuleCanonicalizer never read backfill.binding, so `-b CDISC-SEND` on its own
# still runs stages 2 and 3 corpus-wide. A genuinely one-binding run is
#   backfill-rules.sh --stage 1 -b CDISC-SEND
# Stage 1 already emits the canonical block position and style, so a stage-1-only
# run leaves the corpus canonical; confirm with `--stage 3 --dry-run` (reports
# zero files not yet canonical).
#
# This edits rules-src/checks/ ONLY (the hand-edited source of truth). To
# propagate the new fields into the generated rules/ corpus,
# run scripts/regen-rules-corpus.sh afterwards.
#
# The source workbooks are read from /data/cdisc-docs (override with --docs-dir).
#
# Usage (from anywhere):
#   lib/corej-cdisc-rules/scripts/backfill-rules.sh [options]
#
# Options:
#   -n, --dry-run          compute and report per-binding counts without writing
#   -b, --binding NAME     limit to one registry binding (FDA, CDISC-CG,
#                          CDISC-SEND, CDISC-AD, PMDA-SDTM, PMDA-AD)
#                          NOTE: filters stage 1 only - see above
#   -s, --stage LIST       run only these stages, comma-separated (1, 2, 3;
#                          default 1,2,3). They always run in numeric order.
#       --docs-dir DIR     source-workbook directory (default /data/cdisc-docs)
#       --checks-root DIR  checks root (default rules-src/checks, module-relative)
#       --gap-log PATH     CG-linkage gap log (default
#                          documentation/rule-review-results/CG-linkage-gaps.md)
#   -h, --help             show this help and exit
#
# Runs from the module root so rules-src/checks/ and the gap-log path resolve.
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "${MODULE_DIR}/../.." && pwd)"

usage() { sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'; }

DRY_RUN=false
STAGES="1,2,3"
PROPS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
    -n | --dry-run)
        DRY_RUN=true
        PROPS+=("-Dbackfill.dryRun=true")
        shift
        ;;
    -b | --binding)
        PROPS+=("-Dbackfill.binding=$2")
        shift 2
        ;;
    -s | --stage | --stages)
        # Accept spaces as separators too; run_stage matches on a comma-delimited list, so a
        # space-separated value would otherwise validate and then select nothing.
        STAGES="${2// /,}"
        shift 2
        ;;
    --docs-dir)
        PROPS+=("-Dcdisc.docs.dir=$2")
        shift 2
        ;;
    --checks-root)
        PROPS+=("-Dbackfill.checksRoot=$2")
        shift 2
        ;;
    --gap-log)
        PROPS+=("-Dbackfill.gapLog=$2")
        shift 2
        ;;
    -h | --help)
        usage
        exit 0
        ;;
    *)
        echo "backfill-rules.sh: unknown argument '$1'" >&2
        usage >&2
        exit 2
        ;;
    esac
done

if [[ -z "${STAGES//[[:space:],]/}" ]]; then
    echo "backfill-rules.sh: --stage selected no stage; nothing would run" >&2
    exit 2
fi
for stage in ${STAGES//,/ }; do
    case "${stage}" in
    1 | 2 | 3) ;;
    *)
        echo "backfill-rules.sh: --stage takes 1, 2 and/or 3 (got '${stage}')" >&2
        exit 2
        ;;
    esac
done

# Runs stage $1 (main class $2, label $3) when --stage selected it; otherwise says so.
run_stage() {
    case ",${STAGES}," in
    *",$1,"*)
        cgen_run_main_test "$2"
        RAN_A_STAGE=yes
        ;;
    *)
        echo "[backfill-rules] stage $1 ($3) SKIPPED - not in --stage ${STAGES}"
        ;;
    esac
}

cd "${MODULE_DIR}"

# THE STAGES MUST RUN AGAINST THIS TREE'S ENGINE, NOT AN INSTALLED JAR.
#
# These stages edit rules-src/checks/ — the hand-edited source of truth — and they write as they
# go, so a stale engine can rewrite part of it and then die. The corpus regen carried the same
# defect and it was measured there on 2026-08-04: NoSuchMethodError Operation.getMissingValues()
# after 54 files had already been rewritten (+32,463 / -11,380 lines).
#
# scripts/lib/corpus-gen-env.sh builds the reactor and resolves the classpath so all EIGHT
# net.cumba.corej dependencies come from this tree's target/classes, then refuses to start a stage
# if any of them still resolves from ~/.m2. Its header records why the two obvious repairs do not
# work — in particular that adding `-am` to a bare `exec:java` fires the goal on corej-parent, the
# first reactor module. Do not reintroduce an `exec:java` stage here: CorpusGenScriptGuardTest
# fails the build if any script under scripts/ carries that shape.
# shellcheck source=lib/corpus-gen-env.sh
source "${MODULE_DIR}/scripts/lib/corpus-gen-env.sh"

# The CLI options become JVM system properties on each stage.
CGEN_JVM_OPTS=(${PROPS[@]+"${PROPS[@]}"})

cgen_resolve_classpaths

# Fail fast BEFORE the first stage runs — an abort here leaves rules-src/checks/ untouched.
cgen_assert_fresh_engine "${CGEN_CP_TEST}" "test-scope classpath"

# All three stages are test-scope: OriginalTextBackfill and its siblings live in src/test because
# jackson-dataformat-yaml and Apache POI are test-scoped (they must not leak into the engine jar).

# Stage 1 - the verbatim own-sheet Source: block.
run_stage 1 net.cumba.corej.core.expr.convert.OriginalTextBackfill OriginalTextBackfill

# Stage 2 - the cross-family Standards[].References[] linkage (runs the conformance
# oracle first; --dry-run maps to -Dbackfill.dryRun=true, honoured as validate/no-write).
run_stage 2 net.cumba.corej.core.expr.convert.StandardsLinkageBackfill StandardsLinkageBackfill

# Stage 3 - canonicalise the final Source+linkage result: defined top-level key order
# + uniform double-quoted/folded style + single trailing newline (comments stripped).
# Runs LAST so it normalises the completed corpus; --dry-run maps to -Dbackfill.dryRun=true,
# honoured as report-only (lists files that are not yet canonical, no write).
run_stage 3 net.cumba.corej.core.expr.convert.RuleCanonicalizer RuleCanonicalizer

if [[ "${DRY_RUN}" == false && -n "${RAN_A_STAGE:-}" ]]; then
    echo
    echo "[backfill-rules] rules-src/checks/ updated. Next: run"
    echo "[backfill-rules]   lib/corej-cdisc-rules/scripts/regen-rules-corpus.sh"
    echo "[backfill-rules] to propagate the Source block + linkage into rules/."
fi
