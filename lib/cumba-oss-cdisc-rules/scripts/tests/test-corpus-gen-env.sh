#!/usr/bin/env bash
#
# Unit tests for the stale-engine guard in scripts/lib/corpus-gen-env.sh.
#
# Pure bash against SYNTHETIC classpaths: no Maven, no reactor build, no corpus
# writes, ~1s. That is what makes it safe to run inside `mvn test` —
# CorpusGenScriptGuardTest shells out to this file. The real regeneration is
# never run from a test: those generators write to rules/, rules-legacy/ and
# rules-src/, so gating them would automate the corruption the guard removes.
#
# Usage:
#   lib/corej-cdisc-rules/scripts/tests/test-corpus-gen-env.sh
#
# Exit 0 = all cases pass. Any failure prints the case and exits non-zero.
set -uo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="$(cd "${TESTS_DIR}/.." && pwd)"

# shellcheck source=../lib/corpus-gen-env.sh
source "${SCRIPTS_DIR}/lib/corpus-gen-env.sh"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/corpus-gen-env-test.XXXXXX")"
trap 'rm -rf "${WORK}"' EXIT

PASS=0
FAIL=0

fail() {
    FAIL=$((FAIL + 1))
    echo "  FAIL: $*" >&2
}

pass() {
    PASS=$((PASS + 1))
    echo "  ok: $*"
}

# Runs cgen_assert_fresh_engine, capturing its stderr. Sets GUARD_RC and
# GUARD_OUT. Runs in the current shell (the guard only reads state).
run_guard() {
    local cp="$1" label="${2:-classpath}"
    GUARD_OUT="$(cgen_assert_fresh_engine "${cp}" "${label}" 2>&1)"
    GUARD_RC=$?
}

expect_pass() {
    local case_name="$1" cp="$2"
    run_guard "${cp}"
    if ((GUARD_RC == 0)); then
        pass "${case_name}"
    else
        fail "${case_name}: expected rc 0, got ${GUARD_RC}"
        echo "${GUARD_OUT}" | sed 's/^/        /' >&2
    fi
}

expect_abort() {
    local case_name="$1" cp="$2" needle="$3"
    run_guard "${cp}"
    if ((GUARD_RC == 0)); then
        fail "${case_name}: expected a non-zero rc, guard allowed the run"
        return
    fi
    if [[ "${GUARD_OUT}" != *"${needle}"* ]]; then
        fail "${case_name}: abort message missing '${needle}'"
        echo "${GUARD_OUT}" | sed 's/^/        /' >&2
        return
    fi
    # Every abort must carry the remedy and the no-write promise.
    if [[ "${GUARD_OUT}" != *"REMEDY"* ]]; then
        fail "${case_name}: abort message carries no REMEDY section"
        return
    fi
    if [[ "${GUARD_OUT}" != *"NOTHING HAS BEEN WRITTEN"* ]]; then
        fail "${case_name}: abort message does not state that nothing was written"
        return
    fi
    pass "${case_name}"
}

# ---------------------------------------------------------------------------
# Fixture: a synthetic tree with compiled engine classes and a repository.
# ---------------------------------------------------------------------------

FAKE_CORE="${WORK}/lib/corej-cdisc-core/target/classes"
mkdir -p "${FAKE_CORE}/net/cumba/cdisc/core/model"
: >"${FAKE_CORE}/net/cumba/cdisc/core/model/Operation.class"
touch -d '2026-08-04 12:00:00' "${FAKE_CORE}/net/cumba/cdisc/core/model/Operation.class"

FAKE_M2="${WORK}/m2/repository"
OLD_ENGINE_JAR="${FAKE_M2}/net/cumba/corej/corej-cdisc-core/0.1.0-SNAPSHOT/corej-cdisc-core-0.1.0-SNAPSHOT.jar"
NEW_ENGINE_JAR="${FAKE_M2}/net/cumba/corej/corej-cdisc-core/0.2.0-SNAPSHOT/corej-cdisc-core-0.2.0-SNAPSHOT.jar"
OLD_DATATABLE_JAR="${FAKE_M2}/net/cumba/corej/corej-datatable/0.1.0-SNAPSHOT/corej-datatable-0.1.0-SNAPSHOT.jar"
THIRD_PARTY_JAR="${FAKE_M2}/com/fasterxml/jackson/core/jackson-databind/2.21.3/jackson-databind-2.21.3.jar"

mkdir -p "$(dirname "${OLD_ENGINE_JAR}")" "$(dirname "${NEW_ENGINE_JAR}")" \
    "$(dirname "${OLD_DATATABLE_JAR}")" "$(dirname "${THIRD_PARTY_JAR}")"
