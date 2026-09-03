#!/usr/bin/env bash
#
# Regenerates the `rule-doc-census` region of
# documentation/CORE-RULES-SPECIFICATION.md — the machine-derived half of the
# rules specification (corpus view sizes, the Rule_Type / Sensitivity /
# Executability / OperationType / BuiltinRegistry rosters and their corpus
# distributions). One-shot tool; NOT part of the default build.
#
# Usage (from anywhere):
#   lib/corej-cdisc-rules/scripts/gen-rule-doc-census.sh
#
# RuleDocCensusDriftTest fails the build whenever the committed region stops
# matching this generator's output, so the region can never quietly go stale the
# way the hand-written counts around it did (wave-13 lane E measured ~28 of ~35
# unanchored counts as drifted, against zero drift in the one document a CI
# ratchet already pinned).
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "${MODULE_DIR}"

# THE GENERATOR MUST RUN AGAINST THIS TREE'S ENUMS, NOT AN INSTALLED JAR.
#
# The census IS a transcript of RuleType / Sensitivity / Executability /
# OperationType / BuiltinRegistry. A stale corej-cdisc-core on the classpath
# yields a roster describing an engine nobody is shipping, and
# RuleDocCensusDriftTest then pins the wrong text — the exact failure mode the
# corpus regen hit on 2026-08-04 (NoSuchMethodError after 54 files had already
# been rewritten). scripts/lib/corpus-gen-env.sh builds the reactor, resolves the
# classpath from this tree's target/classes and refuses to start if any
# net.cumba.corej dependency still resolves from ~/.m2.
#
# Do not reintroduce an `exec:java` invocation here: CorpusGenScriptGuardTest
# fails the build if any script under scripts/ carries that shape.
# shellcheck source=lib/corpus-gen-env.sh
source "${MODULE_DIR}/scripts/lib/corpus-gen-env.sh"

cgen_resolve_classpaths

# Fail fast BEFORE the generator runs — an abort here leaves the document untouched.
cgen_assert_fresh_engine "${CGEN_CP_TEST}" "test-scope classpath"

# RuleDocCensus is test-scope (it is a documentation tool, not shipped code), so
# it needs the test classpath — the same scope RuleCorpusGenerator runs in.
cgen_run_main_test net.cumba.corej.core.doc.RuleDocCensus \
    "${MODULE_DIR}/documentation/CORE-RULES-SPECIFICATION.md"
