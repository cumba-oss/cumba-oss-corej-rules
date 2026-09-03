#!/usr/bin/env bash
#
# ⛔⛔ THIS FILE STAYS. PLAN-rules-corpus-build-integration §11 proposed retiring it, on the
# premise that "its classpath resolution and freshness assertion exist only because generation
# ran outside Maven". That premise is FALSE, and the count is the whole argument: NINE scripts
# source this file, and only TWO of them are the corpus generators that moved into the build —
#
#   apply-sheet-proposals.sh   gen-expression-check-spec.sh   gen-routing-census.sh
#   gen-rule-doc-census.sh     regen-rule-equivalence.sh      gen-findings-snapshot.sh
#   backfill-rules.sh
#
# — all of which stay manual by that same plan's Decisions 3, and all of which still run a
# generator main against a hand-resolved classpath. Deleting this would break every one of them.
#
# CorpusGenScriptGuardTest therefore needs no change either: its `found.size() >= 3` floor is
# still comfortably met.
#
#
# Shared build/run environment for every generator script under
# lib/corej-cdisc-rules/scripts/. SOURCE this file; do not execute it.
#
#   source "$(dirname "${BASH_SOURCE[0]}")/lib/corpus-gen-env.sh"
#   cgen_resolve_classpaths
#   cgen_run_main_test net.cumba.corej.core.expr.convert.RuleCorpusGenerator
#
# ---------------------------------------------------------------------------
# WHY THIS FILE EXISTS
# ---------------------------------------------------------------------------
#
# The generators WRITE AS THEY GO. Run against a stale engine they rewrite part
# of the corpus and then die — or, worse, do not die at all.
#
# Measured 2026-08-04, regenerating after Fix #145 landed: NoSuchMethodError
# net.cumba.corej.core.model.Operation.getMissingValues(), thrown only after 54
# files had already been rewritten (+32,463 / -11,380 lines). That crash was
# LUCK: the stale jar happened to lack a method. A stale engine that merely
# produces DIFFERENT output throws nothing, writes the whole corpus, and the
# diff reads as ordinary regen churn.
#
# The cause: the stages invoked `exec:java` with `-pl lib/corej-cdisc-rules` and
# no `-am`, so corej-cdisc-core was not in the reactor and Maven resolved it
# from ~/.m2 — the INSTALLED jar, of arbitrary age — rather than the classes
# just compiled in this tree.
#
# ---------------------------------------------------------------------------
# TWO REPAIRS THAT DO NOT WORK — both measured, do not retry them
# ---------------------------------------------------------------------------
#
# (1) Adding `-am` to the exec:java invocation. A direct plugin-goal invocation
#     runs the goal on EVERY module in the reactor. Measured:
#
#       mvn -P main -pl lib/corej-cdisc-rules -am exec:java -Dexec.mainClass=X
#       => Failed to execute goal org.codehaus.mojo:exec-maven-plugin:3.6.3:java
#          (default-cli) on project corej-parent
#
#     It fired exec:java on corej-parent, the FIRST reactor module, before ever
#     reaching corej-cdisc-rules.
#
# (2) Prepending lib/corej-cdisc-core/target/classes to the classpath. That
#     repairs the engine and leaves SEVEN further reactor artifacts still
#     resolving from ~/.m2: corej-cdisc-core's test-jar (stage 0 runs in test
#     scope), corej-datatable, corej-datatable-impl, corej-datatable-help,
#     corej-cdisc-library, corej-cdisc-define and corej-web-api. All EIGHT must
#     come from this tree, not just the engine.
#
# ---------------------------------------------------------------------------
# WHAT THIS FILE DOES INSTEAD
# ---------------------------------------------------------------------------
#
# cgen_resolve_classpaths combines the `test-compile` LIFECYCLE PHASE with the
# `dependency:build-classpath` GOAL in a single `-am` reactor invocation.
# Because the phase actually runs, Maven sets each reactor dependency's artifact
# file to its freshly compiled output, so build-classpath emits
# `<module>/target/classes` directories instead of repository jars — for all
# eight modules at once. The stages then run under a plain `java -cp` with that
# classpath.
#
# ORDER IS LOAD-BEARING: the phase and the goal must stay in the SAME mvn
# invocation. Split them and the goal-only run has no built reactor to resolve
# against, and every net.cumba.corej dependency falls back to ~/.m2.
#
# Nothing is installed, so a ~/.m2 shared with parallel lanes/worktrees is left
# untouched.
#
# cgen_assert_fresh_engine then refuses to let a stage start unless that
# classpath really is tree-local. It runs BEFORE the first generator, so an
# abort writes zero files.
#
# scripts/tests/test-corpus-gen-env.sh pins the guard; CorpusGenScriptGuardTest
# runs that suite and additionally asserts that no script under scripts/ has
# re-introduced the defective invocation shape beside a guarded one.