: >"${OLD_ENGINE_JAR}"
: >"${NEW_ENGINE_JAR}"
: >"${OLD_DATATABLE_JAR}"
: >"${THIRD_PARTY_JAR}"
touch -d '2026-07-01 09:00:00' "${OLD_ENGINE_JAR}"
touch -d '2026-08-04 18:00:00' "${NEW_ENGINE_JAR}"
touch -d '2026-07-01 09:00:00' "${OLD_DATATABLE_JAR}"
touch -d '2026-07-01 09:00:00' "${THIRD_PARTY_JAR}"

FAKE_RULES="${WORK}/lib/corej-cdisc-rules/target/classes"
mkdir -p "${FAKE_RULES}"

# Point the guard at the synthetic tree. CGEN_CORE_CLASSES is an ordinary shell
# variable, so no production escape hatch is needed to redirect it.
CGEN_CORE_CLASSES="${FAKE_CORE}"

echo "test-corpus-gen-env.sh"

# ---------------------------------------------------------------------------
# Cases
# ---------------------------------------------------------------------------

# 1. The shape cgen_resolve_classpaths produces: reactor modules as directories,
#    third-party from the repository. This must be allowed.
expect_pass "fresh tree-local classpath is allowed" \
    "${FAKE_RULES}:${FAKE_CORE}:${THIRD_PARTY_JAR}"

# 2. The 2026-08-04 incident: the engine resolved from ~/.m2 and older than the
#    compiled classes.
expect_abort "stale engine jar aborts" \
    "${FAKE_RULES}:${OLD_ENGINE_JAR}:${THIRD_PARTY_JAR}" \
    "OLDER than"

# 3. A NEWER engine jar is not safer — it was built from some other tree.
expect_abort "newer engine jar also aborts" \
    "${FAKE_RULES}:${NEW_ENGINE_JAR}:${THIRD_PARTY_JAR}" \
    "not built by this reactor run"

# 4. The guard covers every reactor artifact, not just the engine. This is the
#    case a bare "prepend core/target/classes" repair would have missed.
expect_abort "a non-engine reactor jar aborts even when the engine is fresh" \
    "${FAKE_RULES}:${FAKE_CORE}:${OLD_DATATABLE_JAR}:${THIRD_PARTY_JAR}" \
    "corej-datatable-0.1.0-SNAPSHOT.jar"

# 5. Both offenders are reported, not just the first.
run_guard "${FAKE_RULES}:${OLD_ENGINE_JAR}:${OLD_DATATABLE_JAR}"
if ((GUARD_RC != 0)) &&
    [[ "${GUARD_OUT}" == *"corej-cdisc-core-0.1.0-SNAPSHOT.jar"* ]] &&
    [[ "${GUARD_OUT}" == *"corej-datatable-0.1.0-SNAPSHOT.jar"* ]] &&
    [[ "${GUARD_OUT}" == *"2 artifact(s)"* ]]; then
    pass "every offending artifact is listed"
else
    fail "every offending artifact is listed: rc=${GUARD_RC}"
    echo "${GUARD_OUT}" | sed 's/^/        /' >&2
fi

# 6. The engine is on the classpath but was never compiled in this tree.
saved_core="${CGEN_CORE_CLASSES}"
CGEN_CORE_CLASSES="${WORK}/lib/corej-cdisc-core-never-built/target/classes"
expect_abort "uncompiled engine aborts" \
    "${FAKE_RULES}:${CGEN_CORE_CLASSES}:${THIRD_PARTY_JAR}" \
    "not compiled in this tree"
CGEN_CORE_CLASSES="${saved_core}"

# 7. No jar, but the compiled engine is not on the classpath at all — the stages
#    would fail or, worse, pick up something else.
expect_abort "engine absent from the classpath aborts" \
    "${FAKE_RULES}:${THIRD_PARTY_JAR}" \
    "not on the"

# 8. cgen_resolve_classpaths never ran.
expect_abort "empty classpath aborts" "" "did not run"

# 9. A third-party jar whose name merely resembles a reactor artifact must not
#    trip the guard — the group path is what identifies a reactor module.
LOOKALIKE="${FAKE_M2}/com/example/corej-cdisc-core/1.0/corej-cdisc-core-1.0.jar"
mkdir -p "$(dirname "${LOOKALIKE}")"
: >"${LOOKALIKE}"
expect_pass "a lookalike jar outside the reactor group path is allowed" \
    "${FAKE_RULES}:${FAKE_CORE}:${LOOKALIKE}"

# ---------------------------------------------------------------------------

echo
echo "test-corpus-gen-env.sh: ${PASS} passed, ${FAIL} failed"
((FAIL == 0)) || exit 1
exit 0