# Idempotent source.
if [[ -n "${CGEN_ENV_SOURCED:-}" ]]; then
    return 0
fi
CGEN_ENV_SOURCED=1

CGEN_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# scripts/lib -> scripts -> lib/corej-cdisc-rules
CGEN_MODULE_DIR="$(cd "${CGEN_LIB_DIR}/../.." && pwd)"
CGEN_REPO_ROOT="$(cd "${CGEN_MODULE_DIR}/../.." && pwd)"

# The engine. Its presence and its provenance are what the guard checks.
CGEN_CORE_CLASSES="${CGEN_REPO_ROOT}/lib/corej-cdisc-core/target/classes"
# Any always-present engine class works; this is the one whose signature change
# produced the 2026-08-04 NoSuchMethodError.
CGEN_ENGINE_MARKER="net/cumba/corej/core/model/Operation.class"

# The local-repository path segment shared by every reactor artifact. A jar
# under this segment on the classpath means a reactor module was resolved from
# ~/.m2 rather than from this tree.
CGEN_REACTOR_GROUP_PATH="/net/cumba/corej/"

# Formatting is irrelevant to generation, so Spotless is disabled: these tools
# must never rewrite (or fail on) sources they do not own — important when other
# work is in flight in the shared tree. Uses the parent pom's own phase-binding
# switch plus the plugin-level skip flags as belt-and-suspenders.
CGEN_SPOTLESS_OFF=(
    -Dspotless.apply.phase=none
    -Dspotless.check.phase=none
    -Dspotless.apply.skip=true
    -Dspotless.check.skip=true
)

# Extra JVM options (system properties) for the stages. Callers may append.
CGEN_JVM_OPTS=()

CGEN_JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/}java"

# Populated by cgen_resolve_classpaths.
CGEN_CP_RUNTIME=""
CGEN_CP_TEST=""

# ---------------------------------------------------------------------------
# Classpath resolution
# ---------------------------------------------------------------------------

# Builds the reactor through test-compile and captures the module's dependency
# classpath in both scopes. Sets CGEN_CP_RUNTIME and CGEN_CP_TEST.
#
# `${project.build.directory}` is evaluated per module, so the `-am` sweep of
# build-classpath writes each module its own file and nothing clobbers.
cgen_resolve_classpaths() {
    local out_runtime="${CGEN_MODULE_DIR}/target/corpus-gen-cp-runtime.txt"
    local out_test="${CGEN_MODULE_DIR}/target/corpus-gen-cp-test.txt"

    rm -f "${out_runtime}" "${out_test}"

    # test-compile (not compile): the split-storage and backfill tools live in
    # src/test because jackson-dataformat-yaml and Apache POI are test-scoped
    # (they must not leak into the engine jar).
    mvn -P main -f "${CGEN_REPO_ROOT}/pom.xml" -pl lib/corej-cdisc-rules -am \
        "${CGEN_SPOTLESS_OFF[@]}" -DskipTests \
        test-compile dependency:build-classpath \
        -Dmdep.outputFile='${project.build.directory}/corpus-gen-cp-runtime.txt' \
        -DincludeScope=runtime

    mvn -P main -f "${CGEN_REPO_ROOT}/pom.xml" -pl lib/corej-cdisc-rules -am \
        "${CGEN_SPOTLESS_OFF[@]}" -DskipTests \
        test-compile dependency:build-classpath \
        -Dmdep.outputFile='${project.build.directory}/corpus-gen-cp-test.txt' \
        -DincludeScope=test

    if [[ ! -s "${out_runtime}" || ! -s "${out_test}" ]]; then
        cgen__guard_fail \
            "the dependency classpath could not be resolved." \
            "  expected: ${out_runtime}" \
            "            ${out_test}"
        return 1
    fi

    # build-classpath emits dependencies only — the module's own output goes in
    # front, exactly as exec:java would have placed it.
    CGEN_CP_RUNTIME="${CGEN_MODULE_DIR}/target/classes:$(cat "${out_runtime}")"
    CGEN_CP_TEST="${CGEN_MODULE_DIR}/target/classes:${CGEN_MODULE_DIR}/target/test-classes:$(cat "${out_test}")"
}

# ---------------------------------------------------------------------------
# The guard
# ---------------------------------------------------------------------------

# cgen_assert_fresh_engine <classpath> [label]
#
# Returns 0 when the classpath is tree-local, non-zero (after printing the abort
# banner) otherwise. Call this before the first generator runs.
cgen_assert_fresh_engine() {
    local cp="${1:-}"
    local label="${2:-classpath}"

    if [[ -z "${cp}" ]]; then
        cgen__guard_fail "the ${label} is empty — cgen_resolve_classpaths did not run."
        return 1
    fi

    local marker="${CGEN_CORE_CLASSES}/${CGEN_ENGINE_MARKER}"
    if [[ ! -f "${marker}" ]]; then
        cgen__guard_fail \
            "the engine is not compiled in this tree, so the stages could only run" \
            "against an installed jar of unknown age." \
            "  missing: ${marker}"
        return 1
    fi

    local -a entries=()
    IFS=':' read -r -a entries <<<"${cp}"

    local saw_core_classes=false
    local -a offenders=()
    local entry
    for entry in "${entries[@]}"; do
        [[ -z "${entry}" ]] && continue
        if [[ "${entry}" == "${CGEN_CORE_CLASSES}" ]]; then
            saw_core_classes=true
        fi
        if [[ "${entry}" == *.jar && "${entry}" == *"${CGEN_REACTOR_GROUP_PATH}"* ]]; then
            offenders+=("${entry}")
        fi
    done

    if ((${#offenders[@]} > 0)); then
        local -a detail=()
        detail+=("a reactor module resolved to an INSTALLED jar instead of this tree's classes.")
        detail+=("${#offenders[@]} artifact(s) of unknown age would be on the ${label}:")
        for entry in "${offenders[@]}"; do
            detail+=("$(cgen__describe_offender "${entry}")")
        done
        cgen__guard_fail "${detail[@]}"
        return 1
    fi

    if [[ "${saw_core_classes}" != true ]]; then
        cgen__guard_fail \
            "the freshly compiled engine is not on the ${label} at all." \
            "  expected entry: ${CGEN_CORE_CLASSES}"
        return 1
    fi

    return 0
}

# Describes one offending jar: where it is, when it was built, and whether it is
# older than this tree's engine classes. The verdict is informational — ANY
# net.cumba.corej jar aborts. A jar that is merely newer is not safer: it was
# built from some other tree.
cgen__describe_offender() {
    local jar="$1"
    local when verdict
    when="$(date -r "${jar}" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo 'unknown')"

    if find "${CGEN_CORE_CLASSES}" -name '*.class' -newer "${jar}" -print -quit 2>/dev/null | grep -q .; then
        verdict="OLDER than ${CGEN_CORE_CLASSES} — this is the stale-engine case"
    else
        verdict="not older than ${CGEN_CORE_CLASSES}, but not built by this reactor run"
    fi

    printf '%s\n     mtime %s\n     %s' "${jar}" "${when}" "${verdict}"
}

cgen__guard_fail() {
    local line
    {
        echo
        echo "=============================================================================="
        echo " ABORTED - stale-engine guard (scripts/lib/corpus-gen-env.sh)"
        echo "=============================================================================="
        for line in "$@"; do
            echo " ${line}"
        done
        echo
        echo " NOTHING HAS BEEN WRITTEN. This check runs before the first generator stage,"
        echo " and the generators write as they go - that is the whole point of stopping"
        echo " here. On 2026-08-04 a stale engine rewrote 54 files (+32,463 / -11,380)"
        echo " before throwing NoSuchMethodError Operation.getMissingValues()."
        echo
        echo " REMEDY"
        echo "   1. Re-run this script. cgen_resolve_classpaths rebuilds the reactor with"
        echo "        mvn -P main -pl lib/corej-cdisc-rules -am -DskipTests \\"
        echo "            test-compile dependency:build-classpath"
        echo "      which must emit <module>/target/classes for every net.cumba.corej"
        echo "      dependency."
        echo "   2. If a jar is still listed above, the test-compile PHASE did not run in"
        echo "      the SAME invocation as the build-classpath GOAL. Splitting them makes"
        echo "      Maven fall back to ~/.m2. Put them back together."
        echo "   3. Do NOT 'fix' this with 'mvn install': ~/.m2 is shared with parallel"
        echo "      lanes and worktrees. Do NOT add -am to a bare exec:java: it fires the"
        echo "      goal on every reactor module (measured - it hit corej-parent first)."
        echo
        echo " If an earlier run did write files, revert before retrying:"
        echo "   git status lib/corej-cdisc-rules/rules"
        echo "   git status lib/corej-cdisc-rules/rules-src"
        echo "=============================================================================="
    } >&2
}

# ---------------------------------------------------------------------------
# Stage execution
# ---------------------------------------------------------------------------

# cgen_run_main <mainClass> [args...]        - main (runtime) scope
# cgen_run_main_test <mainClass> [args...]   - test scope
#
# Both run from the module root, so rules-src/, rules/ and the
# documentation/ output paths resolve module-relative.
cgen_run_main() {
    cgen__run "${CGEN_CP_RUNTIME}" "$@"
}

cgen_run_main_test() {
    cgen__run "${CGEN_CP_TEST}" "$@"
}

cgen__run() {
    local cp="$1"
    shift
    local main="$1"
    shift
    echo "[corpus-gen] ${main} $*" >&2
    (
        cd "${CGEN_MODULE_DIR}" || exit 1
        exec "${CGEN_JAVA}" ${CGEN_JVM_OPTS[@]+"${CGEN_JVM_OPTS[@]}"} -cp "${cp}" "${main}" "$@"
    )
}
